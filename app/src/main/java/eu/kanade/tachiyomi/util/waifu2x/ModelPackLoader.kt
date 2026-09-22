package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * SY --> 超分「模型包」加载器（安装包模式 / 纯资源 APK）。
 *
 * 模型包声明 <uses-feature android:name="mihon.upscale.modelpack" required="false"/>，
 * 并在 <application> 上挂 meta-data "mihon.upscale.modelpack.id" / ".name"（hasCode=false）。
 * 包内 assets/<assetPath>/ 放 ncnn 的 *.param / *.bin（Anime4K 的包放 *.glsl）。
 *
 * 宿主枚举已安装包 → 解压到 filesDir/modelpacks/<id>/ → 模型即从包内加载。
 * 本版本宿主**不再内置任何模型权重**，所以「装了包」= 「模型可用」。
 *
 * 原生层无需改动：nativeInit* 接收的就是文件系统路径（见 waifu2x.cpp 的 net.load_param/load_model）。
 *
 * 两个必须绕开的坑：
 *  1) PackageInfo.reqFeatures 在部分机型（Android 12 / MIUI）恒为 null，无法用它识别包；
 *     改看 applicationInfo.metaData（GET_META_DATA 一定会填）。
 *  2) 极简模型包没有 resources.arsc，getResourcesForApplication 不可靠；
 *     直接 ZipFile 读它的 APK zip（/data/app 下的 APK 全局可读）。
 * SY <--
 */
internal object ModelPackLoader {
    private const val TAG = "ModelPackLoader"

    const val FEATURE = "mihon.upscale.modelpack"
    private const val META_ID = "mihon.upscale.modelpack.id"
    private const val META_NAME = "mihon.upscale.modelpack.name"
    private const val ASSET_PREFIX = "assets/"
    private const val STAMP_FILE = ".pack-stamp"

    data class Pack(
        val id: String,
        val name: String,
        val packageName: String,
        /** 该包 assets/ 解压后的根目录（其下保留包里原本的子目录结构）。 */
        val dir: File,
    )

    @Volatile
    private var cache: List<Pack>? = null

    /** 枚举并解压所有已安装的模型包；结果在进程内缓存。 */
    fun install(context: Context): List<Pack> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val appContext = context.applicationContext
            val pm = appContext.packageManager

            @Suppress("DEPRECATION")
            val packages = try {
                pm.getInstalledPackages(PackageManager.GET_META_DATA)
            } catch (t: Throwable) {
                Log.e(TAG, "getInstalledPackages failed", t)
                emptyList()
            }

            val result = mutableListOf<Pack>()
            for (pkg in packages) {
                val ai = pkg.applicationInfo ?: continue
                val md = ai.metaData

                val id = md?.getString(META_ID)
                val hasFeature = pkg.reqFeatures?.any { it.name == FEATURE } == true
                if (id == null && !hasFeature) continue
                if (id == null) {
                    Log.w(TAG, "pkg ${pkg.packageName} declares feature but has no '$META_ID'; skipping")
                    continue
                }
                val name = md.getString(META_NAME) ?: id

                val dir = File(appContext.filesDir, "modelpacks/$id")
                try {
                    extractPack(ai, dir)
                } catch (t: Throwable) {
                    Log.e(TAG, "extract model pack '$id' failed", t)
                }

                result += Pack(id, name, pkg.packageName, dir)
                Log.i(
                    TAG,
                    "Detected model pack id=$id name=$name pkg=${pkg.packageName} dir=${dir.absolutePath}",
                )
            }
            Log.i(TAG, "install(): ${result.size} model pack(s) -> ${result.map { it.id }}")
            cache = result
            return result
        }
    }

    /**
     * 找出提供 [assetPath] 的模型包目录，只要有内容即可（不限 ncnn 的 param/bin）。
     * Anime4K 的包只有 .glsl，必须走这个入口。找不到返回 null。
     */
    fun resolveDir(context: Context, assetPath: String): File? {
        for (pack in install(context)) {
            val dir = File(pack.dir, assetPath)
            if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true) {
                return dir
            }
        }
        return null
    }

    /**
     * 找出提供 [assetPath] 的模型包目录（要求目录里有 ncnn 的 param+bin）。
     * 找不到返回 null（调用方回退到宿主自带 assets）。
     */
    fun resolveAssetDir(context: Context, assetPath: String): File? {
        for (pack in install(context)) {
            val dir = File(pack.dir, assetPath)
            if (dir.isDirectory && dir.hasNcnnModels()) {
                Log.i(TAG, "Resolved '$assetPath' from pack '${pack.id}' -> ${dir.absolutePath}")
                return dir
            }
        }
        return null
    }

    private fun extractPack(ai: ApplicationInfo, destRoot: File) {
        val apk = File(ai.sourceDir)

        // SY --> 解压结果打戳：包里几十 MB 时，每次进程启动都重拷一遍太浪费。
        // 戳 = APK 的 mtime + 大小；包一升级必然变化，从而触发重解压。
        // SY <--
        val stamp = "${apk.lastModified()}-${apk.length()}"
        val stampFile = File(destRoot, STAMP_FILE)
        val existing = destRoot.listFiles()?.size ?: 0
        if (existing > 1 && stampFile.isFile && stampFile.readText() == stamp) {
            return
        }

        destRoot.deleteRecursively()
        destRoot.mkdirs()
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                if (!name.startsWith(ASSET_PREFIX)) continue
                val rel = name.removePrefix(ASSET_PREFIX)
                if (rel.isEmpty()) continue

                val out = File(destRoot, rel)
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                count++
            }
            Log.i(TAG, "Extracted $count asset(s) from ${apk.name} -> ${destRoot.absolutePath}")
        }
        stampFile.writeText(stamp)
    }

    private fun File.hasNcnnModels(): Boolean {
        val files = listFiles().orEmpty()
        return files.any { it.extension == "param" } && files.any { it.extension == "bin" }
    }
}

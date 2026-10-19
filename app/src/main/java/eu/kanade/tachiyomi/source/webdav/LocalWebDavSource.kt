package eu.kanade.tachiyomi.source.webdav

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.CloudSyncDirectory
import eu.kanade.tachiyomi.data.download.CloudSyncEntry
import eu.kanade.tachiyomi.data.download.normalizePath
import eu.kanade.tachiyomi.source.FolderSource
import eu.kanade.tachiyomi.source.HeavyChapterLoadSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFolder
import eu.kanade.tachiyomi.source.UnmeteredSource
import tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.pdf.PdfiumRenderer
import kotlinx.coroutines.Dispatchers
import logcat.LogPriority
import mihon.core.archive.LocalRandomAccessSource
import mihon.core.archive.RandomAccessSource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * SY --> 本地文件夹版 WebDAV 图源：把 [WebDavSource] 的「根」从远端 WebDAV 服务器换成
 * 「用户选择的本地文件夹」（SAF tree，由 StorageManager 提供），其余浏览/章节/封面/文件夹视图/排序
 * 逻辑全部在 [BaseWebDavSource]（与远端逐字共用）。
 *
 * 本类只承载本地后端差异：
 *  - 访问收敛进私有 [LocalConfig]（SAF/FUSE 文件），结构浏览/章节/封面逐字复用基类；
 *  - 读页经 contentResolver 拿 fd 打开 [LocalRandomAccessSource]（FUSE/SAF 通用），不再走 HTTP Range；
 *  - 封面复用 content:// 懒加载通道（LocalWebDavCoverBridge / LocalWebDavCoverProvider）。
 *
 * 未选择本地文件夹（rootFolders 为空）时，所有方法经 [isConfigured] 优雅降级为空 / 抛明确异常。
 * SY <--
 */
@Inject
@SingleIn(AppScope::class)
class LocalWebDavSource(
    context: Context,
    private val storageManager: StorageManager,
) : BaseWebDavSource(context) {

    init {
        // 封面回退（无 cover.* 时取第一话首页）需要源侧的本地归档/PDF 解析能力。
        LocalWebDavCoverBridge.bindLocalWebDavSource(this)
    }

    override val name: String = "本地图源（本地文件夹）"
    override val id: Long = LocalSource.ID
    override val lang: String = "other"
    override val supportsLatest: Boolean = true
    override fun toString() = name

    /** 未选择本地文件夹时返回 null，所有对外方法据此优雅降级。 */
    private fun configOrNull(): Pair<LocalConfig, String?>? {
        // SY --> 2026-09-29：**缓存 LocalConfig**（SAF 根解析代价高，见 LocalConfig）。
        val version = storageManager.localSourceDirsVersion
        cachedConfig?.let { (v, cfg) -> if (v == version) return cfg to null }

        if (rootFolders().isEmpty()) return null
        val cfg = LocalConfig(storageManager, context)
        cachedConfig = version to cfg
        return cfg to null
    }

    @Volatile
    private var cachedConfig: Pair<Int, LocalConfig>? = null

    /** 用户通过「选择本地文件夹」加入的所有根目录（SAF tree）。 */
    private fun rootFolders(): List<UniFile> = storageManager.getLocalSourceDirectories()

    // ===================== 后端接缝实现（其余逻辑在 BaseWebDavSource） =====================

    override fun isConfigured(): Boolean = configOrNull() != null
    override val backend = WebDavCoverStore.Backend.LOCAL
    override val coverAuthority = LocalWebDavCoverBridge.COVER_AUTHORITY
    override val coverCacheDirName = "localwebdav-covers"
    override val archiveCacheSubdir = LOCAL_ARCHIVE_CACHE_SUBDIR
    override val folderCoverSegment = LocalWebDavCoverBridge.FOLDER_SEGMENT

    /** 本地图源是本地后端：不走 WebDAV/网盘那条「远端流式」加载分支。 */
    override val isRemoteStreamingBackend: Boolean = false

    /** 本地图源是跨进程（SAF/FUSE）调用，并发度取 4 比远端的 6 更稳妥。 */
    override val subdirListDispatcher = Dispatchers.IO.limitedParallelism(4)

    override suspend fun listDirectories(path: String): List<CloudSyncDirectory> =
        configOrNull()?.first?.listDirectories(path) ?: emptyList()

    override suspend fun listFiles(path: String): List<CloudSyncEntry> =
        configOrNull()?.first?.listFiles(path) ?: emptyList()

    /** 文件夹视图的本地根目录「唯一键」列表（同名根带 #n 后缀，见 [buildRootEntries]）。 */
    override fun getFoldersPaths(): List<String> = getLocalRoots().map { it.key } + extraLocalFolders

    override fun openRangeSource(remotePath: String): RandomAccessSource? =
        localRangeSourceForPath(remotePath)

    override suspend fun downloadImageToCache(folderPath: String, fileName: String): File {
        val (config, _) = configOrNull()
            ?: throw IllegalStateException("本地图源未选择文件夹")
        val file = config.downloadImageToCache(folderPath, fileName)
        // SY --> 2026-09-28：散图缓存修前**无上限**（与 WebDAV 侧共用 cache/webdav-images，
        // 实测 118M 且只增不减），补 256MB LRU。放在这里而非 LocalConfig 内，是为了统一记日志。
        val cacheDir = File(context.cacheDir, IMG_CACHE_SUBDIR).apply { mkdirs() }
        enforceLruCacheBudget(cacheDir, IMG_CACHE_BYTES, setOf(file.name))
            .forEach { (name, len) ->
                logcat(LogPriority.INFO) { "LocalWebDavSource: 散图缓存超限，淘汰 $name ($len B)" }
            }
        // SY <--
        return file
    }

    /**
     * 把某一章的归档/电子书拷到应用缓存（真实文件）后返回其 File；[BaseWebDavSource.getArchiveFile]
     * 负责包装成 UniFile。对齐 WebDAV 的「先整本落盘再读」：直接把 SAF/content:// 交给 archiveReader
     * 时 SAF InputStream 不可随机寻址，libarchive 每翻一页都要走慢速跨进程重读，远慢于 WebDAV；
     * 拷到真实文件后由随机读直读，速度对齐 WebDAV（且按路径命中缓存，重开零成本）。
     */
    override suspend fun downloadChapterToCache(remotePath: String): File {
        val (config, _) = configOrNull()
            ?: throw IllegalStateException("本地图源未选择文件夹或路径不存在：$remotePath")
        val src = config.resolveFile(remotePath)
            ?: throw IllegalStateException("本地图源：路径无法解析 $remotePath")
        val cacheDir = File(context.cacheDir, LOCAL_ARCHIVE_CACHE_SUBDIR).apply { mkdirs() }
        val cacheFile = File(cacheDir, remotePath.trimStart('/').replace('/', '_'))
        val srcLen = runCatching { src.length() }.getOrDefault(-1L)
        if (!cacheFile.exists() || (srcLen >= 0L && cacheFile.length() != srcLen)) {
            runCatching { cacheFile.delete() }
            src.openInputStream()?.use { input ->
                cacheFile.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("本地图源：无法打开文件 $remotePath")
            // SY --> 2026-09-28：本目录修前**无上限无淘汰**（与 WebDAV 侧同一处缺陷）。
            // 补 1GB 预算（与 WebDAV 侧同口径）。
            enforceLruCacheBudget(cacheDir, ARCHIVE_CACHE_BYTES, setOf(cacheFile.name))
                .forEach { (name, len) ->
                    logcat(LogPriority.INFO) { "LocalWebDavSource: 章节缓存超限，淘汰 $name ($len B)" }
                }
            // SY <--
        } else {
            // SY --> 命中即刷新 mtime，作为 LRU 依据（常读章节不该因 mtime 旧被优先淘汰）。
            cacheFile.setLastModified(System.currentTimeMillis())
            // SY <--
        }
        return cacheFile
    }

    override suspend fun downloadCoverEntry(actual: CloudSyncEntry, mangaUrl: String, coverDir: File): File? {
        val (config, _) = configOrNull() ?: return null
        val dir = config.resolveFile(mangaUrl) ?: return null
        val src = dir.findFile(actual.name) ?: return null
        val ext = actual.name.substringAfterLast('.', "")
        val baseName = mangaUrl.replace('/', '_')
        val dst = File(coverDir, "$baseName.$ext")
        src.openInputStream()?.use { it.copyTo(dst.outputStream()) }
        return dst.takeIf { it.exists() && it.length() > 0L && !CoverQuality.isBlank(it) }
    }

    override fun openPdfRenderer(ras: RandomAccessSource): PdfiumRenderer =
        PdfiumRenderer.open(ras)

    // ===================== 本地文件夹后端 =====================

    /**
     * 本地文件后端：把 WebDAV 源对 CloudSyncService 的调用收敛成同一组方法，差异只在「后端是本地文件」。
     * 关键点：尽量把 SAF tree URI 还原成真实文件路径（CloudDrive2 等 FUSE 挂载在真实文件系统路径），
     * 用 java.io.File 直接访问——目录列表 / 读页 / 抽封面都走真实 fd，比 SAF content:// 快且稳。
     */
    private class LocalConfig(
        storageManager: StorageManager,
        private val context: Context,
    ) {
        /** 根目录列表只解析一次（SAF 查询代价高）。每个根优先还原成真实文件路径。唯一键见 [buildRootEntries]。 */
        private val roots: List<ResolvedRoot> = run {
            val unis = storageManager.getLocalSourceDirectories()
            val entries = buildRootEntries(unis.map { it.name.orEmpty() })
            unis.mapIndexed { i, uni ->
                val entry = entries[i]
                val realPath = uni.uri?.let { treeUriToRealPath(it) }
                if (realPath != null && File(realPath).exists()) {
                    val f = File(realPath)
                    val realUni = UniFile.fromFile(f)
                    if (realUni != null) {
                        ResolvedRoot(entry.key, entry.displayName, realUni, f)
                    } else {
                        ResolvedRoot(entry.key, entry.displayName, uni, null)
                    }
                } else {
                    ResolvedRoot(entry.key, entry.displayName, uni, null)
                }
            }
        }

        /**
         * SY --> 兼容旧 LocalSource：原版本地漫画的 url 首段是「漫画目录名」（相对默认 local 目录），
         * 而不是本源的「根名/子路径」。旧漫画面条（source=LocalSource.ID）被本源接管后，首段永远匹配不上
         * 任何 root.name → 解析失败、读不到。这里存下默认 local 目录，在首段不匹配任何 root 时回退。
         */
        private val legacyBase: UniFile? = runCatching { storageManager.getExistingLocalSourceDirectory() }.getOrNull()
        private val legacyBaseReal: File? = legacyBase?.uri?.let { uri ->
            when (uri.scheme) {
                "file" -> uri.path?.let { p -> File(p).takeIf { it.exists() } }
                else -> treeUriToRealPath(uri)?.let { p -> File(p).takeIf { it.exists() } }
            }
        }
        // SY <--

        /**
         * 把形如 `rootName/子路径` 的相对路径解析到本地 UniFile（找不到返回 null）。真实路径模式下经
         * java.io.File 导航再包成 UniFile.fromFile（后续读/列都是真实文件，快）；SAF 退回模式下经
         * UniFile.findFile 导航。首段不是任何 root 名时回退成旧 LocalSource 格式（整段相对默认 local 目录）。
         * SY 2026-10-01：resolveFile 内部有跨进程 SAF 树遍历（findFile），封面扫描 / 列章节会反复解析
         * 同一父目录。加会话级 ConcurrentHashMap 记忆化（仅缓存命中结果），把昂贵的 findFile 链降到 O(1)。
         */
        private val resolveFileMemo = ConcurrentHashMap<String, UniFile?>()

        fun resolveFile(path: String): UniFile? {
            resolveFileMemo[path]?.let { return it }
            val result = resolveFileImpl(path)
            if (result != null) resolveFileMemo[path] = result
            return result
        }

        private fun resolveFileImpl(path: String): UniFile? {
            val segs = path.split('/').filter { it.isNotBlank() }
            if (segs.isEmpty()) return null
            val root = roots.firstOrNull { it.key == segs.first() }
            if (root != null) {
                if (root.realFile != null) {
                    var cur = root.realFile
                    for (s in segs.drop(1)) {
                        cur = File(cur, s)
                        if (!cur.exists()) return null
                    }
                    return UniFile.fromFile(cur)
                }
                var cur: UniFile = root.uni
                for (s in segs.drop(1)) {
                    cur = cur.findFile(s) ?: return null
                }
                return cur
            }
            // SY --> 旧 LocalSource 格式回退：整段相对默认 local 目录解析。
            if (legacyBase != null) {
                var cur: UniFile = legacyBase
                var ok = true
                for (s in segs) {
                    val next = cur.findFile(s)
                    if (next == null) {
                        ok = false
                        break
                    }
                    cur = next
                }
                if (ok) return cur
            }
            // 2026-09-29：再兜底到「各根目录下」（同 resolveRealFile 的老格式兜底）。
            for (root in roots) {
                var cur: UniFile = root.uni
                var ok = true
                for (s in segs) {
                    val next = cur.findFile(s)
                    if (next == null) {
                        ok = false
                        break
                    }
                    cur = next
                }
                if (ok) return cur
                root.uni.findFile(segs.last())?.let { return it }
            }
            return null
            // SY <--
        }

        /** 同 [resolveFile]，但返回真实 File（仅真实路径模式下非空）。供需要 fd 随机读的封面/读页使用。 */
        // SY 2026-10-01：同 resolveFile 的记忆化（仅缓存命中结果）。
        private val resolveRealFileMemo = ConcurrentHashMap<String, File?>()

        fun resolveRealFile(path: String): File? {
            resolveRealFileMemo[path]?.let { return it }
            val result = resolveRealFileImpl(path)
            if (result != null) resolveRealFileMemo[path] = result
            return result
        }

        private fun resolveRealFileImpl(path: String): File? {
            val segs = path.split('/').filter { it.isNotBlank() }
            if (segs.isEmpty()) return null
            val root = roots.firstOrNull { it.key == segs.first() }
            if (root != null) {
                val base = root.realFile ?: return null
                var cur = base
                for (s in segs.drop(1)) {
                    cur = File(cur, s)
                    if (!cur.exists()) return null
                }
                return cur
            }
            // SY --> 旧 LocalSource 格式回退：整段相对默认 local 目录。
            val base = legacyBaseReal
            if (base != null) {
                var cur = base
                var ok = true
                for (s in segs) {
                    cur = File(cur, s)
                    if (!cur.exists()) {
                        ok = false
                        break
                    }
                }
                if (ok) return cur
            }
            // 2026-09-29：再兜底到「各根目录下」——见 [resolveLegacyReal]。
            return resolveLegacyReal(path)
            // SY <--
        }

        /**
         * SY --> 2026-09-29：老格式 url 的第二轮兜底。老 LocalSource 存的是**裸文件名 / 相对路径**，
         * 首段不是任何根名。这里补两轮：① 各根 + 整段相对路径；② 各根 + 仅文件名。结果做记忆化。
         * SY <--
         */
        // SY 2026-09-30：子目录枚举改成并发后会被多线程访问，HashMap 并发写会损坏/死循环，换 ConcurrentHashMap。
        private val legacyRealMemo = ConcurrentHashMap<String, File?>()

        private fun resolveLegacyReal(path: String): File? {
            if (legacyRealMemo.containsKey(path)) return legacyRealMemo[path]
            val name = path.substringAfterLast('/')
            val found = roots.asSequence()
                .mapNotNull { it.realFile }
                .flatMap { root -> sequenceOf(File(root, path), File(root, name)) }
                .firstOrNull { it.isFile || it.isDirectory }
            legacyRealMemo[path] = found
            return found
        }
        // SY <--

        fun listDirectories(path: String): List<CloudSyncDirectory> {
            val dir = resolveFile(path) ?: return emptyList()
            return dir.listFiles().orEmpty()
                .filter { it.isDirectory }
                .map { CloudSyncDirectory(name = it.name.orEmpty(), path = "$path/${it.name}", lastModified = it.lastModified()) }
        }

        fun listFiles(path: String): List<CloudSyncEntry> {
            val dir = resolveFile(path) ?: return emptyList()
            return dir.listFiles().orEmpty()
                .filter { !it.isDirectory }
                .map { CloudSyncEntry(name = it.name.orEmpty(), path = "$path/${it.name}", isCollection = false, lastModified = it.lastModified()) }
        }

        fun downloadCover(mangaUrl: String, coverDir: File): File? {
            val dir = resolveFile(mangaUrl) ?: return null
            if (!dir.isDirectory) return null
            coverDir.mkdirs()
            val baseName = mangaUrl.replace('/', '_')
            for (ext in listOf("jpg", "webp", "png", "jpeg")) {
                val src = dir.findFile("cover.$ext") ?: continue
                val out = File(coverDir, "$baseName.$ext")
                src.openInputStream()?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                if (out.exists() && out.length() > 0 && !CoverQuality.isBlank(out)) return out
            }
            return null
        }

        fun downloadImageToCache(folderPath: String, fileName: String): File {
            val dir = resolveFile(folderPath) ?: error("LocalWebDavSource: 目录不存在 $folderPath")
            val src = dir.findFile(fileName)
                ?: error("LocalWebDavSource: 图片不存在 $folderPath/$fileName")
            val cacheDir = File(context.cacheDir, IMG_CACHE_SUBDIR).apply { mkdirs() }
            // SY --> 2026-10-01：改用共享 Hash.sha256。
            val key = Hash.sha256("$folderPath/$fileName")
            // SY <--
            val cacheFile = File(cacheDir, key)
            if (!cacheFile.exists()) {
                src.openInputStream()?.use { input ->
                    cacheFile.outputStream().use { input.copyTo(it) }
                }
            } else {
                // 命中即刷新 mtime，作为 LRU 依据。
                cacheFile.setLastModified(System.currentTimeMillis())
            }
            return cacheFile
        }
    }

    /**
     * 本地根目录条目（唯一键 + 显示名）。默认根在前；同名根按 [buildRootEntries] 加 `#n` / ` (n)` 后缀，
     * **不再 distinct 掉重名根** —— 这正是「两个 local 只出现一个、后加的用不了」的根因。
     */
    private fun getLocalRoots(): List<LocalRootEntry> =
        buildRootEntries(rootFolders().map { it.name.orEmpty() })

    /**
     * 文件夹视图：本地根 + 进程内额外子文件夹。
     * 重名根的显示名是「名字 (2)」，而 **url 始终是唯一键**（列表身份 / 隐藏键 / 路径解析统一用它）。
     */
    override suspend fun getFolders(): List<SourceFolder> = withIOContext {
        if (!isConfigured()) return@withIOContext emptyList()
        getLocalRoots().map { entry ->
            SourceFolder(
                url = entry.key,
                name = entry.displayName,
                coverUrl = "content://$coverAuthority/$folderCoverSegment/${Uri.encode(entry.key)}",
            )
        } + extraLocalFolders.map { path ->
            SourceFolder(
                url = path,
                name = path.substringAfterLast('/', path).ifBlank { path },
                coverUrl = "content://$coverAuthority/$folderCoverSegment/${Uri.encode(path)}",
            )
        }
    }

    /** 本次运行内通过 addFolder 额外加入的子文件夹（进程级，重启后回到仅选中的根目录）。 */
    private val extraLocalFolders = mutableListOf<String>()

    /**
     * SY 2026-10-06：「删除文件夹」= 真移除（仅进程内 [extraLocalFolders]）；
     * SAF 根目录不是相对路径，匹配不到 → 调用方退回隐藏语义（重选时自动解除隐藏）。
     */
    fun removeExtraLocalFolder(path: String): Boolean = extraLocalFolders.remove(normalizePath(path))

    override suspend fun addFolder(identifier: String): Boolean {
        // SY --> 本地图源「添加文件夹」：SAF tree URI 作为新根目录加入 storageManager（与 LocalSource 同源，
        // 存进 localSourceExtraDirectories，rootFolders()/configOrNull() 据此刷新）。非 URI 的相对路径
        // （进程内额外子文件夹）走下方原逻辑。
        if (Uri.parse(identifier).scheme != null) {
            return storageManager.addLocalSourceDirectory(identifier)
        }
        // SY <--
        val path = normalizePath(identifier)
        if (path.isBlank()) return false
        if (path in getFoldersPaths()) return false
        // 校验本地目录存在（解析失败即视为无效路径）
        val (config, _) = configOrNull() ?: return false
        val exists = runCatching { config.listDirectories(path) }.isSuccess
        if (!exists) return false
        extraLocalFolders += path
        return true
    }

    /** 按相对路径解析本地文件并以只读随机读源打开（FUSE/SAF 通用）。找不到或无法打开时返回 null。 */
    private fun localRangeSourceForPath(path: String): RandomAccessSource? {
        val real = configOrNull()?.first?.resolveRealFile(path)
        if (real != null && real.exists()) {
            return runCatching { LocalRandomAccessSource(real) }.getOrNull()
                ?: run {
                    logcat(LogPriority.WARN) { "LocalWebDavSource: 本地文件无法随机读，跳过 $path" }
                    return null
                }
        }
        val file = configOrNull()?.first?.resolveFile(path)
        if (file == null) {
            logcat(LogPriority.WARN) { "LocalWebDavSource: 本地路径无法解析，跳过 $path" }
            return null
        }
        val pfd = runCatching { context.contentResolver.openFileDescriptor(file.uri, "r") }.getOrNull()
            ?: run {
                logcat(LogPriority.WARN) { "LocalWebDavSource: 无法以只读方式打开本地文件，跳过 $path" }
                return null
            }
        return LocalRandomAccessSource(pfd.fileDescriptor)
    }

    /**
     * 暴露给 ChapterLoader：把带 [BaseWebDavSource.IMG_FOLDER_MARKER] 的散图文件夹章节 url
     * 解析成本地目录 UniFile。找不到根（未选文件夹 / 路径不存在）时返回 null，调用方据此走兜底。
     */
    fun resolveImageFolderDir(chapterUrl: String): UniFile? {
        val path = chapterUrl.removeSuffix(BaseWebDavSource.IMG_FOLDER_MARKER).trimStart('/')
        return configOrNull()?.first?.resolveFile(path)
    }

    companion object {
        /** 散图文件夹章节的 url 标记（与 ChapterLoader 共用，见 BaseWebDavSource）。 */
        const val IMG_FOLDER_MARKER = BaseWebDavSource.IMG_FOLDER_MARKER

        /** 本地章节归档/电子书拷到真实文件后的缓存目录（对齐 WebDAV 的下载缓存，读页走本地快 I/O）。 */
        private const val LOCAL_ARCHIVE_CACHE_SUBDIR = "localwebdav-archives"

        /**
         * `localwebdav-archives` 磁盘预算：1GB，超限按 LRU 淘汰（与 WebDAV 侧同口径）。
         * 本地图源读页前会把整章拷成本地真实文件（对齐 WebDAV 的「先落盘再读」），修前无任何上限。
         */
        private const val ARCHIVE_CACHE_BYTES = 1L * 1024 * 1024 * 1024

        /** 散图文件夹单张图片的磁盘缓存目录（与 WebDAV 侧共用同一目录，预算也一致：256MB LRU）。 */
        private const val IMG_CACHE_SUBDIR = "webdav-images"

        /** [IMG_CACHE_SUBDIR] 磁盘预算：256MB，超限按 LRU 淘汰。散图单张 1–3MB，256MB 足够滚读若干话。 */
        private const val IMG_CACHE_BYTES = 256L * 1024 * 1024
    }
}

/**
 * 把 SAF tree URI 还原成真实文件路径（如 content://.../tree/primary:CloudDrive →
 * /storage/emulated/0/CloudDrive）。CloudDrive2 等 FUSE 挂载盘在此路径下是真实文件系统，
 * 可直接 java.io.File 访问——这是与 WebDAV（HTTP 真实文件）真正对齐的关键。
 * 放文件级（private）以便 [LocalWebDavSource.LocalConfig]（非 inner 嵌套类）也能调用。
 */
private fun treeUriToRealPath(uri: Uri): String? {
    return runCatching {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(':')
        if (parts.size < 2) return null
        val volume = parts[0]
        val relative = parts.drop(1).joinToString("/")
        val root = when (volume) {
            "primary" -> Environment.getExternalStorageDirectory().absolutePath
            else -> "/storage/$volume"
        }
        "$root/$relative"
    }.getOrNull()
}

/**
 * SY 2026-10-01：本地根目录的**唯一键 / 显示名**。
 *
 * 背景（用户实测）：根目录身份原先直接用「目录名」⇒ 同名目录必然冲突 ——
 * ① `getLocalFolders().distinct()` 把后一个同名根直接吞掉，列表里根本不出现；
 * ② `resolveFile` 按 `roots.firstOrNull { it.name == 首段 }` 解析，永远命中第一个 ⇒
 * 后加入的那个「添加成功也用不了」。
 *
 * 现在按出现顺序给同名根加序号后缀：键 = `local` / `local#2` / `local#3`…，
 * 显示名 = `local` / `local (2)` / `local (3)`…。
 * 默认根永远排在最前 ⇒ 首个同名根保持原名，既有漫画面条的 `名字/子路径` url 不受影响。
 */
private data class LocalRootEntry(val key: String, val displayName: String)

private fun buildRootEntries(names: List<String>): List<LocalRootEntry> {
    val seen = HashMap<String, Int>()
    return names.map { raw ->
        val base = raw.ifBlank { "root" }
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        LocalRootEntry(
            key = if (n == 1) base else "$base#$n",
            displayName = if (n == 1) base else "$base ($n)",
        )
    }
}

/** 一个本地根目录的解析结果：优先真实文件路径，否则退回 SAF UniFile。 */
private data class ResolvedRoot(val key: String, val name: String, val uni: UniFile, val realFile: File?)

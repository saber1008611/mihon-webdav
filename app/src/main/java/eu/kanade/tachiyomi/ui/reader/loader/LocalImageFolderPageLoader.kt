package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * SY --> 本地散图文件夹章节的页加载器（**懒加载 + 按需缓存**），把 [WebDavImageFolderPageLoader]
 * 的「不预下载整目录、按页按需取、后台预取」逻辑搬到本地图源。
 *
 * 背景：本地图源的散图文件夹原本走 [DirectoryPageLoader]——每页都直接 `openInputStream()` 读 FUSE
 * 挂载盘（CloudDrive2）。FUSE 背后是网络存储，每次直读都打穿到云端，来回翻页/整话超分反复读同一张
 * 图时尤其慢。这里改为：
 *  - [getPages] 只列目录（不读任何图片字节）；
 *  - [loadPage] 首次才把图片拷到 App 缓存（已存在零流量复用），置 [Page.State.Ready]；
 *  - 每页就绪后后台预取后续若干页，滑入可见区前已就绪，减轻底部「黑图」；
 *  - 超分按需进行（增强开关 + 预载页数窗口，2026-09-29 原「整话超分」开关已删除）。
 *
 * 与 WebDAV 版本的区别只在「取字节」这一步：WebDAV 是 HTTP 下载，这里是 FUSE 拷贝——但两者都落到
 * 同一份 App 缓存语义，复用同一套懒加载/预取/超分框架。
 */
internal class LocalImageFolderPageLoader(
    private val context: Context,
    private val dir: UniFile,
) : PageLoader() {

    // SY --> 整话超分：与 ArchivePageLoader / WebDavImageFolderPageLoader 一致，读一次偏好判断开关。
    private val readerPreferencesOrNull: ReaderPreferences?
        get() = (context.applicationContext as? App)?.graph?.readerPreferences
    // SY <--

    private var imageFiles: List<UniFile> = emptyList()
    private var loadedPages: List<ReaderPage>? = null

    /** 每张图片一把锁：并发（当前页 + 预取 + 整话超分）请求同一张时只拷一次。 */
    private val locks = ConcurrentHashMap<Int, Mutex>()

    /** 后台预取 / 整话超分的协程域；[recycle] 时取消。 */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        // 只列目录、不读任何图片字节。
        imageFiles = dir.listFiles()
            ?.filter { !it.isDirectory && ImageUtil.isImage(it.name.orEmpty()) { it.openInputStream() } }
            ?.sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
            ?: emptyList()
        logcat(LogPriority.DEBUG) { "LocalImageFolderPageLoader: ${imageFiles.size} 张图片 @ ${dir.uri}" }
        return imageFiles.mapIndexed { i, _ -> ReaderPage(i) }.also { loadedPages = it }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
        if (page.status == Page.State.Ready) return
        val file = ensureCached(page.index)
        if (file != null) {
            page.stream = { file.inputStream() }
            page.status = Page.State.Ready
        } else {
            page.status = Page.State.Error(IOException("图片读取失败：${imageFiles.getOrNull(page.index)?.name}"))
        }
        // 让后续若干页也提前拷到缓存（滑到即可立即解码，减少底部黑图）。
        prefetch(page.index)
    }

    /** 把第 [index] 张图片拷到本地缓存（并发安全；已缓存零流量）。 */
    private suspend fun ensureCached(index: Int): File? {
        val src = imageFiles.getOrNull(index) ?: return null
        val mutex = locks.getOrPut(index) { Mutex() }
        return mutex.withLock {
            runCatching {
                val cacheDir = File(context.cacheDir, LOCAL_IMG_SUBDIR).apply { mkdirs() }
                // SY --> 2026-10-01：改用共享 Hash.sha256。
                val key = Hash.sha256(src.uri.toString())
                // SY <--
                val cacheFile = File(cacheDir, key)
                if (!cacheFile.exists() || cacheFile.length() == 0L) {
                    src.openInputStream()?.use { input ->
                        cacheFile.outputStream().use { out -> input.copyTo(out) }
                    } ?: throw IOException("openInputStream 返回 null：${src.name}")
                }
                cacheFile
            }.onFailure { e ->
                logcat(LogPriority.WARN, e) { "LocalImageFolderPageLoader: 缓存失败 ${src.name}" }
            }.getOrNull()
        }
    }

    /**
     * 后台预取后续 [PREFETCH_AHEAD] 页（只拷贝；置 Ready 以便绑定即解码）。
     *
     * SY --> 2026-09-28：同 WebDavImageFolderPageLoader —— 预取**限制并发度为 3**，
     * 既不 5 路全量并发抢「正在出图那一页」的资源，也**不能串行**
     * （串行会让未缓存的页在队列里排队等拷贝，滑到时还没就绪 ⇒ 反而更卡）。
     * 顺序语义保留：仍按 `from+1 .. end` 依次投喂，先投的就是马上要看的。
     * SY <--
     */
    private val prefetchDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(3)

    private fun prefetch(from: Int) {
        val pages = loadedPages ?: return
        val end = minOf(from + PREFETCH_AHEAD, pages.size - 1)
        if (end <= from) return
        for (j in (from + 1)..end) {
            val p = pages[j]
            if (p.status == Page.State.Ready) continue
            workScope.launch(prefetchDispatcher) {
                val f = ensureCached(j) ?: return@launch
                if (p.status != Page.State.Ready) {
                    p.stream = { f.inputStream() }
                    p.status = Page.State.Ready
                }
            }
        }
    }

    override fun recycle() {
        super.recycle()
        workScope.cancel()
        // 缓存文件留在 context.cacheDir/local-images，由系统清理回收，这里不主动删。
    }

    private companion object {
        /** 本地散图缓存子目录（与 WebDAV 的 webdav-images 区分开，便于排查）。 */
        const val LOCAL_IMG_SUBDIR = "local-images"

        /**
         * 每页就绪后，向后预取多少页（拷贝到本地缓存）。
         * SY --> 2026-09-28：4 → 10。与 WebDavImageFolderPageLoader 同步（原值带宽闲置）。
         * SY <--
         */
        // SY --> 2026-09-28：用户要求「预加载改回 5 页」（与 WebDavImageFolderPageLoader 同口径）。
        // 窗口从 10 收回 5：滑动时并发下载 / 解码的页减少，CPU 与内存带宽留给 RenderThread。
        // SY <--
        const val PREFETCH_AHEAD = 5
    }
}

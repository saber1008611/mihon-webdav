package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.webdav.BaseWebDavSource
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
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * SY --> 散图文件夹章节的页加载器（**懒加载**）。
 *
 * 章节来源：目录里直接是图片、没有任何压缩包/电子书文件时，由
 * [eu.kanade.tachiyomi.source.webdav.BaseWebDavSource.getChapterList] 合成一个（或每个图片子目录一个）
 * 带 [WebDavSource.IMG_FOLDER_MARKER] 标记的章节。
 *
 * 旧实现进章即在 [getPages] 里把目录内**所有**图片逐张下载到本地缓存后才返回 —— 一卷全彩双页扫描
 * （实测「出包」卷 01 = 100 张 / 82.7MB / 平均 846KB）要等 82MB 下完才出第一页，就是
 * 「图片文件夹加载非常慢」的根因。
 *
 * 现改为：
 *  - [getPages] **只列目录**（一次 PROPFIND，几十毫秒即返回），页保持默认 [Page.State.Queue]；
 *  - 图片字节在 [loadPage] 时才按需下载（条漫的预绑定窗口会提前为离屏页调用 [loadPage]），
 *    下载后落盘缓存（已存在零流量复用），并把页置 [Page.State.Ready]；
 *  - 每页就绪后顺带**后台预取后续若干页**，让页在滑入可见区前已下载就绪，减轻底部「黑图」；
 *  - 超分按需进行（增强开关 + 预载页数窗口，2026-09-29 原「整话超分」开关已删除）。
 * SY <--
 */
internal class WebDavImageFolderPageLoader(
    private val context: Context,
    // SY 2026-10-05：放宽为 BaseWebDavSource —— 网盘源（115/夸克）同样支持散图文件夹章节。
    private val source: BaseWebDavSource,
    private val folderPath: String,
) : PageLoader() {

    // SY --> 整话超分：与 ArchivePageLoader 一致，读一次偏好判断整话超分开关。
    private val readerPreferencesOrNull: ReaderPreferences?
        get() = (context.applicationContext as? App)?.graph?.readerPreferences
    // SY <--

    private var imageNames: List<String> = emptyList()
    private var loadedPages: List<ReaderPage>? = null

    /** 每张图片一把锁：并发（当前页 + 预取 + 整话超分）请求同一张时只下一次。 */
    private val locks = ConcurrentHashMap<Int, Mutex>()

    /** 后台预取 / 整话超分的协程域；[recycle] 时取消。 */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override var isLocal: Boolean = true

    // SY --> 2026-09-28：WebDAV 逐张 HTTP 下载，必须把条漫预绑定窗口拉远，
    // 让 `loadPage → downloadImageToCache` 跑在用户滑到之前。
    // 见 [eu.kanade.tachiyomi.ui.reader.loader.PageLoader.preloadDistanceExtra]。
    override val preloadDistanceExtra: Int = 4

    override suspend fun getPages(): List<ReaderPage> {
        // 只列目录、不下载任何图片（一次 PROPFIND）。
        imageNames = source.listImageFiles(folderPath)
        logcat(LogPriority.DEBUG) { "WebDavImageFolderPageLoader: ${imageNames.size} 张图片 @ $folderPath" }
        return imageNames.mapIndexed { i, _ -> ReaderPage(i) }.also { loadedPages = it }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
        if (page.status == Page.State.Ready) return
        val file = ensureDownloaded(page.index)
        if (file != null) {
            page.stream = { file.inputStream() }
            page.status = Page.State.Ready
        } else {
            page.status = Page.State.Error(IOException("图片下载失败：${imageNames.getOrNull(page.index)}"))
        }
        // 让后续若干页也提前下载好（滑到即可立即解码，减少底部黑图）。
        prefetch(page.index)
    }

    /** 下载第 [index] 张图片到本地缓存（并发安全；已缓存零流量）。 */
    private suspend fun ensureDownloaded(index: Int): File? {
        val name = imageNames.getOrNull(index) ?: return null
        val mutex = locks.getOrPut(index) { Mutex() }
        return mutex.withLock {
            runCatching { source.downloadImageToCache(folderPath, name) }
                .onFailure { e -> logcat(LogPriority.WARN, e) { "WebDavImageFolderPageLoader: 下载失败 $name" } }
                .getOrNull()
        }
    }

    /**
     * 后台预取后续 [PREFETCH_AHEAD] 页（只下载；置 Ready 以便绑定即解码）。
     *
     * SY --> 2026-09-28 最终定案（经过多轮真机对照后）：
     *   · **不要「滑动时不取图」**（ReaderScrollGate 门控）—— 那是死路：
     *     门控把预取挂起 ⇒ 用户停下时下一页**才刚开始下载** ⇒ 「停下要半秒才出图」；
     *     而当前页的 loadPage 又不受门控 ⇒ 滑动时仍在下载 ⇒ 「滑动仍然卡」。
     *     两头都没讨好。用户实测明确否了这条路线。
     *   · 正确姿势：**尽早、持续地预取**，把 HTTP 下载藏到用户看不到的时间里。
     *   · 并发 `limitedParallelism(2)`：顺序优先（先投的先下），且不给「正在出图那页」
     *     与滚动渲染抢太多资源。
     * SY <--
     */
    private val prefetchDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2)

    private fun prefetch(from: Int) {
        val pages = loadedPages ?: return
        val end = minOf(from + PREFETCH_AHEAD, pages.size - 1)
        if (end <= from) return
        for (j in (from + 1)..end) {
            val p = pages[j]
            if (p.status == Page.State.Ready) continue
            workScope.launch(prefetchDispatcher) {
                val f = ensureDownloaded(j) ?: return@launch
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
        // 缓存文件留在 context.cacheDir/webdav-images，由缓存上限/系统清理回收，这里不主动删。
    }

    private companion object {
        /**
         * 每页就绪后，向后预取多少页（下载到本地缓存）。
         * SY --> 2026-09-28：4 → 10。原值太小（4 张图几百毫秒就下完，带宽大量闲置，
         * 用户反馈「网速没拉满」）。提到 10 后翻页/滑动时约 10 张在途并发下载。
         * SY <--
         */
        // SY --> 2026-09-28：用户要求「预加载改回 5 页」。
        // §四十九 曾把散图预取窗口从 5 提到 10 以压「底部黑图」；但窗口越大，滑动时
        // 同时下载 / 解码的页越多 ⇒ CPU 与内存带宽被后台预取抢走 ⇒ RenderThread 掉帧
        // （真机 TB371FC 实测：绘制指令慢帧 183、Janky 33%）。回退到 5 页。
        // SY <--
        // SY --> 2026-09-28：窗口 5 → 10（用户要求实测）。
        //
        // 关键认知修正：本地图源与 WebDAV 访问的**是同一个 CloudDrive**，两者都有网络延迟。
        // 但本地图源走的是 CloudDrive 的 **FUSE 挂载**，它自己在内核层做预读/缓存，
        // 能盖住 RTT；而 WebDAV 是**逐条 HTTP 请求**，没有这层预读 ⇒
        // 只能靠加大本窗口 + 并发来把 RTT 藏起来。
        // 故这里只加大 WebDAV 的窗口；本地图源（已流畅）保持 5，不动好的那条路。
        // SY <--
        const val PREFETCH_AHEAD = 10
    }
}

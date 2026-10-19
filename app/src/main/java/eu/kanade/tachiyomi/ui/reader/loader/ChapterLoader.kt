package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import logcat.LogPriority
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format
import eu.kanade.tachiyomi.source.webdav.BaseWebDavSource
import eu.kanade.tachiyomi.source.webdav.LocalWebDavSource
import eu.kanade.tachiyomi.source.webdav.WebDavCoverStore
import eu.kanade.tachiyomi.ui.reader.loader.LocalImageFolderPageLoader

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterCache: ChapterCache,
    private val manga: Manga,
    private val source: Source,
) {

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter) {
        if (chapterIsReady(chapter)) {
            return
        }

        chapter.state = ReaderChapter.State.Loading
        withIOContext {
            logcat { "Loading pages for ${chapter.chapter.name}" }
            try {
                val loader = getPageLoader(chapter)
                chapter.pageLoader = loader

                val pages = loader.getPages()
                    .onEach { it.chapter = chapter }

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }

                // If the chapter is partially read, set the starting page to the last the user read
                // otherwise use the requested page.
                if (!chapter.chapter.read) {
                    chapter.requestedPage = chapter.chapter.last_page_read
                }

                chapter.state = ReaderChapter.State.Loaded(pages)
            } catch (e: Throwable) {
                chapter.state = ReaderChapter.State.Error(e)
                throw e
            }
        }
    }

    /**
     * Checks [chapter] to be loaded based on present pages and loader in addition to state.
     */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean {
        return chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null
    }

    /**
     * Returns the page loader to use for this [chapter].
     */
    private suspend fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val dbChapter = chapter.chapter

        // SY --> 2026-09-28 封面保存策略：打开章节时「顺手」把该漫画封面抽出来落盘
        // （后台守护协程、同 key 去重、失败静默）。此后书架网格 / 历史 / 书签 / 文件夹视图
        // 全部命中磁盘缓存、零网络请求，不必先逛一遍书架才有封面。见 WebDavCoverStore。
        // SY 2026-10-05：统一按 BaseWebDavSource 泛化 —— 网盘源（115/夸克）同样适用，
        // backend 由各源自带，不再逐后端 hard-cast。
        if (source is BaseWebDavSource) {
            WebDavCoverStore.generateAsync(context, source.backend, manga.url, source)
        }
        // SY <--

        val isDownloaded = downloadManager.isChapterDownloadedOnDisk(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            source,
        )
        val loader = when {
            isDownloaded -> DownloadPageLoader(
                chapter,
                manga,
                source,
                downloadManager,
                downloadProvider,
            )
            source is LocalSource -> source.getFormat(chapter.chapter).let { format ->
                when (format) {
                    is Format.Directory -> DirectoryPageLoader(format.file)
                    is Format.Archive -> ArchivePageLoader(format.file.archiveReader(context))
                    is Format.Epub -> EpubPageLoader(format.file.epubReader(context))
                    // SY --> PDF：Android 内置 PdfRenderer 逐页渲染，无第三方依赖。
                    is Format.Pdf -> PdfPageLoader(format.file)
                    // SY <--
                }
            }
            // SY --> 路线 A 方案 1：先把章节归档/电子书流式下载到缓存，再用现成阅读器读页。
            // 压缩包走 ArchivePageLoader（libarchive），epub 走 EpubPageLoader（专门解析 OPF）。
            // SY --> 2026-09-28：补上散图文件夹章节（url 带 IMG_FOLDER_MARKER）的接线。
            // 原先这里只处理归档，散图章节会误入 getArchiveFile 而失败；WebDavImageFolderPageLoader
            // 本来就是为它写的（按需下载 + PREFETCH_AHEAD 预取），一直没被实例化。
            // SY 2026-10-05：泛化为「远端流式后端」= WebDAV + 网盘源（115/夸克），
            // 本地图源（isRemoteStreamingBackend=false）落回下面自己的分支。
            source is BaseWebDavSource && source.isRemoteStreamingBackend -> {
                val url = chapter.chapter.url
                if (url.endsWith(BaseWebDavSource.IMG_FOLDER_MARKER)) {
                    WebDavImageFolderPageLoader(
                        context,
                        source,
                        url.removeSuffix(BaseWebDavSource.IMG_FOLDER_MARKER).trimStart('/'),
                    )
                } else {
                    // SY --> 2026-09-28 流式化（性能/磁盘修复，实测选型）：此前这里一律 getArchiveFile，
                    // 即「先把整本下到 cache/webdav-archives 再读」。而 WebDavSource 早就备好了
                    // 三个 Range 流式入口（getArchiveHandle/getPdfSource/getStreamSource）却
                    // **全仓库 0 处调用**——实测用户库该目录仅 11 个文件就吃掉 1.2G。
                    //
                    // 但**不是所有格式都该走流式**，2026-09-28 在真机上量了同一本 222MB 的 PDF 卷
                    // （手机侧 curl，服务器 20.5MB/s）：
                    //   · 整本下载（旧路径）          11.4s，占 222MB 磁盘
                    //   · pdfium 走 Range 逐块读      31.4s ← pdfium 打开时**会通读整个文件**，
                    //                                  拆成 1MB Range 请求只是多付请求开销（慢 2.6 倍）
                    //   · zip 只拉中央目录尾部 64KB   47ms  ← 流式对 ZIP 是压倒性优势
                    // 故最终只把 **zip/cbz** 切到流式（RemoteZipReader 直读中央目录，每页只拉该条目），
                    // pdf / epub / tar / cbt / rar / 7z 保持整本下载（行为与旧版一致）。
                    // 整本下载的目录另有 1GB LRU 上限兜底（见 WebDavSource.ARCHIVE_CACHE_BYTES）。
                    // SY 2026-10-05：网盘源（115/夸克）的章节 url 是 id 段（无扩展名），
                    // 分流必须按「真实文件名」（源的 id→名字缓存，浏览时已填充）；
                    // WebDAV/本地源 fileNameOf 就是原样末段，行为不变。
                    val fileName = source.fileNameOf(url)
                    when (fileName.substringAfterLast('.', "").lowercase()) {
                        "zip", "cbz" -> {
                            // SY 2026-09-29：补上「正向」日志。此前只有回退时才 WARN，走流式时无痕，
                            // 无法从 logcat 判断流式到底有没有生效（排查时踩过）。
                            logcat(LogPriority.INFO) { "ChapterLoader: WebDAV 流式打开 zip（不整本下载）$url" }
                            ArchivePageLoader(source.getArchiveHandle(chapter.chapter))
                        }
                        // SY --> 2026-09-28：PDF 二选一 —— 先探 /Prev 链长再决定。
                        //  浅链（正常/线性化，1~2 跳）→ 流式：首屏几次 Range 往返、零磁盘；
                        //  深链（「每页一个增量 revision」拼出的卷实测 205 跳）→ 整本下载兜底
                        //  （流式要串行跳几百次 xref，还被 1MB 取块放大到约 200MB 流量，不划算）。
                        // 见 WebDavSource.openPdfChapter / util.pdf.probePdfStreamability。
                        "pdf" -> when (val handle = source.openPdfChapter(chapter.chapter)) {
                            is BaseWebDavSource.PdfChapterHandle.Stream -> PdfPageLoader(handle.source)
                            is BaseWebDavSource.PdfChapterHandle.File -> PdfPageLoader(handle.file)
                        }
                        // SY <--
                        // SY --> 2026-10-01：MOBI/AZW（Palm DB 容器）接线。
                        // 此前 `isMobiFile` 已把这些文件**列成章节**（见 WebDavSource.getChapterList），
                        // 但这里没有对应分支 ⇒ 落到 else 的 `getArchiveFile` → `ArchivePageLoader`
                        // （libarchive 不认识 PDB 容器）⇒「能点开、打不开」。改走 `MobiPageLoader`：
                        // 开章只做「文件头 + 记录表 + record0」的小段 Range 读拿到页布局，
                        // 每一页的图片 record 在真正读该页时才按需拉取（小段随机读，不整本下载）。
                        "mobi", "azw", "azw3" -> {
                            logcat(LogPriority.INFO) { "ChapterLoader: WebDAV 流式打开 MOBI（PDB record）$url" }
                            MobiPageLoader(context, source.getMobiSource(chapter.chapter), url)
                        }
                        // SY <--
                        else -> source.getArchiveFile(chapter.chapter).let { file ->
                            when (file.extension?.lowercase()) {
                                "epub" -> EpubPageLoader(file.epubReader(context))
                                "pdf" -> PdfPageLoader(file)
                                else -> ArchivePageLoader(file.archiveReader(context))
                            }
                        }
                    }
                    // SY <--
                }
            }
            // SY <--
            // SY --> 本地文件夹版 WebDAV 图源（LocalWebDavSource）：与 WebDavSource 同款逻辑，
            // 根换成用户选择的本地文件夹。散图文件夹章节（url 带 IMG_FOLDER_MARKER）走 LocalImageFolderPageLoader。
            source is LocalWebDavSource -> {
                val url = chapter.chapter.url
                if (url.endsWith(LocalWebDavSource.IMG_FOLDER_MARKER)) {
                    val dir = source.resolveImageFolderDir(url)
                        ?: throw IllegalStateException("本地图源：散图文件夹不存在 $url")
                    LocalImageFolderPageLoader(context, dir)
                } else {
                    // SY --> 2026-09-29：本地图源也走**流式**（zip/cbz、pdf）。
                    //
                    // 修前这里**一律 `getArchiveFile`**，即「先把整本拷进 cache/localwebdav-archives
                    // 再读」。真机实测：打开 `画师/247020_...zip` 实打实拷了 **441MB**，
                    // 紧接着另一个 zip 又拷 **122MB** —— 用户报「流式打开 zip 没生效」即指此。
                    //
                    // 本地随机读几乎零成本（真实路径直接 `RandomAccessSource` 打开，不走 HTTP、
                    // 不经 SAF 跨进程流），所以 zip/cbz 与 pdf 都改走**已有**的流式入口；
                    // `LocalWebDavSource.getArchiveHandle/getPdfSource` 早写好了却一直没被调用。
                    //
                    // epub / tar / rar / 7z / mobi 等仍需真实文件（libarchive 无法直接吃随机读源），
                    // 保持整本拷贝，行为与旧版一致。
                    when (url.substringAfterLast('.', "").lowercase()) {
                        "zip", "cbz" -> {
                            logcat(LogPriority.INFO) { "ChapterLoader: 本地图源流式打开 zip（不整本拷贝）$url" }
                            ArchivePageLoader(source.getArchiveHandle(chapter.chapter))
                        }
                        "pdf" -> {
                            logcat(LogPriority.INFO) { "ChapterLoader: 本地图源流式打开 pdf（不整本拷贝）$url" }
                            PdfPageLoader(source.getPdfSource(chapter.chapter))
                        }
                        // SY --> 2026-10-01：MOBI/AZW 接线（与 WebDAV 侧同理）。
                        // 本地随机读几乎零成本（真实路径直接打开 RandomAccessSource），走 `MobiPageLoader`
                        // 逐页随机读内嵌图片 record，无需整本拷贝。见 LocalWebDavSource.getMobiSource。
                        "mobi", "azw", "azw3" -> {
                            logcat(LogPriority.INFO) { "ChapterLoader: 本地图源流式打开 MOBI（PDB record）$url" }
                            MobiPageLoader(context, source.getMobiSource(chapter.chapter), url, isLocal = true)
                        }
                        // SY <--
                        else -> source.getArchiveFile(chapter.chapter).let { file ->
                            when (file.extension?.lowercase()) {
                                "epub" -> EpubPageLoader(file.epubReader(context))
                                "pdf" -> PdfPageLoader(file)
                                else -> ArchivePageLoader(file.archiveReader(context))
                            }
                        }
                    }
                    // SY <--
                }
            }
            // SY <--
            source is HttpSource -> HttpPageLoader(chapter, source, chapterCache)
            source is StubSource -> error(context.stringResource(MR.strings.source_not_installed, source.toString()))
            else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
        }
        // SY --> 2026-09-29 修复「深链 PDF 读了之后占位图不换真封面」：
        // 上面构建 loader 时深链 PDF 已经整本下载到缓存，此刻再补一次封面生成，
        // 就能零请求从本地缓存抽真封面；抽成后 invalidateCoverImage 让网格立即刷新。
        // SY 2026-10-05：统一按 BaseWebDavSource 泛化（网盘源 115/夸克同样适用）。
        if (source is BaseWebDavSource) {
            WebDavCoverStore.generateNowIfMissing(context, source.backend, manga.url, source)
        }
        // SY <--
        return loader
    }
}

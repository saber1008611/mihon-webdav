package eu.kanade.tachiyomi.source.webdav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.CloudSyncDirectory
import eu.kanade.tachiyomi.data.download.CloudSyncEntry
import eu.kanade.tachiyomi.data.download.normalizePath
import eu.kanade.tachiyomi.source.FolderSource
import eu.kanade.tachiyomi.source.HeavyChapterLoadSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFolder
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.pdf.PdfiumRenderer
import eu.kanade.tachiyomi.util.pdf.probePdfStreamability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.core.archive.ArchiveHandle
import mihon.core.archive.RandomAccessSource
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.service.ChapterRecognition
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * SY --> 双源公共基类：WebDavSource（远端 WebDAV）与 LocalWebDavSource（本地文件夹）的浏览/章节/封面/
 * 文件夹视图/排序逻辑**逐字相同**，仅后端调用不同。本类承载全部共享逻辑，后端差异抽成
 * `protected abstract` / `open` 接缝（listDirectories/listFiles/downloadImageToCache/downloadChapterToCache/
 * openRangeSource/getFoldersPaths/coverAuthority/backend/…），两个子类只实现自己的后端。
 *
 * 关键语义约定（避免回归）：
 *  - `tryHandlePdfCoverLocally` 默认 `false`（本地源不处理 PDF 深链占位图，行为与本地源原版一致）；
 *    远端源 override 为完整逻辑。
 *  - `openPdfRenderer` 抽象 `PdfiumRenderer.openProxy(context, ras)`（远端）与 `PdfiumRenderer.open(ras)`（本地）。
 *  - `downloadCoverEntry` 抽象 cover.* 下载路径（远端 HTTP、本地 SAF）。
 *  - `hasPlaceholderCover` 默认 `false`（远端源 override）。
 * SY <--
 */
abstract class BaseWebDavSource protected constructor(
    protected val context: Context,
) : Source, UnmeteredSource, FolderSource, HeavyChapterLoadSource {

    // SY --> 文件夹视图：当前限定的根目录（相对路径），null = 列出所有根目录的漫画合集
    private var activeFolderPath: String? = null
    // SY <--

    abstract override val id: Long
    abstract override val name: String
    override val lang: String = "other"
    override val supportsLatest: Boolean = true
    override fun toString() = name

    /** 稳定的排序 Filter 实例（单例，见 WebDavSource 原注释）。 */
    private val sortFilter = object : Filter.Sort("排序", arrayOf("最近更新", "名称")) {}

    // ===================== 后端接缝（子类实现） =====================

    /**
     * 当前后端是否已配置（未选文件夹 / 未填 Cookie → 优雅降级）。
     * SY 2026-10-05：放宽为 public —— 封面 Provider（按 authority 路由到对应源）需要用它短路。
     */
    abstract fun isConfigured(): Boolean

    /**
     * 封面存储后端（决定封面缓存目录 / Provider authority 路由 / generateAsync 的源分发）。
     * SY 2026-10-05：放宽为 public —— 调用点统一按 `is BaseWebDavSource` 泛化后需要读它。
     */
    abstract val backend: WebDavCoverStore.Backend

    /**
     * SY 2026-10-05：是否**远端流式**后端（WebDAV / 115 / 夸克 = true，本地文件夹源 = false）。
     *
     * 用来把「阅读页加载器 / 打开 PDF 章节」这批调用点里的 `is WebDavSource` 分支泛化成
     * `is BaseWebDavSource && isRemoteStreamingBackend`：网盘源与 WebDAV 走同一条
     * 「Range 流式 + 深链 PDF 二选一」路径，而本地图源有自己独立的分支（SAF / 本地目录）。
     */
    open val isRemoteStreamingBackend: Boolean get() = true

    /** 封面 content:// 的 authority（WebDavCoverProvider / LocalWebDavCoverProvider 不同）。 */
    protected abstract val coverAuthority: String

    /** 扫描封面用的本地缓存目录名（webdav-covers / localwebdav-covers）。 */
    protected abstract val coverCacheDirName: String

    /** 整本下载缓存子目录名（webdav-archives / localwebdav-archives）。 */
    protected abstract val archiveCacheSubdir: String

    /** 文件夹封面 content:// 的中间段（两源均为 "folder"）。 */
    protected abstract val folderCoverSegment: String

    protected abstract suspend fun listDirectories(path: String): List<CloudSyncDirectory>
    protected abstract suspend fun listFiles(path: String): List<CloudSyncEntry>
    protected abstract fun getFoldersPaths(): List<String>
    /** 把指定散图下载到本地缓存（已存在零流量复用），返回落盘文件。供外部 [WebDavImageFolderPageLoader] 调用。 */
    abstract suspend fun downloadImageToCache(folderPath: String, fileName: String): File
    protected abstract suspend fun downloadChapterToCache(remotePath: String): File
    protected abstract fun openRangeSource(remotePath: String): RandomAccessSource?

    /** cover.* 命中后把该文件取到 coverDir，返回落盘文件或 null。 */
    protected abstract suspend fun downloadCoverEntry(actual: CloudSyncEntry, mangaUrl: String, coverDir: File): File?

    /** PDF 首页渲染：远端走 openProxy，本地走 open。 */
    protected abstract fun openPdfRenderer(ras: RandomAccessSource): PdfiumRenderer

    /**
     * PDF 深链占位图处理。默认 false（本地源不处理，直接走 Range/本地抽取）。
     * 远端源 override 为完整逻辑（本地缓存优先 / 深链写占位图）。
     */
    protected open suspend fun tryHandlePdfCoverLocally(
        targetPath: String,
        baseName: String,
        title: String,
        coverFile: File,
    ): Boolean = false

    /** 深链 PDF 是否已写好占位封面（远端源 override；本地源返回 false）。 */
    protected open fun hasPlaceholderCover(base: String): Boolean = false

    /**
     * 章节子目录并发枚举的专用 IO 调度器。远端 WebDAV 瓶颈在往返次数、并发度取 6；
     * 本地图源是跨进程（SAF/FUSE）调用，取 4 更稳妥。子类按自身后端 override。
     */
    protected open val subdirListDispatcher = Dispatchers.IO.limitedParallelism(6)

    // ===================== 浏览 / 章节 / 排序 =====================

    override suspend fun getPopularManga(page: Int): MangasPage = withIOContext {
        MangasPage(applySort(browseMangaList(), defaultLatest = false), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = withIOContext {
        MangasPage(applySort(browseMangaList(), defaultLatest = true), false)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        val q = query.trim()
        val all = browseMangaList()
        val result = if (q.isBlank()) all else all.filter { it.manga.title.contains(q, ignoreCase = true) }
        MangasPage(applySort(result, defaultLatest = false), false)
    }

    // SY --> 文件夹视图：限定了根目录时只列该目录下的漫画，否则列出所有根目录的合集
    private suspend fun browseMangaList(): List<WebDavMangaListItem> {
        return activeFolderPath?.let { listMangaInDirectory(it) } ?: listAllManga()
    }

    /**
     * 列出某个目录下的漫画：该目录的直接子目录即多章节漫画；若目录里直接是章节文件
     * （pdf/cbz/epub 等），则每个文件也当作一本单文件漫画。
     */
    private suspend fun listMangaInDirectory(dirPath: String): List<WebDavMangaListItem> {
        if (!isConfigured()) return emptyList()
        return runCatching {
            // SY --> 2026-09-30：进目录的「子目录 + 文件」两次枚举并行，并接入 DirListingCache。
            val (dirs, fileEntries) = coroutineScope {
                val dirsDeferred = async {
                    DirListingCache.directories(context, id, dirPath) { listDirectories(dirPath) }
                }
                val filesDeferred = async {
                    DirListingCache.entries(context, id, dirPath) { listFiles(dirPath) }
                }
                dirsDeferred.await() to filesDeferred.await()
            }
            // SY <--
            val dirMangas = dirs.map { dir ->
                val url = dir.path.trimStart('/')
                val manga = SManga.create().apply {
                    title = dir.name
                    this.url = url
                    thumbnail_url = coverContentUri(url)
                }
                WebDavMangaListItem(manga, dir.lastModified)
            }
            // SY --> 单文件漫画：目录里直接是章节文件，也当作一本漫画（url 即文件全路径）
            // MOBI/AZW 家族同样算（其内嵌图片记录可当章节读，见 MobiPageLoader）。
            val fileMangas = fileEntries
                .filter { isChapterFile(it.name) || isMobiFile(it.name) }
                .map { entry ->
                    val url = entry.path.trimStart('/')
                    val title = entry.name.substringBeforeLast('.', entry.name)
                    val manga = SManga.create().apply {
                        this.title = title
                        this.url = url
                        thumbnail_url = coverContentUri(url)
                    }
                    WebDavMangaListItem(manga, entry.lastModified)
                }
            // SY <--
            val result = dirMangas + fileMangas
            // SY --> 2026-09-30：进目录后后台预热封面（有界 + 跳过已缓存，见 prefetchCovers）。
            prefetchCovers(result.map { it.manga.url })
            // SY <--
            result
        }.getOrElse { e ->
            logcat(LogPriority.ERROR, e) { "$name: failed to list manga in $dirPath" }
            emptyList()
        }
    }
    // SY <--

    // SY --> 2026-09-30：进目录后预热封面（有界 + 跳过已缓存）。
    private val coverPrefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coverPrefetchSemaphore = Semaphore(1)
    private val COVER_PREFETCH_LIMIT = 48
    // SY 2026-10-06：进目录预取的取直链并发压到 1（单线程），并与「扫描封面单线程」同档错峰，
    // 防「打开未扫描大文件夹」一次性砸 ~48 次 115 风控取链接口（proapi.115.com/downurl）。2 线程仍会被踢。
    private val COVER_PREFETCH_STAGGER_MS = 1500L
    private fun prefetchCovers(urls: List<String>) {
        if (urls.isEmpty()) return
        val toFetch = urls.asSequence().take(COVER_PREFETCH_LIMIT)
            .filter { WebDavCoverStore.existingCoverFile(context, backend, it) == null }
            // SY 2026-10-06 修复：占位封面（深链 PDF 等）也算「已处理」——否则进目录时
            // prefetchCovers 对每个占位图再发一次 115 downlink，挤占全局 0.67 r/s 闸，
            // 把用户打开漫画/翻页的真实请求全堵在后面。占位图命中即跳过。
            .filter { !coverPlaceholderExists(it) }
            .toList()
        if (toFetch.isEmpty()) return
        coverPrefetchScope.launch {
            toFetch.forEach { url ->
                launch {
                    coverPrefetchSemaphore.withPermit {
                        // 错峰：每领到一本就先等一下，把取直链请求在时间轴上摊平（与 2 线程扫描同策略）
                        delay(COVER_PREFETCH_STAGGER_MS)
                        runCatching { ensureCover(url) }
                    }
                }
            }
        }
    }

    /** 该 url 是否已写过占位封面（深链 PDF 等）；命中则与真实封面同待遇，不再请求 115。 */
    private fun coverPlaceholderExists(url: String): Boolean = hasPlaceholderCover(url.replace('/', '_'))
    // SY <--

    // SY <--

    /**
     * 列出所有漫画：根目录的直接子目录即漫画文件夹（单层）。
     * 列表只列目录、不预拉封面——封面改填 content:// 直链，由 Coil 按需懒加载。
     */
    private suspend fun listAllManga(): List<WebDavMangaListItem> {
        if (!isConfigured()) return emptyList()
        return runCatching {
            getFoldersPaths()
                .flatMap { folder ->
                    runCatching { listDirectories(folder) }.getOrElse { emptyList() }
                }
                .map { dir ->
                    val url = dir.path.trimStart('/')
                    val manga = SManga.create().apply {
                        title = dir.name
                        this.url = url
                        thumbnail_url = coverContentUri(url)
                    }
                    WebDavMangaListItem(manga, dir.lastModified)
                }
                .distinctBy { it.manga.url }
        }.getOrElse { e ->
            logcat(LogPriority.ERROR, e) { "$name: failed to list manga" }
            emptyList()
        }
    }

    /** SY --> 文件夹视图的根目录列表：供「下拉刷新 / 扫描封面」标记 stale 用。 */
    fun folderPaths(): List<String> = getFoldersPaths()
    // SY <--

    /** 列表项 + 目录修改时间（毫秒），用于「最近更新」排序。 */
    private data class WebDavMangaListItem(val manga: SManga, val lastModified: Long)

    /**
     * 按用户在筛选对话框选的排序（sortFilter）对列表排序。
     * 用户未选（state == null）时：最新 tab 默认按目录修改时间倒序；其它默认保持原顺序。
     * 用户已选：index 0 = 最近更新；index 1 = 名称（数字自然序）；ascending 控制升降序。
     */
    private fun applySort(items: List<WebDavMangaListItem>, defaultLatest: Boolean): List<SManga> {
        val sel = sortFilter.state
        val sorted = if (sel == null) {
            if (!defaultLatest) items else items.sortedByDescending { it.lastModified }
        } else {
            when (sel.index) {
                0 -> if (sel.ascending) items.sortedBy { it.lastModified } else items.sortedByDescending { it.lastModified }
                1 -> items.sortedWith { a, b ->
                    // SY 2026-09-29：名称按「数字自然序」排（第2话<第10话），不再按首字符字典序
                    if (sel.ascending) a.manga.title.compareToCaseInsensitiveNaturalOrder(b.manga.title)
                    else b.manga.title.compareToCaseInsensitiveNaturalOrder(a.manga.title)
                }
                else -> items
            }
        }
        return sorted.map { it.manga }
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withIOContext {
        // 未配置时保留现有数据，绝不返回空章节把已入库的章节冲掉。
        val configured = isConfigured()
        val updatedChapters = if (fetchChapters && configured) getChapterList(manga) else chapters
        SMangaUpdate(manga, updatedChapters)
    }

    private suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        if (!isConfigured()) return@withIOContext emptyList()
        val url = manga.url.trimStart('/')
        // SY --> 单文件漫画：manga.url 本身就是章节文件路径，直接返回单章
        // （MOBI/AZW 家族也算可读章节：MobiPageLoader 把内嵌图片记录逐条当页）
        // SY 2026-10-06：判定改用**真实文件名** —— 网盘挂载目录的 url 是 id 段（无扩展名），
        // 用路径末段判会把 mobi/pdf 误当文件夹（封面全失败、章节列不出的真因）。
        val realName = fileNameOf(url)
        if (isChapterFile(realName) || isMobiFile(realName)) {
            val name = realName.substringBeforeLast('.', realName)
            return@withIOContext listOf(
                SChapter.create().apply {
                    this.url = url
                    this.name = name
                    chapter_number = 1f
                },
            )
        }
        // SY <--
        runCatching {
            // SY --> 统一构造章节：url 取文件全路径；name 取文件名；章号按标题+文件名解析。
            fun newChapter(chPath: String, rawName: String): SChapter =
                SChapter.create().apply {
                    this.url = chPath.trimStart('/')
                    this.name = rawName.substringBeforeLast('.', rawName)
                    chapter_number = ChapterRecognition
                        .parseChapterNumber(manga.title, this.name, this.chapter_number.toDouble())
                        .toFloat()
                }
            // SY <--

            // SY --> 2026-09-30：本章目录的「文件 + 子目录」两次枚举并行，并接入 DirListingCache。
            val (entries, subDirs) = coroutineScope {
                val filesDeferred = async {
                    DirListingCache.entries(context, id, url) { listFiles(url) }
                }
                val dirsDeferred = async {
                    DirListingCache.directories(context, id, url) { listDirectories(url) }
                }
                filesDeferred.await() to dirsDeferred.await()
            }
            // SY <--

            val chapters = entries
                // SY --> MOBI/AZW 家族纳入章节（详见 MobiPageLoader）
                .filter { isChapterFile(it.name) || isMobiFile(it.name) }
                .map { newChapter(it.path, it.name) }
                .toMutableList()

            // SY --> 章节子目录扁平化：本库常把分卷/话放进子目录，直接子目录里的可读章节文件同样逐个列成章节；
            // 若子目录里没有章节文件、只有图片，则合成一个散图文件夹章节（url 带 IMG_FOLDER_MARKER）。
            // SY --> 2026-09-30：子目录枚举并发（原本 for 串行 = N 次串行 PROPFIND/跨进程往返）。
            val subResults = coroutineScope {
                subDirs.map { sub ->
                    async(subdirListDispatcher) {
                        sub to runCatching {
                            DirListingCache.entries(context, id, sub.path) { listFiles(sub.path) }
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            // SY <--
            for ((sub, subFiles) in subResults) {
                // SY 2026-10-01 D6：扩展名只解析一次，章节过滤与图片判定复用，避免同一列表两次遍历重复 substringAfterLast。
                val filesWithExt = subFiles.map { it to it.name.substringAfterLast('.', "").lowercase() }
                val subChapters = filesWithExt.filter { (f, _) -> isChapterFile(f.name) || isMobiFile(f.name) }
                if (subChapters.isNotEmpty()) {
                    subChapters.forEach { (f, _) -> chapters += newChapter(f.path, f.name) }
                } else if (filesWithExt.any { (_, ext) -> ext in IMAGE_EXTENSIONS }) {
                    chapters += SChapter.create().apply {
                        this.url = "${sub.path.trimStart('/')}$IMG_FOLDER_MARKER"
                        name = sub.name
                        chapter_number = ChapterRecognition
                            .parseChapterNumber(manga.title, this.name, 0.0)
                            .toFloat()
                    }
                }
            }

            // 整目录散图：目录里直接是图片、既无章节文件也无章节子目录时，把整目录图片当作一个章节。
            if (chapters.isEmpty()) {
                val hasImages = entries.any {
                    it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                }
                if (hasImages) {
                    chapters += SChapter.create().apply {
                        this.url = "$url$IMG_FOLDER_MARKER"
                        name = manga.title
                        chapter_number = 1f
                    }
                }
            }
            // SY <--
            chapters.sortedWith { c1, c2 -> c2.name.compareToCaseInsensitiveNaturalOrder(c1.name) }
        }.getOrElse { e ->
            logcat(LogPriority.ERROR, e) { "$name: failed to list chapters for ${manga.url}" }
            emptyList()
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw UnsupportedOperationException("$name pages are loaded by ChapterLoader")

    override fun getFilterList(): FilterList = FilterList(sortFilter)

    // SY --> 文件夹视图（FolderSource）：进源先列根目录，点进后才是漫画列表
    override suspend fun getFolders(): List<SourceFolder> = withIOContext {
        if (!isConfigured()) return@withIOContext emptyList()
        getFoldersPaths().map { path ->
            SourceFolder(
                url = path,
                name = path.substringAfterLast('/', path).ifBlank { path },
                coverUrl = "content://$coverAuthority/$folderCoverSegment/${Uri.encode(path)}",
            )
        }
    }

    override suspend fun setActiveFolder(folderUrl: String?) {
        activeFolderPath = folderUrl?.let { normalizePath(it) }?.takeIf { it.isNotBlank() }
    }

    // ===================== 阅读句柄（共享，后端经 openRangeSource） =====================

    /**
     * SY --> 流式读：为某一章构建「不整本下载」的归档句柄（zip/cbz 走远程随机读 + 页级缓存；
     * 非标准 ZIP 回落整本下载）。
     */
    suspend fun getArchiveHandle(chapter: SChapter): ArchiveHandle = withIOContext {
        val ras = openRangeSource(chapter.url)
            ?: throw IllegalStateException("$name: 未配置或路径不存在：${chapter.url}")
        try {
            CachingArchiveHandle(RemoteZipReader(ras))
        } catch (e: RemoteZipReader.RemoteZipUnsupportedException) {
            ras.close()
            logcat(LogPriority.WARN) { "$name: RemoteZipReader 不支持，回退整本读取: ${chapter.url}" }
            getArchiveFile(chapter).archiveReader(context)
        }
    }

    /** 共用的随机读源：base 不关心远端还是本地，交给 openRangeSource。 */
    fun getPdfSource(chapter: SChapter): RandomAccessSource = runCatchingGetRange(chapter)
    fun getMobiSource(chapter: SChapter): RandomAccessSource = runCatchingGetRange(chapter)
    fun getStreamSource(chapter: SChapter): RandomAccessSource = runCatchingGetRange(chapter)

    /**
     * SY 2026-10-05：章节逻辑路径对应的**真实文件名**（带扩展名）。
     *
     * WebDAV / 本地源的章节 url 就是文件路径，末段即名字；网盘源（115/夸克）的 url 是
     * **id 段拼接**（cid/pickcode/fid），无扩展名 —— 子类 override 用「id → 名字」缓存解析，
     * ChapterLoader 据此按扩展名分流（zip 流式 / pdf 链长探测 / mobi / 其它整本下载）。
     */
    open suspend fun fileNameOf(url: String): String = url.trimStart('/').substringAfterLast('/')

    private fun runCatchingGetRange(chapter: SChapter): RandomAccessSource =
        openRangeSource(chapter.url) ?: throw IllegalStateException("$name: 未配置或路径不存在：${chapter.url}")

    /**
     * 把某一章的归档/电子书落到本地缓存目录后返回其 UniFile（zip/cbz 走 Range 流式时不会走到这里）。
     */
    suspend fun getArchiveFile(chapter: SChapter): UniFile = withIOContext {
        val file = downloadChapterToCache(chapter.url)
        UniFile.fromFile(file) ?: throw IllegalStateException("$name: 无法把缓存文件包装为 UniFile ${chapter.url}")
    }

    /**
     * SY --> 散图文件夹：列出目录里的图片文件名（自然排序），供 WebDavImageFolderPageLoader 读页。
     */
    suspend fun listImageFiles(folderPath: String): List<String> = withIOContext {
        if (!isConfigured()) return@withIOContext emptyList()
        listFiles(folderPath)
            .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
            .map { it.name }
    }

    // ===================== 封面解析 =====================

    /**
     * SY --> 文件夹封面：取该目录下（自然排序）第一个漫画目录的封面。
     * 漫画自身无 cover 时 resolveCover 会继续回退到第一话首页。
     */
    suspend fun resolveFolderCover(folderPath: String, coverDir: File): File? = withIOContext {
        if (!isConfigured()) return@withIOContext null
        runCatching {
            val normalized = normalizePath(folderPath)
            val firstDir = listDirectories(normalized)
                .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
                .firstOrNull()
            if (firstDir != null) {
                resolveCover(firstDir.path.trimStart('/'), coverDir)
            } else {
                // SY --> 文件夹里没有子目录（直接是章节文件）时，取第一个章节文件首页当封面
                val baseName = "${normalized.trimStart('/').replace('/', '_')}__folder"
                val coverFile = File(coverDir, "$baseName.jpg")
                val tmpFile = File(coverDir, "$baseName.jpg.tmp")
                tmpFile.delete()
                val sortedFiles = listFiles(normalized)
                    .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
                // SY --> 章节候选里并入 MOBI/AZW（只用于取封面；它们仍不能当章节打开）
                val firstChapter = sortedFiles.firstOrNull { isChapterFile(it.name) || isCoverOnlyFile(it.name) }
                // SY <--
                if (firstChapter != null) {
                    // SY --> B1：深链 PDF 的封面策略（本地缓存优先 / 深链写占位图）
                    if (tryHandlePdfCoverLocally(firstChapter.path, baseName, normalized.trimStart('/').substringAfterLast('/'), coverFile)) {
                        return@withIOContext coverFile.takeIf { it.exists() && it.length() > 0L }
                    }
                    // SY <--
                    runCatching {
                        extractFirstImageFromRemote(firstChapter.path.trimStart('/'), tmpFile)
                    }.onFailure { e ->
                        logcat(LogPriority.WARN, e) { "$name: 文件夹封面抽取失败 $normalized" }
                    }
                } else {
                    // SY --> 散图文件夹：没有章节文件，用目录里第一张「非空白」图片作封面
                    val images = sortedFiles
                        .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
                        .take(COVER_PROBE_CANDIDATES)
                    for (entry in images) {
                        runCatching {
                            copyToFile(downloadImageToCache(normalized, entry.name).inputStream(), tmpFile)
                        }.onFailure { e ->
                            logcat(LogPriority.WARN, e) { "$name: 文件夹散图封面失败 $normalized" }
                        }
                        if (tmpFile.exists() && tmpFile.length() > 0L && !CoverQuality.isBlank(tmpFile)) break
                    }
                    // SY <--
                }
                if (tmpFile.exists() && tmpFile.length() > 0L && !CoverQuality.isBlank(tmpFile)) {
                    if (tmpFile.renameTo(coverFile)) coverFile else null.also { tmpFile.delete() }
                } else {
                    tmpFile.delete()
                    null
                }
                // SY <--
            }
        }.onFailure { e ->
            logcat(LogPriority.WARN, e) { "$name: folder cover failed $folderPath" }
        }.getOrNull()
    }
    // SY <--

    /**
     * SY --> 封面解析（供 WebDavCoverProvider / LocalWebDavCoverProvider 按需调用）：
     * 1. 优先远端/本地 cover.{jpg,webp,png,jpeg}；2. 没有 cover 时回退到「第一个章节的第一张图片」。
     * 结果统一落到 coverDir/{mangaUrl}.jpg，下次 exists 判断会直接命中，不会重复抽封面。
     * SY <--
     */
    suspend fun resolveCover(mangaUrl: String, coverDir: File): File? = withIOContext {
        if (!isConfigured()) return@withIOContext null
        val trimmed = mangaUrl.trimStart('/')

        // SY --> 单文件漫画：mangaUrl 即章节文件全路径，直接下载并抽首页作封面
        // （MOBI/AZW 这类「只能取封面、不能当章节读」的电子书也算，见 COVER_ONLY_EXTS）
        // SY 2026-10-06：判定改用**真实文件名**（网盘挂载目录的 url 是 id 段，无扩展名）
        val realName = fileNameOf(trimmed)
        if (isChapterFile(realName) || isCoverOnlyFile(realName)) {
            val baseName = trimmed.replace('/', '_')
            val coverFile = File(coverDir, "$baseName.jpg")
            // SY --> B1：深链 PDF 的封面策略（本地缓存优先 / 深链写占位图）
            if (tryHandlePdfCoverLocally(trimmed, baseName, trimmed.substringAfterLast('/'), coverFile)) {
                return@withIOContext coverFile.takeIf { it.exists() && it.length() > 0L }
            }
            // SY <--
            val tmpFile = File(coverDir, "$baseName.jpg.tmp")
            tmpFile.delete()
            runCatching { extractFirstImageFromRemote(trimmed, tmpFile) }.onFailure { e ->
                logcat(LogPriority.WARN, e) { "$name: 单文件封面抽取失败 $trimmed" }
            }
            return@withIOContext if (tmpFile.exists() && tmpFile.length() > 0L && !CoverQuality.isBlank(tmpFile)) {
                downsampleCover(tmpFile) // SY
                if (tmpFile.renameTo(coverFile)) coverFile else null.also { tmpFile.delete() }
            } else {
                tmpFile.delete()
                null
            }
        }
        // SY <--

        // 0) 磁盘已命中 → 零请求
        val baseName = mangaUrl.replace('/', '_')
        val coverFile = File(coverDir, "$baseName.jpg")
        cachedCoverOrNull(coverDir, baseName)?.let { return@withIOContext it }

        val entries = listFiles(trimmed)
            .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }

        // 1) 远端/本地 cover.* 优先：只对列表中确认存在的名字发请求（至多 1 次）
        for (ext in COVER_FILE_EXTS) {
            val actual = entries.firstOrNull { it.name.equals("cover.$ext", ignoreCase = true) } ?: continue
            val dst = File(coverDir, "$baseName.$ext")
            downloadCoverEntry(actual, trimmed, coverDir)
                ?.takeIf { it.exists() && it.length() > 0L && !CoverQuality.isBlank(it) }
                ?.let { return@withIOContext it }
            runCatching { dst.delete() }
        }

        // 2) 回退：第一话首页（或散图文件夹的第一张图片）——复用上面那份 entries，不再重复列目录
        runCatching {
            val sortedFiles = entries
            val tmpFile = File(coverDir, "$baseName.jpg.tmp")
            tmpFile.delete()
            var ok = false
            // SY --> 章节候选 = 可读章节 + MOBI 家族（后者只用于取封面）
            val firstChapter = sortedFiles.firstOrNull { isChapterFile(it.name) || isCoverOnlyFile(it.name) }
            logcat(LogPriority.DEBUG) {
                "$name: 封面候选 $mangaUrl → 目录 ${sortedFiles.size} 项，选中=${firstChapter?.name ?: "无"}"
            }
            // SY <--
            if (firstChapter != null) {
                // SY --> B1：深链 PDF 的封面策略（本地缓存优先 / 深链写占位图）
                if (tryHandlePdfCoverLocally(firstChapter.path, baseName, trimmed.substringAfterLast('/'), coverFile)) {
                    return@withIOContext coverFile.takeIf { it.exists() && it.length() > 0L }
                }
                // SY <--
                runCatching { extractFirstImageFromRemote(firstChapter.path.trimStart('/'), tmpFile) }
                    .onFailure { e ->
                        logcat(LogPriority.WARN, e) { "$name: 封面回退失败 ${firstChapter.path}" }
                    }
                ok = tmpFile.exists() && tmpFile.length() > 0L
            } else {
                // SY --> 散图文件夹：没有章节文件，用目录里第一张「非空白」图片作封面
                val images = sortedFiles
                    .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
                    .take(COVER_PROBE_CANDIDATES)
                for (entry in images) {
                    runCatching {
                        copyToFile(downloadImageToCache(mangaUrl, entry.name).inputStream(), tmpFile)
                    }.onFailure { e ->
                        logcat(LogPriority.WARN, e) { "$name: 散图封面抽取失败 $mangaUrl" }
                    }
                    if (tmpFile.exists() && tmpFile.length() > 0L && !CoverQuality.isBlank(tmpFile)) {
                        ok = true
                        break
                    }
                }
                // SY <--
            }
            if (ok) {
                downsampleCover(tmpFile) // SY
                if (tmpFile.renameTo(coverFile)) coverFile else null.also { tmpFile.delete() }
            } else {
                tmpFile.delete()
                null
            }
        }.onFailure { e ->
            logcat(LogPriority.WARN, e) { "$name: 封面回退失败 $mangaUrl" }
        }.getOrNull()
    }

    /**
     * SY --> 扫描封面（书架「扫描封面」批量入口）：对 resolveCover 跑一遍；磁盘缓存已有这张封面就零流量跳过。
     */
    suspend fun ensureCover(mangaUrl: String): Boolean = withIOContext {
        // SY --> 必须与网格 CoverProvider 共用同一封面缓存目录。
        val coverDir = File(context.filesDir, coverCacheDirName).apply { mkdirs() }
        val base = mangaUrl.replace('/', '_')
        val candidates = listOf("jpg", "webp", "png", "jpeg").map { File(coverDir, "$base.$it") }
        val cached = candidates.firstOrNull { it.exists() && it.length() > 0 && !CoverQuality.isBlank(it) }
        if (cached != null) {
            // SY --> 历史遗留的全尺寸封面：命中缓存也补一次降采样（>450px 才动，之后即零成本）
            downsampleCover(cached)
            // SY <--
            return@withIOContext true
        }
        // SY 2026-10-06 修复：占位封面（深链 PDF 等）已落盘即视为完成，避免「打开章节 / 进目录预热」
        // 每次都把占位图当未命中、重跑 resolveCover 砸 115（每次 resolveCover 都走一次 0.67 r/s 闸的
        // downlink 请求）。占位图只在扫描时解析一次，之后一律命中。
        // SY 2026-10-06 修订：但「用户读过该卷」后本地整本缓存已就绪——此刻必须零请求从本地抽真封面、
        // 把占位图升级成真封面，否则网格永远停在占位图（"阅读后封面不换"的真因）。判定只查本地缓存、
        // 不碰网络：本地缓存命中 → 落下去跑 resolveCover（内部 handlePdfDeepChainCover step① 本地抽取）；
        // 本地缓存未命中 → 仍未读过，保持占位、跳过重抽（不请求 115）。
        if (hasPlaceholderCover(base)) {
            // SY 2026-10-08 修复：「是否已读过」改由占位图 sidecar 记录的深链章节路径查缓存。
            // 单文件漫画 mangaUrl 即章节路径（旧判定兼容保留），但文件夹漫画的 mangaUrl 是目录、
            // 缓存按章节路径存——旧判定永远 miss → 占位图读后永远不升级（"阅读后封面不换"第二根因）。
            // sidecar 由 writeDeepChainPlaceholder 落盘；旧版占位图无 sidecar → 回退 mangaUrl 判定。
            val deepChapterPath = WebDavCoverStore.deepChainChapterPath(context, base)
            val readCached = when {
                deepChapterPath != null -> cachedArchiveFile(deepChapterPath) != null
                else -> cachedArchiveFile(mangaUrl) != null
            }
            if (readCached) {
                logcat(LogPriority.INFO) { "$name: 占位封面命中本地缓存，升级真封面（零请求）$mangaUrl" }
                // 不 return，落下去跑 resolveCover → 深链 PDF 改从本地抽真封面
            } else {
                logcat(LogPriority.INFO) { "$name: 占位封面已存在且未读，跳过重抽（不请求 115）$mangaUrl" }
                return@withIOContext true
            }
        }
        // SY --> 缓存里只有「空白页」时不能跳过，否则坏版本时期写下的纯白图会被永久固化
        candidates.firstOrNull { it.exists() && it.length() > 0 }?.let {
            logcat(LogPriority.WARN) { "$name: 缓存封面是空白页，重新抽取 $mangaUrl" }
        }
        // SY <--
        runCatching { resolveCover(mangaUrl, coverDir) }
            .onFailure { logcat(LogPriority.WARN, it) { "$name: 扫描封面失败 $mangaUrl" } }
            .getOrNull()
            ?.let { return@withIOContext true }
        // SY --> B1：深链 PDF 的真实封面目录故意保持为空（占位图另存于 fallback 目录），
        // 所以这里把「已写占位」也算作成功，否则扫描会把它们全统计成失败。
        if (hasPlaceholderCover(base)) {
            logcat(LogPriority.WARN) { "$name: 深链 PDF 用占位封面（读过该卷后会自动换成真封面）$mangaUrl" }
            return@withIOContext true
        }
        // SY <--
        // SY --> 诊断：解析成功但没有任何产物（早退/无候选）时也要留痕，避免再出现「失败但零日志」。
        logcat(LogPriority.WARN) { "$name: 封面无产物（目录无可用章节/图片，或格式不支持）$mangaUrl" }
        false
    }
    // SY <--

    /**
     * SY --> 封面抽取统一入口（全格式流式，绝不整本下载）：zip/cbz/epub 直读 ZIP 中央目录、
     * tar/cbt 扫前几十个头块、pdf 用 pdfium 渲染首页、mobi 取内嵌封面，都只拉「第一张图」的量。
     * rar/cbr/7z/cb7 等无法随机访问的格式（或流式失败）直接跳过。
     */
    private suspend fun extractFirstImageFromRemote(remotePath: String, outFile: File) = withIOContext {
        // SY 2026-10-06：扩展名取**真实文件名**（网盘挂载目录的 url 是 id 段，无扩展名，
        // 否则 zip/mobi/pdf 全部落进「无流式抽取器」分支，封面全失败）。
        val ext = fileNameOf(remotePath).substringAfterLast('.', "").lowercase()
        if (ext in RANGE_STREAM_EXTS) {
            if (!isConfigured()) {
                logcat(LogPriority.WARN) { "$name: 未配置，封面跳过 $remotePath" }
                return@withIOContext
            }
            val ras = openRangeSource(remotePath) ?: return@withIOContext
            val handle = try {
                if (ext in TAR_EXTS) RemoteTarReader(ras) else RemoteZipReader(ras)
            } catch (e: IOException) {
                ras.close()
                logcat(LogPriority.WARN, e) { "$name: 封面流式不支持，跳过 $remotePath" }
                null
            }
            val ok = handle?.let {
                runCatching { it.use { h -> extractFirstImageFromHandle(h, outFile, remotePath) } }
                    .onFailure { e -> logcat(LogPriority.WARN, e) { "$name: 封面流式抽取失败，跳过 $remotePath" } }
                    .getOrDefault(false)
            } == true
            if (!ok) runCatching { outFile.delete() }
            return@withIOContext
        }
        // SY --> MOBI / AZW：PDB 容器，从 record0 的 EXTH 取 coverOffset，只读那一条 record
        if (ext in MOBI_EXTS) {
            if (!isConfigured()) {
                logcat(LogPriority.WARN) { "$name: 未配置，封面跳过 $remotePath" }
                return@withIOContext
            }
            val ras = openRangeSource(remotePath) ?: return@withIOContext
            var ok = false
            try {
                ok = RemoteMobiCover(ras).extractCover(outFile)
                if (!ok) {
                    logcat(LogPriority.WARN) { "$name: MOBI 无可识别封面 record，跳过 $remotePath" }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "$name: MOBI 封面抽取失败，跳过 $remotePath" }
            } finally {
                runCatching { ras.close() }
            }
            if (!ok) runCatching { outFile.delete() }
            return@withIOContext
        }
        // SY <--
        if (ext != "pdf") {
            // 诊断：明确写出「为什么这本没封面」，避免再出现「失败但零日志」的黑盒
            logcat(LogPriority.WARN) { "$name: 封面跳过（无流式抽取器 .$ext）：$remotePath" }
            return@withIOContext
        }
        // SY --> B1 兜底闸门：深链 PDF 走 Range 抽首页要串行 205 跳，调用方通常已在 resolveCover 里拦下；
        // 这里再兜一次，防止未来新增路径绕过。
        if (isDeepChainPdfOrPlaceholder(remotePath)) {
            logcat(LogPriority.WARN) { "$name: 深链 PDF 跳过 Range 抽封面（应由占位图兜底）$remotePath" }
            return@withIOContext
        }
        // SY <--
        val ras = openRangeSource(remotePath) ?: return@withIOContext
        runCatching {
            openPdfRenderer(ras).use { r ->
                if (r.pageCount <= 0) return@use
                // SY --> 跳过开头的空白页：扫描件第 1 页常是白页（留白页/装订背页），直接渲染第 1 页
                // 会得到一张纯白封面并「成功」入库。这里按序探到第一张有内容的页为止。
                val last = minOf(r.pageCount, COVER_PROBE_CANDIDATES) - 1
                var picked = -1
                for (i in 0..last) {
                    r.renderPageJpegFile(i, outFile, COVER_MIN_WIDTH, COVER_MAX_WIDTH, COVER_JPEG_QUALITY)
                    if (!CoverQuality.isBlank(outFile)) {
                        picked = i
                        break
                    }
                    logcat(LogPriority.WARN) {
                        "$name: PDF 第 ${i + 1} 页是空白页，继续探下一页 $remotePath"
                    }
                }
                when {
                    picked > 0 ->
                        logcat(LogPriority.WARN) { "$name: PDF 封面改用第 ${picked + 1} 页 $remotePath" }
                    picked < 0 -> {
                        logcat(LogPriority.WARN) {
                            "$name: PDF 前 ${last + 1} 页全为空白，放弃封面 $remotePath"
                        }
                        runCatching { outFile.delete() }
                    }
                }
                // SY <--
            }
        }.onFailure { e ->
            ras.close()
            runCatching { outFile.delete() }
            logcat(LogPriority.WARN, e) { "$name: PDF 流式封面失败，跳过 $remotePath" }
        }
    }

    /**
     * 深链 PDF 的兜底闸门（默认 false；远端源 override 为真实探测）。
     * 放这里是为了让 extractFirstImageFromRemote 的 pdf 分支能统一调用，而不必关心后端。
     */
    protected open suspend fun isDeepChainPdfOrPlaceholder(remotePath: String): Boolean = false

    /**
     * SY --> 从任意 ArchiveHandle（远程流式或本地整本）抽「第一张图片」写入 outFile。
     * 与 extractFirstImage 的归档分支同规则（自然排序取首个图片条目），仅依赖窄接口。
     */
    private fun extractFirstImageFromHandle(handle: ArchiveHandle, outFile: File, tag: String): Boolean {
        outFile.parentFile?.mkdirs()
        var total = 0
        // SY --> 取「前若干张」候选（原本只取第 1 张）：第 1 张是纯白页时直接跳过它，避免把空白图当封面写进缓存。
        val candidates = handle.useEntries { entries ->
            val all = entries.toList()
            total = all.size
            all
                // 用本文件的 IMAGE_EXTENSIONS 判定，而不是 ImageUtil.isImage：
                // 后者只登记了 "jpg"，会把 zip 里常见的 .jpeg 条目漏掉。
                .filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
                .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
                .take(COVER_PROBE_CANDIDATES)
        }
        if (candidates.isEmpty()) {
            logcat(LogPriority.WARN) { "$name: 归档内无图片条目（共 $total 条），跳过 $tag" }
            return false
        }
        for (entry in candidates) {
            val src = handle.getInputStream(entry.name)
            if (src == null) {
                logcat(LogPriority.WARN) { "$name: 归档条目读取失败（${entry.name}），跳过 $tag" }
                continue
            }
            src.use { copyToFile(it, outFile) }
            if (outFile.length() <= 0L) {
                logcat(LogPriority.WARN) { "$name: 归档首图写出为空（${entry.name}），跳过 $tag" }
                continue
            }
            if (!CoverQuality.isBlank(outFile)) return true
            logcat(LogPriority.WARN) { "$name: 归档条目是空白页，试下一条（${entry.name}）$tag" }
        }
        logcat(LogPriority.WARN) { "$name: 归档前 ${candidates.size} 张图全为空白，放弃封面 $tag" }
        runCatching { outFile.delete() }
        return false
    }
    // SY <--

    private fun copyToFile(input: InputStream, outFile: File) {
        input.use { src -> outFile.outputStream().use { dst -> src.copyTo(dst) } }
    }

    // SY --> Komiho 同款封面后处理（MAX_PX=450 / JPEG q80）：抽出的首图往往是 1500-3500px 原图，
    // Coil 每个网格格子都要整幅解码，书架滚动/扫描后刷新就慢。统一降采样到最长边 ≤450px 再压成 JPEG。
    private fun downsampleCover(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || maxOf(width, height) <= COVER_MAX_PX) return
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= COVER_MAX_PX) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return
        val scale = COVER_MAX_PX.toFloat() / maxOf(decoded.width, decoded.height)
        val bmp = if (scale < 1f && decoded.width > 0 && decoded.height > 0) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        val tmp = File(file.parentFile, file.name + ".ds")
        runCatching {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, COVER_DOWNSAMPLE_QUALITY, it) }
            if (tmp.length() > 0 && file.length() > 0) {
                file.delete()
                tmp.renameTo(file)
            } else {
                tmp.delete()
            }
        }.onFailure { e ->
            tmp.delete()
            logcat(LogPriority.DEBUG, e) { "$name: 封面降采样失败 ${file.name}" }
        }
        bmp.recycle()
    }
    // SY <--

    /**
     * 整本缓存里是否已有该章节（与 [downloadChapterToCache] 的命名规则一致）。
     * SY 2026-10-06：改 protected open —— 「云盘」门面要把网盘挂载目录的缓存查询
     * 重定向到网盘自己的归档缓存目录（否则深链 PDF「读后从本地缓存抽真封面」的
     * 兜底对网盘永远失效：下载落在 quark-archives 等，查询却在 webdav-archives 找）。
     */
    protected open fun cachedArchiveFile(remotePath: String): File? {
        val fullPath = remotePath.trimStart('/')
        if (fullPath.lastIndexOf('/') <= 0) return null
        val file = File(File(context.cacheDir, archiveCacheSubdir), fullPath.replace('/', '_'))
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    // SY --> 2026-10-05：PDF 流式路由上提到基类（原先只在 WebDavSource，网盘源 115/夸克同样需要）。
    // 只依赖接缝（openRangeSource / getArchiveFile / archiveCacheSubdir / openPdfRenderer），
    // WebDAV 与网盘共用；本地图源不override（isDeepChainPdfOrPlaceholder 默认 false，行为不变）。

    /**
     * 已判定「PDF /Prev 链过深、流式不划算」的章节 url（内存级，随进程）。
     * 深链文件一律走整本下载；下载后归档缓存里就有本地文件，后续打开由
     * [cachedArchiveFile] 直接命中，不会再次探测。
     */
    private val pdfDeepChainUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 已判定「浅链、可流式」的章节 url（内存级）。**必须记住**：探测有约 1.5s 的固定开销，
     * 若每次打开都重探，像那本 684MB 的卷（流式 80ms 出页）反而被 1.5s 探测拖累。
     */
    private val pdfStreamableUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * PDF 章节的「**能流式就流式，流式不划算才退回整本下载**」入口。
     * 判据是 [probePdfStreamability] 量出的 `/Prev` 链长：
     *  - **浅链**（≤ 默认跳数）→ [PdfChapterHandle.Stream]，首屏几次 Range 往返、零磁盘；
     *  - **深链**（实测 205 跳，xref 数据散布在 224MB 里）→ [PdfChapterHandle.File] 整本下载兜底。
     * 判定顺序：① 整本缓存命中 → 直接读；② 已知深链 → 直接下载；③ 已知浅链 → 直接流式（不重探）；
     * ④ 否则探测一次并记住结论。
     */
    suspend fun openPdfChapter(chapter: SChapter): PdfChapterHandle = withIOContext {
        // ① 整本缓存命中：零成本（重复打开同一卷就走这条，实测 pdfium 本地打开 24ms）。
        cachedArchiveFile(chapter.url)?.let { cached ->
            return@withIOContext PdfChapterHandle.File(
                UniFile.fromFile(cached)
                    ?: throw IllegalStateException("$name: 无法把缓存文件包装为 UniFile"),
            )
        }
        // ② 已知深链：直接整本下载。
        if (pdfDeepChainUrls.contains(chapter.url)) {
            logcat(LogPriority.WARN) { "$name: PDF 深链（已判过）→ 整本下载: ${chapter.url}" }
            return@withIOContext PdfChapterHandle.File(getArchiveFile(chapter))
        }
        // ③ 已知浅链：直接流式，**不重探**（探测有约 1.5s 固定开销）。
        if (pdfStreamableUrls.contains(chapter.url)) {
            logcat(LogPriority.WARN) { "$name: PDF 浅链（已判过）→ 流式: ${chapter.url}" }
            return@withIOContext PdfChapterHandle.Stream(
                openRangeSource(chapter.url)
                    ?: throw IllegalStateException("$name: 未配置或路径不存在：${chapter.url}"),
            )
        }

        // ④ 探测链长后二选一。
        val source = openRangeSource(chapter.url)
            ?: throw IllegalStateException("$name: 未配置或路径不存在：${chapter.url}")
        val t0 = System.currentTimeMillis()
        val probe = runCatching { probePdfStreamability(source) }.getOrNull()
        val elapsed = System.currentTimeMillis() - t0
        val size = runCatching { source.size }.getOrDefault(-1L)
        // `isLocalFallback` 只在 WebDAV/网盘共用的适配器上有（本地源直接本地随机读，没有该状态）。
        val localFallback = (source as? WebDavRandomAccessSource)?.isLocalFallback == true
        if (probe != null && (probe.chainEnded || localFallback)) {
            // `isLocalFallback`：服务器不支持 Range 时适配器已自己整本落盘并转为本地随机读，
            // 此时链长探测没有意义，且若判成「深链」会再下载第二份。
            val why = if (probe.chainEnded) "浅链" else "已整本回退"
            logcat(LogPriority.WARN) {
                "$name: PDF $why → 流式 hops=${probe.hops} 探测=${elapsed}ms size=${size}B: ${chapter.url}"
            }
            pdfStreamableUrls.add(chapter.url)
            return@withIOContext PdfChapterHandle.Stream(source)
        }
        runCatching { source.close() }
        pdfDeepChainUrls.add(chapter.url)
        logcat(LogPriority.WARN) {
            "$name: PDF 深链 → 整本下载 hops=${probe?.hops ?: -1} 探测=${elapsed}ms size=${size}B: ${chapter.url}"
        }
        PdfChapterHandle.File(getArchiveFile(chapter))
    }

    /** [openPdfChapter] 的两种结果：流式随机读源 / 整本下载后的本地文件。 */
    sealed interface PdfChapterHandle {
        /** 流式：交给 [eu.kanade.tachiyomi.ui.reader.loader.PdfPageLoader] 持有并负责 close。 */
        class Stream(val source: RandomAccessSource) : PdfChapterHandle

        /** 兜底：整本下载到应用缓存后的本地文件。 */
        class File(val file: UniFile) : PdfChapterHandle
    }

    /**
     * 该 PDF 是否「深链」（`/Prev` 链过长）——复用 [openPdfChapter] 的结论；未判过时用
     * **极小预算**探一次并记住（探测成本与跳数成正比，封面场景更不能多探：4 跳 / 800ms 封顶）。
     * `isLocalFallback`（服务器不支持 Range、适配器已整本落盘为本地读）直接算浅链。
     */
    private suspend fun isDeepChainPdf(targetPath: String): Boolean {
        val path = targetPath.trimStart('/')
        if (pdfDeepChainUrls.contains(path)) return true
        if (pdfStreamableUrls.contains(path)) return false
        val source = openRangeSource(path) ?: return false
        return try {
            val probe = runCatching {
                probePdfStreamability(source, maxHops = COVER_PDF_PROBE_HOPS, timeBudgetMs = COVER_PDF_PROBE_BUDGET_MS)
            }.getOrNull()
            val deep =
                (source as? WebDavRandomAccessSource)?.isLocalFallback != true &&
                    (probe == null || !probe.chainEnded)
            if (deep) pdfDeepChainUrls.add(path) else pdfStreamableUrls.add(path)
            logcat(LogPriority.WARN) {
                "$name: PDF 封面链长探测 hops=${probe?.hops ?: -1} " +
                    "→ ${if (deep) "深链(占位)" else "浅链(抽取)"} $path"
            }
            deep
        } finally {
            runCatching { source.close() }
        }
    }

    /** 供远端子类（WebDAV / 网盘）的 [isDeepChainPdfOrPlaceholder] 委托。 */
    protected suspend fun isRemotePdfDeepChain(remotePath: String): Boolean = isDeepChainPdf(remotePath)

    /**
     * 从**本地**整本缓存抽 PDF 首页写 [outFile]（零网络）；成功返回 true。
     * 整页渲染成字节后一次性落盘，避免失败留下半截文件。
     */
    private fun extractPdfFirstPageFromLocal(local: File, outFile: File): Boolean = runCatching {
        val bytes = ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfiumRenderer.open(pfd).use { r ->
                if (r.pageCount <= 0) return false
                r.renderPageJpegBytes(0, COVER_MIN_WIDTH, COVER_MAX_WIDTH, COVER_JPEG_QUALITY)
            }
        }
        if (bytes.isEmpty()) return false
        outFile.outputStream().use { it.write(bytes) }
        outFile.exists() && outFile.length() > 0L
    }.getOrDefault(false)

    /**
     * B1 深链 PDF 封面策略的完整实现（WebDAV / 网盘源共用）：
     *  ① 该卷已在整本缓存里（用户读过）→ 从**本地**抽首页，**零请求**；
     *  ② 量 `/Prev` 链长（极小预算）：深链 → 写占位图 + 放弃抽取；
     *  ③ 浅链 → 交回调用方走 Range 抽取（只需几次请求）。
     * 深链不能用 Range 抽：这类卷实测 `/Prev` 链 **205 跳**，抽一张封面要串行 ~230 次请求 / ~232MB，
     * 网盘按调用次数风控时足以把账号踢下线。
     */
    protected suspend fun handlePdfDeepChainCover(
        targetPath: String,
        baseName: String,
        title: String,
        coverFile: File,
    ): Boolean {
        // SY 2026-10-08 修复：网盘源的 targetPath 是 **id 段拼接（无扩展名）**，原判定直接 return false，
        // 深链 PDF 占位策略对全部网盘源（115 Open / 夸克 / 阿里）从未生效（115 Open 实测暴露）。
        // 改用 fileNameOf 解析真实文件名（网盘子类走 id→名字缓存；WebDAV 默认末段，行为不变）。
        val realName = runCatching { fileNameOf(targetPath) }.getOrNull().orEmpty()
        val ext = realName.ifBlank { targetPath }.substringAfterLast('.', "").lowercase()
        if (ext != "pdf") return false
        cachedArchiveFile(targetPath)?.let { local ->
            if (extractPdfFirstPageFromLocal(local, coverFile)) {
                logcat(LogPriority.WARN) { "$name: PDF 封面改用本地缓存抽取（零请求）$targetPath" }
                return true
            }
        }
        if (!isDeepChainPdf(targetPath)) return false
        // SY 2026-10-08：记录深链章节路径（sidecar），供 ensureCover 读后升级判定零网络命中
        // （文件夹漫画的 mangaUrl 是目录，旧判定 cachedArchiveFile(mangaUrl) 永远 miss）。
        WebDavCoverStore.writeDeepChainPlaceholder(context, baseName, title, targetPath)
        logcat(LogPriority.WARN) { "$name: 深链 PDF 跳过 Range 抽封面（改占位图）$targetPath" }
        return true
    }
    // SY <--

    /**
     * 判断文件名是否为支持的章节格式（共 10 种）：压缩包 zip/cbz/rar/cbr/7z/cb7/tar/cbt（libarchive 解压），
     * 外加 epub（EpubReader）与 pdf（pdfium 逐页渲染）。
     */
    private fun isChapterFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_CHAPTER_EXTENSIONS
    }

    private val SUPPORTED_CHAPTER_EXTENSIONS = setOf(
        "zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt", "epub", "pdf",
    )

    /**
     * SY --> 「只取封面、不当章节读」的电子书格式（MOBI/AZW 家族）。
     * 为什么单独一组：它们不能进 SUPPORTED_CHAPTER_EXTENSIONS（阅读器没有 MOBI 解码器，进了就变成
     * 「能点开但打开失败」）。但封面是能取的，所以只在选封面候选时把它们算进来。
     */
    private fun isCoverOnlyFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in COVER_ONLY_EXTS
    }

    private val COVER_ONLY_EXTS = setOf("mobi", "azw", "azw3")

    /** MOBI 家族（可流式取封面的电子书）。 */
    private val MOBI_EXTS = setOf("mobi", "azw", "azw3")

    /**
     * SY --> 是否为 MOBI/AZW 家族文件。既然 RemoteMobiCover 已能从 PDB 容器里按 record 读出内嵌图片，
     * 这些「漫画类 MOBI」的内嵌图片记录就能直接当页读，因此在章节识别里也算可读章节。
     */
    internal fun isMobiFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in MOBI_EXTS
    }
    // SY <--

    /** 散图文件夹识别用的图片扩展名。 */
    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif", "tif", "tiff",
    )

    /** 远端/本地封面候选文件名（与两个 Provider / [WebDavCoverStore] 同序：先命中的优先）。 */
    private val COVER_FILE_EXTS = listOf("jpg", "webp", "png", "jpeg")

    /** 磁盘已命中的封面文件（空白页不算命中）；零请求快速返回用。 */
    private fun cachedCoverOrNull(coverDir: File, baseName: String): File? =
        COVER_FILE_EXTS.firstNotNullOfOrNull { ext ->
            File(coverDir, "$baseName.$ext")
                .takeIf { it.exists() && it.length() > 0L && !CoverQuality.isBlank(it) }
        }

    /**
     * 生成封面 content:// 直链（CoverProvider）。Coil 加载该 URI 时在可见项才触发一次带鉴权下载，
     * 天然懒加载、不阻塞列表；详情页也走同一条通道。
     */
    private fun coverContentUri(mangaUrl: String): String =
        "content://$coverAuthority/${Uri.encode(mangaUrl)}"

    companion object {
        /**
         * 散图文件夹章节的 url 标记：目录里直接是图片、没有任何章节文件时，给合成章节的 url
         * 追加此后缀以区分「整目录图片」章节；ChapterLoader 据此分流到 WebDavImageFolderPageLoader。
         */
        const val IMG_FOLDER_MARKER = "#__webdav_img__"

        /** 散图文件夹单张图片的磁盘缓存目录（两源共用 cache/webdav-images）。 */
        protected const val IMG_CACHE_SUBDIR = "webdav-images"

        /** 散图缓存 256MB LRU。 */
        protected const val IMG_CACHE_BYTES = 256L * 1024 * 1024

        // SY --> 2026-10-05：PDF 封面/流式相关常量随逻辑一并上提（原在 WebDavSource）。

        /**
         * 封面场景的 `/Prev` 链长探测预算：比打开章节更省（封面不值得花 1.5s 去探）。
         * 4 跳 ≈ 1s，足够区分「1~2 跳的浅链」与「205 跳的深链」。
         */
        private const val COVER_PDF_PROBE_HOPS = 4
        private const val COVER_PDF_PROBE_BUDGET_MS = 1200L

        /** PDF 封面渲染宽度钳位与 JPEG 质量（比阅读器小，省内存也够清晰）。 */
        private const val COVER_MIN_WIDTH = 600
        private const val COVER_MAX_WIDTH = 1200
        private const val COVER_JPEG_QUALITY = 85
        // SY <--
        // SY --> Komiho 同款封面降采样目标（MAX_PX=450 / JPEG_QUALITY=80）
        private const val COVER_MAX_PX = 450
        private const val COVER_DOWNSAMPLE_QUALITY = 80
        // SY <--

        /**
         * SY --> 取封面时最多向后探查多少个「候选」（PDF 的页 / 归档的图片条目）。
         * 扫描件第 1 页经常是白页，只认第 1 页就会得到一张白封面。
         */
        private const val COVER_PROBE_CANDIDATES = 5

        /** SY --> 封面可走 Range 流式直读第一张图的格式（zip 系直读中央目录、tar 系扫头块）。 */
        private val RANGE_STREAM_EXTS = setOf("zip", "cbz", "epub", "tar", "cbt")
        private val TAR_EXTS = setOf("tar", "cbt")
        // SY <--
    }
}

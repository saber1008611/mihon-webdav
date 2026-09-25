package eu.kanade.tachiyomi.source.webdav

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.CloudSyncConfig
import eu.kanade.tachiyomi.data.download.CloudSyncEntry
import eu.kanade.tachiyomi.data.download.CloudSyncService
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.File

/**
 * SY --> 路线 A 方案 1：
 *
 * 一个「自包含」的 WebDAV 阅读源——与上传侧（CloudSyncService 把 CBZ 推到网盘）对称，
 * 这里负责把设备 A 上传的漫画在设备 B 上读回来。
 *
 * 设计要点：
 *  - 复用同一套 WebDAV 配置（DownloadPreferences 里的 cloudSync* 偏好）与同一个 CloudSyncService。
 *  - 远端目录结构与上传完全一致：destination/{漫画目录}/{章节}.cbz，漫画封面为同名 cover.jpg（与章节并列）。
 *  - 目录浏览：destination 的直接子目录即漫画文件夹；listFiles 列每个漫画文件夹内的 .cbz 章节。
 *  - 阅读：不直接实现 getPageList，而是由 ChapterLoader 在 `source is WebDavSource` 分支里
 *    先 getArchiveFile 把 CBZ 流式下载到缓存，再用现成的 ArchivePageLoader 读页（翻页/封面/ComicInfo 全复用）。
 *  - getPageList 抛 UnsupportedOperationException 是为了与 LocalSource 保持同一契约（页面由 ChapterLoader 提供）。
 *
 * 未配置 WebDAV（没开同步 / URL 为空）时，所有浏览/详情方法都优雅返回空，不会抛异常连累全局搜索等调用方。
 * SY <--
 */
@Inject
@SingleIn(AppScope::class)
class WebDavSource(
    private val context: Context,
    private val cloudSyncService: CloudSyncService,
    private val downloadPreferences: DownloadPreferences,
) : Source, UnmeteredSource {

    init {
        // 让封面 Provider 能拿到与上传同源的下载通道与 WebDAV 偏好。
        WebDavCoverBridge.cloudSyncService = cloudSyncService
        WebDavCoverBridge.downloadPreferences = downloadPreferences
    }

    override val name: String = "WebDAV 云盘"

    override val id: Long = ID

    override val lang: String = "other"

    override val supportsLatest: Boolean = true

    override fun toString() = name

    /**
     * 稳定的排序 Filter 实例：必须是单例（不能每次 getFilterList 都 new），
     * 否则界面上用户在筛选对话框改的排序 state 与源内部读到的不是同一个对象，
     * 「热门 / 最新」两个 tab（GetRemoteManga 不带 filters 参数）就收不到排序选择。
     * Search 路径虽会传 filters，但也是这个同一实例，因此三处排序完全统一。
     * index 0 = 最近更新（按目录修改时间），index 1 = 名称。
     */
    private val sortFilter = object : Filter.Sort(
        "排序",
        arrayOf("最近更新", "名称"),
    ) {}

    /** 未配置时返回 null，所有对外方法据此优雅降级。 */
    private fun configOrNull(): Pair<CloudSyncConfig, String>? {
        if (!downloadPreferences.cloudSyncEnabled.get()) return null
        val config = CloudSyncConfig(
            url = downloadPreferences.cloudSyncUrl.get(),
            username = downloadPreferences.cloudSyncUsername.get(),
            password = downloadPreferences.cloudSyncPassword.get(),
        )
        if (!config.isValid) return null
        return config to downloadPreferences.cloudSyncDestination.get()
    }

    override suspend fun getPopularManga(page: Int): MangasPage = withIOContext {
        MangasPage(applySort(listAllManga(), defaultLatest = false), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = withIOContext {
        MangasPage(applySort(listAllManga(), defaultLatest = true), false)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        val q = query.trim()
        val all = listAllManga()
        val result = if (q.isBlank()) all else all.filter { it.manga.title.contains(q, ignoreCase = true) }
        MangasPage(applySort(result, defaultLatest = false), false)
    }

    /**
     * 列出 WebDAV 上所有漫画：destination 的直接子目录即漫画文件夹（单层）。
     *
     * 返回时带上目录修改时间（lastModified）供排序使用——SManga 在本 fork 里没有时间字段，
     * 因此用内部 [WebDavMangaListItem] 携带，避免污染/依赖共享的 SManga 定义。
     *
     * 关键优化：列表只列目录、**不预拉封面**——否则在几十上百本漫画的 44G 库里，
     * 进源要等全部 cover.jpg 下载完才显示，转圈半天。
     * 封面改为填「content://」直链（WebDavCoverProvider），由 Coil 在网格项
     * 滚动到可见时才各自按需下载（天然懒加载，不阻塞列表，且用与上传同源的
     * CloudSyncService 带鉴权下载，绕开 Mihon 全局图片加载器对 WebDAV 鉴权的限制）。
     */
    private suspend fun listAllManga(): List<WebDavMangaListItem> {
        val (config, destination) = configOrNull() ?: return emptyList()
        return runCatching {
            cloudSyncService.listDirectories(config, destination)
                .map { dir ->
                    val url = dir.path.trimStart('/')
                    val manga = SManga.create().apply {
                        title = dir.name
                        this.url = url
                        thumbnail_url = coverContentUri(url)
                    }
                    WebDavMangaListItem(manga, dir.lastModified)
                }
        }.getOrElse { e ->
            logcat(LogPriority.ERROR, e) { "WebDavSource: failed to list manga" }
            emptyList()
        }
    }

    /** 列表项 + 目录修改时间（毫秒），用于「最近更新」排序。 */
    private data class WebDavMangaListItem(val manga: SManga, val lastModified: Long)

    /**
     * 按用户在筛选对话框选的排序（[sortFilter]）对列表排序。
     * 用户未选（state == null）时：
     *  - 最新 tab（defaultLatest=true）默认按目录修改时间倒序；
     *  - 热门 / 搜索默认保持 WebDAV 返回的原顺序（PROPFIND 顺序）。
     * 用户已选：index 0 = 最近更新（按 lastModified），index 1 = 名称；ascending 控制升降序。
     */
    private fun applySort(items: List<WebDavMangaListItem>, defaultLatest: Boolean): List<SManga> {
        val sel = sortFilter.state
        val sorted = if (sel == null) {
            if (!defaultLatest) items else items.sortedByDescending { it.lastModified }
        } else {
            when (sel.index) {
                0 -> if (sel.ascending) items.sortedBy { it.lastModified } else items.sortedByDescending { it.lastModified }
                1 -> if (sel.ascending) items.sortedBy { it.manga.title.lowercase() } else items.sortedByDescending { it.manga.title.lowercase() }
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
        // 未配置 WebDAV 时保留现有数据，绝不返回空章节把已入库的章节冲掉。
        val configured = configOrNull() != null
        // 详情不要改写 thumbnail_url：列表里填的 content:// 直链（WebDavCoverProvider）在详情页
        // 同样由 MangaCoverFetcher 走 Type.URI 加载，与网格网格完全一致。若在此改写成 file://
        // 本地路径，会被详情页的 upsert 写进数据库，返回列表时污染封面——表现为「详情封面
        // 消失、返回后整列封面也消失」。WebDavSource 没有额外的详情元数据可拉，原样返回即可。
        val updatedChapters = if (fetchChapters && configured) getChapterList(manga) else chapters
        SMangaUpdate(manga, updatedChapters)
    }

    private suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        val (config, _) = configOrNull() ?: return@withIOContext emptyList()
        runCatching {
            cloudSyncService.listFiles(config, manga.url)
                .filter { isChapterFile(it.name) }
                .map { entry ->
                    SChapter.create().apply {
                        url = entry.path.trimStart('/')
                        name = entry.name.substringBeforeLast('.', entry.name)
                        chapter_number = ChapterRecognition
                            .parseChapterNumber(manga.title, this.name, this.chapter_number.toDouble())
                            .toFloat()
                    }
                }
                .sortedWith { c1, c2 -> c2.name.compareTo(c1.name) }
        }.getOrElse { e ->
            logcat(LogPriority.ERROR, e) { "WebDavSource: failed to list chapters for ${manga.url}" }
            emptyList()
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw UnsupportedOperationException("WebDavSource pages are loaded by ChapterLoader")

    override fun getFilterList(): FilterList = FilterList(sortFilter)

    /**
     * 把某一章的归档/电子书流式下载到应用缓存目录，返回其 UniFile。
     * ChapterLoader 拿到后按扩展名选择 ArchivePageLoader（压缩包）或 EpubPageLoader（epub），
     * 复用全部归档阅读逻辑。
     */
    suspend fun getArchiveFile(chapter: SChapter): UniFile = withIOContext {
        val (config, _) = configOrNull()
            ?: throw IllegalStateException("WebDAV 未配置，无法读取章节")
        val fullPath = chapter.url.trimStart('/')
        val lastSlash = fullPath.lastIndexOf('/')
        require(lastSlash > 0) { "WebDavSource: 非法的章节路径 $fullPath" }
        val remoteDir = fullPath.substring(0, lastSlash)
        val fileName = fullPath.substring(lastSlash + 1)

        val cacheDir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val cacheFile = File(cacheDir, fullPath.replace('/', '_'))

        if (!cacheFile.exists()) {
            // 先下后读：阅读前必须先把整本 CBZ 落到缓存（方案 1）。
            cloudSyncService.downloadFile(config, remoteDir, fileName, cacheFile)
        }
        UniFile.fromFile(cacheFile)
            ?: throw IllegalStateException("WebDavSource: 无法把缓存文件包装为 UniFile")
    }

    /**
     * 判断远端文件名是否为支持的章节格式（共 10 种）。
     * 与本地图源一致：压缩包 zip/cbz/rar/cbr/7z/cb7/tar/cbt（底层 libarchive 原生解压），
     * 外加 epub 电子书（EpubReader）与 pdf（Android 内置 PdfRenderer 逐页渲染）。
     */
    private fun isChapterFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_CHAPTER_EXTENSIONS
    }

    private val SUPPORTED_CHAPTER_EXTENSIONS = setOf(
        "zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt", "epub", "pdf",
    )

    /**
     * 生成封面 content:// 直链（WebDavCoverProvider）。Coil 加载该 URI 时在可见项
     * 才触发一次带鉴权下载，天然懒加载、不阻塞列表；详情页也走同一条通道。
     */
    private fun coverContentUri(mangaUrl: String): String =
        "content://${WebDavCoverBridge.COVER_AUTHORITY}/${Uri.encode(mangaUrl)}"

    companion object {
        /** 与 LocalSource.ID=0 区分；选一个不易与扩展源 id 冲突的固定值。 */
        const val ID = -20_260_924L

        private const val CACHE_SUBDIR = "webdav-archives"
    }
}

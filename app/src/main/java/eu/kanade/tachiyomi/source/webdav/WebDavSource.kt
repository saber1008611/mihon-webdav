package eu.kanade.tachiyomi.source.webdav

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.CloudSyncConfig
import eu.kanade.tachiyomi.data.download.CloudSyncDirectory
import eu.kanade.tachiyomi.data.download.CloudSyncEntry
import eu.kanade.tachiyomi.data.download.CloudSyncService
import eu.kanade.tachiyomi.data.download.normalizePath
import eu.kanade.tachiyomi.source.FolderSource
import eu.kanade.tachiyomi.source.netdisk.BaseNetdiskSource
import eu.kanade.tachiyomi.source.netdisk.NetdiskPreferences
import eu.kanade.tachiyomi.source.netdisk.aliyun.AliyunPanSource
import eu.kanade.tachiyomi.source.netdisk.cloud115open.Cloud115OpenSource
import eu.kanade.tachiyomi.source.netdisk.quark.QuarkSource
import eu.kanade.tachiyomi.source.HeavyChapterLoadSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFolder
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.pdf.PdfiumRenderer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.core.archive.RandomAccessSource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.File

/**
 * SY --> 路线 A 方案 1：与上传侧（CloudSyncService 把 CBZ 推到网盘）对称的「自包含」WebDAV 阅读源。
 *
 * 浏览/章节/封面/文件夹视图/排序逻辑全部在 [BaseWebDavSource]；本类只承载远端后端差异：
 *  - 配置来自 DownloadPreferences 的 cloudSync* 偏好，经 CloudSyncService 发请求；
 *  - PDF 流式路由（[openPdfChapter]：浅链流式 / 深链整本下载）；
 *  - 远端 Range 随机读源（[WebDavRandomAccessSource]）；
 *  - 深链 PDF 占位封面策略（[tryHandlePdfCoverLocally] / [hasPlaceholderCover]）。
 *
 * 未配置 WebDAV 时所有方法经 [isConfigured] 优雅降级为空 / 抛明确异常。
 * SY <--
 */
@Inject
@SingleIn(AppScope::class)
class WebDavSource(
    context: Context,
    private val cloudSyncService: CloudSyncService,
    private val downloadPreferences: DownloadPreferences,
    // SY 2026-10-06：网盘门面 —— 挂载进「云盘」的网盘目录按前缀路由到这三个后端
    private val quarkSource: QuarkSource,
    private val aliyunPanSource: AliyunPanSource,
    private val cloud115OpenSource: Cloud115OpenSource,
    private val netdiskPreferences: NetdiskPreferences,
) : BaseWebDavSource(context) {

    init {
        // 让封面 Provider 能拿到与上传同源的下载通道与 WebDAV 偏好。
        WebDavCoverBridge.cloudSyncService = cloudSyncService
        WebDavCoverBridge.downloadPreferences = downloadPreferences
        // 封面回退（无 cover.* 时取第一话首页）需要源侧的缓存下载与归档解析能力。
        WebDavCoverBridge.bindWebDavSource(this)
    }

    // SY 2026-10-05：用户要求改名「云盘」（原「WebDAV 云盘」）
    override val name: String = "云盘"
    override val id: Long = ID
    override val lang: String = "other"
    override val supportsLatest: Boolean = true
    override fun toString() = name

    /**
     * 未配置时返回 null，所有对外方法据此优雅降级。
     */
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

    // ===================== 网盘门面路由（SY 2026-10-06） =====================

    /**
     * 「添加网盘文件夹」挂载的目录以 `@<routeKey>:<id>` 前缀出现在文件夹视图里；
     * 所有后端接缝（列目录/文件、Range 流式、下载缓存、封面）遇到该前缀一律委托给
     * 对应网盘源 —— 于是网盘内容**完整共用云盘的封面规则 / 加载 / 流式读取**。
     */
    private fun netdiskFor(routePath: String): BaseNetdiskSource? {
        // ⚠️ 路径可能被 normalizePath 加了前导「/」（setActiveFolder / resolveFolderCover 等），
        // 必须先剥掉再匹配前缀，否则会掉进 WebDAV PROPFIND 分支（1.0.13 真机踩坑：HTTP 404）。
        val p = routePath.trimStart('/')
        if (!p.startsWith("@")) return null
        return when (p.removePrefix("@").substringBefore(':')) {
            QuarkSource.ROUTE_KEY -> quarkSource
            AliyunPanSource.ROUTE_KEY -> aliyunPanSource
            Cloud115OpenSource.ROUTE_KEY -> cloud115OpenSource
            else -> null
        }
    }

    private fun innerPath(routePath: String): String =
        routePath.trimStart('/').removePrefix("@").substringAfter(':')

    private fun rePrefixDirs(key: String, dirs: List<CloudSyncDirectory>): List<CloudSyncDirectory> =
        dirs.map { it.copy(path = "@$key:${it.path.trimStart('/')}") }

    private fun rePrefixEntries(key: String, entries: List<CloudSyncEntry>): List<CloudSyncEntry> =
        entries.map { it.copy(path = "@$key:${it.path.trimStart('/')}") }

    /** 挂载记录解析：`"<routeKey>|<id>|<显示名>"`。 */
    private fun netdiskMounts(): List<Triple<String, String, String>> =
        netdiskPreferences.netdiskMounts.get().mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size < 3) null else Triple(parts[0], parts[1], parts.drop(2).joinToString("|"))
        }

    /** 「添加网盘文件夹」挂载入口（可视化选择器调用；重复挂载返回 false）。 */
    fun addNetdiskMount(routeKey: String, id: String, name: String): Boolean {
        val entry = "$routeKey|$id|$name"
        val current = netdiskPreferences.netdiskMounts.get()
        if (entry in current) return false
        netdiskPreferences.netdiskMounts.set(current + entry)
        // SY 2026-10-06：重挂载时穿透目录缓存（旧缓存可能是删除前的失败/空列表）
        val routePath = "@$routeKey:$id"
        DirListingCache.markStaleDirectory(ID, routePath)
        DirListingCache.markStaleEntries(ID, routePath)
        return true
    }

    /**
     * SY 2026-10-06：网盘文件夹挂载后的**默认封面规则 = 4 线程扫描封面**
     * （与书架「扫描封面」同款并发：Semaphore(4) 滑动窗口，防网盘风控）。
     */
    suspend fun scanNetdiskFolderCovers(routePath: String) = withIOContext {
        val urls = runCatching { listDirectories(routePath) }
            .onFailure { e -> logcat(LogPriority.WARN, e) { "云盘: 扫描封面列目录失败 $routePath" } }
            .getOrDefault(emptyList())
            .map { it.path.trimStart('/') }
        val semaphore = Semaphore(4)
        logcat(LogPriority.INFO) { "云盘: 网盘文件夹挂载后扫描封面（4线程，共 ${urls.size} 本）$routePath" }
        coroutineScope {
            urls.forEach { url ->
                launch {
                    semaphore.withPermit { runCatching { ensureCover(url) } }
                }
            }
        }
    }

    // ===================== 后端接缝实现（其余逻辑在 BaseWebDavSource） =====================

    /** WebDAV 已配置**或**有网盘挂载目录时即可浏览（网盘内容不依赖 WebDAV 配置）。 */
    override fun isConfigured(): Boolean = configOrNull() != null || netdiskMounts().isNotEmpty()
    override val backend = WebDavCoverStore.Backend.WEBDAV
    override val coverAuthority = WebDavCoverBridge.COVER_AUTHORITY
    override val coverCacheDirName = "webdav-covers"
    override val archiveCacheSubdir = CACHE_SUBDIR
    override val folderCoverSegment = WebDavCoverBridge.FOLDER_SEGMENT

    override suspend fun listDirectories(path: String): List<CloudSyncDirectory> {
        val nd = netdiskFor(path) ?: return configOrNull()?.let {
            cloudSyncService.listDirectories(it.first, path)
        } ?: emptyList()
        return rePrefixDirs(nd.routeKey, nd.delegateListDirectories(innerPath(path)))
    }

    override suspend fun listFiles(path: String): List<CloudSyncEntry> {
        val nd = netdiskFor(path) ?: return configOrNull()?.let {
            cloudSyncService.listFiles(it.first, path)
        } ?: emptyList()
        return rePrefixEntries(nd.routeKey, nd.delegateListFiles(innerPath(path)))
    }

    /** 文件夹视图的云端目录列表：WebDAV 目录 + 挂载的网盘目录（前缀路由）。 */
    override fun getFoldersPaths(): List<String> =
        getWebDavFolders() + netdiskMounts().map { (key, id, _) -> "@$key:$id" }

    /** 文件夹视图：WebDAV 目录用路径末段做名字，网盘挂载目录用挂载时登记的显示名。 */
    override suspend fun getFolders(): List<SourceFolder> = withIOContext {
        val own = getWebDavFolders().filter { it.isNotBlank() }.map { path ->
            SourceFolder(
                url = path,
                name = path.substringAfterLast('/', path).ifBlank { path },
                coverUrl = "content://$coverAuthority/$folderCoverSegment/${Uri.encode(path)}",
            )
        }
        val netdisks = netdiskMounts().map { (key, id, name) ->
            val url = "@$key:$id"
            SourceFolder(
                url = url,
                name = name,
                coverUrl = "content://$coverAuthority/$folderCoverSegment/${Uri.encode(url)}",
            )
        }
        own + netdisks
    }

    override fun openRangeSource(remotePath: String): RandomAccessSource? {
        // SY 2026-10-06：网盘挂载目录 → 委托网盘后端（动态直链 + 网盘签名头）
        netdiskFor(remotePath)?.let { return it.delegateOpenRangeSource(innerPath(remotePath)) }
        val (config, _) = configOrNull() ?: return null
        return newRangeSource(config, remotePath)
    }

    override suspend fun downloadImageToCache(folderPath: String, fileName: String): File {
        // SY 2026-10-06：网盘挂载目录 → 委托网盘后端（缓存目录同 cache/webdav-images，共用 256MB LRU）
        netdiskFor(folderPath)?.let { return it.downloadImageToCache(innerPath(folderPath), fileName) }
        val (config, _) = configOrNull()
            ?: throw IllegalStateException("WebDAV 未配置，无法读取章节")
        val cacheDir = File(context.cacheDir, IMG_CACHE_SUBDIR).apply { mkdirs() }
        // SY --> 2026-10-01：改用共享 Hash.sha256（手写 hex，避免每次 new MessageDigest + format 分配）。
        val key = Hash.sha256("$folderPath/$fileName")
        // SY <--
        val cacheFile = File(cacheDir, key)
        if (!cacheFile.exists()) {
            // SY --> 诊断用（INFO 级，release 可见）：量化单张图 HTTP 下载耗时。
            val t0 = SystemClock.uptimeMillis()
            cloudSyncService.downloadFile(config, folderPath, fileName, cacheFile)
            val cost = SystemClock.uptimeMillis() - t0
            logcat(LogPriority.INFO) { "WebDavSource: 下载 $fileName 耗时 ${cost}ms" }
            // SY <--
            // SY --> 2026-09-28：本目录修前**无上限**（实测 118M 且只增不减）。见 [IMG_CACHE_BYTES]。
            enforceLruCacheBudget(cacheDir, IMG_CACHE_BYTES, setOf(cacheFile.name))
                .forEach { (name, len) ->
                    logcat(LogPriority.INFO) { "WebDavSource: 散图缓存超限，淘汰 $name ($len B)" }
                }
            // SY <--
        } else {
            // SY --> 命中即刷新 mtime，作为 LRU 依据（常看的散图不该因 mtime 旧被优先淘汰）。
            cacheFile.setLastModified(System.currentTimeMillis())
            // SY <--
        }
        return cacheFile
    }

    override suspend fun downloadChapterToCache(remotePath: String): File {
        // SY 2026-10-06：网盘挂载目录 → 委托网盘后端（整本缓存落在网盘自己的归档目录，同样 1GB LRU）
        netdiskFor(remotePath)?.let { return it.delegateDownloadChapterToCache(innerPath(remotePath)) }
        val (config, _) = configOrNull()
            ?: throw IllegalStateException("WebDAV 未配置，无法读取章节")
        val fullPath = remotePath.trimStart('/')
        val lastSlash = fullPath.lastIndexOf('/')
        require(lastSlash > 0) { "WebDavSource: 非法的章节路径 $fullPath" }
        val remoteDir = fullPath.substring(0, lastSlash)
        val fileName = fullPath.substring(lastSlash + 1)

        val cacheDir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val cacheFile = File(cacheDir, fullPath.replace('/', '_'))

        if (!cacheFile.exists()) {
            // 先下后读：阅读前必须先把整本 CBZ 落到缓存（方案 1）。
            cloudSyncService.downloadFile(config, remoteDir, fileName, cacheFile)
            // SY --> 2026-09-28：本目录修前**无上限无淘汰**（实测 11 个文件累积 1.2G）。
            // zip/cbz 已改走 Range 流式（见 ChapterLoader），这里仍有 pdf/epub/rar/7z 落盘，
            // 必须设预算兜底。见 [ARCHIVE_CACHE_BYTES] 与 [enforceLruCacheBudget]。
            enforceLruCacheBudget(cacheDir, ARCHIVE_CACHE_BYTES, setOf(cacheFile.name))
                .forEach { (name, len) ->
                    logcat(LogPriority.INFO) { "WebDavSource: 章节缓存超限，淘汰 $name ($len B)" }
                }
            // SY <--
        } else {
            // SY --> 命中即刷新 mtime，作为 LRU 依据：否则「常读的老章节」会因 mtime 旧而被优先淘汰。
            cacheFile.setLastModified(System.currentTimeMillis())
            // SY <--
        }
        return cacheFile
    }

    override suspend fun downloadCoverEntry(actual: CloudSyncEntry, mangaUrl: String, coverDir: File): File? {
        // SY 2026-10-06：网盘挂载目录 → 委托网盘后端下载 cover.*（封面落进云盘的 webdav-covers，共用规则）
        netdiskFor(mangaUrl)?.let {
            val innerActual = actual.copy(path = innerPath(actual.path))
            return it.delegateDownloadCoverEntry(innerActual, innerPath(mangaUrl), coverDir)
        }
        val (config, _) = configOrNull() ?: return null
        val trimmed = mangaUrl.trimStart('/')
        val ext = actual.name.substringAfterLast('.', "")
        val baseName = mangaUrl.replace('/', '_')
        val dst = File(coverDir, "$baseName.$ext")
        cloudSyncService.downloadFile(config, trimmed, actual.name, dst)
        return dst.takeIf { it.exists() && it.length() > 0L && !CoverQuality.isBlank(it) }
    }

    override fun openPdfRenderer(ras: RandomAccessSource): PdfiumRenderer =
        PdfiumRenderer.openProxy(context, ras)

    // ===================== 深链 PDF 占位封面策略（B1） =====================

    /**
     * SY --> B1（2026-09-28）PDF 封面策略。完整实现已上提到 [BaseWebDavSource.handlePdfDeepChainCover]
     * （2026-10-05，网盘源 115/夸克共用同一套逻辑），这里只做委托。
     * SY <--
     */
    override suspend fun tryHandlePdfCoverLocally(
        targetPath: String,
        baseName: String,
        title: String,
        coverFile: File,
    ): Boolean {
        // SY 2026-10-06：网盘挂载目录 → 委托网盘后端的深链策略（占位图写共享 fallback 目录，键含前缀，互不冲突）
        netdiskFor(targetPath)?.let {
            return it.delegateTryHandlePdfCoverLocally(innerPath(targetPath), baseName, title, coverFile)
        }
        return handlePdfDeepChainCover(targetPath, baseName, title, coverFile)
    }

    override fun hasPlaceholderCover(base: String): Boolean =
        WebDavCoverStore.fallbackCoverFile(context, base).exists()

    override suspend fun isDeepChainPdfOrPlaceholder(remotePath: String): Boolean {
        netdiskFor(remotePath)?.let {
            return runCatching { it.delegateIsDeepChainPdfOrPlaceholder(innerPath(remotePath)) }.getOrDefault(false)
        }
        return runCatching { isRemotePdfDeepChain(remotePath) }.getOrDefault(false)
    }

    /** SY 2026-10-06：网盘挂载目录的章节 id 无扩展名，真实文件名委托网盘后端解析（ChapterLoader 分流用）。 */
    override suspend fun fileNameOf(url: String): String {
        netdiskFor(url)?.let { return it.fileNameOf(innerPath(url)) }
        return super.fileNameOf(url)
    }

    /** SY 2026-10-06：网盘挂载目录的整本缓存落在网盘自己的归档目录，查询重定向（修「读后补封面」兜底失效）。 */
    override fun cachedArchiveFile(remotePath: String): File? {
        netdiskFor(remotePath)?.let { return it.delegateCachedArchiveFile(innerPath(remotePath)) }
        return super.cachedArchiveFile(remotePath)
    }

    // ===================== 远端独有：PDF 流式路由 =====================

    /** 共用的 Range 随机读源工厂：流式章节（zip/cbz、pdf）都从这里出。 */
    private fun newRangeSource(config: CloudSyncConfig, remotePath: String): WebDavRandomAccessSource =
        WebDavRandomAccessSource(
            url = cloudSyncService.resolveFileUrl(config, remotePath),
            username = config.username,
            password = config.password,
            fallbackCacheDir = File(context.cacheDir, FALLBACK_SUBDIR),
            cacheMaxBytes = FALLBACK_CACHE_BYTES,
        )

    // SY --> 2026-10-05：openPdfChapter / PdfChapterHandle / cachedArchiveFile / isDeepChainPdf /
    // extractPdfFirstPageFromLocal 已上提到 BaseWebDavSource（网盘源 115/夸克共用同一套逻辑）。

    // ===================== 文件夹视图：云端目录列表 + 添加 =====================

    /** 文件夹视图的云端目录列表：默认 destination 在前，外加用户添加的额外目录。 */
    private fun getWebDavFolders(): List<String> {
        val destination = normalizePath(downloadPreferences.cloudSyncDestination.get())
        val extras = downloadPreferences.cloudSyncFolders.get()
            .map { normalizePath(it) }
            .filter { it.isNotBlank() && it != destination }
        return (listOf(destination) + extras).filter { it.isNotBlank() }.distinct()
    }

    override suspend fun addFolder(identifier: String): Boolean {
        val path = normalizePath(identifier)
        if (path.isBlank()) return false
        val destination = normalizePath(downloadPreferences.cloudSyncDestination.get())
        val current = downloadPreferences.cloudSyncFolders.get()
        if (path == destination || path in current) return false
        // 校验远端目录存在（PROPFIND 失败即视为无效路径）
        val (config, _) = configOrNull() ?: return false
        val exists = runCatching { cloudSyncService.listDirectories(config, path) }.isSuccess
        if (!exists) return false
        downloadPreferences.cloudSyncFolders.set(current + path)
        return true
    }

    companion object {
        /** 与 LocalSource.ID=0 区分；选一个不易与扩展源 id 冲突的固定值。 */
        const val ID = -20_260_924L

        /** 散图文件夹章节的 url 标记（与 ChapterLoader 共用，见 BaseWebDavSource）。 */
        const val IMG_FOLDER_MARKER = BaseWebDavSource.IMG_FOLDER_MARKER

        private const val CACHE_SUBDIR = "webdav-archives"

        /**
         * [CACHE_SUBDIR] 磁盘预算：1GB，超限按 LRU 淘汰。
         * 该目录存的是「整本下载」的章节（pdf/epub/tar/rar/7z 等；zip/cbz 已改走 Range 流式）。
         * 修前此目录**无上限无淘汰**，实测仅 11 个文件就累积 1.2G。PDF 走 Range 反而更慢
         * （pdfium 打开时会通读全文件，实测 31.4s vs 整本 11.4s），所以只能靠预算兜底。
         */
        private const val ARCHIVE_CACHE_BYTES = 1L * 1024 * 1024 * 1024

        /** 流式读整本回退（非 Range 服务器 / rar·7z 等）的磁盘缓存目录。 */
        private const val FALLBACK_SUBDIR = "webdav-stream"

        /** 整本回退磁盘缓存上限：4GB，超限按 LRU 淘汰其它整本。 */
        private const val FALLBACK_CACHE_BYTES = 4L * 1024 * 1024 * 1024

        /** 散图文件夹单张图片的磁盘缓存目录。 */
        private const val IMG_CACHE_SUBDIR = "webdav-images"

        /**
         * [IMG_CACHE_SUBDIR] 磁盘预算：256MB，超限按 LRU 淘汰。散图单张 1–3MB，256MB 足够滚读若干话。
         */
        private const val IMG_CACHE_BYTES = 256L * 1024 * 1024

        // SY --> 2026-10-05：PDF 封面/链长探测常量已随逻辑上提到 BaseWebDavSource。
    }
}

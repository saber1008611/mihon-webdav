package eu.kanade.tachiyomi.source.netdisk.cloud115open

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.netdisk.BaseNetdiskSource
import eu.kanade.tachiyomi.source.netdisk.NetdiskPreferences
import eu.kanade.tachiyomi.source.webdav.WebDavCoverBridge
import eu.kanade.tachiyomi.source.webdav.WebDavCoverStore

/**
 * SY 2026-10-08 --> 115 网盘图源（**开放平台 OAuth 模式**，官方 Open API）。
 *
 * 115 的唯一接入链路（原 Cookie 模式源已移除）。
 * 浏览/章节/封面/文件夹视图/排序全部复用
 * [BaseNetdiskSource]（WebDAV 文件夹样式），本类只绑定 [Cloud115OpenApi]。
 * 逻辑路径 = fid 段拼接（根 "0"），真实文件名经 id 缓存解析。
 *
 * 用户侧：设置 → 网盘 → 115 开放平台（oplist.org 取令牌 → 粘贴 access/refresh token）。
 *
 * ⚠️ **封面走独立 authority**（`app.mihonW.cloud115open.cover`）与独立缓存目录
 * （`cloud115open-covers` / `cloud115open-archives`），不与 Cookie 模式共享——
 * 两边的文件 id（fid/pickcode）体系完全不同，混用会串。
 * SY <--
 */
@Inject
@SingleIn(AppScope::class)
class Cloud115OpenSource(
    context: Context,
    private val api: Cloud115OpenApi,
    private val preferences: NetdiskPreferences,
) : BaseNetdiskSource(context, BaseNetdiskSource.sharedDownloadClient) {

    init {
        // 封面 Provider（按 authority 路由）需要能拿到本源实例。
        WebDavCoverBridge.bindCloud115OpenSource(this)
    }

    override val name: String = "115 网盘（开放平台）"
    override val id: Long = ID
    override val lang: String = "other"
    override val supportsLatest: Boolean = true
    override fun toString() = name

    /** 云盘门面路由键（挂载目录前缀 `@115open:`）。 */
    override val routeKey: String = ROUTE_KEY

    override val netdiskClient: Cloud115OpenApi get() = api

    override fun isConfigured(): Boolean = preferences.isCloud115OpenConfigured()
    override val backend = WebDavCoverStore.Backend.CLOUD115OPEN
    override val coverAuthority = WebDavCoverBridge.CLOUD115OPEN_AUTHORITY
    override val coverCacheDirName = "cloud115open-covers"
    override val archiveCacheSubdir = "cloud115open-archives"
    override val folderCoverSegment = WebDavCoverBridge.FOLDER_SEGMENT

    /** 与 Cookie 模式同口径：115 目录枚举是账号级限速，子目录并发保守（2）。 */
    override val subdirListDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2)

    override fun rootId(): String = "0"
    override fun rootDisplayName(): String = "115 网盘（开放平台）"

    /** 文件夹视图的云端目录列表：根目录在前，外加用户添加的挂载点。 */
    override fun getFoldersPaths(): List<String> {
        val root = rootId()
        val extras = preferences.cloud115OpenFolders.get()
            .map { it.trim().trimStart('/') }
            .filter { it.isNotBlank() && it != root }
        return (listOf(root) + extras).distinct()
    }

    override fun onFolderAdded(id: String) {
        val current = preferences.cloud115OpenFolders.get()
        preferences.cloud115OpenFolders.set(current + id)
    }

    companion object {
        const val ROUTE_KEY = "115open"

        /** 与 WebDavSource / QuarkSource / AliyunPanSource 区分的固定源 id。 */
        const val ID = -20_261_004L
    }
}

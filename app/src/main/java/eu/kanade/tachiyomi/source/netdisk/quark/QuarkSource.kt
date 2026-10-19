package eu.kanade.tachiyomi.source.netdisk.quark

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.netdisk.BaseNetdiskSource
import eu.kanade.tachiyomi.source.netdisk.NetdiskPreferences
import eu.kanade.tachiyomi.source.webdav.WebDavCoverBridge
import eu.kanade.tachiyomi.source.webdav.WebDavCoverStore

/**
 * SY --> 夸克网盘图源（非官方 Web API，Cookie 认证）。
 *
 * 「WebDAV 文件夹样式」的完整复用：浏览 / 章节 / 封面 / 文件夹视图 / 排序全部走
 * [BaseWebDavSource][eu.kanade.tachiyomi.source.webdav.BaseWebDavSource]，本类只绑定
 * 夸克的 [QuarkApi]。逻辑路径 = fid 段拼接（根 "0"），真实文件名经 id 缓存解析。
 *
 * 用户侧：设置 → 网盘源 → 夸克（粘贴 Cookie）→ 根目录默认网盘根，可添加更多挂载点。
 * SY <--
 */
@Inject
@SingleIn(AppScope::class)
class QuarkSource(
    context: Context,
    private val api: QuarkApi,
    private val preferences: NetdiskPreferences,
) : BaseNetdiskSource(context, BaseNetdiskSource.sharedDownloadClient) {

    init {
        // 封面 Provider（按 authority 路由）需要能拿到本源实例。
        WebDavCoverBridge.bindQuarkSource(this)
    }

    override val name: String = "夸克网盘"
    override val id: Long = ID
    override val lang: String = "other"
    override val supportsLatest: Boolean = true
    override fun toString() = name

    /** 云盘门面路由键（挂载目录前缀 `@quark:`）。 */
    override val routeKey: String = "quark"

    override val netdiskClient: QuarkApi get() = api

    override fun isConfigured(): Boolean = preferences.isQuarkConfigured()
    override val backend = WebDavCoverStore.Backend.QUARK
    override val coverAuthority = WebDavCoverBridge.QUARK_AUTHORITY
    override val coverCacheDirName = "quark-covers"
    override val archiveCacheSubdir = "quark-archives"
    override val folderCoverSegment = WebDavCoverBridge.FOLDER_SEGMENT

    /** 夸克限速约 2 r/s，子目录并发比 WebDAV 的 6 更保守。 */
    override val subdirListDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2)

    override fun rootId(): String = preferences.quarkRoot.get().trim().ifBlank { "0" }
    override fun rootDisplayName(): String = "夸克网盘"

    /** 文件夹视图的云端目录列表：根目录在前，外加用户添加的挂载点。 */
    override fun getFoldersPaths(): List<String> {
        val root = rootId()
        val extras = preferences.quarkFolders.get()
            .map { it.trim().trimStart('/') }
            .filter { it.isNotBlank() && it != root }
        return (listOf(root) + extras).distinct()
    }

    override fun onFolderAdded(id: String) {
        val current = preferences.quarkFolders.get()
        preferences.quarkFolders.set(current + id)
    }

    companion object {
        const val ROUTE_KEY = "quark"

        /** 与 WebDavSource.ID / Cloud115Source.ID 区分的固定源 id。 */
        const val ID = -20_261_002L
    }
}

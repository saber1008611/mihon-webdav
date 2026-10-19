package eu.kanade.tachiyomi.source.netdisk.aliyun

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.sync.service.AliyunPanService
import eu.kanade.tachiyomi.source.netdisk.BaseNetdiskSource
import eu.kanade.tachiyomi.source.netdisk.NetdiskPreferences
import eu.kanade.tachiyomi.source.webdav.WebDavCoverBridge
import eu.kanade.tachiyomi.source.webdav.WebDavCoverStore

/**
 * SY --> 阿里云盘图源（阅读）：**与「备份同步」共用同一套登录**（syncPreferences 的
 * refresh_token / drive_id），扫码登录一次，两边都可用。
 *
 * 后端复用备份同步已真机跑通的 [AliyunPanService]（list / getDownloadUrl / 会话签名），
 * 本类只把它包成 [BaseNetdiskSource] 的接缝 —— 浏览/章节/封面/文件夹视图全部复用 WebDAV 样式。
 * 逻辑路径 = fileId 段拼接（根 `root`）。
 *
 * 「添加文件夹」支持两种输入：目录的 **fileId**（网页版链接里的 id）或 **目录名**
 * （按根目录下的名字匹配，比 115/夸克更省事）。
 * SY <--
 */
@Inject
@SingleIn(AppScope::class)
class AliyunPanSource(
    context: Context,
    private val aliyunPanService: AliyunPanService,
    private val syncPreferences: SyncPreferences,
    private val netdiskPreferences: NetdiskPreferences,
) : BaseNetdiskSource(context, BaseNetdiskSource.sharedDownloadClient) {

    /** 阿里云盘后端适配器（包既有 AliyunPanService）。 */
    private val api = AliyunApi(aliyunPanService, syncPreferences)

    init {
        // 封面 Provider（按 authority 路由）需要能拿到本源实例。
        WebDavCoverBridge.bindAliyunPanSource(this)
    }

    override val name: String = "阿里云盘"
    override val id: Long = ID
    override val lang: String = "other"
    override val supportsLatest: Boolean = true
    override fun toString() = name

    /** 云盘门面路由键（挂载目录前缀 `@aliyun:`）。 */
    override val routeKey: String = "aliyun"

    override val netdiskClient: AliyunApi get() = api

    /** 与备份同步共用登录：refresh_token 非空即已配置。 */
    override fun isConfigured(): Boolean = syncPreferences.aliyunPanRefreshToken.get().isNotBlank()
    override val backend = WebDavCoverStore.Backend.ALIYUNPAN
    override val coverAuthority = WebDavCoverBridge.ALIYUN_AUTHORITY
    override val coverCacheDirName = "aliyun-covers"
    override val archiveCacheSubdir = "aliyun-archives"
    override val folderCoverSegment = WebDavCoverBridge.FOLDER_SEGMENT

    /** 阿里云盘 2 r/s 限速，子目录并发保守。 */
    override val subdirListDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2)

    override fun rootId(): String = "root"
    override fun rootDisplayName(): String = "阿里云盘"

    /** 文件夹视图的云端目录列表：根目录在前，外加用户添加的挂载点。 */
    override fun getFoldersPaths(): List<String> {
        val root = rootId()
        val extras = netdiskPreferences.aliyunFolders.get()
            .map { it.trim().trimStart('/') }
            .filter { it.isNotBlank() && it != root }
        return (listOf(root) + extras).distinct()
    }

    override fun onFolderAdded(id: String) {
        val current = netdiskPreferences.aliyunFolders.get()
        netdiskPreferences.aliyunFolders.set(current + id)
    }

    /**
     * 「添加文件夹」支持两种输入：目录 **fileId** 或 **目录名**（在根目录下按名字匹配；
     * 匹配到的目录名也顺手登记进显示名缓存，文件夹视图直接显示可读名字）。
     */
    override suspend fun addFolder(identifier: String): Boolean {
        val input = identifier.trim().trimStart('/')
        if (input.isBlank()) return false
        if (input in getFoldersPaths()) return false
        // ① 按 fileId 直接列一次验证
        if (runCatching { netdiskClient.list(input) }.isSuccess) {
            onFolderAdded(input)
            return true
        }
        // ② 按目录名在根目录匹配
        val matched = runCatching { netdiskClient.list(rootId()) }.getOrDefault(emptyList())
            .firstOrNull { it.isDir && it.name == input }
            ?: return false
        if (runCatching { netdiskClient.list(matched.id) }.isSuccess) {
            api.rememberName(matched.id, matched.name)
            onFolderAdded(matched.id)
            return true
        }
        return false
    }

    companion object {
        const val ROUTE_KEY = "aliyun"

        /** 与 WebDavSource.ID / Cloud115Source.ID / QuarkSource.ID 区分的固定源 id。 */
        const val ID = -20_261_003L
    }
}

package eu.kanade.tachiyomi.source.netdisk

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * SY --> 网盘源（115 / 夸克）配置。
 *
 * 设计对齐 DownloadPreferences 的 `cloudSync*` 偏好（WebDAV 侧）：每个网盘只存「凭据 + 根目录 +
 * 用户额外添加的目录」，不落任何派生状态。凭据直接用明文字符串存 PreferenceStore
 * （与 WebDAV 的 username/password、阿里云盘的 refreshToken 同级保护）。
 *
 * 三个网盘（115 / 夸克 / 阿里云盘）在设置里统一归到「网盘源」入口：
 *  - 115 / 夸克：本源实现，凭据 + 根目录在本类；
 *  - 阿里云盘：复用已有的 SyncPreferences / AliyunPanSyncService（双向备份），入口同屏展示，不重复实现。
 *
 * ⚠️ 目录语义：与 WebDAV 一致——挂载目录偏好存的是**相对根的目录标识**列表
 * （115 用 cid，夸克用 fid），默认根为 "0"；用户可添加更多挂载点，文件夹视图逐个列出。
 * SY <--
 */
@Inject
@SingleIn(AppScope::class)
class NetdiskPreferences(
    private val preferenceStore: PreferenceStore,
) {

    // ===================== 115 开放平台（OAuth，官方 Open API；Cookie 模式已移除） =====================

    /**
     * SY 2026-10-08：115 开放平台令牌对（`access_token` + `refresh_token`，OAuth 2.0，
     * `Authorization: Bearer` 认证）。获取：api.oplist.org → 「115 验证网盘」→ 115 App 扫码授权
     * → 复制两个令牌粘贴进来。与上面的 Cookie 模式**双轨并存**、互不影响。
     */
    val cloud115OpenAccessToken: Preference<String> = preferenceStore.getString("netdisk_115_open_access_token", "")

    /** 115 开放平台刷新令牌（换新时同时更新 access；每账号每应用最多 2 个，第 3 次踢最早的）。 */
    val cloud115OpenRefreshToken: Preference<String> = preferenceStore.getString("netdisk_115_open_refresh_token", "")

    /** 115 开放平台额外挂载的目录 fid 列表（文件夹视图逐个列出）。 */
    val cloud115OpenFolders: Preference<Set<String>> = preferenceStore.getStringSet("netdisk_115_open_folders", emptySet())

    // ===================== 夸克（非官方 Web API，Cookie 认证） =====================

    /** 夸克登录后的完整 Cookie 串。空 = 未配置。 */
    val quarkCookie: Preference<String> = preferenceStore.getString("netdisk_quark_cookie", "")

    /** 夸克挂载的根目录 fid；"0" = 网盘根目录。 */
    val quarkRoot: Preference<String> = preferenceStore.getString("netdisk_quark_root", "0")

    /** 夸克额外挂载的目录 fid 列表。 */
    val quarkFolders: Preference<Set<String>> = preferenceStore.getStringSet("netdisk_quark_folders", emptySet())

    // ===================== 阿里云盘（阅读源；凭据与「同步服务」共用 SyncPreferences） =====================

    /** 阿里云盘额外挂载的目录 fileId 列表。 */
    val aliyunFolders: Preference<Set<String>> = preferenceStore.getStringSet("netdisk_aliyun_folders", emptySet())

    // ===================== 挂载进「云盘」（WebDAV 源）文件夹视图的网盘目录 =====================

    /**
     * SY 2026-10-06：用户通过「添加网盘文件夹」挂载的目录，**出现在「云盘」（WebDAV 源）的
     * 文件夹视图里**，并共用云盘的封面/加载/流式读取规则（WebDavSource 按前缀路由到网盘后端）。
     * 每条格式：`"<routeKey>|<目录id>|<显示名>"`（routeKey = 115 / quark / aliyun）。
     */
    val netdiskMounts: Preference<Set<String>> = preferenceStore.getStringSet("netdisk_mounts", emptySet())

    /**
     * SY 2026-10-06：按「云盘」文件夹视图里的路由路径（`@<routeKey>:<id>`，可带前导 /）移除挂载记录。
     * 「删除网盘文件夹」= 真删除挂载（不再用隐藏键），返回是否移除了条目。
     */
    fun removeNetdiskMountByUrl(routePath: String): Boolean {
        val p = routePath.trimStart('/').removePrefix("@")
        val key = p.substringBefore(':')
        val id = p.substringAfter(':', "")
        if (key.isBlank() || id.isBlank()) return false
        val prefix = "$key|$id|"
        val current = netdiskMounts.get()
        val remaining = current.filterNot { it.startsWith(prefix) }
        return if (remaining.size != current.size) {
            netdiskMounts.set(remaining.toSet())
            true
        } else {
            false
        }
    }

    /** SY 2026-10-08：115 开放平台是否已配置（access + refresh 都非空）。 */
    fun isCloud115OpenConfigured(): Boolean =
        cloud115OpenAccessToken.get().isNotBlank() && cloud115OpenRefreshToken.get().isNotBlank()

    fun isQuarkConfigured(): Boolean = quarkCookie.get().isNotBlank()
}

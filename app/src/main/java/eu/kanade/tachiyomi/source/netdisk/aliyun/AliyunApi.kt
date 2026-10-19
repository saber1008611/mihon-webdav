package eu.kanade.tachiyomi.source.netdisk.aliyun

import android.os.SystemClock
import eu.kanade.tachiyomi.data.sync.service.AliyunPanService
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.source.netdisk.NetdiskClient
import eu.kanade.tachiyomi.source.netdisk.NetdiskDownloadInfo
import eu.kanade.tachiyomi.source.netdisk.NetdiskEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.util.lang.withIOContext
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * SY --> 阿里云盘客户端：**适配既有 [AliyunPanService]**（备份同步已真机跑通的 Web API）。
 *
 * 不是重新实现——阿里云盘的「登录（refresh_token）/ 会话签名 / 列目录 / 取下载直链」
 * 在备份同步里已经全部做完（`listFiles` 带分页、`getDownloadUrl` 4 小时时效），这里只是
 * 把它们包成 [NetdiskClient] 五动词，给 [AliyunPanSource]（阅读图源）用。凭据与「同步服务」共用，
 * 那边扫码登录后这边立即可用。
 *
 * 直链头：下载 URL 是预签名 OSS 链接，[AliyunPanService.download] 用 UA + Referer 请求，
 * 这里保持完全一致（常量逐字对齐该类，改那边记得同步改这边）。
 * SY <--
 */
class AliyunApi(
    private val service: AliyunPanService,
    private val syncPreferences: SyncPreferences,
    ) : NetdiskClient {

    /** fileId → 真实文件名（列表时缓存）。 */
    private val names = ConcurrentHashMap<String, String>()

    private data class CachedLink(val info: NetdiskDownloadInfo, val atElapsed: Long)
    private val links = ConcurrentHashMap<String, CachedLink>()
    private val linkMutex = Mutex()

    /** 连通性测试：拉用户信息（顺带把 default drive id 写进偏好）。 */
    suspend fun check() = withIOContext {
        service.getUserInfo()
    }

    private suspend fun ensureDriveId(): String {
        syncPreferences.aliyunPanDriveId.get().takeIf { it.isNotBlank() }?.let { return it }
        val info = service.getUserInfo()
        return info.defaultDriveId.ifBlank {
            throw IllegalStateException("阿里云盘未返回 drive id，请重新登录")
        }
    }

    override suspend fun list(id: String): List<NetdiskEntry> = withIOContext {
        val drive = ensureDriveId()
        val parent = id.trim().ifBlank { ROOT_PARENT }
        service.listFiles(drive, parent).map { fe ->
            names[fe.fileId] = fe.name
            NetdiskEntry(
                id = fe.fileId,
                name = fe.name,
                isDir = fe.type != "file",
                size = fe.size,
                mtime = parseTime(fe.updatedAt),
            )
        }
    }

    override suspend fun downlink(fileId: String): NetdiskDownloadInfo = withIOContext {
        links[fileId]?.let { cached ->
            // get_download_url 的 expire_sec=14400（4h），取 30min 保守缓存
            if (SystemClock.elapsedRealtime() - cached.atElapsed < DLINK_TTL_MS) {
                return@withIOContext cached.info
            }
            links.remove(fileId)
        }
        linkMutex.withLock {
            links[fileId]?.let { cached ->
                if (SystemClock.elapsedRealtime() - cached.atElapsed < DLINK_TTL_MS) {
                    return@withIOContext cached.info
                }
            }
            val drive = ensureDriveId()
            val url = service.getDownloadUrl(drive, fileId)
            val info = NetdiskDownloadInfo(url = url, name = names[fileId], size = null)
            links[fileId] = CachedLink(info, SystemClock.elapsedRealtime())
            info
        }
    }

    override suspend fun resolveName(fileId: String): String? = names[fileId]

    override fun invalidateDownlink(fileId: String) {
        links.remove(fileId)
    }

    override fun downloadHeaders(): Map<String, String> = mapOf(
        "User-Agent" to DOWNLOAD_UA,
        "Referer" to DOWNLOAD_REFERER,
    )

    /** fileId 登记真实文件名（addFolder 按名匹配 / 兜底用）。 */
    fun rememberName(fileId: String, name: String) {
        names[fileId] = name
    }

    private fun parseTime(raw: String): Long {
        if (raw.isBlank()) return 0L
        return runCatching { Instant.parse(raw).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    companion object {
        private const val ROOT_PARENT = "root"
        private const val DLINK_TTL_MS = 30 * 60_000L

        // ⚠️ 逐字对齐 AliyunPanService 的下载头（私有常量，无法直接引用）
        private const val DOWNLOAD_REFERER = "https://www.aliyundrive.com/"
        private const val DOWNLOAD_UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

package eu.kanade.tachiyomi.source.netdisk.cloud115open

import android.os.SystemClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.netdisk.NetdiskClient
import eu.kanade.tachiyomi.source.netdisk.NetdiskDownloadInfo
import eu.kanade.tachiyomi.source.netdisk.NetdiskEntry
import eu.kanade.tachiyomi.source.netdisk.NetdiskHttp
import eu.kanade.tachiyomi.source.netdisk.NetdiskPreferences
import eu.kanade.tachiyomi.source.netdisk.naturalCompare
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * SY 2026-10-08 --> 115 网盘客户端（**开放平台 OAuth 模式**，官方 Open API）。
 *
 * SY 2026-10-08 晚：原 Cookie 模式（Cloud115Api，Web 接口 + m115 加密）已整体移除，
 * 本类是 115 唯一的接入链路。
 * 契约来自 115 官方开放平台文档（yuque.com/115yun/open）+ OpenList 115_open 驱动 /
 * 115-sdk-go 源码（MIT，仅对照协议不复制实现）：
 *
 *  - 认证：全部 `/open/` 系接口带 `Authorization: Bearer <access_token>`；无 Cookie、无 m115 加解密。
 *  - 列表：`GET proapi.115.com/open/ufile/files`，参数 `cid/limit/offset/asc/o/show_dir`；
 *    条目 `fc=="0"` 为目录（用 `fid` 进入下一层），文件用 `pc`（pick_code）取直链。
 *  - 直链：`POST proapi.115.com/open/ufile/downurl`，form `pick_code`；
 *    响应 `data` 为 `{<文件id>: {file_name, file_size, pick_code, sha1, url: {url}}}`（**明文**）。
 *    ⚠️ **直链与取链时的 User-Agent 绑定**（OpenList `Link()` 同款：取链带什么 UA，下载就必须带
 *    什么 UA），故 [downloadHeaders] 返回与取链一致的固定 UA。
 *  - 刷新：`POST passportapi.115.com/open/refreshToken`，form `refresh_token`（**无需 AppKey**，
 *    按 IP 频控；返回新的 access_token，refresh_token 有效期不延长）。
 *    401 系错误码（`40140116` no auth 等，`Is401Started`）→ 刷新一次并重试（OpenList 同款策略）。
 *
 * 登录方式（方案 A）：api.oplist.org → 「115 验证网盘」→ 勾选"使用 OpenList 提供的参数" →
 * 115 App 扫码授权 → 把 access_token / refresh_token 粘贴进 App 设置。
 *
 * 风控口径：开放平台是**官方授权通道**，不走 Web 端 WAF（无 405 风控熔断那套）；
 * 但仍做限速礼貌（OpenList 默认 LimitRate=1r/s 同级）+ 按 pick_code 合并并发取链。
 * SY <--
 */
@Inject
@SingleIn(AppScope::class)
class Cloud115OpenApi(
    private val preferences: NetdiskPreferences,
) : NetdiskClient {

    private val client: OkHttpClient by lazy { NetdiskHttp.newClient() }

    // ===================== 会话级缓存 =====================

    /** cid → (取表时刻, 全量条目)。目录 + 文件两次并行枚举合并成一次 API。 */
    private data class ListCache(val atElapsed: Long, val entries: List<NetdiskEntry>)
    private val listCache = ConcurrentHashMap<String, ListCache>()
    private val listMutex = Mutex()

    /** 文件 id → 真实文件名（列表/取链时缓存）。 */
    private val names = ConcurrentHashMap<String, String>()

    /** pick_code → 短时效直链（同文件多次 Range 读不能每次都打一次取链）。 */
    private data class CachedLink(val info: NetdiskDownloadInfo, val atElapsed: Long)
    private val downlinks = ConcurrentHashMap<String, CachedLink>()

    /** 同 pick_code 并发取链合并（leader/follower，与 Cloud115Api 同款）。 */
    private val downurlFlights = ConcurrentHashMap<String, CompletableDeferred<NetdiskDownloadInfo>>()

    /** 文件 id → pick_code（列表时登记；downlink 的入参是文件 id）。 */
    private val pickCodeById = ConcurrentHashMap<String, String>()

    // ===================== 令牌 =====================

    /** 当前 access_token（每次现读偏好，设置页粘贴后立即生效）。 */
    private fun accessToken(): String = preferences.cloud115OpenAccessToken.get().trim()

    private fun refreshToken(): String = preferences.cloud115OpenRefreshToken.get().trim()

    private fun isConfigured(): Boolean =
        accessToken().isNotBlank() && refreshToken().isNotBlank()

    /**
     * 刷新 access_token（OpenList `RefreshToken` 同款：form `refresh_token`，无需 AppKey）。
     * 成功后**同时**写回新的 access_token 与 refresh_token（115 约定 refresh 轮换、有效期不延长）。
     */
    private suspend fun doRefresh(): Boolean = withIOContext {
        val request = Request.Builder()
            .url(API_REFRESH_TOKEN)
            .post(
                FormBody.Builder()
                    .add("refresh_token", refreshToken())
                    .build(),
            )
            .build()
        client.newCall(request).execute().use { response ->
            val body = runCatching { JSONObject(response.body.string()) }.getOrNull()
                ?: return@withIOContext false
            // AuthResp 契约：state 无关，看 code==0 且 error 为空
            val code = body.optInt("code", -1)
            val error = body.optString("error")
            if (!response.isSuccessful || code != 0 || error.isNotBlank()) {
                logcat(LogPriority.WARN) {
                    "115 Open 刷新令牌失败: code=$code error=$error message=${body.optString("message")}"
                }
                return@withIOContext false
            }
            val data = body.optJSONObject("data") ?: return@withIOContext false
            val newAccess = data.optString("access_token")
            val newRefresh = data.optString("refresh_token")
            if (newAccess.isBlank() || newRefresh.isBlank()) return@withIOContext false
            preferences.cloud115OpenAccessToken.set(newAccess)
            preferences.cloud115OpenRefreshToken.set(newRefresh)
            logcat(LogPriority.INFO) { "115 Open 令牌已自动刷新" }
            true
        }
    }

    /** 401 系错误码（40140116 no auth / 40140123~26 过期等）→ 值得刷新一次重试。 */
    private fun is401Started(code: Long): Boolean = code.toString().startsWith("401")

    /**
     * 统一带 Bearer 的请求执行：解析 `Resp` 信封（state/code/message/data），
     * 401 系错误码自动刷新令牌并**重试一次**（OpenList authRequest 同款）。
     */
    private suspend fun executeWithAuth(
        request: Request,
        allowRefresh: Boolean = true,
    ): JSONObject = withIOContext {
        fun send(): Triple<Int, Long, String> {
            val authorized = request.newBuilder()
                .header("Authorization", "Bearer ${accessToken()}")
                .build()
            client.newCall(authorized).execute().use { response ->
                val text = response.body.string()
                return Triple(response.code, response.code.toLong(), text)
            }
        }

        val (httpStatus, _, text) = send()
        if (httpStatus !in 200..299) {
            if (httpStatus == 401 && allowRefresh && doRefresh()) {
                return@withIOContext executeWithAuth(request, allowRefresh = false)
            }
            throw IllegalStateException(
                if (httpStatus == 401) "115 开放平台登录状态已失效，请到设置里重新粘贴令牌" else "115 开放平台请求失败（HTTP $httpStatus）",
            )
        }
        val root = JSONObject(text)
        val state = root.optBoolean("state", false)
        val code = root.optLong("code", 0L)
        if (!state) {
            // OpenList authRequest 同款：失败仅看 state；code==99 或 401 系 → 刷新一次重试
            if (allowRefresh && (code == 99L || is401Started(code)) && doRefresh()) {
                return@withIOContext executeWithAuth(request, allowRefresh = false)
            }
            throw IllegalStateException(mapOpenError(code, root.optString("message")))
        }
        root
    }

    // ===================== 公开接口 =====================

    /** 连通性测试：列根目录成功即视为可用（令牌失效会在这里暴露）。 */
    suspend fun check() = withIOContext {
        list("0")
    }

    /**
     * 列出目录（[id] 为文件夹 fid，根目录 `0`），分页拉全。
     * 20s 内存缓存：BaseWebDavSource 会并行调 listDirectories/listFiles，两次调用合并成一次 API。
     */
    override suspend fun list(id: String): List<NetdiskEntry> = withIOContext {
        if (!isConfigured()) throw IllegalStateException("115 开放平台未配置令牌，请到设置 → 网盘 → 115 开放平台粘贴")
        val key = id.ifBlank { "0" }
        listCache[key]?.let { cached ->
            if (SystemClock.elapsedRealtime() - cached.atElapsed < LIST_CACHE_MS) return@withIOContext cached.entries
        }
        listMutex.withLock {
            listCache[key]?.let { cached ->
                if (SystemClock.elapsedRealtime() - cached.atElapsed < LIST_CACHE_MS) return@withIOContext cached.entries
            }
            val fresh = fetchList(key)
            listCache[key] = ListCache(SystemClock.elapsedRealtime(), fresh)
            fresh
        }
    }

    fun invalidateList(id: String? = null) {
        if (id == null) listCache.clear() else listCache.remove(id)
    }

    /**
     * 取下载直链（5min 缓存 + 按 pick_code 合并并发）。
     * [id] 是文件 fid；映射到 pick_code 后取链（映射缺失时回退列父目录不可行——这里直接报错，
     * 正常路径上 list 一定先于 downlink 执行并登记 pickCodeById）。
     */
    suspend fun downurl(id: String): NetdiskDownloadInfo = withIOContext {
        if (!isConfigured()) throw IllegalStateException("115 开放平台未配置令牌，请到设置 → 网盘 → 115 开放平台粘贴")
        val pickCode = pickCodeById[id]
            ?: throw IllegalStateException("115 Open 缺少文件的提取码（$id），请重新进入目录刷新列表")
        downlinks[pickCode]?.let { cached ->
            if (SystemClock.elapsedRealtime() - cached.atElapsed < DOWNLINK_TTL_MS) {
                return@withIOContext cached.info
            }
            downlinks.remove(pickCode)
        }
        fetchDownurlMerged(id, pickCode)
    }

    private suspend fun fetchDownurlMerged(id: String, pickCode: String): NetdiskDownloadInfo {
        val deferred = CompletableDeferred<NetdiskDownloadInfo>()
        val prior = downurlFlights.putIfAbsent(pickCode, deferred)
        if (prior != null) return prior.await()
        return try {
            val info = fetchDownurl(id, pickCode)
            downlinks[pickCode] = CachedLink(info, SystemClock.elapsedRealtime())
            deferred.complete(info)
            info
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            downurlFlights.remove(pickCode)
        }
    }

    /** 直链失效（CDN 403）后由调用方调它，下次重新取链。 */
    fun invalidateDownurl(id: String) {
        pickCodeById[id]?.let { downlinks.remove(it) }
    }

    // ===================== NetdiskClient 接缝 =====================

    override suspend fun downlink(id: String): NetdiskDownloadInfo = downurl(id)

    override fun invalidateDownlink(id: String) = invalidateDownurl(id)

    override suspend fun resolveName(id: String): String? {
        names[id]?.let { return it }
        return runCatching { downurl(id).name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.also { names[id] = it }
    }

    /**
     * 下载直链请求头：开放平台直链**绑定取链时的 UA**（OpenList `Link` 把取链 UA 原样
     * 回填到下载 Header），故取链与下载都用同一个固定 UA。
     */
    override fun downloadHeaders(): Map<String, String> = mapOf(
        "User-Agent" to DOWNLOAD_UA,
    )

    // ===================== 列表 =====================

    private suspend fun fetchList(cid: String): List<NetdiskEntry> = withIOContext {
        var offset = 0L
        val all = mutableListOf<NetdiskEntry>()
        while (true) {
            val query = "cid=${enc(cid)}&limit=$PAGE_SIZE&offset=$offset&asc=0&o=user_utime&show_dir=1"
            val request = Request.Builder().url("$API_FS_FILES?$query").get().build()
            val root = executeWithAuth(request)
            val data = root.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val fid = item.optString("fid")
                val name = item.optString("fn")
                val isDir = item.optString("fc") == "0"
                // 目录用 fid 进入下一层；文件用 pc（pick_code）取直链（OpenList Obj 同款映射）
                val entryId = if (isDir) fid else fid
                val pc = item.optString("pc")
                if (entryId.isBlank()) {
                    logcat(LogPriority.WARN) { "115 Open 列表返回空 fid 条目，已跳过: $name" }
                    continue
                }
                if (!isDir && pc.isNotBlank()) pickCodeById[entryId] = pc
                if (name.isNotBlank()) names[entryId] = name
                all += NetdiskEntry(
                    id = entryId,
                    name = name,
                    isDir = isDir,
                    size = item.optLong("fs", 0L).coerceAtLeast(0L),
                    mtime = item.optLong("upt", 0L) * 1000L,
                )
            }
            val count = root.optLong("count", 0L)
            if (count <= offset + data.length()) break
            offset += PAGE_SIZE
        }
        // 目录在前 + 名称自然序（与 Cookie 模式一致，数字自然序对漫画分卷更友好）
        all.sortedWith(
            compareByDescending<NetdiskEntry> { it.isDir }
                .thenComparator { a, b -> naturalCompare(a.name, b.name) },
        )
    }

    // ===================== 直链（明文响应，无需解密） =====================

    private suspend fun fetchDownurl(id: String, pickCode: String): NetdiskDownloadInfo = withIOContext {
        val request = Request.Builder()
            .url(API_FS_DOWNURL)
            .header("User-Agent", DOWNLOAD_UA) // 直链与 UA 绑定：取链时就固定 UA
            .post(
                FormBody.Builder()
                    .add("pick_code", pickCode)
                    .build(),
            )
            .build()
        val root = executeWithAuth(request)
        val data = root.optJSONObject("data")
            ?: throw IllegalStateException("115 Open 直链响应缺少 data")
        // data = { <文件id>: { file_name, file_size, url: { url } } }
        val keys = data.keys()
        if (!keys.hasNext()) throw IllegalStateException("115 Open 直链数据为空")
        val item = data.getJSONObject(keys.next())
        val url = item.optJSONObject("url")?.optString("url").orEmpty()
        if (url.isBlank()) throw IllegalStateException("115 Open 未返回下载直链")
        val name = item.optString("file_name").takeIf { it.isNotBlank() }?.also { names[id] = it }
        val size = when (val s = item.opt("file_size")) {
            is Number -> s.toLong().coerceAtLeast(0L)
            is String -> s.toLongOrNull()
            else -> null
        }
        NetdiskDownloadInfo(url = url, name = name, size = size)
    }

    // ===================== 工具 =====================

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val API_FS_FILES = "https://proapi.115.com/open/ufile/files"
        private const val API_FS_DOWNURL = "https://proapi.115.com/open/ufile/downurl"
        private const val API_REFRESH_TOKEN = "https://passportapi.115.com/open/refreshToken"

        /**
         * 取链与下载共用 UA（直链绑定 UA）。用普通浏览器 UA —— 开放平台不做 Web WAF 判定，
         * 但直链校验「取链 UA == 下载 UA」，两端一致即可。
         */
        private const val DOWNLOAD_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"

        private const val PAGE_SIZE = 200L
        private const val LIST_CACHE_MS = 20_000L
        private const val DOWNLINK_TTL_MS = 5 * 60_000L

        /** 令牌获取页（设置页「获取令牌」按钮跳转用）。 */
        const val TOKEN_HELPER_URL = "https://api.oplist.org"

        /** 开放平台错误码 → 用户可读文案（对照官方 401 系与通用错误）。 */
        fun mapOpenError(code: Long, message: String): String = when {
            code.toString().startsWith("401") ->
                "115 开放平台登录状态已失效（令牌过期或被解除授权），请重新粘贴令牌"
            code == 40140116L -> "115 令牌无权限（refresh_token 已被新令牌顶掉），请重新获取"
            else -> if (message.isBlank()) "115 开放平台错误($code)" else "115 开放平台错误($code): $message"
        }
    }
}

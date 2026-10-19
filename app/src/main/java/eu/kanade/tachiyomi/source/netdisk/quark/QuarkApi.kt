package eu.kanade.tachiyomi.source.netdisk.quark

import android.net.Uri
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * SY --> 夸克网盘客户端（非官方 Web API，Cookie 认证）。
 *
 * 契约逐条对齐 MHW `quark.rs`（真机已验证 —— 见 `.trellis/spec/backend/quark-source.md`）：
 *
 *  - 认证：粘贴 pan.quark.cn 登录后的 Cookie；请求头三件套 `Cookie` +
 *    `Referer: https://pan.quark.cn` + quark-cloud-drive Electron UA；query 固定 `pr=ucpro&fr=pc`。
 *  - 续期：响应 `Set-Cookie` 里的 `__puus` 回写续期（~3h 过期，回写后会话内一直有效）。
 *  - 列表：`GET /file/sort?pdir_fid={fid}&_page=N&_size=100&...`（根目录 `0`，分页拉全；
 *    `data.list[]` 含 `fid/file_name/size/file/updated_at`，`file=true` 是文件）。
 *  - 直链：`POST /file/download` body `{"fids":[fid]}` → `data[0].download_url`。
 *  - 错误：响应 `{status, code, message, data, metadata}`，`code != 0` 即失败（401/4000 = Cookie 失效）。
 *
 * 直链同样要带三件套头；403 视为直链失效，由调用方 [invalidateDownlink] 后重取一次。
 * SY <--
 */
@Inject
@SingleIn(AppScope::class)
class QuarkApi(
    private val preferences: NetdiskPreferences,
) : NetdiskClient {

    private val client: OkHttpClient by lazy { NetdiskHttp.newClient() }

    // ===================== 会话级缓存 =====================

    private data class ListCache(val atElapsed: Long, val entries: List<NetdiskEntry>)
    private val listCache = ConcurrentHashMap<String, ListCache>()
    private val listMutex = Mutex()

    /** fid → 真实文件名（列表/取链时缓存；封面与扩展名探测都依赖真实文件名）。 */
    private val names = ConcurrentHashMap<String, String>()

    private data class CachedLink(val info: NetdiskDownloadInfo, val atElapsed: Long, val cookieEpoch: Long)
    private val dlinks = ConcurrentHashMap<String, CachedLink>()
    private val dlinkMutex = Mutex()

    /** `__puus` 被回写后 +1：整批直链缓存按代际作废（直链与 Cookie 绑定）。 */
    private val cookieEpoch = java.util.concurrent.atomic.AtomicLong(0)

    // ===================== 公开接口 =====================

    /** 当前 Cookie（`__puus` 回写后会在内存里更新；扫码/重贴后立即生效）。 */
    @Volatile
    private var sessionCookie: String? = null

    private fun cookie(): String {
        sessionCookie?.let { return it }
        return preferences.quarkCookie.get().trim().also { sessionCookie = it }
    }

    /** Cookie 由外部（设置页重新粘贴）改写后调用，作废会话缓存。 */
    fun resetSession() {
        sessionCookie = null
        dlinks.clear()
        listCache.clear()
    }

    private fun rootFid(): String = preferences.quarkRoot.get().trim().ifBlank { "0" }

    /** 连通性测试：`/config` + 根目录首屏 list（登录失效会在这里暴露）。 */
    suspend fun check() = withIOContext {
        request("/config", "GET", emptyList(), null)
        list(rootFid())
    }

    override suspend fun list(fid: String): List<NetdiskEntry> = withIOContext {
        val key = fid.ifBlank { "0" }
        listCache[key]?.let { cached ->
            if (SystemClock.elapsedRealtime() - cached.atElapsed < LIST_CACHE_MS) {
                return@withIOContext cached.entries
            }
        }
        listMutex.withLock {
            listCache[key]?.let { cached ->
                if (SystemClock.elapsedRealtime() - cached.atElapsed < LIST_CACHE_MS) {
                    return@withIOContext cached.entries
                }
            }
            val fresh = fetchList(key)
            listCache[key] = ListCache(SystemClock.elapsedRealtime(), fresh)
            fresh
        }
    }

    fun invalidateList(fid: String? = null) {
        if (fid == null) listCache.clear() else listCache.remove(fid)
    }

    override suspend fun downlink(fid: String): NetdiskDownloadInfo = withIOContext {
        val epoch = cookieEpoch.get()
        dlinks[fid]?.let { cached ->
            if (cached.cookieEpoch == epoch &&
                SystemClock.elapsedRealtime() - cached.atElapsed < DLINK_TTL_MS
            ) {
                return@withIOContext cached.info
            }
            dlinks.remove(fid)
        }
        dlinkMutex.withLock {
            val e = cookieEpoch.get()
            dlinks[fid]?.let { cached ->
                if (cached.cookieEpoch == e &&
                    SystemClock.elapsedRealtime() - cached.atElapsed < DLINK_TTL_MS
                ) {
                    return@withIOContext cached.info
                }
            }
            val info = fetchDownlink(fid)
            dlinks[fid] = CachedLink(info, SystemClock.elapsedRealtime(), cookieEpoch.get())
            info
        }
    }

    /** 直链失效（CDN 403）后由调用方调它，下次重新取链。 */
    override fun invalidateDownlink(fid: String) {
        dlinks.remove(fid)
    }

    /** 解析 fid 对应的真实文件名：列表缓存 → download 响应。 */
    override suspend fun resolveName(fid: String): String? {
        names[fid]?.let { return it }
        return runCatching { downlink(fid).name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.also { names[fid] = it }
    }

    // ===================== 列表 =====================

    private suspend fun fetchList(fid: String): List<NetdiskEntry> {
        val all = mutableListOf<NetdiskEntry>()
        var page = 1L
        while (true) {
            val body = request(
                "/file/sort",
                "GET",
                listOf(
                    "pdir_fid" to fid,
                    "_page" to page.toString(),
                    "_size" to PAGE_SIZE.toString(),
                    "_fetch_total" to "1",
                    "fetch_all_file" to "1",
                    "fetch_risk_file_name" to "1",
                    "_sort" to "file_type:asc,file_name:asc",
                ),
                null,
            )
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: JSONObject()
            val listArr = data.optJSONArray("list") ?: JSONArray()
            val total = root.optJSONObject("metadata")?.optLong("_total", 0L) ?: 0L
            for (i in 0 until listArr.length()) {
                val item = listArr.optJSONObject(i) ?: continue
                val id = item.optString("fid")
                val name = item.optString("file_name")
                val isFile = item.optBoolean("file", false)
                if (id.isBlank()) continue
                if (isFile && name.isNotBlank()) names[id] = name
                all += NetdiskEntry(
                    id = id,
                    name = name,
                    isDir = !isFile,
                    size = item.optLong("size", 0L).coerceAtLeast(0L),
                    mtime = item.optLong("updated_at", 0L) / 1000L,
                )
            }
            if (page * PAGE_SIZE >= total || listArr.length() < PAGE_SIZE) break
            page++
        }
        return all.sortedWith(
            compareByDescending<NetdiskEntry> { it.isDir }
                .thenComparator { a, b -> naturalCompare(a.name, b.name) },
        )
    }

    // ===================== 请求封装 =====================

    /**
     * 统一请求：GET/POST + 三件套头 + `pr/fr` 参数；`code != 0` 报错；
     * 响应 `Set-Cookie` 中的 `__puus` 回写续期（真变了才推进代际，避免误伤直链缓存）。
     */
    private suspend fun request(
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        bodyJson: JSONObject?,
    ): String = withIOContext {
        val cookieNow = cookie()
        val url = buildString {
            append(API_BASE)
            append(path)
            append("?pr=ucpro&fr=pc")
            query.forEach { (k, v) -> append('&').append(k).append('=').append(enc(v)) }
        }
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookieNow)
            .header("Referer", QUARK_REFERER)
            .header("User-Agent", QUARK_UA)
            .apply {
                if (method == "POST") {
                    val media = "application/json".toMediaType()
                    post((bodyJson ?: JSONObject()).toString().toRequestBody(media))
                } else {
                    get()
                }
            }
            .build()
        client.newCall(request).execute().use { response ->
            // __puus 回写续期
            val puus = response.headers.values("Set-Cookie").firstNotNullOfOrNull { sc ->
                sc.split(';').firstOrNull()?.let { kv ->
                    val idx = kv.indexOf('=')
                    if (idx > 0 && kv.substring(0, idx).trim() == "__puus") {
                        kv.substring(idx + 1).trim().takeIf { it.isNotEmpty() }
                    } else {
                        null
                    }
                }
            }
            if (puus != null) {
                val updated = upsertCookie(cookieNow, "__puus", puus)
                if (updated != cookieNow) {
                    cookieEpoch.incrementAndGet()
                    logcat(LogPriority.INFO) { "QuarkApi: __puus 已回写续期" }
                }
                sessionCookie = updated
            }
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("夸克 API HTTP ${response.code}: ${text.take(200)}")
            }
            val parsed = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("解析夸克 API 响应失败")
            }
            val code = parsed.optLong("code", -1L)
            if (code != 0L) {
                throw IllegalStateException(mapQuarkError(code, parsed.optString("message")))
            }
            text
        }
    }

    private suspend fun fetchDownlink(fid: String): NetdiskDownloadInfo = withIOContext {
        val body = request(
            "/file/download",
            "POST",
            emptyList(),
            JSONObject().put("fids", JSONArray().put(fid)),
        )
        val root = JSONObject(body)
        val arr = root.optJSONArray("data") ?: JSONArray()
        val item = arr.optJSONObject(0) ?: throw IllegalStateException("夸克下载接口未返回直链")
        val url = item.optString("download_url")
        if (url.isBlank()) throw IllegalStateException("夸克下载接口未返回直链")
        val name = item.optString("file_name").takeIf { it.isNotBlank() }?.also { names[fid] = it }
        val size = item.optLong("size", -1L).takeIf { it >= 0 }
        NetdiskDownloadInfo(url = url, name = name, size = size)
    }

    // ===================== 请求头（供 Range 适配器复用） =====================

    /** 下载直链也要带三件套（夸克按调用方 UA/Referer 校验签名）。 */
    override fun downloadHeaders(): Map<String, String> = mapOf(
        "Cookie" to cookie(),
        "Referer" to QUARK_REFERER,
        "User-Agent" to QUARK_UA,
    )

    // ===================== 工具 =====================

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /** 把 `k=v` 合并进 Cookie 串（已有同名键则覆盖）。 */
    private fun upsertCookie(cookie: String, key: String, value: String): String {
        val parts = cookie.split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()
        val prefix = "$key="
        val idx = parts.indexOfFirst { it.startsWith(prefix, ignoreCase = true) }
        val entry = "$key=$value"
        if (idx >= 0) parts[idx] = entry else parts.add(entry)
        return parts.joinToString("; ")
    }

    // ===================== 扫码登录（三步，契约对齐 MHW quark.rs 第 67 轮实测修正） =====================

    /** 扫码载荷：[token]/[requestId] 用于轮询，[qrcode] 用于渲染二维码。 */
    data class QrPayload(val token: String, val requestId: String, val qrcode: String)

    /** 扫码会话状态：按 requestId 隔离（Cookie 累积 + 一次性 ticket）。 */
    private class QrSession {
        /** 各步/各跳累积的 Set-Cookie（只取最后一步会缺 cookie ⇒ drive API 判登录过期）。 */
        val cookies = linkedMapOf<String, String>()

        /** 轮询阶段拿到的 service_ticket（**一次性**凭据，不能重复去问）。 */
        var ticket: String? = null
    }

    private val qrSessions = ConcurrentHashMap<String, QrSession>()

    /** 第一步：获取二维码 token 与二维码内容（手机夸克 App 认得的格式）。 */
    suspend fun webQrStart(): QrPayload = withIOContext {
        val requestId = newRequestId()
        qrSessions[requestId] = QrSession()
        val url = "$QR_TOKEN_URL?client_id=$QR_CLIENT_ID&v=$QR_API_VERSION&request_id=${enc(requestId)}"
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            mergeSetCookies(requestId, response)
            val body = JSONObject(response.body.string())
            val token = body.optJSONObject("data")?.optJSONObject("members")?.optString("token").orEmpty()
            if (token.isBlank()) {
                throw IllegalStateException(
                    "获取夸克二维码失败:status=${body.optInt("status", -1)} ${body.optString("message")}",
                )
            }
            QrPayload(token = token, requestId = requestId, qrcode = qrContent(token))
        }
    }

    /** 第二步：轮询扫码状态。0 等待 / 2 已登录 / -1 失败或过期。 */
    suspend fun webQrPoll(token: String, requestId: String): Int = withIOContext {
        val text = qrTicketBody(token, requestId)
        val body = runCatching { JSONObject(text) }.getOrElse { return@withIOContext 0 }
        val ticket = qrTicketOf(body)
        ticket?.let { qrSessions[requestId]?.ticket = it }
        // 只有官方文档明示的失败码才算失败，其余（含未知/缺字段）一律按「等待」处理
        // （MHW 第 67 轮实测：把未枚举的等待码判成失败会让用户即便确认也换不到 Cookie）。
        when {
            ticket != null -> 2
            body.optLong("status", 0L) in setOf(50004002L, 50004003L, 50004004L) -> -1
            else -> 0
        }
    }

    /** 第三步：扫码确认后换取 Cookie（`k=v; k2=v2`，末尾不带 `;`）。 */
    suspend fun webQrCookie(token: String, requestId: String): String = withIOContext {
        val session = qrSessions[requestId] ?: throw IllegalStateException("扫码会话不存在，请重新扫码")
        // 优先用轮询阶段已拿到的 ticket（一次性凭据，不能重复去问）。
        val ticket = session.ticket
            ?: runCatching { JSONObject(qrTicketBody(token, requestId)) }.getOrNull()?.let { qrTicketOf(it) }
            ?: throw IllegalStateException("扫码尚未确认或已过期，请重新扫码")
        // Cookie 必须整条流程累积（含各跳重定向）：手动跟重定向，逐跳收集 Set-Cookie。
        val noRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        var url = "$ACCOUNT_INFO_URL?st=${enc(ticket)}&lw=scan&platform=pc"
        var hops = 0
        while (hops < 5) {
            hops++
            var next: String? = null
            val request = Request.Builder().url(url).apply {
                if (session.cookies.isNotEmpty()) {
                    header("Cookie", session.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
            }.get().build()
            noRedirectClient.newCall(request).execute().use { response ->
                mergeSetCookies(requestId, response)
                next = response.header("Location")
            }
            if (next == null) break
            url = if (next!!.startsWith("http")) {
                next!!
            } else {
                val schemeEnd = url.indexOf("://")
                val hostStart = schemeEnd + 3
                val slash = url.indexOf('/', hostStart)
                if (slash > 0) url.substring(0, slash) + next else url + next
            }
        }
        qrSessions.remove(requestId)
        if (session.cookies.isEmpty()) throw IllegalStateException("夸克未返回会话 Cookie，请重新扫码")
        session.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private suspend fun qrTicketBody(token: String, requestId: String): String = withIOContext {
        val url = "$QR_TICKET_URL?client_id=$QR_CLIENT_ID&v=$QR_API_VERSION&token=${enc(token)}&request_id=${enc(requestId)}"
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            mergeSetCookies(requestId, response)
            response.body.string()
        }
    }

    private fun qrTicketOf(body: JSONObject): String? = runCatching {
        body.optJSONObject("data")?.optJSONObject("members")?.optString("service_ticket").orEmpty()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 把某响应里的 Set-Cookie 合并进该扫码会话（同名后者覆盖）。 */
    private fun mergeSetCookies(requestId: String, response: okhttp3.Response) {
        val session = qrSessions[requestId] ?: return
        response.headers.values("Set-Cookie").forEach { sc ->
            val pair = sc.split(';').firstOrNull()?.trim() ?: return@forEach
            val idx = pair.indexOf('=')
            if (idx > 0) {
                session.cookies[pair.substring(0, idx).trim()] = pair.substring(idx + 1).trim()
            }
        }
    }

    /**
     * 二维码内容：**必须**带 `ssb=weblogin` / `uc_param_str` / `uc_biz_str` 三个参数
     * （MHW 第 67 轮抓包结论），少了它们手机 App 扫到直接判「二维码已过期」。
     */
    private fun qrContent(token: String): String = Uri.Builder()
        .scheme("https")
        .authority("su.quark.cn")
        .appendPath("4_eMHBJ")
        .appendQueryParameter("token", token)
        .appendQueryParameter("client_id", QR_CLIENT_ID)
        .appendQueryParameter("ssb", "weblogin")
        .appendQueryParameter("uc_param_str", "")
        .appendQueryParameter(
            "uc_biz_str",
            "S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0",
        )
        .build()
        .toString()

    /** 服务端只要求一个随机标识。 */
    private fun newRequestId(): String {
        val raw = java.util.UUID.randomUUID().toString().replace("-", "")
        return "${raw.substring(0, 8)}-${raw.substring(8, 12)}-4${raw.substring(13, 16)}" +
            "-${raw.substring(16, 20)}-${raw.substring(20, 32)}"
    }

    companion object {
        private const val QR_TOKEN_URL = "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin"
        private const val QR_TICKET_URL = "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken"
        private const val ACCOUNT_INFO_URL = "https://pan.quark.cn/account/info"
        private const val QR_CLIENT_ID = "532"
        private const val QR_API_VERSION = "1.2"

        private const val API_BASE = "https://drive.quark.cn/1/clouddrive"

        /** 下载直链 / API 请求的 Referer 与 UA 必须与登录域一致（夸克会校验调用方）。 */
        const val QUARK_REFERER = "https://pan.quark.cn"
        const val QUARK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 " +
            "Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"

        private const val PAGE_SIZE = 100L
        private const val LIST_CACHE_MS = 20_000L

        /** 直链缓存 TTL（保守值；403 反应式失效 + Cookie 代际作废两道自愈）。 */
        private const val DLINK_TTL_MS = 120_000L

        fun mapQuarkError(code: Long, message: String): String = when (code) {
            401L, 4000L -> "登录状态失效，请重新粘贴夸克 Cookie"
            else -> if (message.isBlank()) "夸克 API 错误($code)" else "夸克 API 错误($code): $message"
        }
    }
}

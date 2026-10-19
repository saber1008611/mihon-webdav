package eu.kanade.tachiyomi.source.netdisk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SY --> 网盘源公共支撑（115 / 夸克 / 后续更多）。
 *
 * 只放「与具体网盘无关」的能力，避免在每个 API 类里重复：
 *  - [RateGate]：账号级限速（网盘风控盯的是「短时间调用次数」，必须削峰）；
 *  - [classifyRangeProbe]：`Range: bytes=0-0` 响应分类（206 = 可流式 / 200 = 服务器忽略 Range）；
 *  - [NetdiskHttp.downloadToFile]：带自定义头部的整本下载（rar/7z 等无法随机访问格式的兜底）；
 *  - [NetdiskHttp.newClient]：复用全局 NetworkHelper 的 DoH / 代理 / 证书配置，只改超时。
 *
 * ⚠️ 随机读（Range 流式 + 块缓存 + 不支持 Range 时的整本回退）**全部复用**
 * [eu.kanade.tachiyomi.source.webdav.WebDavRandomAccessSource]，网盘侧只提供「动态直链 + 附加头部 +
 * 稳定身份」三样东西，不重复实现那套已有充分实测的读逻辑。
 * SY <--
 */
object NetdiskHttp {

    /**
     * SY --> 网盘 API 专用客户端：**全新构建**，不复用 NetworkHelper.client。
     *
     * 原因：全局 client 带 [eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor]
     * ——「UA 为空就用默认 UA 补上」。而 115 直链接口要求 **UA 置空** + 固定 Referer
     * （取链与下载的头必须完全一致，否则 403 invalid signature，见 115-web-source.md 第 4 节），
     * 夸克也按 UA 校验签名。网盘侧必须对 UA 有完全控制权，故自建（无 cookie jar，Cookie 手动管理）。
     */
    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * SY --> 整本下载 [url] 到 [dst]（带 [headers]）。
     * 落盘前先写 `.part` 再改名，避免中断留下半截文件被当成有效缓存。
     */
    suspend fun downloadToFile(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        dst: File,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("网盘下载失败：HTTP ${response.code}")
            }
            val total = response.body.contentLength()
            val tmp = File(dst.parentFile, dst.name + ".part")
            tmp.delete()
            try {
                response.body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            copied += n
                            onProgress?.invoke(copied, total)
                        }
                    }
                }
                if (tmp.length() <= 0L) throw IllegalStateException("网盘下载为空：$url")
                dst.delete()
                if (!tmp.renameTo(dst)) {
                    tmp.copyTo(dst, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        }
    }
}

/**
 * SY --> 账号级限速门（虚拟时隙调度）。
 *
 * 语义：允许 [burst] 个请求瞬时通过，之后按 [perSecond] 的持续速率放行。
 * 用「虚拟时隙」而非固定 sleep，保证并发调用者有确定性的先后次序（先来先得），
 * 且不会因为每个调用者各自 sleep 固定间隔而把吞吐压到 1/interval。
 */
class RateGate(
    private val perSecond: Double,
    private val burst: Int = 1,
) {
    private val lock = Any()
    private var nextFreeNanos = 0L

    suspend fun acquire() {
        val intervalNanos = (1_000_000_000.0 / perSecond.coerceAtLeast(0.01)).toLong()
        val burstWindowNanos = ((burst - 1).coerceAtLeast(0) * intervalNanos)
        val waitNanos: Long = synchronized(lock) {
            val now = System.nanoTime()
            if (nextFreeNanos == 0L) nextFreeNanos = now
            // 允许突发：调度时间最多只落后「burst-1 个间隔」，超出说明空闲太久，直接对齐到当前。
            val earliest = now - burstWindowNanos
            if (nextFreeNanos < earliest) nextFreeNanos = earliest
            val slot = maxOf(nextFreeNanos, earliest)
            nextFreeNanos = slot + intervalNanos
            (slot - now).coerceAtLeast(0L)
        }
        if (waitNanos > 0L) delay(waitNanos / 1_000_000L)
    }
}

/**
 * SY --> `Range: bytes=0-0` 探测结果。
 * [supported] = 服务器返回 206（支持随机访问，可流式）；[totalSize] = -1 表示未知。
 */
data class RangeProbe(val supported: Boolean, val totalSize: Long)

/**
 * SY --> 把 `bytes=0-0` 的响应分类：
 *  - `206` → 支持 Range，从 `Content-Range: bytes 0-0/12345` 取总大小；
 *  - `200` → 服务器忽略 Range（返回整文件），需整本下载兜底；
 *  - 其它 → 视为不支持。
 */
fun classifyRangeProbe(status: Int, contentRange: String?, contentLength: Long?): RangeProbe = when (status) {
    206 -> {
        val total = contentRange
            ?.substringAfterLast('/', "")
            ?.trim()
            ?.toLongOrNull()
            ?: contentLength
            ?: -1L
        RangeProbe(true, total)
    }
    200 -> RangeProbe(false, contentLength ?: -1L)
    else -> RangeProbe(false, -1L)
}

/** SY --> 网盘直链信息（取链响应里能拿到的最小集合）。 */
data class NetdiskDownloadInfo(
    val url: String,
    val name: String?,
    val size: Long?,
)

/**
 * SY --> 网盘目录项（与具体网盘无关的中间表示）。
 *
 * [id] 是**该网盘的目录标识**：115 用 cid（目录）/ pickcode（文件），夸克用 fid。
 * 上层据此拼「源内相对路径」：逻辑路径 = `/<id>/<id>/...`（每段是该层的 id）。
 * 之所以用 id 而非文件名做路径：网盘接口按 id 取直链，且文件可重名。
 */
data class NetdiskEntry(
    val id: String,
    val name: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val mtime: Long = 0L,
)

/**
 * SY --> 网盘客户端的最小接缝：[BaseNetdiskSource] 只依赖这五个动词，
 * 每接一个新网盘只需实现一个 [NetdiskClient]。
 */
interface NetdiskClient {
    /** 列出目录（[id] 为该网盘的目录标识，根目录由实现自定）下的**直接子项**。 */
    suspend fun list(id: String): List<NetdiskEntry>

    /** 取文件下载直链（短时效；实现内自带 TTL 缓存与并发合并）。 */
    suspend fun downlink(id: String): NetdiskDownloadInfo

    /** 解析文件 id 对应的真实文件名（列表缓存 → 取链响应）。 */
    suspend fun resolveName(id: String): String?

    /** 直链失效（CDN 403 等）后由调用方调它，下次重新取链。 */
    fun invalidateDownlink(id: String)

    /** 下载直链必须携带的请求头（Cookie / UA / Referer，与取链完全一致）。 */
    fun downloadHeaders(): Map<String, String>
}


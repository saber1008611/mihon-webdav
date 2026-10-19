package eu.kanade.tachiyomi.source.webdav

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile
import kotlin.math.min
import logcat.LogPriority
import mihon.core.archive.LocalRandomAccessSource
import mihon.core.archive.RandomAccessSource
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat

/**
 * WebDAV（HTTP）远程随机读取源 —— [RandomAccessSource] 的远程实现。
 *
 * 原理：HTTP Range 请求（206 Partial Content）按 1MB 块拉数据，配合
 * [RemoteZipReader] 的 ZIP 中央目录直读实现「远程随机访问 ZIP 内部条目」，
 * **不整本下载**。Reader / PageLoader / 缓存零感知（与 Local 同一抽象）。
 *
 * - 能力探测（懒）：首个 GET `Range: bytes=0-0`：
 *   - `206` → 从 Content-Range 取文件总大小，启用随机访问；
 *   - `200` → 服务器不支持 Range：整本流式下载到 [fallbackCacheDir] 缓存文件，
 *     之后包装 [LocalRandomAccessSource] 按本地随机读（功能不崩，代价是全量下载）。
 *   - rar/7z 等 [FORCED_FALLBACK_EXTS] 格式：跳过探测直接整本缓存（这类格式无法
 *     随机访问，反复从头扫等于流量爆炸；整本一次下载流量确定性最好，缓存跨会话复用）。
 * - 块缓冲：按字节上限 [MAX_CACHE_BYTES]（约 32MB）做 LRU，块大小 [READ_CHUNK]（1MB），
 *   块起点按 1MB 网格对齐（相邻偏移的读者共用同一块）。
 *   大块摊薄请求数，缓冲未命中才发新的 Range 请求。块大于单次读回调所需（256KB），
 *   命中时部分返回即可，多页并发解码时各页位置互不踩踏。
 * - 线程安全：[ArchivePageLoader] 会并发解码多页共享同一 source，全部状态在 [lock] 内访问；
 *   每次请求独立 Call，close 时取消 in-flight 请求。
 * - 鉴权：Basic Auth（user/pass 可空，匿名时省略 Authorization 头）。
 */
class WebDavRandomAccessSource(
    url: String,
    username: String? = null,
    password: String? = null,
    private val fallbackCacheDir: File? = null,
    /** 整本缓存磁盘上限（字节）：超限按 lastModified LRU 淘汰；0/负数 = 不限。 */
    private val cacheMaxBytes: Long = Long.MAX_VALUE,
    /** 整本下载（rar/7z 强制回退 / 非 Range 回退）进度回调（值域 0f..1f）；不传则不报进度。 */
    private val onProgress: ((Float) -> Unit)? = null,
    /**
     * SY --> 网盘源（115/夸克）：每次请求时**动态取最新直链**。
     *
     * 网盘直链带有效期（115 短时效 / 夸克约 3h），且部分网盘会按调用方 UA 校验签名，
     * 必须每次现取。provider 内部应自带有效期缓存，避免每读一块都发一次取链请求。
     * 返回 null = 取链失败（抛错让上层重试）。
     * 默认 null = 用构造时的 [url]（**WebDAV 行为逐字不变**）。
     */
    private val dynamicUrlProvider: (() -> String?)? = null,
    /**
     * SY --> 网盘源：每次请求附加的头部（Cookie / User-Agent / Referer）。
     * 网盘会校验「取链接口」与「下载直链」的调用方一致（115 尤其严格），故不能只靠现取直链。
     * 默认 null = 仅 Basic Auth（**WebDAV 行为逐字不变**）。
     */
    private val extraHeadersProvider: (() -> Map<String, String>)? = null,
    /**
     * SY --> 网盘源：**缓存身份**（稳定标识）。
     * 动态直链每次都不同，若拿它当块缓存键 / 整本缓存文件名，同一文件每次打开都全冷、
     * 还会在磁盘上堆出一批孤儿缓存。这里固定用「源 id + 逻辑路径」当身份。
     * 默认 null = 用 [url]（**WebDAV 行为逐字不变**）。
     */
    private val stableKey: String? = null,
) : RandomAccessSource {

    /**
     * 规范化后的**基础** URL（宽容中文/空格等未编码字符，逐段百分号编码补齐）。
     * SY --> 网盘源（带 [dynamicUrlProvider]）传进来的是**逻辑路径**而非 URL，跳过规范化。
     */
    private val baseUrl: String = if (dynamicUrlProvider == null) normalizeUrl(url) else url
    // SY <--

    /** 缓存身份（稳定，不随直链轮换变化）。 */
    private val identity: String = stableKey ?: baseUrl

    private val urlLock = Any()
    private var cachedDynamicRaw: String? = null
    private var cachedDynamicNormalized: String? = null

    /**
     * 本次请求实际使用的 URL：有 [dynamicUrlProvider] 时现取直链（同一 raw 结果复用规范化结果），
     * 否则用构造时的 [baseUrl]。
     */
    private fun currentRequestUrl(): String {
        val provider = dynamicUrlProvider ?: return baseUrl
        val got = provider() ?: throw IOException("网盘直链获取失败（登录态可能已失效）")
        synchronized(urlLock) {
            if (got == cachedDynamicRaw) return cachedDynamicNormalized!!
            val normalized = normalizeUrl(got)
            cachedDynamicRaw = got
            cachedDynamicNormalized = normalized
            return normalized
        }
    }

    /**
     * rar/7z 等不支持随机访问的格式：跳过 Range 探测，
     * 打开时直接整本缓存到 [fallbackCacheDir] 后按本地读。
     */
    private val forceFallback = remoteExt() in FORCED_FALLBACK_EXTS

    /**
     * 远程文件扩展名（小写，已按 URL 规范化，query 已剥离）。
     * SY --> 用**稳定身份**而非直链：网盘直链常是无扩展名的签名 URL，拿它判扩展名会失准。
     */
    private fun remoteExt(): String =
        identity.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
    // SY <--

    private val authHeader: String? =
        if (username.isNullOrEmpty() && password.isNullOrEmpty()) {
            null
        } else {
            val raw = "${username.orEmpty()}:${password.orEmpty()}"
            "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        }

    private val client = sharedClient

    private val lock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var probed = false
    private var total = -1L
    private var fallback: LocalRandomAccessSource? = null

    /** 最近缓存的块（start inclusive / end exclusive）：**跨实例共享**，见 [SharedBlockCache]。 */
    private val cacheKey: String
        get() = "$identity@$total"

    private var currentCall: Call? = null

    override val size: Long
        get() {
            ensureProbed()
            return total
        }

    /**
     * SY --> 是否已退化为「整本落盘后的本地随机读」：服务器不支持 Range（探测返回非 206）
     * 或 rar/7z 强制回退时会走这条路。此时读链是**本地瞬时**的，调用方据此避免误判
     * 「深链 → 再整本下载一份」（那会白下第二次）。需先访问 [size] 触发懒探测。
     * SY <--
     */
    val isLocalFallback: Boolean
        get() = fallback != null

    override fun read(offset: Long, length: Int): ByteArray {
        ensureProbed()
        fallback?.let { return it.read(offset, length) }
        synchronized(lock) {
            if (closed) throw IOException("WebDAV source closed")
            if (offset < 0) throw IOException("非法 offset: $offset")
            if (offset >= total) return ByteArray(0)

            // 缓冲命中：返回可以少于 length（契约允许；调用方据此推进 position）
            sharedCache.get(cacheKey, offset)?.let { win ->
                val avail = minOf(win.size, length)
                return win.copyOf(avail)
            }

            // 未命中：Range 拉一块（在 total 处截断）。
            // SY --> 块起点向下对齐 READ_CHUNK 网格：pdfium/zip 多处从相邻偏移各自
            // 首读，不对准时会拉出两个交叠块、把对方挤出 LRU（实测 PDF 打开因此
            // 反复重拉、明显偏慢）。对齐后同一 1MB 区间全局只有一个块，命中率翻倍。
            val chunkStart = offset / READ_CHUNK * READ_CHUNK
            val end = (chunkStart + READ_CHUNK - 1L).coerceAtMost(total - 1)
            val bytes = rangeGet(chunkStart, end)
            // SY <--
            sharedCache.put(cacheKey, chunkStart, bytes)
            // SY --> 返回窗口必须落在请求的 [offset] 上。块是按 READ_CHUNK 网格对齐拉的，
            // 起点是 chunkStart 不是 offset；此前直接 copyOf(length) 把「块的头部」当成
            // 「offset 处」返回，任何非对齐读都拿到错位数据：文件小于 READ_CHUNK 时更是
            // 恒返回文件开头。表现为 zip 找不到 EOCD、pdfium 报 not in PDF format，
            // 整条流式链路（封面抽取 + 流式阅读）全废。修复：从块内 offset 起截取。
            val from = (offset - chunkStart).toInt()
            if (from >= bytes.size) return ByteArray(0)
            val avail = minOf(bytes.size - from, length)
            return bytes.copyOfRange(from, from + avail)
            // SY <--
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // 打断阻塞中的网络读（翻页跳页时旧请求立即失效，不占连接）
            currentCall?.cancel()
            fallback?.close()
            fallback = null
        }
        // SY --> 注意：**不清共享块缓存**。它是「按文件」保留的，正是为了让同一本
        // （尤其 PDF 的固定头部/尾部 xref）下次打开零成本命中。生命周期由全局预算管理。
        // SY <--
    }

    /** 懒探测：一次 GET `Range: bytes=0-0` 同时完成「是否支持 Range」与「文件总大小」确认。 */
    private fun ensureProbed() {
        if (probed) return
        synchronized(lock) {
            if (probed) return
            if (closed) throw IOException("WebDAV source closed")
            if (forceFallback) {
                val file = ensureFallbackFile()
                fallback = LocalRandomAccessSource(file)
                total = file.length()
                probed = true
                return
            }
            val call = client.newCall(newRequestBuilder().header("Range", "bytes=0-0").build())
            currentCall = call
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("WebDAV 探测失败 HTTP ${resp.code}: $identity")
                    }
                    if (resp.code == 206) {
                        // Content-Range: bytes 0-0/<total>
                        total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                            ?: throw IOException("WebDAV 206 未返回 Content-Range: $identity")
                    } else {
                        // 不支持 Range：整本流式落盘（本次响应体即全量数据，直接消费）
                        // → 本地随机读回退（不崩，代价全量下载）
                        // SY --> WARN：读大 PDF 若卡住几分钟，多半是这里悄悄整本下载了，留取证日志
                        logcat(LogPriority.WARN) { "[WebDav] Range 探测返回 ${resp.code}（非 206），整本落盘回退: $identity" }
                        // SY <--
                        val dir = fallbackCacheDir
                            ?: throw IOException("WebDAV 服务器不支持 Range，且未提供回退缓存目录")
                        dir.mkdirs()
                        val file = fallbackFile(dir)
                        val tmp = File(dir, file.name + ".part")
                        val totalLen = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                        resp.body?.byteStream()?.use { input ->
                            tmp.outputStream().use { output ->
                                copyWithProgress(input, output, totalLen) { p -> onProgress?.invoke(p) }
                            }
                        } ?: throw IOException("WebDAV 空响应体: $identity")
                        if (file.exists()) file.delete()
                        tmp.renameTo(file)
                        fallback = LocalRandomAccessSource(file)
                        total = file.length()
                    }
                }
            } finally {
                currentCall = null
            }
            probed = true
        }
    }

    /**
     * 整本缓存落盘（rar/7z 强制回退路径）：文件名 = URL hash + 扩展名，跨会话复用——
     * 已存在完整缓存则跳过下载（重新打开同一卷零流量，命中时刷新 lastModified 作 LRU 依据）。
     * 先落 `.part` 临时文件、完成后原子改名，避免中断留下半包被误用。
     * 磁盘缓存目录受 [cacheMaxBytes] 预算约束，超限淘汰最旧文件。
     */
    private fun ensureFallbackFile(): File {
        val dir = fallbackCacheDir ?: throw IOException("WebDAV 整本缓存需要回退缓存目录")
        dir.mkdirs()
        val file = fallbackFile(dir)
        if (file.exists() && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            logcat(LogPriority.DEBUG) { "[WebDav] 命中整本缓存，跳过下载: ${file.name}" }
            return file
        }
        enforceCacheBudget(dir, keepName = file.name)
        val tmp = File(dir, file.name + ".part")
        // 先用 HEAD 取总大小，整本下载过程上报进度百分比（取不到则不报进度，保持转圈）。
        val totalLen = runCatching { headLength() }.getOrNull() ?: -1L
        val call = client.newCall(newRequestBuilder().build())
        currentCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("WebDAV 下载失败 HTTP ${resp.code}: $identity")
                }
                resp.body?.byteStream()?.use { input ->
                    tmp.outputStream().use { output ->
                        copyWithProgress(input, output, totalLen) { p -> onProgress?.invoke(p) }
                    }
                } ?: throw IOException("WebDAV 空响应体: $identity")
            }
        } finally {
            currentCall = null
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("整本缓存落盘失败: ${file.name}")
        }
        // 新文件计入后若仍超预算，先淘汰别的（不含本次文件，下次打开再自然轮转）
        enforceCacheBudget(dir, keepName = file.name)
        logcat(LogPriority.DEBUG) { "[WebDav] 整本缓存完成: ${file.name} (${file.length()} B)" }
        return file
    }

    /**
     * 磁盘缓存预算（LRU）：目录内 `webdav_*` 完整文件（排除 .part 与 [keepName]）按
     * lastModified 从旧到新淘汰，直到总量回到 [cacheMaxBytes] 内。0/负数 = 不限。
     */
    private fun enforceCacheBudget(dir: File, keepName: String) {
        if (cacheMaxBytes <= 0L) return
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("webdav_") && !f.name.endsWith(".part") }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= cacheMaxBytes) return
        files.filter { it.name != keepName }
            .sortedBy { it.lastModified() }
            .forEach { victim ->
                if (total <= cacheMaxBytes) return
                val len = victim.length()
                if (victim.delete()) {
                    total -= len
                    logcat(LogPriority.DEBUG) { "[WebDav] 磁盘缓存超限，淘汰: ${victim.name} ($len B)" }
                }
            }
    }

    /** 整本缓存的稳定文件名：URL hash（8 位 hex）+ 远程扩展名（缺省 bin）。 */
    private fun fallbackFile(dir: File): File =
        File(dir, "webdav_" + String.format("%08x", identity.hashCode()) + "." + remoteExt().ifBlank { "bin" })

    /**
     * 带进度拷贝：从 [input] 拷到 [output]，约每 256KB 上报一次 [onProgress]（值域 0f..1f）。
     * [total] <= 0 时无法计算百分比，仅上报起始 0f（保持转圈，不抛异常）。
     */
    private fun copyWithProgress(input: InputStream, output: OutputStream, total: Long, onProgress: (Float) -> Unit) {
        onProgress(0f)
        if (total <= 0L) {
            input.copyTo(output)
            return
        }
        val buffer = ByteArray(256 * 1024)
        var written = 0L
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            written += read
            onProgress((written.toFloat() / total).coerceIn(0f, 1f))
        }
        output.flush()
    }

    /** HEAD 取 Content-Length（整本下载总大小）；服务器不支持 HEAD 或缺失时返回 null。 */
    private fun headLength(): Long? {
        val call = client.newCall(newRequestBuilder().head().build())
        currentCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) return null
                return resp.header("Content-Length")?.toLongOrNull()
            }
        } finally {
            currentCall = null
        }
    }

    /** Range 读取 [start]..[endInclusive]（闭区间）。416 视作越界返回空（与契约一致）。 */
    private fun rangeGet(start: Long, endInclusive: Long): ByteArray {
        // SY --> 115 等网盘在高并发下会偶发无视 Range 头、直接回 200+整本文件体。
        // 若不校验 206：整本被当一块收下（白拉几百 MB），且 copyOf(length) 取到的是
        // 文件开头的字节冒充偏移 start 的数据 → pdfium/zip 见错位数据判 "corrupted"。
        // 实测这是 PDF 封面 40-130s 失败与阅读大 PDF 卡几分钟的根因。丢弃错体、
        // 重试一次，仍失败则抛错让上层跳过/回落。
        var attempt = 0
        while (true) {
            attempt++
            val call = client.newCall(
                newRequestBuilder().header("Range", "bytes=$start-$endInclusive").build(),
            )
            currentCall = call
            try {
                call.execute().use { resp ->
                    if (resp.code == 416) return ByteArray(0)
                    if (!resp.isSuccessful) throw IOException("WebDAV HTTP ${resp.code}: $identity")
                    if (resp.code != 206) {
                        logcat(LogPriority.WARN) { "[WebDav] Range 被忽略（HTTP ${resp.code}），丢弃响应重试: $identity $start-$endInclusive" }
                        throw RangeIgnoredException("WebDAV 忽略 Range（${resp.code}）: $identity")
                    }
                    val body = resp.body ?: throw IOException("WebDAV 空响应体: $identity")
                    // 只取请求长度，多余字节不读（防错体超长把内存打爆）
                    val expected = (endInclusive - start + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val src = body.source()
                    val buf = src.readByteArray(expected.toLong()) ?: throw IOException("WebDAV 空响应体: $identity")
                    return buf
                }
            } catch (e: RangeIgnoredException) {
                if (attempt >= 2) throw IOException(e.message, e)
                if (closed) throw IOException("WebDAV source closed")
                runCatching { Thread.sleep(300L * attempt) }
            } finally {
                currentCall = null
            }
        }
    }

    /** 服务器无视 Range 头回了 200（响应体是整本文件，必须丢弃）。 */
    private class RangeIgnoredException(message: String) : IOException(message)
    // SY <--

    private fun newRequestBuilder(): Request.Builder {
        val b = Request.Builder().url(currentRequestUrl())
        authHeader?.let { b.header("Authorization", it) }
        // SY --> 网盘源：附加 Cookie / UA / Referer（网盘会校验调用方身份）。
        extraHeadersProvider?.invoke()?.forEach { (k, v) -> b.header(k, v) }
        // SY <--
        return b
    }

    companion object {
        /** 全局共享 OkHttpClient：连接池/线程池跨所有 WebDAV 章节复用，降低新建连接频次。 */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** Range 块大小：1MB。比单次回调（256KB）大，摊薄请求数。 */
        private const val READ_CHUNK = 1024 * 1024

        /**
         * SY --> 共享块缓存的全局字节预算：64MB（旧实现是「每实例 32MB」）。
         * 理由见 [SharedBlockCache]：实例级缓存随会话销毁，导致同一本浅链 PDF 重复打开时全冷
         * （实测冷 4342ms vs 命中 191ms）。改按文件跨实例共享后，重复打开也能命中。
         * SY <--
         */
        private const val SHARED_CACHE_BYTES = 64L * 1024 * 1024

        /** 全进程共享的 Range 块缓存（`url@size` → 块）。 */
        private val sharedCache = SharedBlockCache(SHARED_CACHE_BYTES)

        /** 这些扩展名的远程归档不支持随机访问，打开时整本缓存到本地。 */
        private val FORCED_FALLBACK_EXTS = setOf("rar", "cbr", "7z", "cb7")

        /**
         * 去掉 host 末尾的 DNS 根标记「.」：部分 WebDAV 服务器在 PROPFIND 的 href 里返回
         * FQDN 绝对名，HTTP URL 不需要该尾点，且 OkHttp 会拒绝带尾点的 host。
         */
        fun stripRootDot(url: String): String {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return url
            val rest = url.substring(schemeEnd + 3)
            val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, slash)
            val colon = authority.indexOf(':')
            val host = if (colon < 0) authority else authority.substring(0, colon)
            if (!host.endsWith('.')) return url
            val strippedAuthority = if (colon < 0) {
                host.removeSuffix(".")
            } else {
                host.removeSuffix(".") + authority.substring(colon)
            }
            return url.substring(0, schemeEnd + 3) + strippedAuthority + rest.substring(slash)
        }

        /** 宽容解析手输 URL：中文/空格等未编码字符按 UTF-8 百分号编码补齐后重建。 */
        fun normalizeUrl(raw: String): String {
            val cleaned = stripRootDot(raw)
            cleaned.toHttpUrlOrNull()?.let { return it.toString() }
            val schemeEnd = cleaned.indexOf("://")
            require(schemeEnd > 0) { "非法 WebDAV URL: $cleaned" }
            val rest = cleaned.substring(schemeEnd + 3)
            val slash = rest.indexOf('/')
            require(slash >= 0) { "非法 WebDAV URL（缺路径）: $cleaned" }
            val authority = rest.substring(0, slash)
            val encodedPath = rest.substring(slash)
                .split('/')
                .joinToString("/") { seg ->
                    if (seg.isEmpty()) seg else URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
                }
            val rebuilt = "${cleaned.substring(0, schemeEnd)}://$authority$encodedPath"
            return rebuilt.toHttpUrlOrNull()?.toString()
                ?: throw IllegalArgumentException("非法 WebDAV URL: $cleaned")
        }
    }
}

/**
 * SY --> 跨实例共享的 Range 块缓存（全局 LRU，条目按 `url@size` 归属到具体文件）。
 *
 * **为什么必须共享**（2026-09-28 实测）：[WebDavRandomAccessSource] 的生命周期 = 一次阅读会话
 * （`PdfPageLoader` / 归档句柄回收时就 close），而 PDF 每次打开都要重取「固定尾部 xref + 头部对象」。
 * 同一本 684MB 浅链卷：**冷缓存打开 4342ms，命中已取回的块只要 191ms —— 20 倍差**。
 * 所以块缓存不能随实例销毁：按文件保留、用全局预算封顶即可。
 *
 * 键里带文件大小（`url@size`），避免服务器上的文件被替换后命中陈旧块。
 * SY <--
 */
private class SharedBlockCache(private val maxBytes: Long) {

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var bytes = 0L

    private class Entry(val key: String, val start: Long, val data: ByteArray) {
        val end: Long get() = start + data.size
    }

    /** 返回「从 [offset] 起直到该块末尾」的窗口；未命中返回 null（调用方自行截到所需长度）。 */
    fun get(key: String, offset: Long): ByteArray? = synchronized(lock) {
        val hit = entries.firstOrNull { it.key == key && offset >= it.start && offset < it.end }
            ?: return@synchronized null
        entries.remove(hit)
        entries.addLast(hit) // LRU touch
        hit.data.copyOfRange((offset - hit.start).toInt(), hit.data.size)
    }

    fun put(key: String, start: Long, data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(lock) {
            entries.addLast(Entry(key, start, data))
            bytes += data.size
            while (bytes > maxBytes && entries.size > 1) {
                bytes -= entries.removeFirst().data.size
            }
            if (bytes > maxBytes) { // 单块就超预算（理论不可能）时兜底丢弃
                bytes -= entries.removeFirst().data.size
            }
        }
    }
}

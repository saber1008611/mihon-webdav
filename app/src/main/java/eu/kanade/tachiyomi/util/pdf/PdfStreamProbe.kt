package eu.kanade.tachiyomi.util.pdf

import mihon.core.archive.RandomAccessSource

/**
 * [probePdfStreamability] 的结果。
 *
 * @param hops 在 `maxHops` 上限内实际走到的 `/Prev` 跳数（含 `startxref` 指向的那一段）。
 * @param chainEnded 链在上限内走完 = **浅链**（正常/线性化 PDF 只有 1~2 跳），适合流式；
 *   false = 链过长或结构异常，流式打开会慢，应退回整本下载。
 */
data class PdfStreamProbeResult(
    val hops: Int,
    val chainEnded: Boolean,
)

/**
 * SY --> 「这个 PDF 走流式打开会不会快」的探测（2026-09-28）。
 *
 * **为什么需要它**（真机实测 + 文件结构取证）：`长篇漫画/魔都` 这类卷**不是线性化 PDF**，
 * 而是「每页一个增量 revision」拼出来的——实测 `startxref → xref → /Prev` 链 **205 跳**，
 * 链上偏移从 234,915,217 一路散布到 163（几乎覆盖整个 224MB），而 205 段 xref 加起来**只有 24.8KB**。
 * 解析器要拿到文档终态**必须串行走完这条链**，这跟引擎无关（pdfium 与 PDF.js 都要跟着 `/Prev` 递归）：
 *  - 开销 ≈ 跳数 × 单次请求耗时（**app 内实测约 256ms/请求**）→ 205 × 256ms ≈ 52s；
 *  - 更糟的是传输层按 1MB 网格取块，205 跳要拉 **199MB**（只为读 24.8KB）→ 实测整份打开 31.4s。
 *
 * **浅链则完全不同**：正常 PDF / 线性化 PDF 只有 1 跳，轻度增量更新也就 2~5 跳，只需 2~4 次往返出首屏。
 * 实测把同一卷用 qpdf(`pikepdf`) 线性化+拍平后：**205 跳 → 2 跳、199MB → 2.1MB**。
 *
 * 所以策略是「**先花几次往返量链长，再决定流式还是整本下载兜底**」，而不是一刀切。
 *
 * 探测本身只需尾部 4KB 找 `startxref`，然后沿 `/Prev` 每跳读一小窗（[WINDOW_BYTES]）、**不解析对象**；
 * 超过 [DEFAULT_MAX_HOPS] 立即判「不适合流式」，避免探测自身把时间花掉。
 *
 * 调用方应在 IO 上下文执行（底层是阻塞随机读）。
 * SY <--
 */
fun probePdfStreamability(
    source: RandomAccessSource,
    maxHops: Int = DEFAULT_MAX_PROBE_HOPS,
    timeBudgetMs: Long = PROBE_TIME_BUDGET_MS,
): PdfStreamProbeResult {
    val size = source.size
    // 小文件不值得探测：整本下载本身很快，且下载后能永久复用。
    if (size <= PROBE_SMALL_FILE_BYTES) return PdfStreamProbeResult(hops = 0, chainEnded = false)

    val tailLen = minOf(TAIL_BYTES.toLong(), size).toInt()
    val tail = readExactAt(source, size - tailLen, tailLen) ?: return PdfStreamProbeResult(0, false)
    val startXref = STARTXREF_RE.findAll(String(tail, Charsets.ISO_8859_1))
        .lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
        ?: return PdfStreamProbeResult(0, false)

    // SY --> 时间预算从**这里**开始计，而不是函数开头。原因（2026-09-28 踩过的坑）：
    // 建源后的第一个请求（Range 探测）+ 尾部读取有约 **1.3~1.5s 的一次性固定开销**，
    // 若把它算进预算，预算就会被它吃光 → 循环第一跳之前就超时 → 永远返回 hops=0「深链」，
    // **连浅链 PDF 也被误判成深链**（实测冰菓 1 跳的卷也被判成深链写了占位图）。
    // 预算只应该约束「跳」的部分（每跳约 256ms）。
    // SY <--
    val startedAt = System.currentTimeMillis()

    var cur = startXref
    var hops = 0
    val visited = HashSet<Long>()
    while (hops < maxHops) {
        // 时间预算：**探测成本与跳数成正比，流式成本也与之成正比**，探得越深浪费越多
        // （探到 37 跳 ≈ 白花一次整本下载的时间）。所以一旦超过预算就判「不适合流式」，
        // 直接去整本下载——这是刻意的保守取值，宁可少省一点，不可让探测本身变成主要开销。
        if (System.currentTimeMillis() - startedAt > timeBudgetMs) return PdfStreamProbeResult(hops, false)
        if (cur <= 0L || cur >= size) return PdfStreamProbeResult(hops, false)
        if (!visited.add(cur)) return PdfStreamProbeResult(hops, false) // /Prev 成环
        val winLen = minOf(WINDOW_BYTES.toLong(), size - cur).toInt()
        val win = readExactAt(source, cur, winLen) ?: return PdfStreamProbeResult(hops, false)
        val winStr = String(win, Charsets.ISO_8859_1)
        // 结构自检：xref 段要么以 `xref` 开头的经典表，要么是 `N 0 obj ... /Type/XRef` 的交叉引用流。
        // 两者都对不上说明链条已断（或偏移不可信），按「不适合流式」处理。
        val head = winStr.take(HEAD_SNIFF)
        if (!head.contains("xref") && !head.contains("obj")) return PdfStreamProbeResult(hops, false)
        val prev = PREV_RE.find(winStr)?.groupValues?.get(1)?.toLongOrNull()
            ?: return PdfStreamProbeResult(hops + 1, chainEnded = true) // 无 /Prev = 链到头
        hops++
        cur = prev
    }
    return PdfStreamProbeResult(hops, false) // 超过上限：深链，交给整本下载
}

/** 读满 [length] 字节（[RandomAccessSource.read] 允许短读，这里循环补齐）；失败返回 null。 */
private fun readExactAt(source: RandomAccessSource, offset: Long, length: Int): ByteArray? {
    if (length <= 0 || offset < 0L) return null
    val out = ByteArray(length)
    var done = 0
    while (done < length) {
        val part = try {
            source.read(offset + done, length - done)
        } catch (t: Throwable) {
            return null
        }
        if (part.isEmpty()) return null
        val n = minOf(part.size, length - done)
        part.copyInto(out, done, 0, n)
        done += n
    }
    return out
}

/** `startxref` 一定在最后 1KB 内（规范要求），取 4KB 更稳。 */
private const val TAIL_BYTES = 4 * 1024

/** 每跳只读这么一窗：xref 段通常几百字节，正常 PDF 的经典 xref 表也就几 KB。 */
private const val WINDOW_BYTES = 8 * 1024

/** 结构自检只看窗口开头这么多字节。 */
private const val HEAD_SNIFF = 96

/**
 * 硬上限（防跑飞）。**真正的闸门是 [PROBE_TIME_BUDGET_MS]**：
 * 实测 app 内每跳约 256ms（且首个请求还有约 1.5s 的一次性连接/后端打开开销），
 * 所以在这台服务器上预算 1500ms 会在约 6 跳处收手；换到低延迟服务器则能探得更深。
 *
 * 区分度完全够：正常 / 线性化 PDF 只有 **1~2 跳**（qpdf 拍平后实测 2 跳；另一本 684MB 的卷实测 1 跳），
 * 而「每页一个增量 revision」拼出的卷是 **205 跳** —— 与阈值之间的间隔是数量级的。
 * 代价：介于两者之间的文件会被保守判成深链去整本下载（好在下载后本地打开是瞬时的）。
 */
const val DEFAULT_MAX_PROBE_HOPS = 32

/**
 * 探测时间预算（毫秒）。**探测成本与跳数成正比、流式成本也与之成正比**（探到几十跳就等于白花一次
 * 整本下载的时间），所以必须设预算，超过即判「不适合流式」去整本下载。
 */
const val PROBE_TIME_BUDGET_MS = 1500L

/** 小于此大小的文件不探测：整本下载本身就快，且下载后可永久复用。 */
const val PROBE_SMALL_FILE_BYTES = 16L * 1024 * 1024

private val STARTXREF_RE = Regex("startxref\\s+(\\d+)")
private val PREV_RE = Regex("/Prev\\s+(\\d+)")

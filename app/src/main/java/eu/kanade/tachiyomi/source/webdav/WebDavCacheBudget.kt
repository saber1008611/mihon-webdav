package eu.kanade.tachiyomi.source.webdav

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * SY --> WebDAV 云盘源 / 本地文件夹图源的磁盘缓存预算（目录总量 + LRU 淘汰），两处源共用。
 *
 * 背景（2026-09-28 实测）：这些缓存目录都是**可达缓存**（内容随时能重新取回来），但修前
 * 有几个目录**完全没有上限**——`cache/webdav-archives` 仅 11 个文件就累积到 1.2G，
 * `cache/webdav-images` 则只增不减（实测 118M 且持续增长）。长时间使用会把应用缓存顶到 GB 级。
 *
 * 淘汰规则：目录总量超 [maxBytes] 时，按 `lastModified` 从旧到新删，直到回到预算内。
 *
 * 两条安全约定（避免删掉正在用的文件）：
 *  1. [keepNames] 里的文件永不淘汰 —— 本次刚落盘、调用方马上要读的那个。
 *  2. 最近 [GRACE_MS] 内修改过的文件一律跳过 —— 并发下载 / 封面抽取等在途产物。
 *     因此极端情况下（短时间内落盘量本身就超预算）目录会**暂时**超过 [maxBytes]，
 *     这是刻意的失败方向：宁可多占一点盘，也不能删掉正在读的文件。
 *     正常使用下「正在读的章节」永远是最新的那个，而被淘汰的是最旧的，故不受影响。
 *
 * 不做日志（`logcat` 需要类内隐式接收者，顶层函数里不可用）：把淘汰结果返回给调用方记日志。
 *
 * @return 被淘汰的「文件名 to 字节数」，已淘汰列表；未触发淘汰时为空。
 */
/**
 * SY --> 2026-09-28：扫描节流状态 —— `目录绝对路径 -> [上次扫描时刻, 上次统计总量]`。
 * 仅供 [enforceLruCacheBudget] 跳过不必要的目录扫描，见那里的说明。
 */
private val budgetScanState = ConcurrentHashMap<String, LongArray>()

/**
 * 距上次完整扫描短于此时长、且上次总量明显低于预算时，跳过本次扫描。
 */
private const val SCAN_THROTTLE_MS = 2_000L

internal fun enforceLruCacheBudget(
    dir: File,
    maxBytes: Long,
    keepNames: Set<String> = emptySet(),
): List<Pair<String, Long>> {
    // SY --> 2026-09-28：扫描节流 —— 这是 WebDAV 出图卡顿的一个**真实热点**。
    //
    // 原实现在**每一次**下载 / 拷贝完成后都执行：
    //   dir.listFiles() + 逐个 length()
    // ⇒ 目录里几百个文件就是几百次 stat。而它位于**预取链路的关键路径**上
    //   （`WebDavSource.downloadImageToCache` 末尾），多路并发预取时这几百次 stat
    //   会成倍叠加，直接拖慢「页来不及就绪」⇒ 未缓存的 WebDAV 新图加载异常卡顿。
    //
    // 现在：上次扫描距今 < [SCAN_THROTTLE_MS] 且上次总量 < 预算 80% 时直接跳过
    //   （此刻离超预算还很远，不可能需要淘汰）。
    //
    // ⚠️ 安全方向不变：只在**未超预算**时才记录节流状态；一旦真的超预算，
    //   仍然每次都扫描并执行淘汰 —— 宁可多扫，也绝不让缓存无上限膨胀。
    // SY <--
    val key = dir.absolutePath
    val now = System.currentTimeMillis()
    budgetScanState[key]?.let { st ->
        if (now - st[0] < SCAN_THROTTLE_MS && st[1] < maxBytes * 8 / 10) {
            return emptyList()
        }
    }

    val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return emptyList()
    var total = files.sumOf { it.length() }
    if (total <= maxBytes) {
        budgetScanState[key] = longArrayOf(now, total)
        return emptyList()
    }

    val evicted = mutableListOf<Pair<String, Long>>()
    val graceCutoff = System.currentTimeMillis() - GRACE_MS
    val victims = files
        .filter { it.name !in keepNames && it.lastModified() < graceCutoff }
        .sortedBy { it.lastModified() }
    for (victim in victims) {
        if (total <= maxBytes) break
        val len = victim.length()
        if (victim.delete()) {
            total -= len
            evicted += victim.name to len
        }
    }
    return evicted
}

/** 刚写入的文件在此时长内不参与淘汰，防止删掉并发下载 / 正在读取的在途产物。 */
private const val GRACE_MS = 60_000L
// SY <--

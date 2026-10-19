package eu.kanade.tachiyomi.source.webdav

import mihon.core.archive.ArchiveEntry
import mihon.core.archive.ArchiveHandle
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * [ArchiveHandle] 的页级内存缓存装饰器。
 *
 * 用途（Phase A）：远程 ZIP 每页读取都是一次网络 Range 请求。阅读器在以下场景会**重复**
 * 读取同一页——整话超分（先 getPages 探测、再逐页 enhance 重读）、来回翻页重新解码。
 * 本类把已解出的页字节缓存在内存（LRU，按字节上限淘汰），命中即免流量。
 *
 * 不落盘：页缓存是会话级的「减少重复下载」优化，不持久化；[maxBytes] 上限保护低端机内存。
 * 整本回退（rar/7z / 非 Range 服务器）走本地 RandomAccessSource，本地读近乎零成本，
 * 通常不需要套本缓存——但套上也无妨（本地读命中即时返回）。
 */
class CachingArchiveHandle(
    private val delegate: ArchiveHandle,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : ArchiveHandle {

    private val cache = LinkedHashMap<String, ByteArray>()
    private var cachedBytes = 0L
    private val lock = Any()

    init {
        // SY 2026-09-29 诊断：见 companion 里 liveHandles/liveBytes 的说明。
        val n = liveHandles.incrementAndGet()
        logcat(LogPriority.DEBUG) {
            "CachingArchiveHandle: open #$n live=$n maxBytes=${maxBytes / 1024 / 1024}MB"
        }
    }

    override val archiveHashCode: Int get() = delegate.archiveHashCode
    override val encrypted: Boolean get() = delegate.encrypted
    override val wrongPassword: Boolean? get() = delegate.wrongPassword

    override fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T = delegate.useEntries(block)

    override fun getInputStream(entryName: String): InputStream? {
        synchronized(lock) {
            cache[entryName]?.let { return ByteArrayInputStream(it) }
        }
        val stream = delegate.getInputStream(entryName) ?: return null
        // 读满条目字节（远程 = 一次 Range；本地 = 一次随机读），之后缓存。
        val bytes = stream.use { it.readBytes() }
        if (bytes.isNotEmpty()) {
            synchronized(lock) {
                val replaced = cache.put(entryName, bytes)
                if (replaced != null) cachedBytes -= replaced.size
                cachedBytes += bytes.size
                // 字节上限 LRU：从最旧条目开始淘汰，直到回到预算内
                var eldest = cache.keys.firstOrNull()
                while (cachedBytes > maxBytes && eldest != null) {
                    val victim = cache.remove(eldest) ?: break
                    cachedBytes -= victim.size
                    eldest = cache.keys.firstOrNull()
                }
            }
        }
        return ByteArrayInputStream(bytes)
    }

    override fun close() {
        val freed: Long
        synchronized(lock) {
            freed = cachedBytes
            cache.clear()
            cachedBytes = 0
        }
        delegate.close()
        // SY 2026-09-29 诊断：见 companion 里 liveHandles 的说明。
        val n = liveHandles.decrementAndGet()
        logcat(LogPriority.DEBUG) {
            "CachingArchiveHandle: close freed=${freed / 1024}KB stillLive=$n"
        }
    }

    companion object {
        /**
         * 内存页缓存上限。
         *
         * SY --> 2026-09-29：**256MB → 24MB**。真机证据（`app.mihonW`，堆上限 512MB，largeHeap）：
         * 「连着滑几本」时 OOM 崩溃，
         * `java.lang.OutOfMemoryError: Failed to allocate a 25756992 byte allocation ... 501MB/512MB`
         * 落在 `SubsamplingScaleImageView$TilesInitTask → Decoder.init`。
         * 堆快照显示 GC 持续运行但占用稳定在 94~95%（**存活集**大，不是碎片）。
         *
         * 放大链路：本缓存是**内存**的，而且每个 `ReaderPage.stream` 都写成
         * `{ reader.getInputStream(entry.name) }` —— **页对象捕获了归档句柄**，
         * 只要还有任何一个旧页对象可达，整个 256MB 缓存就一直活着。
         * 读 5 本 = 最多 5 × 256MB，远超 512MB 堆。
         *
         * 本缓存只是「减少重复读取」的优化（整话超分重读 / 来回翻页重解码），
         * 缓存的是**压缩后**的条目字节（单页通常 0.3~3MB），
         * 24MB 已能覆盖约 10~80 页，收益基本不变，但最坏情况从 256MB 降到 24MB。
         * SY <--
         */
        const val DEFAULT_MAX_BYTES = 24L * 1024 * 1024

        // SY --> 2026-09-29 诊断：存活句柄数。
        // 本类实例持有的是 Java 堆字节数组，是这次 OOM 的第一嫌疑。
        // 判读：正常阅读时应恒为 1；换书瞬时最多 2。若持续增长 ⇒ 旧句柄泄漏，需查
        // `ReaderChapter.unref()` 是否把 references 真正降到 0。
        private val liveHandles = AtomicInteger(0)
        // SY <--
    }
}

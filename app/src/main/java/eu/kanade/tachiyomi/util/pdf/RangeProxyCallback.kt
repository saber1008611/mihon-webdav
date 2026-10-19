package eu.kanade.tachiyomi.util.pdf

import android.os.ProxyFileDescriptorCallback
import mihon.core.archive.RandomAccessSource
import java.util.ArrayDeque
import kotlin.math.min

/**
 * SY --> 把 [RandomAccessSource]（HTTP Range 或本地定位读）伪装成一个真正的文件描述符，
 * 供原生库（pdfium）以 fd 方式随机读。
 *
 * 为什么需要它：pdfium 解析 PDF 时发出的是**大量稀疏小读**（512B~8KB 级别，横跨文件头、
 * xref 表、各个对象流）。若逐条转发成 Range 请求，在高延迟云盘（115/CloudDrive2 这类单请求
 * 数百毫秒起）上一次打开就要成百上千次往返 —— 实测封面扫描单本 107~252 秒，就是这么来的。
 *
 * 这里按 [PROXY_BLOCK_SIZE] 把小读**聚合成整块拉取**，进程内 LRU 缓存，稀疏读命中同一块时
 * 零请求。相比逐条转发，请求数下降两个数量级；相比直接放大 WebDavRandomAccessSource 的
 * READ_CHUNK，好处是块大小与缓存策略可以按「PDF 打开」这一场景单独调，不牵连 zip 流式路径。
 *
 * 注意：pdfium 会试探性读到文件末尾之外（找 startxref 时读尾部固定块），
 * [onRead] 必须把请求量钳在文件长度内并返回实际读到的字节数，绝不能越界短读报错。
 * SY <--
 */
class RangeProxyCallback(
    private val source: RandomAccessSource,
) : ProxyFileDescriptorCallback() {

    private val lock = Any()
    private val blocks = ArrayDeque<Block>()

    private class Block(val start: Long, val bytes: ByteArray) {
        val end: Long get() = start + bytes.size
    }

    override fun onGetSize(): Long = source.size

    override fun onRead(offset: Long, size: Int, outData: ByteArray): Int {
        if (size <= 0) return 0
        val total = source.size
        if (offset < 0L || offset >= total) return 0
        val want = min(size.toLong(), total - offset).toInt()
        var done = 0
        while (done < want) {
            val block = blockAt(offset + done) ?: break
            val from = (offset + done - block.start).toInt()
            val n = min(block.bytes.size - from, want - done)
            if (n <= 0) break
            System.arraycopy(block.bytes, from, outData, done, n)
            done += n
        }
        return done
    }

    override fun onRelease() {
        // SY --> 只释放块缓存，**不**关 source：source 的生命周期交给创建方
        // （PdfiumRenderer 的 owned 列表）。否则代理 fd 打开失败走回退时，
        // pfd.close() 会先把 source 关掉，回退路径随即失效。
        synchronized(lock) { blocks.clear() }
        // SY <--
    }

    /** 命中即移到队尾（LRU）；未命中则按块对齐拉一块。返回 null 表示读失败（上层按 EOF 处理）。 */
    private fun blockAt(pos: Long): Block? = synchronized(lock) {
        val hit = blocks.firstOrNull { pos >= it.start && pos < it.end }
        if (hit != null) {
            blocks.remove(hit)
            blocks.addLast(hit)
            return@synchronized hit
        }
        val start = pos / PROXY_BLOCK_SIZE * PROXY_BLOCK_SIZE
        val bytes = readBlock(start, PROXY_BLOCK_SIZE) ?: return@synchronized null
        val block = Block(start, bytes)
        blocks.addLast(block)
        while (blocks.size > PROXY_MAX_BLOCKS) blocks.removeFirst()
        block
    }

    /** 读满一块：[RandomAccessSource.read] 允许短读，这里循环补齐；全空视为失败。 */
    private fun readBlock(start: Long, length: Int): ByteArray? {
        val out = ByteArray(length)
        var pos = 0
        while (pos < length) {
            val part = try {
                source.read(start + pos, length - pos)
            } catch (t: Throwable) {
                break
            }
            if (part.isEmpty()) break
            val n = min(part.size, length - pos)
            System.arraycopy(part, 0, out, pos, n)
            pos += n
        }
        if (pos == 0) return null
        return if (pos == length) out else out.copyOf(pos)
    }

    private companion object {
        /**
         * 4MB 块 × 6 块 = 单本 24MB 上限。封面扫描并发 4 本时峰值约 96MB —— 再大就有 OOM 风险
         * （Coil 网格同时在解码封面），再小则大 PDF 的 xref 随机读命中率下降。
         */
        const val PROXY_BLOCK_SIZE = 4 * 1024 * 1024
        const val PROXY_MAX_BLOCKS = 6
    }
}

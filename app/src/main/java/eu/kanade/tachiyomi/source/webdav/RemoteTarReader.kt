package eu.kanade.tachiyomi.source.webdav

import mihon.core.archive.ArchiveEntry
import mihon.core.archive.ArchiveHandle
import mihon.core.archive.RandomAccessSource
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/**
 * SY --> 远程 TAR 纯 Kotlin 随机读取器（扫描封面专用）。
 *
 * 与 [RemoteZipReader] 不同，tar 没有中央目录，索引只能顺序扫头块。封面只需要
 * 「第一张图片」，漫画 tar 的第一张图几乎总在最前面几个条目里，故做有限窗口扫描：
 * 最多读 [maxHeaders] 个头块（512B/个），被跳过的条目数据不产生流量。窗口内找不到
 * 可用文件条目就抛 [RemoteTarUnsupportedException]，调用方回落整本下载。
 *
 * 支持 ustar 头（含 prefix 拼接）、GNU 长名（'L' 块）、base-256 size；
 * 非 ustar 魔数（压缩 tar 壳）直接判定不支持。
 */
class RemoteTarReader(
    private val source: RandomAccessSource,
    private val maxHeaders: Int = 64,
) : ArchiveHandle {

    class RemoteTarUnsupportedException(message: String) : IOException(message)

    private class TarEntry(
        val name: String,
        val isFile: Boolean,
        val dataOffset: Long,
        val size: Long,
    )

    override val archiveHashCode: Int = source.hashCode()
    override val encrypted: Boolean get() = false
    override var wrongPassword: Boolean? = null
        private set

    private val entries: List<TarEntry>

    init {
        entries = scan()
        if (entries.none { it.isFile && it.size > 0 }) {
            throw RemoteTarUnsupportedException("前 $maxHeaders 个头块内无文件条目（回落整本下载）")
        }
    }

    // ---------------------------------------------------------------- 构造期扫描

    private fun scan(): List<TarEntry> {
        val out = ArrayList<TarEntry>()
        val total = source.size
        var offset = 0L
        var pendingLongName: String? = null
        var headers = 0
        while (offset + BLOCK <= total && headers < maxHeaders) {
            val h = readFully(offset, BLOCK)
            if (h.allZero()) break // 归档正常结束（两个零块）
            if (h.string(257, 262) != "ustar") {
                throw RemoteTarUnsupportedException("非 ustar 头（可能是压缩 tar）@ $offset")
            }
            val size = h.parseLong(124, 136)
                ?: throw RemoteTarUnsupportedException("size 字段损坏 @ $offset")
            val type = h[156].toInt().toChar()
            var name = h.string(0, 100)
            val prefix = h.string(345, 500)
            if (prefix.isNotEmpty()) name = "$prefix/$name"
            pendingLongName?.let {
                name = it
                pendingLongName = null
            }
            val dataOffset = offset + BLOCK
            val nextOffset = dataOffset + (size + BLOCK - 1) / BLOCK * BLOCK
            when (type) {
                // GNU 长名：紧随其后的数据块是下一条目的完整名字
                'L' -> {
                    if (size <= 0 || size > 4L * 1024 * 1024) {
                        throw RemoteTarUnsupportedException("GNU 长名尺寸异常：$size")
                    }
                    pendingLongName = String(readFully(dataOffset, size.toInt()), Charsets.UTF_8).trim()
                }
                '5' -> out.add(TarEntry(name, isFile = false, dataOffset, 0))
                '0', '7' -> out.add(TarEntry(name, isFile = true, dataOffset, size))
                // ustar 之前老式 tar 的常规文件不写 typeflag（全零）
                else -> if (type.code == 0) out.add(TarEntry(name, isFile = true, dataOffset, size))
            }

            offset = nextOffset
            headers++
        }
        if (out.isEmpty()) throw RemoteTarUnsupportedException("扫描窗口内没有任何条目")
        return out
    }

    // ---------------------------------------------------------------- ArchiveHandle

    override fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T =
        block(entries.asSequence().map { ArchiveEntry(it.name, it.isFile, isEncrypted = false) })

    override fun getInputStream(entryName: String): InputStream? {
        val e = entries.firstOrNull { it.name == entryName && it.isFile } ?: return null
        return TarEntryStream(e)
    }

    override fun close() {
        source.close()
    }

    /** 条目数据流：顺序推进 [pos]，每次向 source 要至多 64KB。 */
    private inner class TarEntryStream(private val entry: TarEntry) : InputStream() {
        private var pos = 0L
        private var eof = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof || pos >= entry.size) return -1
            val want = min(len.toLong(), entry.size - pos).toInt()
            val chunk = readFully(entry.dataOffset + pos, want)
            if (chunk.isEmpty()) return -1
            System.arraycopy(chunk, 0, b, off, chunk.size)
            pos += chunk.size
            if (pos >= entry.size) eof = true
            return chunk.size
        }

        override fun available(): Int = (entry.size - pos).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    // ---------------------------------------------------------------- 工具

    private fun readFully(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var pos = 0
        while (pos < length) {
            val chunk = source.read(offset + pos, length - pos)
            if (chunk.isEmpty()) throw IOException("tar 读取越界/EOF @$offset（请求 $length，已得 $pos）")
            System.arraycopy(chunk, 0, out, pos, chunk.size)
            pos += chunk.size
        }
        return out
    }

    private fun ByteArray.allZero(): Boolean {
        for (b in this) if (b != 0.toByte()) return false
        return true
    }

    /** 取 [from, to) 的 ASCII 字符串，遇 NUL / 空格截断（tar 字段以零或空白填充）。 */
    private fun ByteArray.string(from: Int, to: Int): String {
        var end = from
        while (end < to && this[end] != 0.toByte() && this[end] != ' '.toByte()) end++
        return if (end == from) "" else String(this, from, end - from, Charsets.ISO_8859_1)
    }

    /** size 字段：octal ASCII（base-256 扩展：首字节 0x80 时按大端二进制读）。 */
    private fun ByteArray.parseLong(from: Int, to: Int): Long? {
        if (this[from].toInt() and 0xFF == 0x80) {
            var v = 0L
            for (i in (from + 1) until to) v = (v shl 8) or (this[i].toLong() and 0xFF)
            return v
        }
        // 整段取出后再修剪：有的打包器把 size 写成「前导空格 + 数字」，尾部补 NUL/空格
        val raw = String(this, from, to - from, Charsets.ISO_8859_1)
        val text = raw.trim { ch -> ch.code == 0 || ch == ' ' }
        if (text.isEmpty()) return null
        return text.toLongOrNull(8)
    }

    private companion object {
        /** tar 头块 / 数据块对齐单位（字节）。 */
        const val BLOCK = 512
    }
}

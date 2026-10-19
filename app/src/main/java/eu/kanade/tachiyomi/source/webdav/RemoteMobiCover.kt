package eu.kanade.tachiyomi.source.webdav

import mihon.core.archive.RandomAccessSource
import java.io.File
import java.io.IOException

/**
 * SY --> MOBI / AZW（Palm DB 容器）封面流式抽取。
 *
 * 背景：Kmoe 等站点出的 `.mobi` 是「文字在 record 里、封面图作为**某个独立 record** 原样存放」的
 * PDB 文件。封面位置由 record0 里 EXTH 的 **201 coverOffset / 202 thumbOffset** 指出，
 * 所以可以像 zip 那样只做几段 Range 读就把封面取出来，**绝不整本下载**：
 *
 *  1. PDB 头 78B → 记录数；紧跟其后的记录表（每条 8B：offset u32 + attr u8 + uid u24）；
 *  2. record0（通常 <64KB）→ 里面含 PalmDOC 头 + MOBI 头 + EXTH，读出 coverOffset；
 *  3. 读 coverOffset 指向的那**一条** record（通常 50KB~2MB），魔数校验后直接落盘。
 *
 * 首选路径失败时（没有 EXTH 201、索引越界、魔数不符）回退按「记录体积降序」挑最多
 * [FALLBACK_CANDIDATES] 条大记录试魔数——最多再花几次小请求，仍然不下载整本。
 */
class RemoteMobiCover(private val source: RandomAccessSource) {

    /**
     * 抽取封面写入 [outFile]。
     * @return true 表示成功写出了封面图；false 表示该文件没有可用的封面 record（调用方应跳过）。
     */
    fun extractCover(outFile: File): Boolean {
        outFile.parentFile?.mkdirs()
        val size = source.size
        if (size < PDB_HEADER_SIZE) return false

        val header = runCatching { readFully(0, PDB_HEADER_SIZE) }.getOrNull() ?: return false
        val recordCount = header.u16(76)
        if (recordCount <= 1) return false

        // 记录表：recordCount + 1 条（最后一条是「文件尾」虚拟项，用于算最后一条 record 的长度）
        val tableLen = (recordCount + 1) * 8
        val avail = (size - PDB_HEADER_SIZE).coerceAtMost(tableLen.toLong()).toInt()
        if (avail < 16) return false
        val table = runCatching { readFully(PDB_HEADER_SIZE.toLong(), avail) }.getOrNull() ?: return false
        fun recStart(i: Int): Long =
            if (i * 8 + 4 <= table.size) table.u32(i * 8).toLong() and 0xFFFFFFFFL else -1L

        // ---- 路径 1：EXTH coverOffset ----
        val rec0Start = recStart(0)
        val rec0End = recStart(1)
        if (rec0Start in 0 until size && rec0End > rec0Start) {
            val rec0Len = (rec0End - rec0Start).coerceAtMost(MAX_REC0.toLong()).toInt()
            val rec0 = runCatching { readFully(rec0Start, rec0Len) }.getOrNull()
            if (rec0 != null) {
                val coverIndex = findCoverRecordIndex(rec0)
                if (coverIndex in 1 until recordCount) {
                    if (copyRecord(recStart(coverIndex), recStart(coverIndex + 1), size, outFile)) return true
                }
                val thumbIndex = findThumbRecordIndex(rec0)
                if (thumbIndex in 1 until recordCount) {
                    if (copyRecord(recStart(thumbIndex), recStart(thumbIndex + 1), size, outFile)) return true
                }
            }
        }

        // ---- 路径 2：按记录体积降序试魔数（封面通常是最大的几条之一）----
        val candidates = (1 until recordCount)
            .map { it to (recStart(it + 1) - recStart(it)) }
            .filter { it.second in MIN_IMAGE_RECORD.toLong()..MAX_IMAGE_RECORD.toLong() }
            .sortedByDescending { it.second }
            .take(FALLBACK_CANDIDATES)
        for ((i, _) in candidates) {
            if (copyRecord(recStart(i), recStart(i + 1), size, outFile)) return true
        }
        return false
    }

    /**
     * record0 中找到 EXTH 块，返回 type=201（coverOffset）的值；无则 -1。
     * EXTH：`'EXTH' + u32块长 + u32记录数 + N×(u32类型 + u32长度 + 数据)`，全部**大端**。
     */
    private fun findCoverRecordIndex(rec0: ByteArray): Int = findExthU32(rec0, EXTH_COVER_OFFSET)

    private fun findThumbRecordIndex(rec0: ByteArray): Int = findExthU32(rec0, EXTH_THUMB_OFFSET)

    private fun findExthU32(rec0: ByteArray, wantType: Int): Int {
        val i = indexOf(rec0, EXTH_MAGIC)
        if (i < 0 || i + 12 > rec0.size) return -1
        val count = rec0.u32(i + 8)
        if (count <= 0 || count > 4096) return -1
        var p = i + 12
        repeat(count) {
            if (p + 8 > rec0.size) return -1
            val type = rec0.u32(p)
            val len = rec0.u32(p + 4)
            if (len < 8 || p + len > rec0.size) return -1
            if (type == wantType) {
                val v = if (len >= 12) rec0.u32(p + 8) else return -1
                return if (v == -1) -1 else v // 0xFFFFFFFF 表示无封面
            }
            p += len
        }
        return -1
    }

    /** 读 [start]..[endExclusive] 那条 record，魔数校验通过才写入 [outFile]。 */
    private fun copyRecord(start: Long, endExclusive: Long, fileSize: Long, outFile: File): Boolean {
        if (start < 0 || endExclusive <= start || start >= fileSize) return false
        val len = (endExclusive - start).coerceAtMost(MAX_IMAGE_RECORD.toLong()).toInt()
        if (len < 16) return false
        val data = runCatching { readFully(start, len) }.getOrNull() ?: return false
        if (imageKind(data) == null) return false
        // 多余尾巴（有的写入器在图片后补 0）不裁剪：魔数已确认是完整图片文件
        return runCatching {
            outFile.outputStream().use { it.write(data) }
            outFile.length() > 0
        }.getOrDefault(false)
    }

    /** 只读前 4 字节判魔数（避免把整条 text record 拉下来）。 */
    private fun imageKind(data: ByteArray): String? = when {
        data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> "jpeg"
        data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() &&
            data[2] == 'N'.code.toByte() && data[3] == 'G'.code.toByte() -> "png"
        data.size >= 6 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() -> "gif"
        data.size >= 2 && data[0] == 'B'.code.toByte() && data[1] == 'M'.code.toByte() -> "bmp"
        data.size >= 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() -> "webp"
        else -> null
    }

    private fun indexOf(buf: ByteArray, magic: ByteArray): Int {
        outer@ for (i in 0..buf.size - magic.size) {
            for (j in magic.indices) {
                if (buf[i + j] != magic[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** 单次 Range 读满 [length] 字节（source 单次可能只返回一个块）。 */
    private fun readFully(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        var pos = 0
        while (pos < length) {
            val chunk = source.read(offset + pos, length - pos)
            if (chunk.isEmpty()) throw IOException("读取越界/EOF @${offset + pos}（请求 $length，已得 $pos）")
            val n = minOf(chunk.size, length - pos)
            System.arraycopy(chunk, 0, out, pos, n)
            pos += n
        }
        return out
    }

    private fun ByteArray.u16(off: Int): Int =
        (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(off: Int): Int =
        ((this[off].toInt() and 0xFF) shl 24) or
            ((this[off + 1].toInt() and 0xFF) shl 16) or
            ((this[off + 2].toInt() and 0xFF) shl 8) or
            (this[off + 3].toInt() and 0xFF)

    private companion object {
        const val PDB_HEADER_SIZE = 78
        const val MAX_REC0 = 1024 * 1024
        const val MIN_IMAGE_RECORD = 8 * 1024
        const val MAX_IMAGE_RECORD = 12 * 1024 * 1024
        const val FALLBACK_CANDIDATES = 4

        const val EXTH_COVER_OFFSET = 201
        const val EXTH_THUMB_OFFSET = 202

        val EXTH_MAGIC = byteArrayOf('E'.code.toByte(), 'X'.code.toByte(), 'T'.code.toByte(), 'H'.code.toByte())
    }
}

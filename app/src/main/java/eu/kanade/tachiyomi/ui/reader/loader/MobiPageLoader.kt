package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.Hash
import logcat.LogPriority
import mihon.core.archive.RandomAccessSource
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException

/**
 * SY --> MOBI / AZW（Palm DB 容器）漫画章节的页加载器。
 *
 * 背景：站点出的「漫画 MOBI」把每一页漫画存成一个独立的 PDB record（多为完整 JPEG，
 * 200~300KB/页）；开头若干小 record 是文本/元数据，结尾是 FLIS/FCIS 尾记录。因此把
 * [firstImageIndex, 尾部) 里那些**大 record**按序当作页，即可逐页随机读取而无需整本下载。
 *
 * 关键坑与对策（2026-09-26 实测「花薰凛然」系列的各 .mobi）：
 *  - **PDB 头里的 numRecords 字段不可信**（实测 208 条记录被写成 53248）。因此记录表
 *    采用「增量读取 + 单调递增且不越界才收下」的方式，遇到第一条非法项立即停止，
 *    得到的才是真实记录边界；绝不能按 numRecords 去推算页数。
 *  - 图片 record 起始位置由 record0 的 MOBI 头 **firstImageIndex（偏移 0x5C）** 指出；
 *    解析失败则退化为「从第 1 条起找第一条够大的 record」。
 *  - 页与页的区分**按 record 体积**即可（文本 record ~1~2KB，图片 record ≥ 数十 KB），
 *    无需为每条 record 发一次探测请求（否则 200 条 × 1MB 块会把整本拉空）。
 *
 * 读页策略：进入本章时只做「头部 + 记录表 + record0」的小段随机读拿到页布局；每一页的
 * 图片字节在**真正读该页时**才按 Range 单条拉取到本地缓存（已存在零流量复用），
 * `stream` 打开本地缓存文件——与 [WebDavImageFolderPageLoader] 同构，可复用整话超分。
 * SY <--
 */
internal class MobiPageLoader(
    private val context: Context,
    private val ras: RandomAccessSource,
    private val chapterUrl: String,
    // SY --> 2026-10-01：本地图源走本地随机读（成本低）、WebDAV 走 Range 流式（按网络处理）。
    // 该值供 ReaderViewModel「读到章末是否预载下一话」判断（isLocal=false 才会尝试预载）。
    override var isLocal: Boolean = false,
    // SY <--
) : PageLoader() {

    private val cacheDir: File = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }

    /** 一条 record 的字节区间 [start, end)。 */
    private data class Rec(val start: Long, val end: Long)

    private var records: List<Rec> = emptyList()

    override suspend fun getPages(): List<ReaderPage> {
        records = parseImageRecords()
        logcat(LogPriority.DEBUG) { "MobiPageLoader: ${records.size} 页 @ $chapterUrl" }
        return records.mapIndexed { i, _ ->
            ReaderPage(i).apply {
                // 懒下载：真正读这一页时才把该 record 拉到本地缓存。
                stream = { pageFile(i).inputStream() }
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        // 缓存文件留在 context.cacheDir/webdav-mobi，由缓存上限/系统清理回收；这里只关读源。
        runCatching { ras.close() }
    }

    // ---- 内部实现 ----

    /** 把第 [i] 页对应的 record 拉到本地缓存并返回（已缓存零流量复用）。 */
    private fun pageFile(i: Int): File {
        val rec = records[i]
        val key = cacheKey(rec)
        val f = File(cacheDir, key)
        if (f.exists() && f.length() > 0L) return f
        val len = (rec.end - rec.start).toInt()
        val data = readFully(rec.start, len)
        val tmp = File(cacheDir, "$key.part")
        tmp.outputStream().use { it.write(data) }
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        return f
    }

    private fun parseImageRecords(): List<Rec> {
        val size = ras.size
        require(size >= PDB_HEADER_SIZE) { "文件过小，不是 MOBI/PDB：$chapterUrl" }
        val header = readFully(0, PDB_HEADER_SIZE)
        // numRecords 字段仅作参考（实测可能被写成 53248 而真实只有 208），真实边界由增量校验得出。
        val declaredRecords = header.u16(76)
        val offsets = readRecordOffsets(size)
        require(offsets.size >= 2) { "PDB 记录表损坏：$chapterUrl" }
        val validCount = offsets.size

        val firstImage = firstImageIndex(offsets, size).coerceIn(1, validCount - 1)
        val out = ArrayList<Rec>()
        var i = firstImage
        while (i + 1 < validCount && out.size < MAX_PAGES) {
            val s = offsets[i]
            val e = offsets[i + 1]
            if (s < 0 || e <= s || e > size) break
            val len = e - s
            // 大 record 直接认定为一页（图片页恒为数十 KB~数百 KB，免去逐条探测；
            // 若逐条探测，152 条 record 会把整本（~40MB）在开章时就拉下来）。
            // 小 record 才探测魔数：是图片（如文件末尾的小 GIF 页）则收下，否则视为图片序列结束。
            val isPage = if (len >= MIN_PAGE_BYTES) {
                true
            } else {
                val probe = runCatching { readFully(s, minOf(16L, len).toInt()) }.getOrNull()
                probe != null && imageKind(probe) != null
            }
            if (!isPage) {
                // 起始若落在小 record（文本/元数据）上则跳过；一旦已进入图片序列则视为结束。
                if (out.isEmpty()) { i++; continue } else break
            }
            out.add(Rec(s, e))
            i++
        }
        require(out.isNotEmpty()) { "MOBI 内未找到图片记录（可能不是漫画类 MOBI）：$chapterUrl" }
        logcat(LogPriority.DEBUG) {
            "MobiPageLoader: declaredRecords=$declaredRecords 有效记录=$validCount firstImage=$firstImage 页=${out.size}"
        }
        return out
    }

    /** record0 里 MOBI 头的 firstImageIndex（偏移 0x5C）；解析失败退化为 1。 */
    private fun firstImageIndex(offsets: LongArray, size: Long): Int {
        val r0s = offsets[0]
        val r0e = if (offsets.size > 1) offsets[1] else size
        val r0len = (r0e - r0s).coerceAtMost(MAX_REC0.toLong()).toInt()
        if (r0len <= 0) return 1
        val r0 = runCatching { readFully(r0s, r0len) }.getOrNull() ?: return 1
        val pos = indexOf(r0, MOBI_MAGIC)
        if (pos < 0 || pos + MOBI_FIRST_IMAGE_OFFSET + 4 > r0.size) return 1
        val v = r0.u32(pos + MOBI_FIRST_IMAGE_OFFSET)
        return if (v in 1 until offsets.size) v else 1
    }

    /**
     * 增量读取 PDB 记录表（8 字节/条：offset u32(大端) + attr u8 + uid u24）。
     * 一旦出现「offset 非严格递增」或「offset 越界」即判定表尾结束。
     */
    private fun readRecordOffsets(size: Long): LongArray {
        val out = ArrayList<Long>(512)
        var prev = -1L
        var entryIndex = 0
        while (out.size < MAX_OFFSET_ENTRIES) {
            val start = PDB_HEADER_SIZE + entryIndex.toLong() * 8
            if (start >= size) break
            val want = minOf(TABLE_CHUNK_ENTRIES.toLong() * 8, size - start).toInt()
            if (want < 8) break
            val buf = runCatching { readFully(start, want) }.getOrNull() ?: break
            var broke = false
            var j = 0
            while (j + 8 <= buf.size) {
                val off = buf.u32(j).toLong() and 0xFFFFFFFFL
                if (off <= prev || off > size) { broke = true; break }
                out.add(off)
                prev = off
                j += 8
            }
            if (broke) break
            entryIndex += j / 8
            if (want < TABLE_CHUNK_ENTRIES * 8) break
        }
        return out.toLongArray()
    }

    private fun cacheKey(rec: Rec): String =
        Hash.sha256("$chapterUrl#${rec.start}#${rec.end}")

    /** 单次 Range 读满 [length] 字节（[RandomAccessSource.read] 可返回更少，循环补齐）。 */
    private fun readFully(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        var pos = 0
        while (pos < length) {
            val chunk = ras.read(offset + pos, length - pos)
            if (chunk.isEmpty()) throw IOException("读取越界/EOF @${offset + pos}（请求 $length，已得 $pos）")
            val n = minOf(chunk.size, length - pos)
            System.arraycopy(chunk, 0, out, pos, n)
            pos += n
        }
        return out
    }

    /** 仅判前几字节魔数（用于区分「小 record 是图片页」还是「图片序列结束」）。 */
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

    private fun ByteArray.u16(off: Int): Int =
        (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(off: Int): Int =
        ((this[off].toInt() and 0xFF) shl 24) or
            ((this[off + 1].toInt() and 0xFF) shl 16) or
            ((this[off + 2].toInt() and 0xFF) shl 8) or
            (this[off + 3].toInt() and 0xFF)

    private fun indexOf(buf: ByteArray, magic: ByteArray): Int {
        outer@ for (i in 0..buf.size - magic.size) {
            for (j in magic.indices) {
                if (buf[i + j] != magic[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private companion object {
        const val CACHE_SUBDIR = "webdav-mobi"
        const val PDB_HEADER_SIZE = 78
        const val MAX_REC0 = 1024 * 1024
        const val TABLE_CHUNK_ENTRIES = 4096
        const val MAX_OFFSET_ENTRIES = 500_000
        const val MAX_PAGES = 5000

        /**
         * 视作「图片页」的最小 record 体积：达到此值不再探测魔数、直接当页（图片页恒为数十 KB
         * 以上）；小于此值的 record 才做一次魔数探测，用于识别文件末尾可能的小图页
         * （实测末页有 47KB 的 GIF）并判定图片序列的结束（RESC/FLIS/FCIS/CMET 等尾记录均远小于此值）。
         */
        const val MIN_PAGE_BYTES = 8 * 1024

        /** MOBI 头里 firstImageIndex 相对 "MOBI" 魔数的偏移。 */
        const val MOBI_FIRST_IMAGE_OFFSET = 0x5C

        val MOBI_MAGIC = byteArrayOf(
            'M'.code.toByte(), 'O'.code.toByte(), 'B'.code.toByte(), 'I'.code.toByte(),
        )
    }
}

package eu.kanade.tachiyomi.source.webdav

import mihon.core.archive.ArchiveEntry
import mihon.core.archive.ArchiveHandle
import mihon.core.archive.RandomAccessSource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.min

/**
 * 远程 ZIP 纯 Kotlin 随机读取器（Phase A：去除了上游 Komiho 的加密支持）。
 *
 * 背景：本仓库未接入加密 CBZ（无 CbzCrypto），因此这里只处理**未加密**的
 * zip / cbz。遇到加密条目（flag bit 0 置位）直接抛 [RemoteZipUnsupportedException]，
 * 由调用方回落到整本下载路径，行为与未支持该文件时一致（不会尝试解密）。
 *
 * 原理：打开时只拉 **尾部 64KB**（解析 EOCD / ZIP64）+ **中央目录**（一次 Range），
 * 建立 名字→(localOffset, compSize, method) 索引；之后每页直接 Range 拉
 * local header + 该页压缩数据，按需解压。**每页流量 = 条目数据本身 + 首次打开的
 * ~几十 KB**，跳页 O(1)，不整本下载。
 *
 * - 线程安全：索引建好后只读不可变；每页独立的 InputStream；source 自身线程安全。
 * - 不支持的情况（非 ZIP、bzip2/zstd 等压缩方法、zip64 越界、加密条目）在**构造期**
 *   抛 [RemoteZipUnsupportedException]，调用方回落整本下载路径，行为不回退。
 * - 实现了与 [mihon.core.archive.ArchiveReader] 相同的 [ArchiveHandle] 窄接口，ArchivePageLoader 零改动复用。
 */
class RemoteZipReader(private val source: RandomAccessSource) : ArchiveHandle {

    /** 构造期即判定「该文件不适合本路径」，调用方应回落整本下载。 */
    class RemoteZipUnsupportedException(message: String) : IOException(message)

    private class CdEntry(
        val name: String,
        val isFile: Boolean,
        val method: Int,
        val compSize: Long,
        val localOffset: Long,
    )

    override val archiveHashCode: Int = source.hashCode()

    // 本仓库不处理加密，恒为 false / null
    override val encrypted: Boolean get() = false
    override var wrongPassword: Boolean? = null
        private set

    private val entries: Map<String, CdEntry>

    init {
        entries = parseCentralDirectory()
    }

    // ---------------------------------------------------------------- 构造期解析

    /** @return 条目索引（名字→CdEntry）。遇到加密条目或不支持的压缩方法直接抛异常。 */
    private fun parseCentralDirectory(): Map<String, CdEntry> {
        val fileSize = source.size

        // 1. 拉尾部 64KB，找 EOCD（PK\005\006，从后往前扫以容忍注释）
        val tailLen = minOf(TAIL_SIZE.toLong(), fileSize).toInt()
        val tail = readFully(fileSize - tailLen, tailLen)
        val eocdIdx = findSignature(tail, EOCD_SIG, fromEnd = true)
            ?: throw RemoteZipUnsupportedException("未找到 EOCD（不是 ZIP？）")

        var totalEntries = tail.u16(eocdIdx + 10).toLong()
        var cdSize = tail.u32(eocdIdx + 12).toLong() and 0xFFFFFFFFL
        var cdOffset = tail.u32(eocdIdx + 16).toLong() and 0xFFFFFFFFL

        // 2. ZIP64：任一字段饱和 → 经 EOCD64 locator 定位 EOCD64
        if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL || totalEntries == 0xFFFFL) {
            val tailStart = fileSize - tailLen
            val locAbs = tailStart + eocdIdx - 20
            if (locAbs < 0) throw RemoteZipUnsupportedException("ZIP64 字段饱和但 EOCD64 locator 越界")
            val locator = if (eocdIdx >= 20) tail.copyOfRange(eocdIdx - 20, eocdIdx) else readFully(locAbs, 20)
            if (locator.u32(0).toLong() != ZIP64_LOC_SIG.toLong()) {
                throw RemoteZipUnsupportedException("ZIP64 字段饱和但找不到 EOCD64 locator")
            }
            val eocd64Offset = locator.u64(8)
            val eocd64 = readFully(eocd64Offset, 56)
            if (eocd64.u32(0).toLong() != ZIP64_EOCD_SIG.toLong()) {
                throw RemoteZipUnsupportedException("EOCD64 签名不符")
            }
            totalEntries = eocd64.u64(32)
            cdSize = eocd64.u64(40)
            cdOffset = eocd64.u64(48)
        }

        // 3. 拉中央目录并解析
        if (cdSize > Int.MAX_VALUE) throw RemoteZipUnsupportedException("中央目录超大（超过 2GB）")
        val cd = readFully(cdOffset, cdSize.toInt())

        val map = HashMap<String, CdEntry>(totalEntries.coerceAtMost(1_000_000).toInt())
        var p = 0
        var count = 0
        while (p + 46 <= cd.size && count < totalEntries) {
            if (cd.u32(p).toLong() != CDH_SIG.toLong()) break // 防御：目录损坏即停
            val flags = cd.u16(p + 8)
            val method = cd.u16(p + 10)
            var compSize = cd.u32(p + 20).toLong() and 0xFFFFFFFFL
            var uncompSize = cd.u32(p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = cd.u16(p + 28)
            val extraLen = cd.u16(p + 30)
            val commentLen = cd.u16(p + 32)
            var localOffset = cd.u32(p + 42).toLong() and 0xFFFFFFFFL
            val name = cd.decodeToString(p + 46, p + 46 + nameLen)

            // ZIP64 扩展字段（0x0001）：仅在对应基础字段饱和时出现，逐字段消费以正确定位。
            if (uncompSize == 0xFFFFFFFFL || compSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                var q = p + 46 + nameLen
                val extraEnd = q + extraLen
                while (q + 4 <= extraEnd) {
                    val id = cd.u16(q)
                    val sz = cd.u16(q + 2)
                    if (id == 0x0001) {
                        var r = q + 4
                        val dataEnd = q + 4 + sz
                        if (uncompSize == 0xFFFFFFFFL && r + 8 <= dataEnd) {
                            uncompSize = cd.u64(r); r += 8
                        }
                        if (compSize == 0xFFFFFFFFL && r + 8 <= dataEnd) {
                            compSize = cd.u64(r); r += 8
                        }
                        if (localOffset == 0xFFFFFFFFL && r + 8 <= dataEnd) {
                            localOffset = cd.u64(r); r += 8
                        }
                        break
                    }
                    q += 4 + sz
                }
            }

            // 加密条目（flag bit 0）：本仓库不支持解密，直接回落整本下载。
            if (flags and 0x0001 != 0) {
                throw RemoteZipUnsupportedException("加密条目不支持（回落整本下载）：$name")
            }
            // 本仓库仅支持 STORED / DEFLATED
            if (method != METHOD_STORED && method != METHOD_DEFLATED) {
                throw RemoteZipUnsupportedException("不支持的压缩方法 method=$method（$name）")
            }
            if (compSize > Int.MAX_VALUE) throw RemoteZipUnsupportedException("单条目超 2GB（$name）")

            if (!name.endsWith("/") && name.isNotEmpty()) {
                map[name] = CdEntry(
                    name = name,
                    isFile = true,
                    method = method,
                    compSize = compSize,
                    localOffset = localOffset,
                )
            }
            p += 46 + nameLen + extraLen + commentLen
            count++
        }

        if (map.isEmpty()) throw RemoteZipUnsupportedException("中央目录无有效条目")
        return map
    }

    // ---------------------------------------------------------------- ArchiveHandle

    override fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T =
        block(entries.values.asSequence().map { ArchiveEntry(it.name, it.isFile, isEncrypted = false) })

    override fun getInputStream(entryName: String): InputStream? {
        val e = entries[entryName] ?: return null

        val dataStart = locateData(e)
        val raw = readFully(dataStart, e.compSize.toInt())
        return when (e.method) {
            METHOD_STORED -> ByteArrayInputStream(raw)
            METHOD_DEFLATED -> InflaterInputStream(ByteArrayInputStream(raw), Inflater(true), 64 * 1024)
            else -> throw IOException("method=${e.method}") // 构造期已过滤，防御
        }
    }

    override fun close() {
        source.close()
    }

    // ---------------------------------------------------------------- 工具

    /** 解析 local header 得数据区起点（30 字节定长 + name + extra，长度以 local header 为准）。 */
    private fun locateData(e: CdEntry): Long {
        val lh = readFully(e.localOffset, 30)
        if (lh.u32(0).toLong() != LFH_SIG.toLong()) {
            throw IOException("local header 签名不符 @${e.localOffset}（${e.name}）")
        }
        val nameLen = lh.u16(26)
        val extraLen = lh.u16(28)
        return e.localOffset + 30 + nameLen + extraLen
    }

    /** 从 source 循环读满 [length] 字节（source 单次可能只返回一个块）。 */
    private fun readFully(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var pos = 0
        while (pos < length) {
            val chunk = source.read(offset + pos, length - pos)
            if (chunk.isEmpty()) throw IOException("读取越界/EOF @${offset + pos}（请求 $length，已得 $pos）")
            System.arraycopy(chunk, 0, out, pos, chunk.size)
            pos += chunk.size
        }
        return out
    }

    /** 在 buf 中找 4 字节签名；fromEnd=true 时从后往前找第一个（EOCD 允许 zip 注释跟在后面）。 */
    private fun findSignature(buf: ByteArray, sig: Int, fromEnd: Boolean): Int? {
        if (fromEnd) {
            for (i in buf.size - 22 downTo 0) {
                if (buf.u32(i).toLong() == sig.toLong()) return i
            }
        } else {
            for (i in 0..buf.size - 4) {
                if (buf.u32(i).toLong() == sig.toLong()) return i
            }
        }
        return null
    }

    private fun ByteArray.u16(off: Int): Int =
        (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(off: Int): Int =
        u16(off) or (u16(off + 2) shl 16)

    private fun ByteArray.u64(off: Int): Long =
        (u32(off).toLong() and 0xFFFFFFFFL) or ((u32(off + 4).toLong() and 0xFFFFFFFFL) shl 32)

    private companion object {
        const val TAIL_SIZE = 64 * 1024
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8

        const val EOCD_SIG = 0x06054b50        // PK\005\006
        const val ZIP64_LOC_SIG = 0x07064b50   // PK\006\007
        const val ZIP64_EOCD_SIG = 0x06064b50  // PK\006\006
        const val CDH_SIG = 0x02014b50         // PK\001\002（central directory header）
        const val LFH_SIG = 0x04034b50         // PK\003\004（local file header）
    }
}

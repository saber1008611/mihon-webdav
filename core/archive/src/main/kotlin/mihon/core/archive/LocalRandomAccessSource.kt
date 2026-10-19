package mihon.core.archive

import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

/**
 * 基于本地真实文件的随机读取实现。
 *
 * 用途（Phase A）：WebDAV 整本回退路径——当服务器不支持 HTTP Range，或章节是
 * rar/7z 等无法随机访问的格式时，先把整本下载到本地缓存，再用本类按本地随机读，
 * 复用同一个 [ArchiveReader]，无需改动阅读器。
 *
 * 实现用 [java.nio.channels.FileChannel] 的**定位读** `read(buf, position)`：每次读都显式带
 * 文件偏移、不依赖通道的共享游标，因此多个页面并发预取（大跳页时）各自读各自的位置互不干扰，
 * 避免 `RandomAccessFile.seek()+read()` 在多线程下游标被踩导致的偶发读数错乱。
 *
 * SY --> 新增 [FileDescriptor] 次级构造：本地图源指向 CloudDrive2 这类 FUSE 挂载盘时，
 * 通过 `contentResolver.openFileDescriptor(uri, "r")` 拿到的 fd 直接打开，无需把 UniFile 还原成
 * `java.io.File`（SAF content:// 路径无法还原 File）。上层（ChapterLoader）据此把 FUSE 文件
 * 套上分块缓存层，复用 WebDAV 的「按需随机读」链路，避免整本拉取。
 */
class LocalRandomAccessSource private constructor(private val channel: FileChannel) : RandomAccessSource {

    constructor(file: File) : this(RandomAccessFile(file, "r").channel)

    // FUSE / SAF 文件没有可还原的 java.io.File 路径，只能经 contentResolver 拿 fd 打开。
    // FileInputStream(fd) 包装出的 FileChannel 是只读的，但定位读 read(buf, position) 完全支持，
    // 与 RandomAccessFile 的 channel 在「按需随机读」语义上一致。
    constructor(fd: FileDescriptor) : this(FileInputStream(fd).channel)

    override val size: Long
        get() = channel.size()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0) return EMPTY
        val available = (channel.size() - offset).coerceAtLeast(0L)
        val n = min(length.toLong(), available).toInt()
        if (n == 0) return EMPTY
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            // 定位读：从文件 offset+read 处读入 buf[read..n)，不触碰通道游标，线程安全
            val r = channel.read(ByteBuffer.wrap(buf, read, n - read), offset + read)
            if (r <= 0) break
            read += r
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    override fun close() = channel.close()

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

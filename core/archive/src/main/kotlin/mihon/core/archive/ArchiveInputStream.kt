package mihon.core.archive

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry as LibArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile

/**
 * libarchive 归档输入流。
 *
 * 两种模式（私有主构造 + 两个公有构造器）：
 * - mode 0 = mmap 内存块（本地 SAF / content uri），零成本全量映射；
 * - mode 1 = [RandomAccessSource] 回调（Local / WebDAV / SMB 真随机访问），不整本 mmap，
 *   通过 libarchive 的 Read/Seek/Skip 回调按需向底层源取数，实现「远程随机读 ZIP 内部条目」。
 *
 * 说明：本仓库未接入加密 CBZ（无 CbzCrypto），故去掉了上游 Komiho 的加密分支。
 */
internal class ArchiveInputStream private constructor(
    private val mode: Int,
    private val buffer: Long,
    private val mmapSize: Long,
    private val source: RandomAccessSource?,
) : InputStream() {

    private val lock = Any()

    @Volatile
    private var isClosed = false

    private val archive = Archive.readNew()

    private val oneByteBuffer = ByteBuffer.allocateDirect(1)

    init {
        try {
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            if (mode == 0) {
                Archive.readOpenMemoryUnsafe(archive, buffer, mmapSize)
            } else {
                val state = CallbackState(source!!)
                // readSetSeekCallback 必须在 readOpen2 之前调用（archive 仍处 state 'new'）
                Archive.readSetSeekCallback(archive, SeekCallback(state))
                Archive.readOpen2(archive, state, OPEN_CALLBACK, ReadCallback(state), SKIP_CALLBACK, CLOSE_CALLBACK)
            }
        } catch (e: ArchiveException) {
            close()
            throw e
        }
    }

    /** 内存映射（本地 mmap）构造器。 */
    constructor(buffer: Long, size: Long) : this(0, buffer, size, null)

    /** 回调式（RandomAccessSource）构造器：Local / WebDAV 共用，真正随机访问，不整本 mmap。 */
    constructor(source: RandomAccessSource) : this(1, 0, 0, source)

    private fun read(buffer: ByteBuffer) {
        buffer.clear()
        Archive.readData(archive, buffer)
        buffer.flip()
    }

    override fun read(): Int {
        read(oneByteBuffer)
        return if (oneByteBuffer.hasRemaining()) oneByteBuffer.get().toUByte().toInt() else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val buffer = ByteBuffer.wrap(b, off, len)
        read(buffer)
        return if (buffer.hasRemaining()) buffer.remaining() else -1
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
        }

        Archive.readFree(archive)
    }

    fun getNextEntry(): ArchiveEntry? {
        return Archive.readNextHeader(archive).takeUnless { it == 0L }?.let { entry ->
            val name = LibArchiveEntry.pathnameUtf8(entry) ?: LibArchiveEntry.pathname(entry)?.decodeToString() ?: return null
            val isFile = LibArchiveEntry.filetype(entry) == LibArchiveEntry.AE_IFREG
            // 本仓库不处理加密条目（无 CbzCrypto），一律视为未加密。
            ArchiveEntry(name, isFile, isEncrypted = false)
        }
    }

    /** 回调式读取的共享状态：libarchive 的 Read/Seek/Skip 回调都操作同一个 [position]。 */
    private class CallbackState(val source: RandomAccessSource) {
        val buffer = ByteBuffer.allocate(READ_CHUNK)
        val scratch = ByteArray(READ_CHUNK)
        var position = 0L
    }

    private class ReadCallback(private val state: CallbackState) : Archive.ReadCallback<CallbackState> {
        override fun onRead(archive: Long, data: CallbackState?): ByteBuffer {
            val st = data ?: error("null client data")
            st.buffer.clear()
            val bytes = st.source.read(st.position, READ_CHUNK)
            if (bytes.isNotEmpty()) {
                System.arraycopy(bytes, 0, st.scratch, 0, bytes.size)
                st.buffer.put(st.scratch, 0, bytes.size)
            }
            st.buffer.flip()
            st.position += bytes.size
            return st.buffer
        }
    }

    private class SeekCallback(private val state: CallbackState) : Archive.SeekCallback<CallbackState> {
        override fun onSeek(archive: Long, data: CallbackState?, offset: Long, whence: Int): Long {
            val st = data ?: error("null client data")
            st.position = when (whence) {
                SEEK_SET -> offset
                SEEK_CUR -> st.position + offset
                SEEK_END -> st.source.size + offset
                // libarchive 只会传 0/1/2，非法值防御性报错
                else -> error("Invalid whence: $whence")
            }
            return st.position
        }
    }

    private companion object {
        // 256KB 预读块：WebDAV 阶段复用同一缓冲即可把大量小读取合并为较少的大 Range 请求
        const val READ_CHUNK = 256 * 1024
        const val SEEK_SET = 0
        const val SEEK_CUR = 1
        const val SEEK_END = 2

        val OPEN_CALLBACK = object : Archive.OpenCallback<CallbackState> {
            override fun onOpen(archive: Long, data: CallbackState?) {}
        }
        val CLOSE_CALLBACK = object : Archive.CloseCallback<CallbackState> {
            override fun onClose(archive: Long, data: CallbackState?) {}
        }
        val SKIP_CALLBACK = object : Archive.SkipCallback<CallbackState> {
            override fun onSkip(archive: Long, data: CallbackState?, request: Long): Long {
                val st = data ?: error("null client data")
                val remaining = (st.source.size - st.position).coerceAtLeast(0L)
                val skip = minOf(request, remaining)
                st.position += skip
                return skip
            }
        }
    }
}

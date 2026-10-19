package mihon.core.archive

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants.PROT_READ
import android.system.OsConstants.MAP_PRIVATE
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.InputStream

/**
 * 归档阅读器，实现 [ArchiveHandle] 窄接口。
 *
 * 两种构造器：
 * - [ArchiveReader]（[ParcelFileDescriptor]）：本地 SAF / content uri 走 mmap 内存映射（保持原有行为）；
 * - [ArchiveReader]（[RandomAccessSource]）：Local / WebDAV 走回调式随机访问，不整本 mmap。
 *
 * [ArchivePageLoader] 只依赖 [ArchiveHandle]，两条路径共用同一套页面构建 / 解码 / 整话超分逻辑。
 *
 * 说明：本仓库未接入加密 CBZ（无 CbzCrypto），[encrypted] 恒为 false、[wrongPassword] 恒为 null。
 */
class ArchiveReader : ArchiveHandle {

    val size: Long
    private val address: Long?
    private val source: RandomAccessSource?

    /** 本地 mmap 构造器（SAF / content uri 走这条，保持原有行为）。 */
    constructor(pfd: ParcelFileDescriptor) {
        size = pfd.statSize
        address = Os.mmap(0, size, PROT_READ, MAP_PRIVATE, pfd.fileDescriptor, 0)
        source = null
    }

    /** 回调式构造器（Local / WebDAV 走这条，真正随机访问，不整本 mmap）。 */
    constructor(source: RandomAccessSource) {
        this.source = source
        size = source.size
        address = null
    }

    override var encrypted: Boolean = false
        private set

    override var wrongPassword: Boolean? = null
        private set

    override val archiveHashCode: Int
        get() = address?.hashCode() ?: source!!.hashCode()

    private fun newStream(): ArchiveInputStream =
        if (address != null) ArchiveInputStream(address, size) else ArchiveInputStream(source!!)

    override fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T =
        newStream().use { block(generateSequence { it.getNextEntry() }) }

    override fun getInputStream(entryName: String): InputStream? {
        val archive = newStream()
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name == entryName) {
                    return archive
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
        return null
    }

    override fun close() {
        if (address != null) Os.munmap(address, size)
        source?.close()
    }
}

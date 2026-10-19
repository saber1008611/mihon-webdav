package mihon.core.archive

import java.io.Closeable
import java.io.InputStream

/**
 * 归档句柄窄接口：从 ArchivePageLoader 对归档阅读器的实际依赖面提取。
 *
 * [ArchiveReader]（libarchive 本地/回调路径）与 [RemoteZipReader]（远程 ZIP 中央目录
 * 直读路径）同实现此接口，ArchivePageLoader 只依赖接口，两条路径共用同一套
 * 页面构建 / 解码 / 整话超分逻辑。
 *
 * 说明：本仓库未接入加密 CBZ（无 CbzCrypto），因此 [encrypted] 恒为 false、
 * [wrongPassword] 恒为 null；保留字段以便将来扩展且不破坏调用面。
 */
interface ArchiveHandle : Closeable {
    /** 每实例唯一（ArchivePageLoader 建临时目录用）。 */
    val archiveHashCode: Int

    /** 是否存在加密条目（本仓库恒 false）。 */
    val encrypted: Boolean

    /** null=未校验过密码；true=密码错误；false=密码正确。本仓库恒 null。 */
    val wrongPassword: Boolean?

    fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T

    /** 返回条目内容的解压流；条目不存在返回 null。流独立，可并发开多条。 */
    fun getInputStream(entryName: String): InputStream?
}

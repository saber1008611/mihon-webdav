package mihon.core.archive

class ArchiveEntry(
    val name: String,
    val isFile: Boolean,
    /** 条目是否加密。本仓库未接入加密 CBZ（无 CbzCrypto），恒为 false。 */
    val isEncrypted: Boolean = false,
)

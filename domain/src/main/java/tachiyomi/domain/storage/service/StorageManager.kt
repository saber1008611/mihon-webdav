package tachiyomi.domain.storage.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import java.io.File

@Inject
@SingleIn(AppScope::class)
class StorageManager(
    private val context: Context,
    private val storagePreferences: StoragePreferences,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private var baseDir: UniFile? = getBaseDir(storagePreferences.baseStorageDirectory.get())

    // SY --> 2026-09-29：本地目录解析结果的缓存版本号。
    //
    // 背景（真机实测）：`getLocalSourceDirectory()` / `getDownloadsDirectory()` 都是
    // `baseDir?.createDirectory(…)`。当存储位置被设在 **CloudDrive 这类只读 FUSE 挂载**上时，
    // 这个 mkdir **必然失败**，而每次调用都要先走一趟「远程往返才被拒绝」：
    //   · `isChapterDownloadedOnDisk` 单次实测 **303ms**（它内部就要 downloads 目录）；
    //   · `LocalConfig`（本地图源的根目录解析）**每次构造**都会再调一次 `getLocalSourceDirectory()`，
    //     而它原本每次调用都重建 —— 于是「打开一本本地压缩包」里塞进多次这种无谓的失败往返。
    // 做法：按版本号缓存这几个「解析代价高 / 会失败」的结果（mkdir 幂等，可安全复用），
    // baseDir 或额外根目录变化时自增版本号使其失效。
    private val localDirsVersion = java.util.concurrent.atomic.AtomicInteger(0)

    /** 供上层（如 LocalWebDavSource 缓存已解析的根目录列表）做失效判断，读取代价接近零。 */
    val localSourceDirsVersion: Int get() = localDirsVersion.get()

    private val dirCacheLock = Any()
    private var cachedLocalDir: Pair<Int, UniFile?>? = null
    private var cachedDownloadsDir: Pair<Int, UniFile?>? = null
    // SY <--

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        storagePreferences.baseStorageDirectory.changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                baseDir = getBaseDir(uri)
                baseDir?.let { parent ->
                    parent.createDirectory(AUTOMATIC_BACKUPS_PATH)
                    parent.createDirectory(LOCAL_SOURCE_PATH)
                    parent.createDirectory(DOWNLOADS_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                }
                _changes.send(Unit)
                localDirsVersion.incrementAndGet() // SY：baseDir 变了 ⇒ 缓存的目录解析结果失效
            }
            .launchIn(scope)

        // SY --> 本地图源额外根目录变化时同样广播，驱动浏览页刷新
        storagePreferences.localSourceExtraDirectories.changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach {
                _changes.send(Unit)
                localDirsVersion.incrementAndGet() // SY：根目录集合变了 ⇒ 缓存失效
            }
            .launchIn(scope)
        // SY <--
    }

    private fun getBaseDir(uri: String): UniFile? {
        return UniFile.fromUri(context, uri.toUri())
            .takeIf { it?.exists() == true }
    }

    fun getAutomaticBackupsDirectory(): UniFile? {
        return baseDir?.createDirectory(AUTOMATIC_BACKUPS_PATH)
    }

    fun getDownloadsDirectory(): UniFile? {
        // SY 2026-09-29：见 localDirsVersion 注释 —— 避免每次都做一次注定失败的远程 mkdir。
        synchronized(dirCacheLock) {
            val v = localDirsVersion.get()
            cachedDownloadsDir?.let { if (it.first == v) return it.second }
            val dir = baseDir?.createDirectory(DOWNLOADS_PATH)
            cachedDownloadsDir = v to dir
            return dir
        }
    }

    fun getLocalSourceDirectory(): UniFile? {
        // SY 2026-09-29：同上。
        synchronized(dirCacheLock) {
            val v = localDirsVersion.get()
            cachedLocalDir?.let { if (it.first == v) return it.second }
            val dir = baseDir?.createDirectory(LOCAL_SOURCE_PATH)
            cachedLocalDir = v to dir
            return dir
        }
    }

    // SY --> 本地图源多根目录支持：默认 local 文件夹 + 用户通过 SAF 添加的额外目录

    /**
     * 所有本地图源根目录（默认 local 在前，额外目录按添加顺序）。
     * SY --> 修复：默认 base 目录（/sdcard/<app_name>）缺失时不再直接返回空列表，
     * 否则会把用户已配置的额外根目录（如 CloudDrive FUSE 挂载）整个丢弃，
     * 本地图源一个漫画都列不出来（表现为「打不开任何漫画 / 取不到封面」）。
     * 默认目录不存在时只回退到额外目录即可。
     */
    /**
     * SY 2026-10-01：SAF 目录 URI 归一化。
     *
     * OpenDocumentTree 返回的是「裸 tree URI」（content://…/tree/<docId>），而 com.hippo.unifile 的
     * [UniFile.fromUri] 只认「tree + document」形式（content://…/tree/<docId>/document/<docId>）：
     * 裸 tree URI 过不了它内部的 isDocumentUri() 判定，会在函数末尾兜底返回 null
     * （已用字节码反汇编确认）。此前 addLocalSourceDirectory 直接存裸 URI，读取时
     * UniFile.fromUri 得到 null、又被 takeIf{exists} 丢弃 ⇒ 额外根目录永远进不了列表，
     * 表现为「点添加文件夹提示成功，但文件夹列表里始终看不到」（K30 Pro 真机实测确认）。
     * 这里统一转成 document 形式（等价于 unifile 内部的 prepareTreeUri）。
     */
    private fun normalizeSafUri(uri: Uri): Uri = runCatching {
        if (DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        } else {
            uri
        }
    }.getOrDefault(uri)

    /**
     * SY 2026-10-01：默认 local 目录的**只读**查询（不创建）。
     * 旧实现走 [getLocalSourceDirectory] 的 `baseDir.createDirectory("local")` —— 只要读一次根目录
     * 列表，磁盘上就会凭空多出一个空的 `local` 目录并被当成根目录显示，与用户自己添加的同名文件夹打架
     * （用户实测：本地图源「认死」一个自带的 local，同名目录冲突）。
     */
    fun getExistingLocalSourceDirectory(): UniFile? =
        runCatching { baseDir?.findFile(LOCAL_SOURCE_PATH)?.takeIf { it.isDirectory } }.getOrNull()

    /** 根目录「真实身份」：SAF 用 documentId、本地文件用绝对路径 —— 同一物理目录的去重依据。 */
    private fun rootIdentity(uni: UniFile): String {
        val uri = uni.uri ?: return "name:" + uni.name.orEmpty()
        return runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: uri.path?.let { File(it).absolutePath }
            ?: uri.toString()
    }

    fun getLocalSourceDirectories(): List<UniFile> {
        // 默认 local 目录：**只在已存在时**才作为根（不再无中生有地创建空 local）
        val defaultDir = getExistingLocalSourceDirectory()
        val raw = storagePreferences.localSourceExtraDirectories.get()
        // SY 2026-10-01：一次性把历史裸 tree URI 就地归一化（幂等；归一化后再调用本函数不会重复写）
        val normalizedUris = raw.map { normalizeSafUri(it.toUri()).toString() }.toSet()
        if (normalizedUris != raw) {
            storagePreferences.localSourceExtraDirectories.set(normalizedUris)
        }
        val extras = normalizedUris
            // 稳定排序：Set 的迭代顺序不保证，而根目录的「唯一键」依赖顺序（见 LocalWebDavSource.buildRootEntries）
            .sorted()
            .mapNotNull { uri ->
                runCatching { UniFile.fromUri(context, uri.toUri()) }
                    .getOrNull()
                    ?.takeIf { it.exists() && it.isDirectory }
            }
        val roots = (if (defaultDir != null) listOf(defaultDir) else emptyList()) + extras
        // 同一物理目录只留一个（默认在前优先）——用户把 app 自带的 local 目录再「添加」一遍时不再出现两个同名根
        return roots.distinctBy { rootIdentity(it) }
    }

    /**
     * 添加一个本地图源根目录（SAF URI）。
     * 2026-10-01：① 存**归一化**后的 URI（裸 tree URI 会让 UniFile.fromUri 解析失败、列表看不到）；
     * ② 幂等 —— 已存在（或归一化后已存在）时返回 true，重复添加不再误报「添加失败」。
     */
    fun addLocalSourceDirectory(uri: String): Boolean {
        val current = storagePreferences.localSourceExtraDirectories.get()
        val normalized = normalizeSafUri(uri.toUri()).toString()
        if (normalized in current || uri in current) return true
        // 与默认 local 目录指向同一处则不重复登记（默认目录已存在时本就作为根包含在内）
        if (getExistingLocalSourceDirectory()?.uri?.toString() == normalized) return true
        storagePreferences.localSourceExtraDirectories.set(current + normalized)
        localDirsVersion.incrementAndGet() // SY：不等 Flow（Lazily 共享，可能无人收集）也要失效缓存
        return true
    }

    /** 移除一个本地图源根目录。 */
    fun removeLocalSourceDirectory(uri: String) {
        val current = storagePreferences.localSourceExtraDirectories.get()
        storagePreferences.localSourceExtraDirectories.set(current - uri)
        localDirsVersion.incrementAndGet() // SY：同上
    }
    // SY <--
}

private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val LOCAL_SOURCE_PATH = "local"

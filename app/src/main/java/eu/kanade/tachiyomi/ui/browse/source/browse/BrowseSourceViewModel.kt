package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import tachiyomi.data.PageTrackingPagingSource
import tachiyomi.data.source.SourcePage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.asState
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.webdav.BaseWebDavSource
import eu.kanade.tachiyomi.source.webdav.DirListingCache
import eu.kanade.tachiyomi.data.download.normalizePath
import eu.kanade.tachiyomi.source.webdav.LocalWebDavSource
import eu.kanade.tachiyomi.source.webdav.WebDavSource
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.FolderSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFolder
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.common.utils.mutate
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

@AssistedInject
class BrowseSourceViewModel(
    @Assisted private val sourceId: Long,
    @Assisted listingQuery: String?,
    private val sourceManager: SourceManager,
    sourcePreferences: SourcePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val coverCache: CoverCache,
    private val getRemoteManga: GetRemoteManga,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val getManga: GetManga,
    private val updateManga: UpdateManga,
    private val addTracks: AddTracks,
    getIncognitoState: GetIncognitoState,
    // SY 2026-10-06：网盘挂载目录的「删除」= 移除挂载记录（真删除）
    private val netdiskPreferences: eu.kanade.tachiyomi.source.netdisk.NetdiskPreferences,
    // SY 2026-10-06：WebDAV 额外目录的「删除」= 从 cloudSyncFolders 移除（真删除）
    private val downloadPreferences: tachiyomi.domain.download.service.DownloadPreferences,
) : ViewModel() {

    val state: StateFlow<BrowseSourceViewModel.State>
        field = MutableStateFlow<BrowseSourceViewModel.State>(State(Listing.valueOf(listingQuery)))

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: Long, listingQuery: String?): BrowseSourceViewModel
    }

    val displayModeState = sourcePreferences.sourceDisplayMode.asState(viewModelScope)
    var displayMode by displayModeState

    // SY --> 对齐书架：本地/WebDAV 源使用书架显示模式（暴露 State 以便界面观察）
    val libraryDisplayModeState = libraryPreferences.displayMode.asState(viewModelScope)
    var libraryDisplayMode by libraryDisplayModeState
    // SY <--

    private val source: Source? get() = state.value.source

    init {
        viewModelScope.launchIO {
            val source = sourceManager.getOrStub(sourceId)

            state.update {
                var query: String? = null
                var listing = it.listing

                if (listing is Listing.Search) {
                    query = listing.query
                    listing = Listing.Search(query, source.getFilterList())
                }

                it.copy(
                    source = source,
                    listing = listing,
                    filters = source.getFilterList(),
                    toolbarQuery = query,
                )
            }

            // SY --> 文件夹视图：支持 FolderSource 的源（本地图源 / WebDAV 云盘）进源先列文件夹
            if (source is FolderSource) {
                migrateLegacyHiddenFolderKeys()
                val folders = runCatching { source.getFolders() }
                    .onFailure { logcat(LogPriority.ERROR, it) { "Failed to load source folders" } }
                    .getOrNull()
                state.update { it.copy(folders = filterHiddenFolders(folders)) }
            }
            // SY <--

            if (!getIncognitoState.await(source.id)) {
                sourcePreferences.lastUsedSource.set(source.id)
            }
        }
    }

    /**
     * Flow of Pager flow tied to [State.listing] and the requested start page.
     * Changing [State.startPage] (via [jumpToPage]) re-creates the Pager with the
     * new [androidx.paging.Pager.initialKey] so the list reloads from that page.
     */
    // SY --> 跳转页码：记录每个 manga 所属的真实源页码，供滚动时联动 FAB 显示
    private val pagingGeneration = AtomicLong()
    private val mangaSourcePages = ConcurrentHashMap<Long, SourcePage>()
    // SY <--

    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()
    val mangaPagerFlowFlow = state
        .filter { it.source != null }
        .map { MangaPageRequest(it.listing, it.startPage, it.pageRequestId, it.activeFolder?.url) }
        .distinctUntilChanged()
        .map { request ->
            // SY --> 每次重建 Pager 都换新 generation 并清空旧映射，避免跨列表残留
            val generation = pagingGeneration.incrementAndGet()
            mangaSourcePages.clear()
            // SY <--
            Pager(
                config = PagingConfig(pageSize = 25),
                initialKey = request.startPage.toLong(),
            ) {
                // SY --> 用包装源记录每页 manga 的真实页码
                PageTrackingPagingSource(
                    delegate = getRemoteManga(sourceId, request.listing.query ?: "", request.listing.filters),
                    onPageLoaded = { page, mangas ->
                        if (pagingGeneration.get() == generation) {
                            mangas.forEach { manga ->
                                mangaSourcePages[manga.id] = SourcePage(generation, page)
                            }
                        }
                    },
                )
                // SY <--
            }.flow.map { pagingData ->
                pagingData.map { manga ->
                    // SY 2026-10-01 用户要求：**不再订阅数据库**，只做一次性查询。
                    //
                    // 原实现是 `getManga.subscribe(url, source).stateIn(viewModelScope)`：
                    // ① 内层 stateIn 默认 SharingStarted.Eagerly ⇒ 该条目一旦进入分页就**永久订阅**；
                    // ② 一个 1800 本的文件夹就会挂着上千个数据库订阅。SQLDelight 每次事务提交都会
                    //    notifyListeners，全部订阅者逐个 dispatch 回主线程，与主线程取消息争
                    //    MessageQueue 锁 —— 实测「走文件夹进入阅读器」Janky 15.8% / Slow UI 39 /
                    //    handleInput 峰值 127ms，而直接进阅读器只有 2.6%（只有 1 个订阅者）。
                    // 现在改成 `await(id)` 一次性查询：查完即止、不订阅，列表不再跟随数据库变化
                    // （需要看到最新状态时由用户手动刷新，这是用户明确选择的取舍）。
                    flow { emit(getManga.await(manga.id) ?: manga) }
                        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), manga)
                }
                    .filter { !hideInLibraryItems || !it.value.favorite }
            }
                .cachedIn(viewModelScope)
        }
        // SY 2026-10-01：原本是 SharingStarted.Lazily —— 只要有第一个订阅者，就**永不停止**。
        // 于是「从文件夹书架点进阅读器」以后，这个 ViewModel 仍然在监听数据库：阅读器每存一次
        // 阅读进度就通知一轮，通知要 dispatch 回主线程，与主线程取消息争 MessageQueue 锁。
        // 实测同一台设备：走文件夹进入 = Janky 15.8% / Slow UI 39 / handleInput 峰值 127ms；
        // 直接拉起阅读器 = Janky 2.6%（因为只有阅读器一个订阅者）。
        // 改为 WhileSubscribed：离开页面 5 秒后自动退订，回来自动恢复，列表刷新行为不变。
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyFlow())

    // SY --> 文件夹视图：进入 / 退出文件夹，以及添加文件夹

    /** 进入某个文件夹：源侧限定浏览范围，界面切到漫画列表（重置分页与搜索）。 */
    fun enterFolder(folder: SourceFolder) {
        val folderSource = source as? FolderSource ?: return
        viewModelScope.launchIO {
            runCatching { folderSource.setActiveFolder(folder.url) }
                .onFailure { logcat(LogPriority.ERROR, it) { "Failed to enter folder ${folder.url}" } }
            state.update {
                it.copy(
                    folders = null,
                    activeFolder = folder,
                    listing = Listing.Popular,
                    toolbarQuery = null,
                    browsePage = 1,
                    startPage = 1,
                    pageRequestId = it.pageRequestId + 1,
                )
            }
        }
    }

    /** 退出文件夹回到文件夹视图（重新拉取，让新添加的文件夹立即可见）。 */
    fun exitFolder() {
        val folderSource = source as? FolderSource ?: return
        viewModelScope.launchIO {
            runCatching { folderSource.setActiveFolder(null) }
            val folders = runCatching { folderSource.getFolders() }.getOrNull()
            state.update {
                it.copy(
                    folders = filterHiddenFolders(folders),
                    activeFolder = null,
                    listing = Listing.Popular,
                    toolbarQuery = null,
                    browsePage = 1,
                    startPage = 1,
                    pageRequestId = it.pageRequestId + 1,
                )
            }
        }
    }

    /** 添加文件夹（本地图源传 SAF URI，WebDAV 传云端路径）；成功后刷新文件夹列表。 */
    fun addFolder(identifier: String, onResult: (Boolean) -> Unit) {
        val folderSource = source as? FolderSource ?: return
        viewModelScope.launchIO {
            val ok = runCatching { folderSource.addFolder(identifier) }
                .onFailure { logcat(LogPriority.ERROR, it) { "Failed to add folder $identifier" } }
                .getOrDefault(false)
            if (ok) {
                // SY 2026-10-01：刚添加的文件夹必须可见 —— 清掉它的隐藏键。
                // 否则「先长按删（=仅隐藏视图）再加」会一直提示添加成功却看不到（用户实测困惑点）。
                unhideJustAdded(identifier)
                val folders = runCatching { folderSource.getFolders() }.getOrNull()
                state.update { it.copy(folders = filterHiddenFolders(folders)) }
            }
            withUIContext { onResult(ok) }
        }
    }

    /**
     * 解除「刚添加的文件夹」的隐藏。入参可能是 SAF URI（本地图源）或云端路径（WebDAV）；
     * 两种都按「完整标识 + 末段名」匹配隐藏键（本地源的键用根目录名，WebDAV 用相对路径）。
     */
    private fun unhideJustAdded(identifier: String) {
        val hidden = libraryPreferences.folderLibraryHiddenFolders.get()
        if (hidden.isEmpty()) return
        val decoded = runCatching { Uri.decode(identifier) }.getOrDefault(identifier)
        val leaf = decoded.trimEnd('/').substringAfterLast('/')
        val candidates = setOf(identifier, decoded, leaf)
        val isLocal = source is LocalSource || source is LocalWebDavSource
        val prefix = if (isLocal) "local:" else "src:$sourceId:"
        val remove = hidden.filter { key ->
            if (!key.startsWith(prefix)) return@filter false
            val rest = key.removePrefix(prefix)
            // 本地源：键可能是重名后缀形式（local#2），按 `#` 前的主体名一并解除隐藏
            rest in candidates || (isLocal && rest.substringBefore('#') == leaf)
        }
        if (remove.isNotEmpty()) {
            libraryPreferences.folderLibraryHiddenFolders.set(hidden - remove.toSet())
            logcat(LogPriority.INFO) { "添加文件夹：解除隐藏 $remove" }
        }
    }

    fun refreshFolders() {
        val folderSource = source as? FolderSource ?: return
        viewModelScope.launchIO {
            // SY --> 主动刷新同样要穿透目录缓存，否则文件夹层永远命中旧缓存
            runCatching {
                currentlyViewedFolderPaths().forEach { path ->
                    // SY 2026-10-05：统一按 BaseWebDavSource 泛化（网盘源 115/夸克同样要穿透缓存）。
                    (source as? BaseWebDavSource)?.let {
                        DirListingCache.markStaleDirectory(it.id, path)
                        DirListingCache.markStaleEntries(it.id, path)
                    }
                }
            }
            // SY <--
            val folders = runCatching { folderSource.getFolders() }.getOrNull()
            state.update { it.copy(folders = filterHiddenFolders(folders)) }
        }
    }

    /** SY --> 扫描封面完成后重建 Pager：网格项重新组合、Coil 重新按新缓存取封面。 */
    fun refreshMangaList() {
        state.update { it.copy(pageRequestId = it.pageRequestId + 1) }
    }

    /**
     * SY 2026-10-06：「更新书架」菜单入口（文件夹书架 / 云盘）。
     *
     * 与扫描封面的「先 stale 再 refresh」同源：强制穿透 [DirListingCache] 缓存，
     * 让当前浏览层（漫画层重列该文件夹、文件夹层重列云盘根目录）从云端重新枚举——
     * 即用户要的「更新书架 = 刷新列表」，看到新加进云盘的书、去掉被删的。
     * 文件夹层与漫画层都刷新，不挑层，调用方无脑接即可。
     */
    fun refreshLibrary() {
        markCurrentListingStale()
        refreshMangaList()
        refreshFolders()
    }

    /**
     * SY --> 2026-09-30：下拉刷新 / 扫描封面时的「强制重新枚举」。
     *
     * 目录枚举已接入 [DirListingCache]（新鲜窗口内直接用缓存 ⇒ 秒开），但主动刷新入口必须
     * 能穿透缓存，否则「下拉刷新看不到新加进云盘的书」。源接口没有 forceRefresh 参数，
     * 所以在重建 Pager **之前**登记当前目录为 stale：下一次枚举会跳过缓存同步重拉。
     */
    private fun markCurrentListingStale() {
        currentlyViewedFolderPaths().forEach { path ->
            // SY 2026-10-05：统一按 BaseWebDavSource 泛化（网盘源 115/夸克同样要穿透缓存）。
            (state.value.source as? BaseWebDavSource)?.let {
                DirListingCache.markStaleDirectory(it.id, path)
                DirListingCache.markStaleEntries(it.id, path)
            }
        }
    }

    /**
     * 当前界面正在浏览的目录集合：
     *  - 漫画层（[State.activeFolder] 非空）= 该文件夹；
     *  - 文件夹层 = 源的全部根目录（默认 destination + 用户添加的额外目录）。
     */
    private fun currentlyViewedFolderPaths(): List<String> {
        val current = state.value
        current.activeFolder?.url?.let { return listOf(it) }
        // SY 2026-10-05：统一按 BaseWebDavSource 泛化（网盘源 115/夸克的 folderPaths 同样在基类）。
        return (current.source as? BaseWebDavSource)
            ?.let { src -> runCatching { src.folderPaths() }.getOrDefault(emptyList()) }
            ?: emptyList()
    }
    // SY <--

    // SY --> 文件夹书架：隐藏文件夹（仅视图，不动原文件）。
    // 2026-10-01 修复：隐藏键原本按 "sourceId:url" 区分，但 LocalSource 与 LocalWebDavSource 共用
    // id=0（LocalSource.ID），且 LocalWebDavSource 的根目录名常与 LocalSource 的默认 "local" 重名
    // ⇒ 在任一入口隐藏 "0:local" 会把两个源的同名文件夹一起藏掉（表现为「添加文件夹后列表里没有」）。
    // 改为按「源类命名空间」区分：本地图源族（LocalSource/LocalWebDavSource）共用 "local:<name>"，
    // 其余源用 "src:<sourceId>:<url>"，互不串扰。
    private fun hiddenFolderKeyOf(folder: SourceFolder): String {
        return if (source is LocalSource || source is LocalWebDavSource) {
            // SY 2026-10-01：用 url（本地源 = 唯一键，重名根带 #n 后缀）而非 name，避免同名根隐藏键互相覆盖
            "local:${folder.url.ifBlank { folder.name }}"
        } else {
            "src:$sourceId:${folder.url}"
        }
    }

    private fun filterHiddenFolders(folders: List<SourceFolder>?): List<SourceFolder>? {
        if (folders == null) return null
        val hidden = libraryPreferences.folderLibraryHiddenFolders.get()
        return folders.filter { hiddenFolderKeyOf(it) !in hidden }
    }

    /** 把某个文件夹从软件视图隐藏（持久化，不删除远端/本地文件）。 */
    fun hideFolder(folder: SourceFolder) {
        val url = folder.url
        // SY 2026-10-06：网盘挂载目录的「删除」= **真删除** —— 移除挂载记录 + 清目录缓存，
        // 不写隐藏键（否则重挂载会被旧隐藏键滤掉，用户踩过：删除后再添加不显示）。
        // 封面缓存刻意保留：重挂载后内容未变，可秒出封面。
        if (url.trimStart('/').startsWith("@")) {
            netdiskPreferences.removeNetdiskMountByUrl(url)
            DirListingCache.markStaleDirectory(sourceId, url)
            DirListingCache.markStaleEntries(sourceId, url)
            state.update { s -> s.copy(folders = s.folders?.filter { it.url != folder.url }) }
            logcat(LogPriority.INFO) { "删除网盘文件夹：已移除挂载 ${folder.url}" }
            return
        }
        // SY 2026-10-06：WebDAV 额外目录的「删除」同样真删除（从 cloudSyncFolders 移除；
        // 默认 destination 不删，退回隐藏语义）。
        val webdav = state.value.source as? WebDavSource
        if (webdav != null) {
            val path = normalizePath(url)
            val destination = normalizePath(downloadPreferences.cloudSyncDestination.get())
            if (path != destination && path in downloadPreferences.cloudSyncFolders.get()) {
                downloadPreferences.cloudSyncFolders.set(downloadPreferences.cloudSyncFolders.get() - path)
                removeHiddenKey("src:$sourceId:$path")
                DirListingCache.markStaleDirectory(sourceId, path)
                DirListingCache.markStaleEntries(sourceId, path)
                state.update { s -> s.copy(folders = s.folders?.filter { it.url != folder.url }) }
                logcat(LogPriority.INFO) { "删除 WebDAV 文件夹：已从目录列表移除 $path" }
                return
            }
            // destination 或未知路径 → 走底部隐藏语义
        }
        // SY 2026-10-06：本地源进程内额外子文件夹 → 真移除；
        // SAF 根目录保持隐藏语义（重选同一目录时自动解除隐藏）。
        val local = state.value.source as? LocalWebDavSource
        if (local != null && Uri.parse(url).scheme == null && local.removeExtraLocalFolder(url)) {
            state.update { s -> s.copy(folders = s.folders?.filter { it.url != folder.url }) }
            logcat(LogPriority.INFO) { "删除本地文件夹：已移除 $url" }
            return
        }
        val key = hiddenFolderKeyOf(folder)
        val current = libraryPreferences.folderLibraryHiddenFolders.get().toMutableSet()
        if (current.add(key)) libraryPreferences.folderLibraryHiddenFolders.set(current)
        state.update { s -> s.copy(folders = s.folders?.filter { hiddenFolderKeyOf(it) != key }) }
    }

    /** SY 2026-10-06：从隐藏集合移除单个键（真删除路径用，防旧键残留挡重挂载）。 */
    private fun removeHiddenKey(key: String) {
        val current = libraryPreferences.folderLibraryHiddenFolders.get()
        if (key in current) libraryPreferences.folderLibraryHiddenFolders.set(current - key)
    }

    /**
     * SY 2026-10-01 一次性迁移：旧 "sourceId:url" 隐藏键 → 新命名空间格式。
     * 旧键的致命问题：LocalSource 与 LocalWebDavSource 共用 id=0，键 "0:xxx" 在两个源（族内）串扰，
     * 且本地图源根目录名与 url 相同场景下，在任一入口隐藏 "0:local" 会把之后重新添加的同名根目录
     * 一起藏掉（用户表现为「添加文件夹成功但列表里看不到」）。
     * 迁移策略：
     *  - "0:xxx"（本地源族）→ 作废（取消隐藏）。隐藏是纯视图操作，重新隐藏成本低。
     *  - 其余（各 WebDAV 源，id 全局唯一）→ 机械改写为 "src:<id>:<url>"，保留原隐藏意图。
     */
    private fun migrateLegacyHiddenFolderKeys() {
        // SY 2026-10-06：必须无条件先跑 —— 下方 legacy 迁移有两处 early-return，
        // 清理放后面会永远执行不到（1.0.18 真机踩坑：重挂载文件夹被旧隐藏键滤掉不显示）。
        cleanupNetdiskHiddenKeys()
        val current = libraryPreferences.folderLibraryHiddenFolders.get()
        if (current.isEmpty()) return
        val legacy = current.filterNot { it.startsWith("local:") || it.startsWith("src:") }
        if (legacy.isEmpty()) return
        val migrated = legacy.mapNotNull { key ->
            val idx = key.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val idPart = key.substringBefore(':')
            val rest = key.substring(idx + 1)
            if (idPart == "0") null else "src:$idPart:$rest" // "0:" = 本地源族，作废
        }
        libraryPreferences.folderLibraryHiddenFolders.set(current - legacy.toSet() + migrated)
        logcat(LogPriority.INFO) { "文件夹隐藏键迁移：处理 ${legacy.size} 条旧格式键 → $migrated" }
    }

    /**
     * SY 2026-10-06：网盘挂载目录（@ 前缀）不再使用隐藏键（删除=真删除挂载）——
     * 清理 1.0.13~1.0.15 时期旧「删除」写下的隐藏键，否则重挂载的文件夹会被滤掉不显示。
     */
    private fun cleanupNetdiskHiddenKeys() {
        val current = libraryPreferences.folderLibraryHiddenFolders.get()
        if (current.isEmpty()) return
        val netdiskHidden = current
            .filter { it.startsWith("src:") && it.substringAfter(':', "").substringBefore(':') == sourceId.toString() }
            .filter { it.substringAfter(':', "").substringAfter(':', "").startsWith("@") }
        if (netdiskHidden.isNotEmpty()) {
            libraryPreferences.folderLibraryHiddenFolders.set(current - netdiskHidden.toSet())
            logcat(LogPriority.INFO) { "文件夹隐藏键清理：移除网盘挂载目录的隐藏键 $netdiskHidden" }
        }
    }
    // SY <--

    // SY --> 扫描封面：批量解析「当前文件夹内全部漫画」的封面；并发度 [threads] 可调（默认 4，单线程档专治 115 风控）。
    data class CoverScan(val done: Int, val total: Int, val failed: Int)

    private val coverScanRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val coverScanProgressFlow = MutableStateFlow<CoverScan?>(null)
    val coverScanProgress: StateFlow<CoverScan?> = coverScanProgressFlow

    /**
     * 文件夹层（activeFolder 非空）才有意义：拉全量列表（本地/WebDAV 源的
     * getPopularManga 本就一次性返回整个文件夹），逐个 ensureCover，已就绪的秒过。
     */
    fun scanCovers(threads: Int = SCAN_COVERS_PARALLELISM, slow: Boolean = false) {
        val source = state.value.source ?: return
        if (state.value.activeFolder == null) return
        // SY 2026-10-05：网盘源（115/夸克）与 WebDAV 同待遇（均为 BaseWebDavSource）。
        if (!(source is LocalSource || source is BaseWebDavSource)) return
        if (!coverScanRunning.compareAndSet(false, true)) return // 已在跑，忽略重复点击

        viewModelScope.launchIO {
            try {
                // SY --> 先重建列表：让「本次要扫描的集合」与服务器现状一致
                // （新加进云盘的漫画会出现、被删掉的会消失——即用户要的「扫描封面顺带扫增删」）
                // ⚠️ 先登记 stale，再 refreshMangaList()：目录缓存不能把这次主动刷新挡回去。
                markCurrentListingStale()
                refreshMangaList()
                // SY <--
                val mangas = runCatching { source.getPopularManga(1).mangas }
                    .onFailure { logcat(LogPriority.ERROR, it) { "扫描封面：列目录失败" } }
                    .getOrDefault(emptyList())
                val total = mangas.size
                val done = java.util.concurrent.atomic.AtomicInteger()
                val failed = java.util.concurrent.atomic.AtomicInteger()
                coverScanProgressFlow.value = CoverScan(0, total, 0)

                // SY --> 多条 worker 从**共享队列**取书：同一时刻最多 [threads] 本在跑，
                // 谁先做完谁立刻领下一本（滑动窗口，不是分批等齐）。
                //
                // 并发上限 = [threads]；[threads]<=1（菜单里的「单线程扫描封面」）或 slow=true
                // （「慢速封面4线程」）时再让每本之间留间隔 + 错峰起跑：并发不变，但请求在时间轴上被摊开。
                // 115 网盘风控盯的是「短时间调用次数」，削掉瞬时峰值比单纯降并发更有效
                // （实测同步扫描 228 本会在几秒内砸出全部请求）。单线程档专治「被 115 踢下线」。
                val staggerMs = when {
                    threads <= 1 -> SCAN_COVERS_SLOWEST_STAGGER_MS
                    slow -> SCAN_COVERS_SLOW_STAGGER_MS
                    else -> 0L
                }
                val modeLabel = if (staggerMs > 0) "慢速" else "标准"
                logcat(LogPriority.WARN) {
                    "扫描封面: 开始（${modeLabel}模式 / $threads 线程" +
                        "${if (staggerMs > 0) " / 每本间隔 ${staggerMs}ms" else ""} / 共 $total 本）"
                }
                val queue = ConcurrentLinkedQueue(mangas)
                coroutineScope {
                    repeat(threads) { worker ->
                        launch {
                            if (staggerMs > 0L) delay(staggerMs * worker) // 错峰起跑，别 4 个请求同时砸出去
                            while (true) {
                                val manga = queue.poll() ?: break
                                // SY --> 每本耗时取证（WARN 保证 release 可见）：定位扫描慢在哪本书/哪种格式
                                val t0 = System.currentTimeMillis()
                                val ok = runCatching {
                                    (source as? BaseWebDavSource)?.ensureCover(manga.url) ?: false
                                }.getOrDefault(false)
                                val ms = System.currentTimeMillis() - t0
                                if (ms > 1000 || !ok) {
                                    logcat(LogPriority.WARN) {
                                        "扫描封面: ${manga.url} → ${if (ok) "成功" else "失败"}，耗时 ${ms}ms"
                                    }
                                }
                                // SY <--
                                if (!ok) failed.incrementAndGet()
                                coverScanProgressFlow.value = CoverScan(done.incrementAndGet(), total, failed.get())
                                // 领下一本之前留个喘息窗口，给网盘降速（慢速模式）
                                if (staggerMs > 0L) delay(staggerMs)
                            }
                        }
                    }
                }
                delay(1500) // 让 UI 来得及展示终态

                // SY --> 扫描收尾：① 再对一次目录（把扫描期间服务器上的增删同步进列表）
                // ② 重建 Pager 让新封面立即显示；③ 日志报出增删明细，便于真机核对。
                val after = runCatching { source.getPopularManga(1).mangas }.getOrNull()
                if (after != null) {
                    val beforeUrls = mangas.map { it.url }.toSet()
                    val afterUrls = after.map { it.url }.toSet()
                    val added = afterUrls - beforeUrls
                    val removed = beforeUrls - afterUrls
                    if (added.isNotEmpty() || removed.isNotEmpty()) {
                        logcat(LogPriority.WARN) {
                            "扫描封面: 目录同步 → 新增 ${added.size} 本${added.take(5)}，" +
                                "移除 ${removed.size} 本${removed.take(5)}"
                        }
                    } else {
                        logcat(LogPriority.WARN) { "扫描封面: 目录同步 → 无增删（共 ${after.size} 本）" }
                    }
                }
                refreshMangaList()
                refreshFolders()
                // SY <--
            } finally {
                coverScanProgressFlow.value = null
                coverScanRunning.set(false)
            }
        }
    }
    // SY <--


    // SY --> 对齐书架：本地/WebDAV 源的长按管理
    fun toggleSelection(manga: Manga) {
        if (!state.value.isLibraryLikeSource) return
        state.update { current ->
            current.copy(
                selection = current.selection.mutate {
                    if (!it.remove(manga.id)) it.add(manga.id)
                },
                selectionMode = true,
            )
        }
    }

    /** 全选：传入当前已加载（可见）的全部 manga id。 */
    fun selectAll(ids: Set<Long>) {
        if (!state.value.isLibraryLikeSource) return
        state.update { it.copy(selection = ids, selectionMode = ids.isNotEmpty()) }
    }

    /** 反选：在已加载（可见）的 id 集合中，选取当前未选中的。 */
    fun invertSelection(ids: Set<Long>) {
        if (!state.value.isLibraryLikeSource) return
        state.update { current ->
            val newSel = ids.filter { it !in current.selection }.toSet()
            current.copy(selection = newSel, selectionMode = newSel.isNotEmpty())
        }
    }

    fun clearSelection() {
        state.update { it.copy(selection = emptySet(), selectionMode = false) }
    }
    // SY <--

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    // SY --> 文件夹书架：暴露列数偏好（Int，Compose State），供书架网格直接使用
    fun columnsPreferenceForOrientation(orientation: Int): PreferenceMutableState<Int> {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        return (if (isLandscape) libraryPreferences.landscapeColumns else libraryPreferences.portraitColumns)
            .asState(viewModelScope)
    }
    // SY <--

    fun resetFilters() {
        val source = source ?: return
        state.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        state.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: FilterList) {
        state.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source?.getFilterList() ?: FilterList())

        state.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
                browsePage = 1,
                startPage = 1,
                // SY 2026-09-29：筛选/排序确认（filters 非空）时强制让 Pager 重建并重拉，
                // 否则已在 Search listing 内再次改排序会因 distinctUntilChanged 被丢弃而不重排。
                pageRequestId = if (filters != null) it.pageRequestId + 1 else it.pageRequestId,
            )
        }
    }

    fun searchGenre(genreName: String) {
        val defaultFilters = source?.getFilterList() ?: return
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is SourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is SourceModelFilter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is SourceModelFilter.TriState -> filter.state = 1
                            is SourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is SourceModelFilter.Select<*>) {
                val index = sourceFilter.values.filterIsInstance<String>()
                    .indexOfFirst { it.equals(genreName, true) }

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }

        state.update {
            val listing = if (genreExists) {
                Listing.Search(query = null, filters = defaultFilters)
            } else {
                Listing.Search(query = genreName, filters = defaultFilters)
            }
            it.copy(
                filters = defaultFilters,
                listing = listing,
                toolbarQuery = listing.query,
                browsePage = 1,
                startPage = 1,
            )
        }
    }

    // SY --> 跳转页码：跳到指定页并（必要时）重新从目标页加载列表
    fun jumpToPage(page: Int) {
        if (page < 1) return
        state.update {
            it.copy(
                browsePage = page,
                startPage = page,
                pageRequestId = it.pageRequestId + 1,
            )
        }
    }

    // SY --> 滚动联动：根据当前首个可见 manga 反查其真实源页码，仅更新显示用 browsePage（不触发 Pager 重建）
    fun updateVisibleManga(mangaId: Long) {
        val sourcePage = mangaSourcePages[mangaId]
            ?.takeIf { it.generation == pagingGeneration.get() }
            ?.page
            ?: return
        state.update { s -> if (sourcePage == s.browsePage) s else s.copy(browsePage = sourcePage) }
    }
    // SY <--

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    fun changeMangaFavorite(manga: Manga) {
        viewModelScope.launch {
            var new = manga.copy(
                favorite = !manga.favorite,
                dateAdded = when (manga.favorite) {
                    true -> 0
                    false -> Clock.System.now().toEpochMilliseconds()
                },
            )

            if (!new.favorite) {
                new = new.removeCovers(coverCache)
            } else {
                setMangaDefaultChapterFlags.await(manga)
                addTracks.bindEnhancedTrackers(manga, sourceManager.getOrStub(manga.source))
            }

            updateManga.await(new.toMangaUpdate())
        }
    }

    fun addFavorite(manga: Manga) {
        viewModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveMangaToCategories(manga, defaultCategory)

                    changeMangaFavorite(manga)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveMangaToCategories(manga)

                    changeMangaFavorite(manga)
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(manga.id).map { it.id }
                    setDialog(
                        Dialog.ChangeMangaCategory(
                            manga,
                            categories.mapAsCheckboxState { it.id in preselectedIds },
                        ),
                    )
                }
            }
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    suspend fun getDuplicateLibraryManga(manga: Manga): List<MangaWithChapterCount> {
        return getDuplicateLibraryManga.invoke(manga)
    }

    private fun moveMangaToCategories(manga: Manga, vararg categories: Category) {
        moveMangaToCategories(manga, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveMangaToCategories(manga: Manga, categoryIds: List<Long>) {
        viewModelScope.launchIO {
            setMangaCategories.await(
                mangaId = manga.id,
                categoryIds = categoryIds.toList(),
            )
        }
    }

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        state.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        state.update { it.copy(toolbarQuery = query) }
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteManga.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteManga.QUERY_LATEST, filters = FilterList())
        data class Search(
            override val query: String?,
            override val filters: FilterList,
        ) : Listing(query = query, filters = filters)

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteManga.QUERY_POPULAR -> Popular
                    GetRemoteManga.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
        data class AddDuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class ChangeMangaCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState.State<Category>>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val source: Source? = null,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
        val browsePage: Int = 1,
        val startPage: Int = 1,
        val pageRequestId: Int = 0,
        // SY --> 文件夹视图：folders 非 null 表示显示文件夹网格；activeFolder 表示已进入某文件夹
        val folders: List<SourceFolder>? = null,
        val activeFolder: SourceFolder? = null,
        // SY <--
        // SY --> 对齐书架：本地/WebDAV 源的长按选择态
        val selection: Set<Long> = emptySet(),
        val selectionMode: Boolean = false,
        // SY <--
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
        // SY --> 对齐书架：本地图源 / WebDAV 云盘使用书架式浏览逻辑
        val isLibraryLikeSource: Boolean
            // SY 2026-10-05：网盘源（115/夸克）与 WebDAV 同待遇
            get() = source.let { it is LocalSource || it is BaseWebDavSource }
        // SY <--
    }

    // SY --> 触发 Pager 重建的请求键：listing 变化或跳转页码时都会改变
    private data class MangaPageRequest(
        val listing: Listing,
        val startPage: Int,
        val requestId: Int,
        // SY --> 文件夹视图：进入/退出文件夹也要重建 Pager
        val folderUrl: String? = null,
        // SY <--
    )
    // SY <--
}

// SY --> 扫描封面并发度：标准 / 慢速档固定 4 线程，避免云盘（115/阿里/夸克类）风控限速
private const val SCAN_COVERS_PARALLELISM = 4

/**
 * SY --> 「慢速封面4线程」：每完成一本后额外等待的毫秒数（同时也用作起跑错峰步长）。
 *
 * 并发仍是 [SCAN_COVERS_PARALLELISM]（用户要的「同时只扫 4 本，扫完一本补一本」），
 * 这个间隔只负责把请求在时间轴上摊平——后端 115 + OpenList 的风控按「短时间调用次数」
 * 判定，削峰比单纯降并发生效更快。调大可更保守，调到 0 就退化成标准扫描。
 * SY <--
 */
private const val SCAN_COVERS_SLOW_STAGGER_MS = 800L

/**
 * SY 2026-10-06：「单线程扫描封面」档的错峰间隔（同时用作起跑错峰步长）。
 *
 * 这一档并发上限压到 1（[scanCovers] 传入 threads=1）、间隔比慢速4线程更长，
 * 专治「被 115 踢下线」——同一时刻只有 1 个取直链在飞、且每本之间留 1.5s 喘息。
 * 连 2 线程档仍触发 115 风控时，用这一档（最保守）。数值 = 比 [SCAN_COVERS_SLOW_STAGGER_MS] 更保守。
 * SY <--
 */
private const val SCAN_COVERS_SLOWEST_STAGGER_MS = 1500L
// SY <--

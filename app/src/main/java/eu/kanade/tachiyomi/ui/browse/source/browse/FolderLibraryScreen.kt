package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.library.LibrarySettingsDialog
import eu.kanade.presentation.library.components.LibraryCompactGrid
import eu.kanade.presentation.library.components.LibraryComfortableGrid
import eu.kanade.presentation.library.components.LibraryList
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.more.settings.screen.CloudSyncDirectoryPicker
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.library.LibrarySettingsViewModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.BookmarkAdd
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.SelectAll
import mihon.icons.materialsymbols.rounded.FlipToBack
import mihon.presentation.core.util.collectAsLazyPagingItems
import mihon.core.common.utils.mutate
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.netdisk.aliyun.AliyunPanSource
import eu.kanade.tachiyomi.source.netdisk.cloud115open.Cloud115OpenSource
import eu.kanade.tachiyomi.source.netdisk.quark.QuarkSource
import eu.kanade.tachiyomi.source.webdav.LocalWebDavSource
import eu.kanade.tachiyomi.source.webdav.WebDavSource
import mihon.app.di.appGraph
import eu.kanade.tachiyomi.source.SourceFolder

/**
 * SY --> 两个「文件夹书架」：本地图源 (LocalSource) 与 WebDAV 云盘 (WebDavSource) 各自一个实例。
 *
 * 与 BrowseSourceScreen 的区别：整体使用书架的全部逻辑与组件（LibraryToolbar 顶栏、
 * LibraryCompactGrid/ComfortableGrid/List 网格、LibrarySettingsDialog 的筛选/排序/显示、
 * 长按多选 + 底部批量加入/移出书架），文件夹层与进文件夹后的漫画层都是书架样式。
 */
data class FolderLibraryScreen(
    val sourceId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val viewModel =
            assistedMetroViewModel<BrowseSourceViewModel, BrowseSourceViewModel.Factory> {
                create(sourceId = sourceId, listingQuery = null)
            }
        val state by viewModel.state.collectAsState()

        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        // SY --> 书架显示模式 / 列数：与书架完全一致的偏好
        val displayMode by viewModel.libraryDisplayModeState
        val configuration = LocalConfiguration.current
        val columns by remember(configuration.orientation) {
            viewModel.columnsPreferenceForOrientation(configuration.orientation)
        }
        // SY <--

        // SY --> 顶栏搜索（仅文件夹层按名称过滤；进入文件夹后对漫画层无效）
        var searchQuery by remember { mutableStateOf<String?>(null) }

        // SY --> 文件夹层数据：过滤 + 包装成书架项 + 长按多选隐藏态
        val folders = state.folders
        val filteredFolders = remember(folders, searchQuery) {
            val q = searchQuery
            when {
                folders == null -> emptyList()
                q.isNullOrBlank() -> folders
                else -> folders.filter { it.name.contains(q.trim(), ignoreCase = true) }
            }
        }
        val libraryItems = remember(filteredFolders) { filteredFolders.map { it.toLibraryItem(sourceId) } }
        var folderSelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
        val folderSelectionMode = folderSelection.isNotEmpty() && folders != null
        // SY <--

        val onFolderClick: (Long) -> Unit = { id ->
            if (folderSelectionMode) {
                folderSelection = folderSelection.mutate { if (!it.remove(id)) it.add(id) }
            } else {
                folderSelection = emptySet()
                filteredFolders.firstOrNull { it.url.hashCode().toLong() == id }
                    ?.let { viewModel.enterFolder(it) }
            }
        }
        val onFolderLongClick: (Long) -> Unit = { id ->
            folderSelection = folderSelection.mutate { if (!it.remove(id)) it.add(id) }
        }

        // SY --> 顶栏返回：文件夹选择模式先清选择，在文件夹内先退回文件夹，否则退出书架
        val navigateUp: () -> Unit = {
            when {
                folderSelectionMode -> folderSelection = emptySet()
                state.selectionMode -> viewModel.clearSelection()
                state.activeFolder != null -> viewModel.exitFolder()
                else -> navigator.pop()
            }
        }
        BackHandler(
            enabled = folderSelectionMode || state.activeFolder != null || state.selectionMode,
        ) {
            when {
                folderSelectionMode -> folderSelection = emptySet()
                state.selectionMode -> viewModel.clearSelection()
                state.activeFolder != null -> viewModel.exitFolder()
            }
        }
        // SY <--

        // SY --> 添加文件夹：本地图源用 SAF 目录选择器，WebDAV 用「同步目标位置」目录树选择器
        var showAddFolderDialog by remember { mutableStateOf(false) }
        val addLocalFolderLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: SecurityException) {
                    logcat(LogPriority.ERROR, e)
                }
                viewModel.addFolder(uri.toString()) { ok ->
                    context.toast(if (ok) MR.strings.action_folder_added else MR.strings.action_folder_add_failed)
                }
            }
        }
        val onAddFolderClick: (() -> Unit)? = if (state.folders != null) {
            {
                if (state.source is LocalSource || state.source is LocalWebDavSource) {
                    addLocalFolderLauncher.launch(null)
                } else {
                    showAddFolderDialog = true
                }
            }
        } else {
            null
        }
        // SY <--

        // SY 2026-10-05：「添加网盘文件夹」—— WebDAV 文件夹层（云盘）菜单里提供，
        // 先选网盘（仅显示已登录的），再进可视化目录选择器逐层点选，不用填 cid/fid。
        var showNetdiskChooser by remember { mutableStateOf(false) }
        val netdiskPreferences = remember { context.appGraph.netdiskPreferences }
        // SY 2026-10-06：网盘文件夹挂载后立即刷新「云盘」文件夹列表
        LaunchedEffect(Unit) {
            netdiskPreferences.netdiskMounts.changes().collect {
                viewModel.refreshFolders()
            }
        }
        val onAddNetdiskFolderClick: (() -> Unit)? = if (state.source is WebDavSource) {
            { showNetdiskChooser = true }
        } else {
            null
        }

        // SY --> 书架筛选/排序/显示对话框（复用书架设置 ViewModel，作用于全局书架偏好）
        val settingsViewModel = metroViewModel<LibrarySettingsViewModel>()
        var showSettingsDialog by remember { mutableStateOf(false) }
        val defaultCategory = remember(settingsViewModel) {
            val globalSort = settingsViewModel.libraryPreferences.sortingMode.get()
            Category(
                id = Category.UNCATEGORIZED_ID,
                name = "Default",
                order = 0,
                flags = globalSort.flag,
            )
        }
        // SY <--

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
        val onWebViewClick = {}

        val source = state.source
        if (source == null) {
            LoadingScreen()
            return
        }

        // 漫画层（进文件夹后）的分页数据
        val mangaList = viewModel.mangaPagerFlowFlow.collectAsLazyPagingItems()

        // SY --> 扫描封面：进度并入标题，结束后提示并重建列表让新封面出现
        val scanProgress by viewModel.coverScanProgress.collectAsState()
        var wasScanningCovers by remember { mutableStateOf(false) }
        LaunchedEffect(scanProgress) {
            if (scanProgress != null) {
                wasScanningCovers = true
            } else if (wasScanningCovers) {
                wasScanningCovers = false
                viewModel.refreshMangaList()
                context.toast(MR.strings.scan_covers_done)
            }
        }
        // SY <--

        val title = scanProgress?.let {
            stringResource(MR.strings.scan_covers_progress, it.done, it.total)
        } ?: state.activeFolder?.name ?: source.name

        Scaffold(
            topBar = {
                LibraryToolbar(
                    hasActiveFilters = false,
                    selectedCount = state.selection.size,
                    title = LibraryToolbarTitle(title),
                    onClickUnselectAll = viewModel::clearSelection,
                    onClickSelectAll = {
                        val ids = (0 until mangaList.itemCount).mapNotNull { mangaList[it]?.value?.id }.toSet()
                        viewModel.selectAll(ids)
                    },
                    onClickInvertSelection = {
                        val ids = (0 until mangaList.itemCount).mapNotNull { mangaList[it]?.value?.id }.toSet()
                        viewModel.invertSelection(ids)
                    },
                    onClickFilter = { showSettingsDialog = true },
                    onClickRefresh = {},
                    // SY 2026-10-06：「更新书架」接上刷新：强制穿透缓存重列当前浏览层（云盘/文件夹）
                    onClickGlobalUpdate = { viewModel.refreshLibrary() },
                    onClickOpenRandomManga = {},
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    scrollBehavior = null,
                    navigateUp = navigateUp,
                    onAddFolder = onAddFolderClick,
                    // SY 2026-10-19：云盘（WebDavSource）的入口叫「添加 WebDAV 文件夹」
                    addFolderIsWebDav = state.source is WebDavSource,
                    onAddNetdiskFolder = onAddNetdiskFolderClick,
                    // SY --> 扫描封面：仅在已进入文件夹（漫画层）且当前没在扫描时提供
                    onScanCover = if (state.activeFolder != null && scanProgress == null) {
                        { viewModel.scanCovers() }
                    } else {
                        null
                    },
                    // SY --> 慢速封面4线程：4 路滑动窗口 + 每本间隔，避免 115 风控
                    onScanCoverSlow = if (state.activeFolder != null && scanProgress == null) {
                        { viewModel.scanCovers(slow = true) }
                    } else {
                        null
                    },
                    // SY 2026-10-06：单线程扫描封面：并发压到 1 + 每本 1.5s 喘息，专治被 115 踢下线（最保守档）
                    onScanCoverThreads2 = if (state.activeFolder != null && scanProgress == null) {
                        { viewModel.scanCovers(threads = 1) }
                    } else {
                        null
                    },
                    // SY <--
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            // SY --> 顶栏高度对齐本地图源：去掉顶层状态栏 inset（只保留底部操作栏），
            // 与 BrowseSourceScreen 等 root 推入界面单份 inset 一致，避免被多推一份状态栏高度。
            contentWindowInsets = WindowInsets.navigationBars,
            // SY <--
            // SY --> 底部操作栏：文件夹选择态（隐藏视图）/ 漫画选择态（加入·移出书架）
            bottomBar = {
                when {
                    folderSelectionMode -> {
                        BottomAppBar {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                IconButton(
                                    onClick = { folderSelection = libraryItems.map { it.id }.toSet() },
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbols.Rounded.SelectAll,
                                        contentDescription = stringResource(MR.strings.action_select_all),
                                    )
                                }
                                IconButton(
                                    onClick = { folderSelection = emptySet() },
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbols.Rounded.FlipToBack,
                                        contentDescription = stringResource(MR.strings.action_unselect_all),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val toHide = filteredFolders.filter { it.url.hashCode().toLong() in folderSelection }
                                        toHide.forEach { viewModel.hideFolder(it) }
                                        folderSelection = emptySet()
                                    },
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbols.Rounded.Delete,
                                        contentDescription = stringResource(MR.strings.action_delete),
                                    )
                                }
                            }
                        }
                    }
                    state.selectionMode -> {
                        BottomAppBar {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                IconButton(
                                    onClick = {
                                        scope.launchIO {
                                            val selected = (0 until mangaList.itemCount).mapNotNull { idx ->
                                                mangaList[idx]?.value?.takeIf { it.id in state.selection }
                                            }
                                            selected.forEach { viewModel.addFavorite(it) }
                                            viewModel.clearSelection()
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbols.Rounded.BookmarkAdd,
                                        contentDescription = stringResource(MR.strings.add_to_library),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        scope.launchIO {
                                            val selected = (0 until mangaList.itemCount).mapNotNull { idx ->
                                                mangaList[idx]?.value?.takeIf { it.id in state.selection && it.favorite }
                                            }
                                            selected.forEach { viewModel.changeMangaFavorite(it) }
                                            viewModel.clearSelection()
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbols.Rounded.Delete,
                                        contentDescription = stringResource(MR.strings.remove_from_library),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            // SY <--
        ) { paddingValues ->
            when {
                // SY --> 加载中（源或文件夹尚未就绪）
                state.folders == null && state.activeFolder == null -> {
                    LoadingScreen(Modifier.padding(paddingValues))
                }
                // SY --> 文件夹层：书架网格渲染（支持长按多选隐藏）
                state.folders != null -> {
                    FolderLibraryGrid(
                        items = libraryItems,
                        displayMode = displayMode,
                        columns = columns,
                        contentPadding = paddingValues,
                        selection = folderSelection,
                        onClick = onFolderClick,
                        onLongClick = onFolderLongClick,
                    )
                }
                // SY --> 漫画层（已进入文件夹）：复用书架式 BrowseSourceContent
                else -> {
                    BrowseSourceContent(
                        source = source,
                        mangaList = mangaList,
                        columns = viewModel.getColumnsPreference(configuration.orientation),
                        displayMode = displayMode,
                        snackbarHostState = snackbarHostState,
                        contentPadding = paddingValues,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = onHelpClick,
                        onLocalSourceHelpClick = onHelpClick,
                        onMangaClick = { manga ->
                            if (state.selectionMode) {
                                viewModel.toggleSelection(manga)
                            } else {
                                // SY --> 只有一章的漫画：点一下直接进阅读器（多章的照旧进详情页）
                                navigator.push(
                                    eu.kanade.tachiyomi.ui.manga.MangaScreen(
                                        manga.id,
                                        true,
                                        openDirectlyIfSingleChapter = true,
                                    ),
                                )
                                // SY <--
                            }
                        },
                        onMangaLongClick = { manga ->
                            viewModel.toggleSelection(manga)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        selection = state.selection,
                    )
                }
            }
        }

        // SY --> 书架筛选/排序/显示对话框
        if (showSettingsDialog) {
            LibrarySettingsDialog(
                onDismissRequest = { showSettingsDialog = false },
                viewModel = settingsViewModel,
                category = defaultCategory,
            )
        }
        // SY <--

        // SY --> WebDAV 添加文件夹：复用「同步目标位置」目录树选择器
        if (showAddFolderDialog) {
            CloudSyncDirectoryPicker(
                onDismiss = { showAddFolderDialog = false },
                onPick = { path ->
                    showAddFolderDialog = false
                    viewModel.addFolder(path) { ok ->
                        context.toast(if (ok) MR.strings.action_folder_added else MR.strings.action_folder_add_failed)
                    }
                },
            )
        }
        // SY 2026-10-05：选择要挂载的网盘（仅显示已登录的），确认后进可视化目录选择器
        if (showNetdiskChooser) {
            val nav = LocalNavigator.currentOrThrow
            val netdiskOptions = listOf(
                Triple("115 网盘（开放平台）", Cloud115OpenSource.ID, netdiskPreferences.isCloud115OpenConfigured()),
                Triple("夸克网盘", QuarkSource.ID, netdiskPreferences.isQuarkConfigured()),
                Triple("阿里云盘", AliyunPanSource.ID, true), // 与备份同步共用登录，登录状态进选择器再验证
            )
            AlertDialog(
                onDismissRequest = { showNetdiskChooser = false },
                title = { Text(text = "添加网盘文件夹") },
                text = {
                    Column {
                        netdiskOptions.forEach { (name, id, ready) ->
                            Text(
                                text = if (ready) name else "$name（未登录）",
                                color = if (ready) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = ready) {
                                        showNetdiskChooser = false
                                        nav.push(NetdiskFolderPickerScreen(id))
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                        Text(
                            text = "进入后逐层点选文件夹完成挂载；挂载的文件夹出现在「云盘」列表里，" +
                                "阅读/封面/流式与云盘一致，挂载后自动扫描封面（4线程）",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showNetdiskChooser = false }) { Text(text = "取消") }
                },
            )
        }
        // SY <--
    }
}

/**
 * SY --> 把 [SourceFolder] 包装成书架项 [LibraryItem]，封面走 content:// 通道（书架网格能直接加载）。
 */
private fun SourceFolder.toLibraryItem(sourceId: Long): LibraryItem {
    val id = url.hashCode().toLong()
    val manga = Manga.create().copy(
        id = id,
        source = sourceId,
        favorite = true,
        thumbnailUrl = coverUrl,
        title = name,
    )
    val libraryManga = LibraryManga(
        manga = manga,
        categories = emptyList(),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )
    val isLocal = sourceId == LocalSource.ID
    return LibraryItem(
        libraryManga = libraryManga,
        downloadCount = 0,
        unreadCount = 0,
        isLocal = isLocal,
        sourceName = "",
        sourceLanguage = "",
        badges = LibraryItem.Badges(
            downloadCount = 0,
            unreadCount = 0,
            isLocal = isLocal,
            sourceLanguage = "",
        ),
    )
}

/**
 * SY --> 文件夹书架网格：按书架显示模式分发到 CompactGrid / ComfortableGrid / List。
 */
@Composable
private fun FolderLibraryGrid(
    items: List<LibraryItem>,
    displayMode: LibraryDisplayMode,
    columns: Int,
    contentPadding: PaddingValues,
    selection: Set<Long> = emptySet(),
    onClick: (Long) -> Unit,
    onLongClick: (Long) -> Unit = {},
) {
    val onClickManga: (LibraryManga) -> Unit = { onClick(it.manga.id) }
    val onLongClickManga: (LibraryManga) -> Unit = { onLongClick(it.manga.id) }
    when (displayMode) {
        LibraryDisplayMode.List -> {
            LibraryList(
                items = items,
                contentPadding = contentPadding,
                selection = selection,
                onClick = onClickManga,
                onLongClick = onLongClickManga,
                onClickContinueReading = null,
                searchQuery = null,
                onGlobalSearchClicked = {},
            )
        }
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
            LibraryCompactGrid(
                items = items,
                showTitle = displayMode == LibraryDisplayMode.CompactGrid,
                columns = columns,
                contentPadding = contentPadding,
                selection = selection,
                onClick = onClickManga,
                onLongClick = onLongClickManga,
                onClickContinueReading = null,
                searchQuery = null,
                onGlobalSearchClicked = {},
            )
        }
        LibraryDisplayMode.ComfortableGrid -> {
            LibraryComfortableGrid(
                items = items,
                columns = columns,
                contentPadding = contentPadding,
                selection = selection,
                onClick = onClickManga,
                onLongClick = onLongClickManga,
                onClickContinueReading = null,
                searchQuery = null,
                onGlobalSearchClicked = {},
            )
        }
    }
}

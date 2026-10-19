package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.BrowseSourcePageDialog
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.webdav.LocalWebDavSource
import eu.kanade.tachiyomi.source.webdav.WebDavSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.NewReleases
import mihon.icons.materialsymbols.roundedfilled.Favorite
import mihon.icons.materialsymbols.rounded.BookmarkAdd
import mihon.icons.materialsymbols.rounded.Delete
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import eu.kanade.tachiyomi.source.netdisk.BaseNetdiskSource
import tachiyomi.source.local.LocalSource

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.browse.SourceFoldersContent
import eu.kanade.presentation.more.settings.screen.CloudSyncDirectoryPicker
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        val viewModel =
            assistedMetroViewModel<BrowseSourceViewModel, BrowseSourceViewModel.Factory> {
                create(sourceId = sourceId, listingQuery = listingQuery)
            }
        val state by viewModel.state.collectAsState()

        // SY --> 对齐书架：本地/WebDAV 源使用书架显示模式与长按选择
        val libraryDisplayMode by viewModel.libraryDisplayModeState
        val sourceDisplayMode by viewModel.displayModeState
        val effectiveDisplayMode = if (state.isLibraryLikeSource) libraryDisplayMode else sourceDisplayMode
        val onDisplayModeChange: (LibraryDisplayMode) -> Unit = {
            if (state.isLibraryLikeSource) {
                viewModel.libraryDisplayMode = it
            } else {
                viewModel.displayMode = it
            }
        }
        // SY <--

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            when {
                // SY --> 对齐书架：选择模式下返回键先清除选择
                state.isLibraryLikeSource && state.selectionMode -> viewModel.clearSelection()
                // SY <--
                // SY --> 文件夹视图：在文件夹内返回 = 退回文件夹网格，而不是退出源
                state.activeFolder != null -> viewModel.exitFolder()
                !state.isUserQuery && state.toolbarQuery != null -> viewModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }
        // SY <--

        // SY --> 文件夹视图：系统返回键同样先退回文件夹网格；选择模式下先清选择
        BackHandler(enabled = state.activeFolder != null || (state.isLibraryLikeSource && state.selectionMode)) {
            when {
                state.isLibraryLikeSource && state.selectionMode -> viewModel.clearSelection()
                state.activeFolder != null -> viewModel.exitFolder()
            }
        }
        // SY <--

        val source = state.source
        if (source == null) {
            LoadingScreen()
            return
        }

        if (source is StubSource) {
            MissingSourceScreen(
                source = source,
                navigateUp = navigateUp,
            )
            return
        }

        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }
        // SY --> 跳转到页码对话框的可见状态
        var showPageDialog by remember { mutableStateOf(false) }
        // SY <--

        // SY --> 文件夹视图：添加文件夹
        val context = LocalContext.current
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
                if (source is LocalSource || source is LocalWebDavSource) {
                    addLocalFolderLauncher.launch(null)
                } else if (source is BaseNetdiskSource) {
                    // SY 2026-10-05：网盘源（115/夸克/阿里云盘）——可视化目录选择器，逐层点选，不用填 cid/fid
                    navigator.push(NetdiskFolderPickerScreen(source.id))
                } else {
                    // WebDAV 等源：弹输入框填云端路径
                    showAddFolderDialog = true
                }
            }
        } else {
            null
        }
        // SY <--

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
        val onWebViewClick = f@{
            val httpSource = source as? HttpSource ?: return@f
            navigator.push(
                WebViewScreen(
                    url = httpSource.getHomeUrl(),
                    initialTitle = httpSource.name,
                    sourceId = httpSource.id,
                ),
            )
        }

        LaunchedEffect(source) {
            assistUrl = (source as? HttpSource)?.getHomeUrl()
        }

        // SY --> 对齐书架：把漫画列表提到 Scaffold 外，供底部操作栏读取
        val mangaList = viewModel.mangaPagerFlowFlow.collectAsLazyPagingItems()
        // SY <--

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {},
                ) {
                    BrowseSourceToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = viewModel::setToolbarQuery,
                        source = source,
                        displayMode = effectiveDisplayMode,
                        onDisplayModeChange = onDisplayModeChange,
                        navigateUp = navigateUp,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = onHelpClick,
                        onSettingsClick = { navigator.push(SourcePreferencesScreen(sourceId)) },
                        onSearch = viewModel::search,
                        onAddFolderClick = onAddFolderClick,
                        // SY --> 对齐书架：书架式源显示选择数 + 全选/反选/取消
                        selectionCount = if (state.isLibraryLikeSource) state.selection.size else 0,
                        onClickSelectAll = {
                            val ids = (0 until mangaList.itemCount).mapNotNull { mangaList[it]?.value?.id }.toSet()
                            viewModel.selectAll(ids)
                        },
                        onClickInvertSelection = {
                            val ids = (0 until mangaList.itemCount).mapNotNull { mangaList[it]?.value?.id }.toSet()
                            viewModel.invertSelection(ids)
                        },
                        onClickUnselectAll = viewModel::clearSelection,
                        // SY <--
                    )

                    // SY --> 文件夹视图不显示热门/最新/筛选等漫画列表专用 chip
                    if (state.folders == null) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = MaterialTheme.padding.small),
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        ) {
                        FilterChip(
                            selected = state.listing == Listing.Popular,
                            onClick = {
                                viewModel.resetFilters()
                                viewModel.setListing(Listing.Popular)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = MaterialSymbols.RoundedFilled.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(FilterChipDefaults.IconSize),
                                )
                            },
                            label = {
                                Text(text = stringResource(MR.strings.popular))
                            },
                        )
                        if (source.supportsLatest) {
                            FilterChip(
                                selected = state.listing == Listing.Latest,
                                onClick = {
                                    viewModel.resetFilters()
                                    viewModel.setListing(Listing.Latest)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = MaterialSymbols.Rounded.NewReleases,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.latest))
                                },
                            )
                        }
                        if (state.filters.isNotEmpty()) {
                            FilterChip(
                                selected = state.listing is Listing.Search,
                                onClick = viewModel::openFilterSheet,
                                leadingIcon = {
                                    Icon(
                                        imageVector = MaterialSymbols.Rounded.FilterList,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.action_filter))
                                },
                            )
                        }
                        }
                    }

                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            // SY --> 对齐书架：选择模式底部操作栏
            bottomBar = {
                if (state.isLibraryLikeSource && state.selectionMode) {
                    BottomAppBar {
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
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
            },
            // SY <--
            // SY --> 右下角页码 FAB：点击弹出跳转对话框（文件夹视图不显示）
            floatingActionButton = {
                if (source !is LocalSource && state.folders == null) {
                    SmallFloatingActionButton(
                        onClick = { showPageDialog = true },
                    ) {
                        Text(
                            text = "# ${state.browsePage}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            },
            // SY <--
        ) { paddingValues ->
            // SY --> 文件夹视图：先展示文件夹网格（取内部首图作封面），点进才是漫画列表
            if (state.folders != null) {
                SourceFoldersContent(
                    folders = state.folders!!,
                    contentPadding = paddingValues,
                    onFolderClick = viewModel::enterFolder,
                )
            } else {
                BrowseSourceContent(
                    source = source,
                    mangaList = mangaList,
                    columns = viewModel.getColumnsPreference(LocalConfiguration.current.orientation),
                    displayMode = effectiveDisplayMode,
                    snackbarHostState = snackbarHostState,
                    contentPadding = paddingValues,
                    onWebViewClick = onWebViewClick,
                    onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                    onLocalSourceHelpClick = onHelpClick,
                    onMangaClick = { manga ->
                        if (state.isLibraryLikeSource && state.selectionMode) {
                            viewModel.toggleSelection(manga)
                        } else {
                            navigator.push(MangaScreen(manga.id, true))
                        }
                    },
                    onMangaLongClick = { manga ->
                        if (state.isLibraryLikeSource) {
                            viewModel.toggleSelection(manga)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else {
                            scope.launchIO {
                                val duplicates = viewModel.getDuplicateLibraryManga(manga)
                                when {
                                    manga.favorite -> viewModel.setDialog(BrowseSourceViewModel.Dialog.RemoveManga(manga))
                                    duplicates.isNotEmpty() -> viewModel.setDialog(
                                        BrowseSourceViewModel.Dialog.AddDuplicateManga(manga, duplicates),
                                    )
                                    else -> viewModel.addFavorite(manga)
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                    },
                    // SY --> 滚动联动：把首个可见 manga 回传给 ViewModel 以更新 FAB 页码
                    onVisibleMangaChanged = viewModel::updateVisibleManga,
                    // SY <--
                    // SY --> 对齐书架：长按选择态
                    selection = state.selection,
                    // SY <--
                )
            }
            // SY <--
        }

        val onDismissRequest = { viewModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseSourceViewModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = viewModel::resetFilters,
                    onFilter = { viewModel.search(filters = state.filters) },
                    onUpdate = viewModel::setFilters,
                )
            }
            is BrowseSourceViewModel.Dialog.AddDuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { viewModel.setDialog(BrowseSourceViewModel.Dialog.Migrate(dialog.manga, it)) },
                )
            }

            is BrowseSourceViewModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceViewModel.Dialog.RemoveManga -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.changeMangaFavorite(dialog.manga)
                    },
                    mangaToRemove = dialog.manga,
                )
            }
            is BrowseSourceViewModel.Dialog.ChangeMangaCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        viewModel.changeMangaFavorite(dialog.manga)
                        viewModel.moveMangaToCategories(dialog.manga, include)
                    },
                )
            }
            else -> {}
        }

        // SY --> 跳转到页码对话框
        if (showPageDialog) {
            BrowseSourcePageDialog(
                currentPage = state.browsePage,
                onDismissRequest = { showPageDialog = false },
                onConfirm = viewModel::jumpToPage,
            )
        }
        // SY <--

        // SY --> 添加 WebDAV 文件夹：复用「同步目标位置」的懒加载目录树选择器
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
        // SY <--

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> viewModel.searchGenre(it.txt)
                        is SearchType.Text -> viewModel.search(it.txt)
                    }
                }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}

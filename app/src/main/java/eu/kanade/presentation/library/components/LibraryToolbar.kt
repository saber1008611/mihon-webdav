package eu.kanade.presentation.library.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SearchToolbar
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.FlipToBack
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.SelectAll
import mihon.icons.materialsymbols.rounded.Storage
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

@Composable
fun LibraryToolbar(
    hasActiveFilters: Boolean,
    selectedCount: Int,
    title: LibraryToolbarTitle,
    onClickUnselectAll: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickGlobalUpdate: () -> Unit,
    onClickOpenRandomManga: () -> Unit,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
    // SY --> 书架顶部快捷入口：本地图源 / WebDAV 云盘
    localSource: eu.kanade.tachiyomi.source.Source? = null,
    webDavSource: eu.kanade.tachiyomi.source.Source? = null,
    onClickLocalSource: () -> Unit = {},
    onClickWebDavSource: () -> Unit = {},
    // SY <--
    // SY --> 文件夹书架：返回箭头 + 添加文件夹（仅文件夹书架传入）
    navigateUp: (() -> Unit)? = null,
    onAddFolder: (() -> Unit)? = null,
    // SY 2026-10-19：「添加文件夹」在 WebDAV 文件夹书架里叫「添加 WebDAV 文件夹」
    addFolderIsWebDav: Boolean = false,
    // SY 2026-10-05：添加网盘文件夹（可视化选择器，仅 WebDAV 文件夹书架传入）
    onAddNetdiskFolder: (() -> Unit)? = null,
    // SY --> 扫描封面：批量解析当前文件夹全部漫画的封面（仅文件夹书架传入）
    onScanCover: (() -> Unit)? = null,
    // SY --> 慢速扫描封面：4 线程滑动窗口、每本之间留间隔，专治 115 网盘风控
    onScanCoverSlow: (() -> Unit)? = null,
    // SY 2026-10-06：2 线程扫描封面：并发压到 2 + 更长间隔，专治「被 115 踢下线」
    onScanCoverThreads2: (() -> Unit)? = null,
    // SY <--
) = when {
    selectedCount > 0 -> LibrarySelectionToolbar(
        selectedCount = selectedCount,
        onClickUnselectAll = onClickUnselectAll,
        onClickSelectAll = onClickSelectAll,
        onClickInvertSelection = onClickInvertSelection,
    )
    else -> LibraryRegularToolbar(
        title = title,
        hasFilters = hasActiveFilters,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        onClickFilter = onClickFilter,
        onClickRefresh = onClickRefresh,
        onClickGlobalUpdate = onClickGlobalUpdate,
        onClickOpenRandomManga = onClickOpenRandomManga,
        scrollBehavior = scrollBehavior,
        localSource = localSource,
        webDavSource = webDavSource,
        onClickLocalSource = onClickLocalSource,
        onClickWebDavSource = onClickWebDavSource,
        // SY --> 文件夹书架
        navigateUp = navigateUp,
        onAddFolder = onAddFolder,
        addFolderIsWebDav = addFolderIsWebDav,
        onAddNetdiskFolder = onAddNetdiskFolder,
        onScanCover = onScanCover,
        onScanCoverSlow = onScanCoverSlow,
        onScanCoverThreads2 = onScanCoverThreads2,
        // SY <--
    )
}

@Composable
private fun LibraryRegularToolbar(
    title: LibraryToolbarTitle,
    hasFilters: Boolean,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickGlobalUpdate: () -> Unit,
    onClickOpenRandomManga: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
    localSource: eu.kanade.tachiyomi.source.Source? = null,
    webDavSource: eu.kanade.tachiyomi.source.Source? = null,
    onClickLocalSource: () -> Unit = {},
    onClickWebDavSource: () -> Unit = {},
    // SY --> 文件夹书架：返回箭头 + 添加文件夹
    navigateUp: (() -> Unit)? = null,
    onAddFolder: (() -> Unit)? = null,
    // SY 2026-10-19：WebDAV 文件夹书架的入口文案与本地文件夹区分
    addFolderIsWebDav: Boolean = false,
    onAddNetdiskFolder: (() -> Unit)? = null,
    onScanCover: (() -> Unit)? = null,
    // SY --> 慢速扫描封面（4 线程 + 每本间隔）
    onScanCoverSlow: (() -> Unit)? = null,
    // SY 2026-10-06：2 线程扫描封面（并发 2 + 更长间隔，防 115 踢下线）
    onScanCoverThreads2: (() -> Unit)? = null,
    // SY <--
) {
    val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
    SearchToolbar(
        titleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.text,
                    // SY --> 扫描封面时标题变长（「正在扫描封面 26/180」），默认 22sp 会被截断，缩小 2 号
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    maxLines = 1,
                    modifier = Modifier.weight(1f, false),
                    overflow = TextOverflow.Ellipsis,
                )
                if (title.numberOfManga != null) {
                    Pill(
                        text = "${title.numberOfManga}",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                        fontSize = 14.sp,
                    )
                }
            }
        },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        navigateUp = navigateUp,
        actions = {
            val filterTint = if (hasFilters) MaterialTheme.colorScheme.active else LocalContentColor.current
            AppBarActions(
                listOfNotNull(
                    // SY --> 本地图源 / WebDAV 云盘快捷入口
                    // SY 2026-10-19：图标下方补一行小号说明（本地 / 云盘），提示这两个入口去哪
                    localSource?.let {
                        AppBar.Action(
                            title = it.name,
                            icon = MaterialSymbols.Rounded.Folder,
                            label = stringResource(MR.strings.library_entry_local_hint),
                            onClick = onClickLocalSource,
                        )
                    },
                    webDavSource?.let {
                        AppBar.Action(
                            title = it.name,
                            icon = MaterialSymbols.Rounded.Storage,
                            label = stringResource(MR.strings.library_entry_cloud_hint),
                            onClick = onClickWebDavSource,
                        )
                    },
                    // SY <--
                    // SY --> 文件夹书架：添加文件夹入口（仅文件夹书架传入）
                    onAddFolder?.let {
                        AppBar.OverflowAction(
                            // SY 2026-10-19：WebDAV 文件夹书架显示「添加 WebDAV 文件夹」
                            title = stringResource(
                                if (addFolderIsWebDav) {
                                    MR.strings.action_add_webdav_folder
                                } else {
                                    MR.strings.action_add_folder
                                },
                            ),
                            onClick = it,
                        )
                    },
                    // SY 2026-10-05：添加网盘文件夹 —— 紧挨「添加文件夹」下面，打开可视化目录选择器
                    onAddNetdiskFolder?.let {
                        AppBar.OverflowAction(
                            title = "添加网盘文件夹",
                            onClick = it,
                        )
                    },
                    // SY --> 扫描封面入口（仅文件夹书架传入）
                    onScanCover?.let {
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_scan_covers),
                            onClick = it,
                        )
                    },
                    // SY --> 慢速封面4线程：紧挨「扫描封面」下面，请求摊平防风控
                    onScanCoverSlow?.let {
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_scan_covers_slow),
                            onClick = it,
                        )
                    },
                    // SY 2026-10-06：2 线程扫描封面：紧挨「慢速封面4线程」下面，并发压到 2 防 115 踢下线
                    onScanCoverThreads2?.let {
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_scan_covers_2threads),
                            onClick = it,
                        )
                    },
                    // SY <--
                    AppBar.Action(
                        title = stringResource(MR.strings.action_filter),
                        icon = MaterialSymbols.Rounded.FilterList,
                        iconTint = filterTint,
                        onClick = onClickFilter,
                    ),
                    AppBar.OverflowAction(
                        title = stringResource(MR.strings.action_update_library),
                        onClick = onClickGlobalUpdate,
                    ),
                    AppBar.OverflowAction(
                        title = stringResource(MR.strings.action_update_category),
                        onClick = onClickRefresh,
                    ),
                    AppBar.OverflowAction(
                        title = stringResource(MR.strings.action_open_random_manga),
                        onClick = onClickOpenRandomManga,
                    ),
                ),
            )
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun LibrarySelectionToolbar(
    selectedCount: Int,
    onClickUnselectAll: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
) {
    AppBar(
        titleContent = { Text(text = "$selectedCount") },
        actions = {
            AppBarActions(
                listOf(
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_all),
                        icon = MaterialSymbols.Rounded.SelectAll,
                        onClick = onClickSelectAll,
                    ),
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_inverse),
                        icon = MaterialSymbols.Rounded.FlipToBack,
                        onClick = onClickInvertSelection,
                    ),
                ),
            )
        },
        isActionMode = true,
        onCancelActionMode = onClickUnselectAll,
    )
}

@Immutable
data class LibraryToolbarTitle(
    val text: String,
    val numberOfManga: Int? = null,
)

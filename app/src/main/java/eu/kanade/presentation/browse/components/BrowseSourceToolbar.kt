package eu.kanade.presentation.browse.components

import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.webdav.WebDavSource
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ViewList
import mihon.icons.materialsymbols.rounded.ViewModule
import mihon.icons.materialsymbols.rounded.FlipToBack
import mihon.icons.materialsymbols.rounded.SelectAll
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    source: Source?,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSearch: (String) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    // SY --> 文件夹视图：非 null 时在三点菜单显示「添加文件夹」（仅文件夹视图页传入）
    onAddFolderClick: (() -> Unit)? = null,
    // SY --> 对齐书架：书架式源长按多选时，顶部显示选择数与全选/反选/取消
    selectionCount: Int = 0,
    onClickSelectAll: () -> Unit = {},
    onClickInvertSelection: () -> Unit = {},
    onClickUnselectAll: () -> Unit = {},
    // SY <--
) {
    // Avoid capturing unstable source in actions lambda
    val title = source?.name
    val isLocalSource = source is LocalSource
    // SY 2026-10-19：WebDAV 云盘源的「添加文件夹」改叫「添加 WebDAV 文件夹」
    val isWebDavSource = source is WebDavSource
    val isConfigurableSource = source is ConfigurableSource

    // SY --> 对齐书架：选择模式下使用 action-mode 顶栏（标题显示选择数，提供全选/反选）
    if (selectionCount > 0) {
        AppBar(
            titleContent = { Text(text = "$selectionCount") },
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
            scrollBehavior = scrollBehavior,
        )
        return
    }
    // SY <--

    var selectingDisplayMode by remember { mutableStateOf(false) }

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = { AppBarTitle(title) },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = navigateUp,
        actions = {
            AppBarActions(
                actions = buildList {
                    if (onAddFolderClick != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(
                                    if (isWebDavSource) {
                                        MR.strings.action_add_webdav_folder
                                    } else {
                                        MR.strings.action_add_folder
                                    },
                                ),
                                onClick = onAddFolderClick,
                            ),
                        )
                    }
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_display_mode),
                            icon = if (displayMode == LibraryDisplayMode.List) {
                                MaterialSymbols.AutoMirroredRounded.ViewList
                            } else {
                                MaterialSymbols.Rounded.ViewModule
                            },
                            onClick = { selectingDisplayMode = true },
                        ),
                    )
                    if (isLocalSource) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.label_help),
                                onClick = onHelpClick,
                            ),
                        )
                    } else {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_in_web_view),
                                onClick = onWebViewClick,
                            ),
                        )
                    }
                    if (isConfigurableSource) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_settings),
                                onClick = onSettingsClick,
                            ),
                        )
                    }
                },
            )

            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
            ) {
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CompactGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                    isChecked = displayMode == LibraryDisplayMode.List,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.List)
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

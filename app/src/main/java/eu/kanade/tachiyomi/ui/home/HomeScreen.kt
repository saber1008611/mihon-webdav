package eu.kanade.tachiyomi.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabNavigator
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource

object HomeScreen : Screen() {

    private val librarySearchEvent = Channel<String>()
    private val openTabEvent = Channel<Tab>()
    private val showBottomNavEvent = Channel<Boolean>()

    @Suppress("ConstPropertyName")
    private const val TabFadeDuration = 200

    @Suppress("ConstPropertyName")
    private const val TabNavigatorKey = "HomeTabs"

    private val TABS = listOf(
        LibraryTab,
        UpdatesTab,
        HistoryTab,
        BrowseTab,
        MoreTab,
    )

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        TabNavigator(
            tab = LibraryTab,
            key = TabNavigatorKey,
        ) { tabNavigator ->
            // Provide usable navigator to content screen
            CompositionLocalProvider(LocalNavigator provides navigator) {
                val tabletUi = isTabletUi()
                val navigationSuiteType = if (tabletUi) {
                    NavigationSuiteType.NavigationRail
                } else {
                    NavigationSuiteType.NavigationBar
                }
                val navigationSuiteState = rememberNavigationSuiteScaffoldState()
                LaunchedEffect(navigationSuiteState, tabletUi) {
                    if (tabletUi) navigationSuiteState.show()
                    showBottomNavEvent.receiveAsFlow().collectLatest { show ->
                        if (tabletUi || show) {
                            navigationSuiteState.show()
                        } else {
                            navigationSuiteState.hide()
                        }
                    }
                }

                val isNavVisible = navigationSuiteState.currentValue == NavigationSuiteScaffoldValue.Visible

                val scope = rememberCoroutineScope()

                // SY --> 2026-10-03 灰调外框
                // 顶栏总高 = 状态栏 inset + Material3 TopAppBar 的 64dp。
                // 内容块的四角（手机）与内拐角（平板）靠同色小方块「收」出圆角，
                // 需要知道顶栏底边落在哪一行，所以在这里算一次。
                val chromeColor = MaterialTheme.colorScheme.surfaceContainer
                val cornerSize = 9.dp
                val topBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
                // SY <--

                val tabContent: @Composable () -> Unit = {
                    AnimatedContent(
                        targetState = tabNavigator.current,
                        transitionSpec = {
                            materialFadeThroughIn(
                                initialScale = 1f,
                                durationMillis = TabFadeDuration,
                            ) togetherWith materialFadeThroughOut(durationMillis = TabFadeDuration)
                        },
                        label = "tabContent",
                    ) {
                        tabNavigator.saveableState(key = "currentTab", it) {
                            it.Content()
                        }
                    }
                }

                // SY --> 2026-09-25: move navigation rail to the right side on tablet UI
                if (navigationSuiteType == NavigationSuiteType.NavigationBar) {
                    Scaffold(
                        // SY --> 顶栏高度对齐：去掉顶层状态栏 inset（只保留底部导航栏），让各 tab 的顶栏从屏幕顶部起算，
                        // 与本地图源等 root 推入界面单份 inset 一致（避免 tab 内容盒被多推一份状态栏高度）。
                        contentWindowInsets = WindowInsets.navigationBars,
                        // SY <--
                        bottomBar = {
                            AnimatedVisibility(
                                visible = isNavVisible,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                // SY --> 2026-10-03 灰调外框：底部栏 80 → 76dp，底色与页底同为灰
                                NavigationBar(
                                    modifier = Modifier.height(76.dp),
                                    containerColor = chromeColor,
                                ) {
                                    TABS.fastForEach { tab ->
                                        val selected = tabNavigator.current::class == tab::class
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = {
                                                if (!selected) {
                                                    tabNavigator.current = tab
                                                } else {
                                                    scope.launch { tab.onReselect(navigator) }
                                                }
                                            },
                                            icon = { NavIcon(tab) },
                                            label = { NavLabel(tab) },
                                        )
                                    }
                                }
                                // SY <--
                            }
                        },
                    ) { paddingValues ->
                        Box(modifier = Modifier.padding(paddingValues)) {
                            tabContent()
                            // SY --> 内容块四角：同色弧形楔块，把方角「收」成 R9（外圈仍是灰）
                            CornerFillet(
                                color = chromeColor,
                                radius = cornerSize,
                                scaleX = -1f,
                                modifier = Modifier.align(Alignment.TopStart).offset(y = topBarHeight),
                            )
                            CornerFillet(
                                color = chromeColor,
                                radius = cornerSize,
                                modifier = Modifier.align(Alignment.TopEnd).offset(y = topBarHeight),
                            )
                            CornerFillet(
                                color = chromeColor,
                                radius = cornerSize,
                                scaleX = -1f,
                                scaleY = -1f,
                                modifier = Modifier.align(Alignment.BottomStart),
                            )
                            CornerFillet(
                                color = chromeColor,
                                radius = cornerSize,
                                scaleY = -1f,
                                modifier = Modifier.align(Alignment.BottomEnd),
                            )
                            // SY <--
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            tabContent()
                        }
                        AnimatedVisibility(
                            visible = isNavVisible,
                            enter = expandHorizontally() + fadeIn(),
                            exit = shrinkHorizontally() + fadeOut(),
                        ) {
                            // SY --> 2026-10-03 灰调外框：窄侧栏（40 → 42dp）+ 图标圆角方片
                            Box(modifier = Modifier.fillMaxHeight()) {
                                // 内拐角：内容块右上角用同色小方块「收」出 R9，灰在外圈包住它
                                CornerFillet(
                                    color = chromeColor,
                                    radius = cornerSize,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .offset(x = -cornerSize, y = topBarHeight),
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(42.dp)
                                        .background(chromeColor)
                                        .padding(vertical = 12.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    TABS.fastForEachIndexed { index, tab ->
                                        val selected = tabNavigator.current::class == tab::class
                                        if (index == TABS.lastIndex) {
                                            // 主分组与「更多」工具组之间的分割线
                                            Box(
                                                modifier = Modifier
                                                    .padding(vertical = 8.dp)
                                                    .size(width = 22.dp, height = 1.dp)
                                                    .background(MaterialTheme.colorScheme.outlineVariant),
                                            )
                                        }
                                        Column(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable(
                                                    onClick = {
                                                        if (!selected) {
                                                            tabNavigator.current = tab
                                                        } else {
                                                            scope.launch { tab.onReselect(navigator) }
                                                        }
                                                    },
                                                )
                                                .padding(vertical = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .clip(RoundedCornerShape(9.dp))
                                                    .background(
                                                        if (selected) {
                                                            MaterialTheme.colorScheme.secondaryContainer
                                                        } else {
                                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                                        },
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                NavIcon(tab, size = 18.dp)
                                            }
                                            NavLabel(tab)
                                        }
                                    }
                                }
                            }
                            // SY <-- 2026-10-03
                        }
                    }
                }
                // SY <-- 2026-09-25
            }

            val goToLibraryTab = { tabNavigator.current = LibraryTab }

            BackHandler(enabled = tabNavigator.current != LibraryTab, onBack = goToLibraryTab)

            LaunchedEffect(Unit) {
                launch {
                    librarySearchEvent.receiveAsFlow().collectLatest {
                        goToLibraryTab()
                        LibraryTab.search(it)
                    }
                }
                launch {
                    openTabEvent.receiveAsFlow().collectLatest {
                        tabNavigator.current = when (it) {
                            is Tab.Library -> LibraryTab
                            Tab.Updates -> UpdatesTab
                            Tab.History -> HistoryTab
                            is Tab.Browse -> {
                                if (it.toExtensions) {
                                    BrowseTab.showExtension()
                                }
                                BrowseTab
                            }
                            is Tab.More -> MoreTab
                        }

                        if (it is Tab.Library && it.mangaIdToOpen != null) {
                            navigator.push(MangaScreen(it.mangaIdToOpen))
                        }
                        if (it is Tab.More && it.toDownloads) {
                            navigator.push(DownloadQueueScreen)
                        }
                    }
                }
            }
        }
    }

    /**
     * 内容块转角处的「弧形楔块」：在方角上补一块同色的圆弧，让方角看起来变成圆角。
     * 基形针对方块的**右上角**（灰在右上、浅色在左下），其它三个角用 [scaleX]/[scaleY] 取负镜像。
     *
     * ⚠️ 不能用 `RoundedCornerShape`：弧要正好是「正方形的 1/4 圆」，而 Compose 会把
     * 圆角半径钳到边长的一半，形状会退化成几乎完整的方块 —— 看起来就是「凸出来一块」。
     */
    @Composable
    private fun CornerFillet(
        color: Color,
        radius: Dp,
        modifier: Modifier = Modifier,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
    ) {
        Box(
            modifier = modifier
                .size(radius)
                .scale(scaleX, scaleY)
                .drawBehind {
                    val r = size.width
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(r, 0f)
                        lineTo(r, r)
                        // ⚠️ 圆心必须落在方块的**对角**（左下 (0, r)），弧从 (r, r) 逆时针扫到 (0, 0)。
                        // 这样弧朝外鼓向方角，浅色块才是「圆角」；
                        // 若把圆心放在方角本身，弧会朝内凹，浅色块仍是方角、只在角上多一小块灰 —— 就是「凸出来」的样子。
                        arcTo(Rect(-r, 0f, r, 2f * r), 0f, -90f, false)
                        close()
                    }
                    drawPath(path, color)
                },
        )
    }

    @Composable
    private fun NavIcon(tab: eu.kanade.presentation.util.Tab, size: Dp = 24.dp) {
        val badge = tabBadge(tab)
        if (badge != null) {
            BadgedBox(badge = { badge() }) {
                Icon(
                    painter = tab.options.icon!!,
                    contentDescription = tab.options.title,
                    modifier = Modifier.size(size),
                )
            }
        } else {
            Icon(
                painter = tab.options.icon!!,
                contentDescription = tab.options.title,
                modifier = Modifier.size(size),
            )
        }
    }

    @Composable
    private fun NavLabel(tab: eu.kanade.presentation.util.Tab) {
        Text(
            text = tab.options.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    @Composable
    private fun tabBadge(tab: eu.kanade.presentation.util.Tab): (@Composable () -> Unit)? {
        val context = LocalContext.current
        val count by produceState(initialValue = 0, tab) {
            val graph = context.appGraph
            when (tab) {
                is UpdatesTab -> {
                    combine(
                        graph.libraryPreferences.newShowUpdatesCount.changes(),
                        graph.libraryPreferences.newUpdatesCount.changes(),
                    ) { show, count ->
                        if (show) count else 0
                    }
                        .collectLatest { value = it }
                }

                is BrowseTab -> {
                    graph.sourcePreferences.extensionUpdatesCount.changes()
                        .collectLatest { value = it }
                }

                else -> value = 0
            }
        }
        if (count <= 0) return null
        return {
            Badge {
                val desc = when (tab) {
                    is UpdatesTab -> pluralStringResource(
                        MR.plurals.notification_chapters_generic,
                        count = count,
                        count,
                    )

                    is BrowseTab -> pluralStringResource(
                        MR.plurals.update_check_notification_ext_updates,
                        count = count,
                        count,
                    )

                    else -> null
                }
                Text(
                    text = count.toString(),
                    modifier = Modifier.semantics {
                        if (desc != null) contentDescription = desc
                    },
                )
            }
        }
    }

    suspend fun search(query: String) {
        librarySearchEvent.send(query)
    }

    suspend fun openTab(tab: Tab) {
        openTabEvent.send(tab)
    }

    suspend fun showBottomNav(show: Boolean) {
        showBottomNavEvent.send(show)
    }

    sealed interface Tab {
        data class Library(val mangaIdToOpen: Long? = null) : Tab
        data object Updates : Tab
        data object History : Tab
        data class Browse(val toExtensions: Boolean = false) : Tab
        data class More(val toDownloads: Boolean) : Tab
    }
}

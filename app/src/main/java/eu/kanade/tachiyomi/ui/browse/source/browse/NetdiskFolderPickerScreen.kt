package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.source.netdisk.BaseNetdiskSource
import eu.kanade.tachiyomi.source.webdav.WebDavSource
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Folder
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * SY 2026-10-05：「添加网盘文件夹」可视化选择器 —— **点选目录，不用填 cid/fid**。
 *
 * 进入后列出该网盘当前目录的子文件夹，点击进入、可逐级返回，右上角「选择此目录」
 * 把当前目录挂载进该网盘源的文件夹视图（与 WebDAV 的「添加文件夹」同一套偏好语义）。
 * 根目录本身不需要选择（登录后自动挂载）。
 */
class NetdiskFolderPickerScreen(val sourceId: Long) : eu.kanade.presentation.util.Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val navigator = LocalNavigator.currentOrThrow
        val sourceManager = remember { context.appGraph.sourceManager }

        var source by remember { mutableStateOf<BaseNetdiskSource?>(null) }
        var webdav by remember { mutableStateOf<WebDavSource?>(null) }
        var loadError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(sourceId) {
            runCatching { sourceManager.getOrStub(sourceId) }
                .onSuccess { source = it as? BaseNetdiskSource }
                .onFailure { loadError = it.message }
            // 挂载目标 = 「云盘」源（网盘目录并入云盘文件夹视图，共用其封面/加载/流式规则）
            runCatching { sourceManager.getOrStub(WebDavSource.ID) }
                .onSuccess { webdav = it as? WebDavSource }
        }

        // 目录栈（不含根）：当前路径 = 栈顶 id，空栈 = 根目录
        var pathStack by remember { mutableStateOf(listOf<Pair<String, String>>()) }
        var folders by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
        var adding by remember { mutableStateOf(false) }

        val currentPath = pathStack.lastOrNull()?.first.orEmpty()
        val currentName = pathStack.lastOrNull()?.second ?: "根目录"

        LaunchedEffect(source, currentPath) {
            val src = source ?: return@LaunchedEffect
            folders = null
            folders = src.browseFolders(currentPath)
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = "${source?.name ?: "网盘"}：$currentName",
                    navigateUp = { navigator.pop() },
                )
            },
        ) { paddingValues ->
            val src = source
            when {
                src == null -> LoadingScreen()
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    // 「选择此目录」：仅子目录层提供（根目录登录后自动挂载）
                    if (pathStack.isNotEmpty()) {
                        Text(
                            text = if (adding) "正在添加…" else "把「$currentName」添加到 ${src.name}",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !adding) {
                                    adding = true
                                    scope.launch {
                                        // SY 2026-10-06：挂载进「云盘」源（@routeKey:id 前缀路由），
                                        // 之后共用云盘的封面/加载/流式读取规则。
                                        val ok = webdav?.let { w ->
                                            runCatching { w.addNetdiskMount(src.routeKey, currentPath, currentName) }
                                                .getOrDefault(false)
                                        } == true
                                        adding = false
                                        if (ok) {
                                            context.toast("已添加「$currentName」到云盘，正在扫描封面（4线程）…")
                                            // 默认封面规则：4 线程扫描该目录（后台跑，不阻塞返回）
                                            val route = "@" + src.routeKey + ":" + currentPath
                                            scope.launch { runCatching { webdav?.scanNetdiskFolderCovers(route) } }
                                            navigator.pop()
                                        } else {
                                            // addNetdiskMount 返回 false 的唯一原因是「挂载记录已存在」
                                            // —— 属正常重复添加，中性提示并返回（列表里已可见）。
                                            context.toast("「$currentName」已经在云盘里了，返回列表即可看到")
                                            navigator.pop()
                                        }
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                        HorizontalDivider()
                    }

                    val list = folders
                    if (list == null) {
                        LoadingScreen()
                        return@Column
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (pathStack.isNotEmpty()) {
                            item(key = "__up__") {
                                FolderRow(
                                    title = "返回上一级（${pathStack.dropLast(1).lastOrNull()?.second ?: "根目录"}）",
                                    onClick = { pathStack = pathStack.dropLast(1) },
                                )
                            }
                        }
                        items(list, key = { it.first }) { (id, name) ->
                            FolderRow(
                                title = name,
                                onClick = {
                                    // 进入下一层：登记目录名（显示名缓存，文件夹视图直接可读）
                                    src.rememberDirName(id, name)
                                    pathStack = pathStack + (id to name)
                                },
                            )
                        }
                        if (list.isEmpty() && pathStack.isNotEmpty()) {
                            item(key = "__empty__") {
                                Text(
                                    text = "此目录下没有子文件夹",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        loadError?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    @Composable
    private fun FolderRow(title: String, onClick: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Folder,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        HorizontalDivider()
    }
}

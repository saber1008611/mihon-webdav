package eu.kanade.presentation.more.settings.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.sync.service.AliyunPanSyncService
import eu.kanade.tachiyomi.source.netdisk.cloud115open.Cloud115OpenApi
import eu.kanade.tachiyomi.ui.setting.track.AliyunPanQrLoginActivity
import eu.kanade.tachiyomi.ui.setting.track.QuarkQrLoginActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * SY 2026-10-05：一级设置项「网盘」——把**所有网盘相关入口归到同一屏**（用户要求）：
 *
 *  - 「网盘源（阅读）」：115 / 夸克 / 阿里云盘的登录、Cookie、测试连接（本屏实现，已从
 *    「数据与备份」整体移入）；
 *  - 「WebDAV」：登录/目标目录/上传选项的实现仍在「下载」设置里（`getCloudSyncGroup` 及其
 *    登录对话框是私有组件，无损搬运成本高），这里放一个直达入口；
 *  - 「备份同步（阿里云盘）」：同上，实现仍在「数据与备份」，放直达入口。
 *
 * 阿里云盘**阅读源**与备份同步**共用登录**：任一边扫码成功，另一边立即可用。
 */
object SettingsNetdiskScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_netdisk

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        return listOf(
            getNetdiskSourceGroup(),
            Preference.PreferenceGroup(
                title = "WebDAV（网盘同步）",
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        // SY 2026-10-19：「云盘同步」已按用户要求改名为「WebDAV 登录」
                        title = stringResource(MR.strings.cloud_sync),
                        subtitle = "WebDAV 登录、目标目录、上传后删除本地、并发上传数（在「下载」设置中配置）",
                        onClick = { navigator.push(SettingsDownloadScreen) },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "备份同步（阿里云盘）",
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = "前往备份同步设置",
                        subtitle = "同步服务（阿里云盘）、备份范围、自动同步（在「数据与备份」中配置）",
                        onClick = { navigator.push(SettingsDataScreen) },
                    ),
                ),
            ),
            Preference.PreferenceItem.InfoPreference(
                "阅读图源（115 网盘 / 夸克网盘 / 阿里云盘）在「浏览 → 图源」里出现；" +
                    "进图源后点「添加文件夹」可挂载更多目录（阿里云盘支持直接填目录名）。",
            ),
        )
    }

    /**
     * 网盘源（阅读）组 —— 自 SettingsDataScreen 移入（2026-10-05），并补上
     * 夸克扫码登录与阿里云盘（阅读源）条目。
     */
    @Composable
    private fun getNetdiskSourceGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val netdiskPreferences = remember { context.appGraph.netdiskPreferences }
        val quarkApi = remember { context.appGraph.quarkApi }
        val quarkSource = remember { context.appGraph.quarkSource }
        val aliyunPanService = remember { context.appGraph.aliyunPanService }
        val aliyunSyncService = remember { context.appGraph.aliyunPanSyncService }
        val aliyunPanSource = remember { context.appGraph.aliyunPanSource }
        val syncPreferences = remember { context.appGraph.syncPreferences }

        val quarkCookie = remember { netdiskPreferences.quarkCookie }
        // SY 2026-10-08：115 开放平台（OAuth）令牌对
        val cloud115OpenApi = remember { context.appGraph.cloud115OpenApi }
        val cloud115OpenAccessToken = remember { netdiskPreferences.cloud115OpenAccessToken }
        val cloud115OpenRefreshToken = remember { netdiskPreferences.cloud115OpenRefreshToken }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_netdisk) + "（阅读）",
            preferenceItems = listOf(
                Preference.PreferenceItem.InfoPreference(
                    "登录后图源会出现在「浏览 → 图源」，浏览与阅读体验和 WebDAV 文件夹一致。" +
                        "阿里云盘与下方「备份同步」共用登录。",
                ),
                // ===================== 115 开放平台（OAuth，SY 2026-10-08） =====================
                Preference.PreferenceItem.InfoPreference(
                    "115 开放平台 = 官方 OAuth 授权链路（与上面的 Cookie 模式双轨并存，可同时配置）。" +
                        "点「获取令牌」打开 api.oplist.org：下拉选「115 验证网盘」→ 勾选「使用 OpenList 提供的参数」→ " +
                        "115 App 扫码授权 → 把得到的访问令牌和刷新令牌分别粘贴到下面两项。",
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "115 开放平台：获取令牌",
                    subtitle = "打开 api.oplist.org（OpenList 官方令牌工具页）",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(Intent.ACTION_VIEW, Uri.parse(Cloud115OpenApi.TOKEN_HELPER_URL)),
                            )
                        }.onFailure { context.toast("未找到可打开网页的应用") }
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    title = "115 开放平台：访问令牌（Access Token）",
                    subtitle = if (cloud115OpenAccessToken.get().isNotBlank()) "已配置" else "未配置（oplist.org 获取后粘贴）",
                    preference = cloud115OpenAccessToken,
                    onValueChanged = { newValue ->
                        scope.launch {
                            cloud115OpenAccessToken.set(newValue.trim())
                            if (newValue.trim().isNotBlank()) cloud115OpenApi.invalidateList()
                        }
                        true
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    title = "115 开放平台：刷新令牌（Refresh Token）",
                    subtitle = if (cloud115OpenRefreshToken.get().isNotBlank()) "已配置（过期会自动刷新）" else "未配置（oplist.org 获取后粘贴）",
                    preference = cloud115OpenRefreshToken,
                    onValueChanged = { newValue ->
                        scope.launch {
                            cloud115OpenRefreshToken.set(newValue.trim())
                            if (newValue.trim().isNotBlank()) cloud115OpenApi.invalidateList()
                        }
                        true
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "115 开放平台：测试连接",
                    subtitle = "注意：每个账号在同一应用最多 2 个刷新令牌，第 3 次获取会顶掉最早的",
                    onClick = {
                        scope.launch {
                            val ok = runCatching { cloud115OpenApi.check() }.isSuccess
                            context.toast(if (ok) "115 开放平台连接成功" else "115 开放平台连接失败（令牌可能无效）")
                        }
                    },
                ),
                // ===================== 夸克 =====================
                Preference.PreferenceItem.TextPreference(
                    title = "夸克网盘：扫码登录",
                    subtitle = "用夸克手机 App 扫码确认，Cookie 自动保存，无需手动复制",
                    onClick = { context.startActivity(QuarkQrLoginActivity.newIntent(context)) },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    title = "夸克网盘：Cookie",
                    subtitle = if (quarkCookie.get().isNotBlank()) {
                        "已配置（也可手动粘贴 pan.quark.cn 的 Cookie）"
                    } else {
                        "未配置"
                    },
                    preference = quarkCookie,
                    onValueChanged = { newValue ->
                        scope.launch {
                            val trimmed = newValue.trim()
                            quarkCookie.set(trimmed)
                            quarkApi.resetSession()
                        }
                        true
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "夸克网盘：测试连接",
                    onClick = {
                        scope.launch {
                            val ok = runCatching { quarkApi.check() }.isSuccess
                            context.toast(if (ok) "夸克连接成功" else "夸克连接失败（Cookie 可能已失效）")
                        }
                    },
                ),
                // ===================== 阿里云盘（阅读源） =====================
                Preference.PreferenceItem.TextPreference(
                    title = "阿里云盘：扫码登录",
                    subtitle = if (syncPreferences.aliyunPanRefreshToken.get().isNotBlank()) {
                        "已登录（与「备份同步」共用；可重新扫码切换账号）"
                    } else {
                        "未登录"
                    },
                    onClick = { context.startActivity(AliyunPanQrLoginActivity.newIntent(context)) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "阿里云盘：测试连接",
                    onClick = {
                        scope.launch {
                            val result = aliyunSyncService.testConnection()
                            when (result) {
                                AliyunPanSyncService.ConnectionTestStatus.SUCCESS ->
                                    context.toast("阿里云盘连接成功")
                                AliyunPanSyncService.ConnectionTestStatus.NOT_CONFIGURED ->
                                    context.toast("阿里云盘未登录")
                                else -> context.toast("阿里云盘连接失败（登录可能已过期）")
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    "阿里云盘阅读源与「备份同步」共用登录：下方同步服务里登录后，" +
                    "图源「阿里云盘」立即可浏览（挂载点在图源内「添加文件夹」填目录名或 fileId）。",
                ),
            ),
        )
    }
}

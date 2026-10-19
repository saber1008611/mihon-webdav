package eu.kanade.tachiyomi.ui.setting.track

import android.content.Context
import android.content.Intent
import android.os.Bundle
import eu.kanade.presentation.sync.QuarkQrLoginScreen
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import mihon.app.di.appGraph

/**
 * SY --> 夸克网盘「扫码登录」：手机夸克 App 扫码确认后换取 Cookie 存进
 * [NetdiskPreferences][eu.kanade.tachiyomi.source.netdisk.NetdiskPreferences]，
 * 免去手动 F12 复制 Cookie。
 */
class QuarkQrLoginActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = appGraph.netdiskPreferences
        val quarkApi = appGraph.quarkApi
        val quarkSource = appGraph.quarkSource

        setComposeContent {
            QuarkQrLoginScreen(
                onUp = { finish() },
                onLoginSuccess = { cookie ->
                    preferences.quarkCookie.set(cookie)
                    // Cookie 换新后作废会话缓存（列表 / 直链 / 名字缓存全部重置）。
                    quarkApi.resetSession()
                    quarkSource.clearNameCaches()
                    setResult(RESULT_OK)
                    finish()
                },
            )
        }
    }

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, QuarkQrLoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}

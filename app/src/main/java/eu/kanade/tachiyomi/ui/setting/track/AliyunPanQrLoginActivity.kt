package eu.kanade.tachiyomi.ui.setting.track

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import eu.kanade.presentation.sync.AliyunPanQrLoginScreen
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import mihon.app.di.appGraph
import tachiyomi.i18n.MR

/**
 * QR-code login for Aliyun Pan (阿里云盘): shows a QR code that the user scans with the
 * Aliyun Drive mobile app. The obtained refresh token is stored in SyncPreferences.
 */
class AliyunPanQrLoginActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val syncPreferences = appGraph.syncPreferences
        val aliyunPanService = appGraph.aliyunPanService

        setComposeContent {
            AliyunPanQrLoginScreen(
                onUp = { finish() },
                onLoginSuccess = { refreshToken ->
                    syncPreferences.aliyunPanRefreshToken.set(refreshToken)
                    // Force a fresh access token for the newly logged-in account.
                    syncPreferences.aliyunPanAccessToken.set("")
                    syncPreferences.aliyunPanTokenExpireTime.set(0L)
                    aliyunPanService.resetSession()
                    toast(MR.strings.aliyun_pan_login_success, Toast.LENGTH_LONG)
                    setResult(RESULT_OK)
                    finish()
                },
            )
        }
    }

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, AliyunPanQrLoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}

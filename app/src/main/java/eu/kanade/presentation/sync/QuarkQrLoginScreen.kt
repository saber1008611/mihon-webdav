package eu.kanade.presentation.sync

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.source.netdisk.quark.QuarkApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import mihon.app.di.appGraph
import tachiyomi.presentation.core.components.material.Scaffold

/**
 * SY --> 夸克网盘「扫码登录」屏：手机夸克 App 扫码确认后换取 Cookie 交给 [onLoginSuccess]，
 * 免去手动 F12 复制 Cookie。
 */
@Composable
fun QuarkQrLoginScreen(
    onUp: () -> Unit,
    onLoginSuccess: (cookie: String) -> Unit,
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var statusText by remember { mutableStateOf("正在获取二维码…") }
    var errorText by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val api = remember { context.appGraph.quarkApi }

    Scaffold(
        topBar = {
            AppBar(
                title = "夸克网盘登录",
                navigateUp = onUp,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            qrBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "夸克登录二维码",
                    modifier = Modifier
                        .size(280.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
            } ?: CircularProgressIndicator()

            Spacer(Modifier.height(24.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "用「夸克」手机 App 扫码并确认登录；成功后 Cookie 自动保存，无需手动复制。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            errorText?.let { err ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val payload = api.webQrStart()
            qrBitmap = generateQrCodeBitmap(payload.qrcode)
            statusText = "等待扫码…"
            while (isActive) {
                delay(2000)
                when (api.webQrPoll(payload.token, payload.requestId)) {
                    2 -> {
                        statusText = "登录成功，正在换取 Cookie…"
                        val cookie = api.webQrCookie(payload.token, payload.requestId)
                        onLoginSuccess(cookie)
                        return@LaunchedEffect
                    }
                    -1 -> {
                        errorText = "二维码已失效或登录被取消，请重新进入本页"
                        return@LaunchedEffect
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorText = e.message ?: "扫码登录失败"
        }
    }
}

private fun generateQrCodeBitmap(content: String, size: Int = 512): Bitmap {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
        content,
        com.google.zxing.BarcodeFormat.QR_CODE,
        size,
        size,
    )
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bmp
}

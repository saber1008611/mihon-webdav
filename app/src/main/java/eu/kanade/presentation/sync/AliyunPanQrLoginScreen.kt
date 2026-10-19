package eu.kanade.presentation.sync

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.data.sync.service.AliyunPanService
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * QR-code login screen for Aliyun Pan (阿里云盘). The user scans the QR code with the Aliyun Drive
 * mobile app, the screen polls for the result and reports the obtained refresh token.
 */
@Composable
fun AliyunPanQrLoginScreen(
    onUp: () -> Unit,
    onLoginSuccess: (refreshToken: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aliyunPanService = remember { context.appGraph.aliyunPanService }

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var statusText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    var done by remember { mutableStateOf(false) }

    fun startQrLogin() {
        errorText = null
        done = false
        pollJob?.cancel()
        pollJob = scope.launch {
            try {
                val info = aliyunPanService.generateQrLogin()
                qrBitmap = generateQrCodeBitmap(info.codeContent)
                statusText = context.stringResource(MR.strings.aliyun_pan_qr_waiting)
                while (isActive && !done) {
                    delay(2500)
                    val result = aliyunPanService.queryQrLogin(info.ck, info.t)
                    when (result.status) {
                        "SCANED" -> statusText = context.stringResource(MR.strings.aliyun_pan_qr_scanned)
                        "CONFIRMED" -> {
                            val token = result.refreshToken
                            if (token.isNullOrBlank()) {
                                errorText = context.stringResource(MR.strings.aliyun_pan_qr_failed)
                            } else {
                                done = true
                                onLoginSuccess(token)
                            }
                            return@launch
                        }
                        "EXPIRED", "CANCELED" -> {
                            errorText = context.stringResource(MR.strings.aliyun_pan_qr_expired)
                            return@launch
                        }
                        else -> statusText = context.stringResource(MR.strings.aliyun_pan_qr_waiting)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorText = e.message
            }
        }
    }

    fun saveQrToGallery() {
        val bitmap = qrBitmap ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "aliyun_pan_qr_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MIhonW")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    context.toast(MR.strings.aliyun_pan_qr_save_failed)
                    return
                }
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.insertImage(
                    context.contentResolver,
                    bitmap,
                    "aliyun_pan_qr",
                    "Aliyun Pan QR login code",
                )
            }
            context.toast(MR.strings.aliyun_pan_qr_saved)
        } catch (e: Exception) {
            logcat(tag = "AliyunPanLogin", priority = LogPriority.ERROR) {
                "Failed to save QR code to gallery: ${e.message}"
            }
            context.toast(MR.strings.aliyun_pan_qr_save_failed)
        }
    }

    DisposableEffect(Unit) {
        onDispose { pollJob?.cancel() }
    }

    LaunchedEffect(Unit) {
        startQrLogin()
    }

    Scaffold(
        topBar = {
            AppBar(
                title = stringResource(MR.strings.pref_aliyun_pan_sign_in),
                navigateUp = onUp,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val bitmap = qrBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = statusText.ifEmpty { " " },
                style = MaterialTheme.typography.bodyMedium,
            )

            errorText?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onUp) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
                if (qrBitmap != null) {
                    Button(onClick = ::saveQrToGallery) {
                        Text(text = stringResource(MR.strings.aliyun_pan_qr_save))
                    }
                }
                Button(onClick = ::startQrLogin) {
                    Text(text = stringResource(MR.strings.aliyun_pan_qr_refresh))
                }
            }
        }
    }
}

private fun generateQrCodeBitmap(content: String, size: Int = 512): Bitmap {
    val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (bitMatrix[x, y]) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

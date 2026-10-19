package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import logcat.LogPriority
import logcat.logcat
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import tachiyomi.core.common.util.lang.withIOContext
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Client for the Aliyun Pan (阿里云盘) Web API.
 *
 * The API surface mirrors the `aliyunpan_web` package of
 * https://github.com/tickstep/aliyunpan-api:
 *  - refresh a browser-obtained `refresh_token` into an `access_token`
 *  - create a device session whose requests are signed with a secp256k1 ECDSA key pair
 *  - list / create / upload / download / delete files
 */
@Inject
@SingleIn(AppScope::class)
class AliyunPanService(
    private val context: Context,
    private val syncPreferences: SyncPreferences,
) {

    companion object {
        // Same client identifiers as the reference aliyunpan-api implementation.
        private const val APP_ID = "25dzX3vbYqktVxyX"
        private const val API_ID = "pJZInNHN2dZWk8qg"

        private const val AUTH_URL = "https://auth.aliyundrive.com"
        private const val API_URL = "https://api.aliyundrive.com"
        private const val USER_URL = "https://user.aliyundrive.com"
        private const val PASSPORT_URL = "https://passport.aliyundrive.com"

        private const val WEB_URL = "https://www.aliyundrive.com"
        private const val WEB_REFERER = "$WEB_URL/"
        private const val ROOT_PARENT_FILE_ID = "root"
        private const val DEVICE_NAME = "TachiyomiX"
        private const val MODEL_NAME = "Android"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val DEFAULT_CHUNK_SIZE = 10L * 1024 * 1024
        private const val MAX_LIST_PAGES = 20
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var sessionSignature: String? = null

    /**
     * Writes a diagnostic line to the app's external files dir so sync/upload failures can be
     * inspected via adb even when logcat is restricted (e.g. on ColorOS/OPPO devices).
     */
    private fun debugLog(message: String) {
        try {
            val file = File(context.getExternalFilesDir(null), "aliyun_sync_debug.log")
            file.appendText("${System.currentTimeMillis()}: $message\n")
        } catch (_: Exception) {
            // diagnostics must never break sync
        }
    }

    // ----------------------------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------------------------

    /**
     * Fetches the signed-in user info. Also refreshes the access token and ensures a
     * device session exists.
     */
    suspend fun getUserInfo(): UserInfoResult = withIOContext {
        val result = executeSignedApi("$USER_URL/v2/user/get", buildJsonObject {}) {
            json.decodeFromString<UserInfoResult>(it)
        }
        if (result.defaultDriveId.isNotBlank()) {
            syncPreferences.aliyunPanDriveId.set(result.defaultDriveId)
        }
        if (result.userId.isNotBlank()) {
            syncPreferences.aliyunPanUserId.set(result.userId)
        }
        result
    }

    /** Lists all files/folders directly inside [parentFileId] (pagination handled). */
    suspend fun listFiles(driveId: String, parentFileId: String): List<FileEntity> = withIOContext {
        var marker = ""
        var page = 0
        val items = mutableListOf<FileEntity>()
        do {
            val body = buildJsonObject {
                put("drive_id", driveId)
                put("parent_file_id", parentFileId.ifBlank { ROOT_PARENT_FILE_ID })
                put("limit", 100)
                put("all", false)
                put("url_expire_sec", 1600)
                put("fields", "*")
                put("order_by", "name")
                put("order_direction", "ASC")
                if (marker.isNotEmpty()) put("marker", marker)
            }
            val result = executeSignedApi("$API_URL/adrive/v3/file/list", body) {
                json.decodeFromString<FileListResult>(it)
            }
            items += result.items
            marker = result.nextMarker
            page++
        } while (marker.isNotEmpty() && page < MAX_LIST_PAGES)
        items
    }

    /** Creates the sync folder under the drive root if it doesn't exist yet. */
    suspend fun ensureSyncFolder(driveId: String, folderName: String): String = withIOContext {
        val existing = listFiles(driveId, ROOT_PARENT_FILE_ID).firstOrNull {
            it.name == folderName && it.type == "folder"
        }
        if (existing != null) {
            return@withIOContext existing.fileId
        }

        val result = executeSignedApi(
            "$API_URL/adrive/v2/file/createWithFolders",
            buildJsonObject {
                put("drive_id", driveId)
                put("parent_file_id", ROOT_PARENT_FILE_ID)
                put("name", folderName)
                put("check_name_mode", "refuse")
                put("type", "folder")
            },
        ) { json.decodeFromString<CreateFileResult>(it) }

        result.exist?.let { return@withIOContext it.fileId }
        if (result.fileId.isBlank()) {
            throw AliyunPanException("Failed to create sync folder '$folderName'")
        }
        result.fileId
    }

    /**
     * Uploads [bytes] into the folder [parentFileId] under the name [name].
     * If a file with the same name already exists it is removed first.
     * @return the file id of the uploaded file
     */
    suspend fun uploadFile(driveId: String, parentFileId: String, name: String, bytes: ByteArray): String {
        ensureSession()

        // The web API cannot overwrite in place, so delete a previously synced file first.
        val existing = listFiles(driveId, parentFileId).firstOrNull {
            it.name == name && it.type == "file"
        }
        if (existing != null) {
            deleteFiles(driveId, listOf(existing.fileId))
        }

        val sha1 = sha1Hex(bytes)
        val proofCode = calcProofCode(syncPreferences.aliyunPanAccessToken.get(), bytes, bytes.size.toLong())
        val partCount = maxOf(1, ((bytes.size + DEFAULT_CHUNK_SIZE - 1) / DEFAULT_CHUNK_SIZE).toInt())
        val createResult = executeSignedApi(
            "$API_URL/adrive/v2/file/createWithFolders",
            buildJsonObject {
                put("drive_id", driveId)
                put("parent_file_id", parentFileId)
                put("name", name)
                put("type", "file")
                put("check_name_mode", "refuse")
                put("size", bytes.size.toLong())
                put("content_hash", sha1)
                put("content_hash_name", "sha1")
                put("proof_version", "v1")
                put("proof_code", proofCode)
                put(
                    "part_info_list",
                    buildJsonArray {
                        for (i in 1..partCount) {
                            add(buildJsonObject { put("part_number", i) })
                        }
                    },
                )
            },
        ) { json.decodeFromString<CreateFileResult>(it) }
        logcat(LogPriority.DEBUG) {
            "createWithFolders: name=$name size=${bytes.size} fileId=${createResult.fileId} uploadId=${createResult.uploadId} parts=${createResult.partInfoList.size} exist=${createResult.exist != null}"
        }
        debugLog(
            "createWithFolders: name=$name size=${bytes.size} fileId=${createResult.fileId} " +
                "uploadId=${createResult.uploadId} parts=${createResult.partInfoList.size} exist=${createResult.exist != null}",
        )

        // Already uploaded before (instant upload / rapid upload), nothing else to do.
        createResult.exist?.let { return it.fileId }

        val fileId = createResult.fileId
        if (fileId.isBlank()) {
            debugLog("createWithFolders returned blank fileId for '$name'")
            throw AliyunPanException("Failed to create upload task for '$name'")
        }

        val parts = createResult.partInfoList.sortedBy { it.partNumber }
        if (parts.isNotEmpty()) {
            var offset = 0L
            for (part in parts) {
                val end = minOf(offset + DEFAULT_CHUNK_SIZE, bytes.size.toLong())
                uploadChunk(part.uploadUrl, bytes.copyOfRange(offset.toInt(), end.toInt()))
                offset = end
            }
            logcat(LogPriority.DEBUG) { "Uploaded ${parts.size} parts for '$name', completing..." }
            debugLog("uploaded ${parts.size} parts for '$name', completing...")
            completeUpload(driveId, fileId, createResult.uploadId)
        } else if (createResult.uploadId.isNotBlank()) {
            debugLog("no parts but uploadId present for '$name', completing...")
            completeUpload(driveId, fileId, createResult.uploadId)
        }
        logcat(LogPriority.DEBUG) { "Upload finished for '$name' (fileId=$fileId)" }
        debugLog("upload finished for '$name' (fileId=$fileId)")
        return fileId
    }

    /** Returns a temporary download URL for a file. */
    suspend fun getDownloadUrl(driveId: String, fileId: String): String = withIOContext {
        val result = executeSignedApi(
            "$API_URL/v2/file/get_download_url",
            buildJsonObject {
                put("drive_id", driveId)
                put("file_id", fileId)
                put("expire_sec", 14400)
            },
        ) { json.decodeFromString<DownloadUrlResult>(it) }
        if (result.url.isBlank()) {
            throw AliyunPanException("Failed to get download URL for file '$fileId'")
        }
        result.url
    }

    /** Downloads the content at [url] (a presigned download URL). */
    suspend fun download(url: String): ByteArray = withIOContext {
        val request = GET(
            url,
            Headers.Builder()
                .add("User-Agent", USER_AGENT)
                .add("Referer", WEB_REFERER)
                .build(),
        )
        val response = client.newCall(request).await()
        response.use {
            if (!it.isSuccessful) {
                throw AliyunPanException("Failed to download file: ${it.code}")
            }
            it.body.bytes()
        }
    }

    /** Moves the given files to the recycle bin. */
    suspend fun deleteFiles(driveId: String, fileIds: List<String>) {
        if (fileIds.isEmpty()) return
        ensureSession()
        val body = buildJsonObject {
            put(
                "requests",
                buildJsonArray {
                    fileIds.forEach { fileId ->
                        add(
                            buildJsonObject {
                                put("id", fileId)
                                put("method", "POST")
                                put("url", "/recyclebin/trash")
                                put(
                                    "headers",
                                    buildJsonObject {
                                        put("Content-Type", "application/json")
                                    },
                                )
                                put(
                                    "body",
                                    buildJsonObject {
                                        put("drive_id", driveId)
                                        put("file_id", fileId)
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put("resource", "file")
        }
        executeSignedApi("$API_URL/adrive/v4/batch", body) { it }
    }

    // ----------------------------------------------------------------------------------
    // QR code login (no browser needed)
    // ----------------------------------------------------------------------------------

    /** Data needed to render and poll a login QR code. */
    data class QrLoginInfo(
        /** Content that must be encoded into the QR code image. */
        val codeContent: String,
        val ck: String,
        val t: Long,
    )

    data class QrLoginStatus(
        /** One of NEW / SCANED / CONFIRMED / EXPIRED / CANCELED. */
        val status: String,
        /** Present when [status] is CONFIRMED. */
        val refreshToken: String? = null,
    )

    /** Generates a QR code login session. */
    suspend fun generateQrLogin(): QrLoginInfo = withIOContext {
        val url = "$PASSPORT_URL/newlogin/qrcode/generate.do" +
            "?appName=aliyun_drive&fromSite=52&appEntrance=web&isMobile=false" +
            "&lang=zh_CN&returnUrl=&bizParams=&_bx-v=2.0.31"
        val response = client.newCall(GET(url)).await()
        response.use {
            if (!it.isSuccessful) {
                throw AliyunPanException("Failed to generate QR code: ${it.code}")
            }
            val root = json.decodeFromString<JsonObject>(it.body.string())
            val data = root["content"]?.jsonObject?.get("data")?.jsonObject
                ?: throw AliyunPanException("Unexpected QR generate response")
            QrLoginInfo(
                codeContent = data["codeContent"]?.jsonPrimitive?.content.orEmpty(),
                ck = data["ck"]?.jsonPrimitive?.content.orEmpty(),
                t = data["t"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }

    /** Polls the status of a QR login session. */
    suspend fun queryQrLogin(ck: String, t: Long): QrLoginStatus = withIOContext {
        val body = buildString {
            append("t=$t")
            append("&ck=$ck")
            append("&appName=aliyun_drive")
            append("&appEntrance=web")
            append("&isMobile=false")
            append("&lang=zh_CN")
            append("&returnUrl=")
            append("&fromSite=52")
            append("&bizParams=")
            append("&navlanguage=zh-CN")
            append("&navPlatform=MacIntel")
        }
        val request = Request.Builder()
            .url("$PASSPORT_URL/newlogin/qrcode/query.do?appName=aliyun_drive&fromSite=52&_bx-v=2.0.31")
            .post(body.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
            .build()
        val response = client.newCall(request).await()
        response.use {
            if (!it.isSuccessful) {
                throw AliyunPanException("Failed to query QR status: ${it.code}")
            }
            val root = json.decodeFromString<JsonObject>(it.body.string())
            val data = root["content"]?.jsonObject?.get("data")?.jsonObject
                ?: return@use QrLoginStatus("")
            val status = data["qrCodeStatus"]?.jsonPrimitive?.content.orEmpty()
            val bizExt = data["bizExt"]?.jsonPrimitive?.contentOrNull
            val refreshToken = if (status == "CONFIRMED" && bizExt != null) {
                parseBizExtRefreshToken(bizExt)
            } else {
                null
            }
            QrLoginStatus(status, refreshToken)
        }
    }

    private fun parseBizExtRefreshToken(bizExt: String): String? {
        return try {
            val decoded = String(Base64.getDecoder().decode(bizExt))
            val payload = json.decodeFromString<JsonObject>(decoded)
            payload["pds_login_result"]?.jsonObject
                ?.get("refreshToken")?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }

    // ----------------------------------------------------------------------------------
    // Token & session
    // ----------------------------------------------------------------------------------

    private suspend fun refreshAccessTokenIfNeeded() = withIOContext {
        val refreshToken = syncPreferences.aliyunPanRefreshToken.get()
        if (refreshToken.isBlank()) {
            throw AliyunPanException("Aliyun Pan refresh token is not configured")
        }
        val accessToken = syncPreferences.aliyunPanAccessToken.get()
        val expireTime = syncPreferences.aliyunPanTokenExpireTime.get()
        val now = System.currentTimeMillis()
        if (accessToken.isNotBlank() && expireTime > now + 60_000) {
            return@withIOContext
        }

        val body = buildJsonObject {
            put("refresh_token", refreshToken)
            put("api_id", API_ID)
            put("grant_type", "refresh_token")
        }
        val text = executeRequest("$AUTH_URL/v2/account/token", body, signed = false)
        val result = json.decodeFromString<TokenResult>(text)
        if (result.accessToken.isBlank()) {
            throw AliyunPanException("Failed to refresh Aliyun Pan access token")
        }
        syncPreferences.aliyunPanAccessToken.set(result.accessToken)
        syncPreferences.aliyunPanTokenExpireTime.set(now + result.expiresIn * 1000)
        if (result.refreshToken.isNotBlank()) {
            syncPreferences.aliyunPanRefreshToken.set(result.refreshToken)
        }
        if (result.userId.isNotBlank()) {
            syncPreferences.aliyunPanUserId.set(result.userId)
        }
        if (result.defaultDriveId.isNotBlank()) {
            syncPreferences.aliyunPanDriveId.set(result.defaultDriveId)
        }
    }

    private suspend fun ensureSession() = withIOContext {
        refreshAccessTokenIfNeeded()
        if (sessionSignature != null) {
            return@withIOContext
        }
        val userId = syncPreferences.aliyunPanUserId.get()
        if (userId.isBlank()) {
            throw AliyunPanException("Failed to resolve Aliyun Pan user id")
        }
        val deviceId = getOrCreateDeviceId()
        val sessionKey = generateSessionKey(userId, deviceId)

        val body = buildJsonObject {
            put("deviceName", DEVICE_NAME)
            put("modelName", MODEL_NAME)
            put("pubKey", sessionKey.publicKey)
        }
        val text = executeRequest(
            "$API_URL/users/v1/users/device/create_session",
            body,
            signed = true,
            signature = sessionKey.signature,
            deviceId = deviceId,
        )
        val result = json.decodeFromString<CreateSessionResult>(text)
        if (!result.result) {
            throw AliyunPanException("Failed to create Aliyun Pan session: ${result.message}")
        }
        sessionSignature = sessionKey.signature
    }

    @Synchronized
    private fun invalidateSession() {
        sessionSignature = null
    }

    /** Clears the in-memory session so it is recreated with the next request (e.g. after re-login). */
    @Synchronized
    fun resetSession() {
        sessionSignature = null
    }

    private fun getOrCreateDeviceId(): String {
        val existing = syncPreferences.aliyunPanDeviceId.get()
        if (existing.isNotBlank()) return existing
        val deviceId = UUID.randomUUID().toString()
        syncPreferences.aliyunPanDeviceId.set(deviceId)
        return deviceId
    }

    // ----------------------------------------------------------------------------------
    // Request helpers
    // ----------------------------------------------------------------------------------

    /**
     * Executes a signed API call, transparently recreating the device session and
     * retrying once when the server reports that the session signature is invalid.
     */
    private suspend fun <T> executeSignedApi(url: String, body: JsonObject, decode: (String) -> T): T {
        ensureSession()
        return try {
            decode(executeRequest(url, body, signed = true))
        } catch (e: AliyunPanSignatureInvalidException) {
            logcat(LogPriority.WARN) { "Aliyun Pan session signature invalid, recreating session" }
            invalidateSession()
            ensureSession()
            decode(executeRequest(url, body, signed = true))
        }
    }

    private suspend fun executeRequest(
        url: String,
        body: JsonObject,
        signed: Boolean,
        signature: String? = null,
        deviceId: String? = null,
    ): String {
        val headers = if (signed) {
            signedHeaders(
                signature = signature ?: sessionSignature,
                deviceId = deviceId ?: syncPreferences.aliyunPanDeviceId.get(),
            )
        } else {
            basicHeaders()
        }
        val request = POST(url, headers, body.toString().toRequestBody("application/json".toMediaType()))
        val response = client.newCall(request).await()
        return response.use {
            val text = it.body.string()
            if (!it.isSuccessful) {
                if (signed && text.contains("DeviceSessionSignatureInvalid", ignoreCase = true)) {
                    throw AliyunPanSignatureInvalidException()
                }
                logcat(LogPriority.ERROR) { "Aliyun Pan API error ${it.code}: $text" }
                throw AliyunPanException("Aliyun Pan API error ${it.code}: $text")
            }
            text
        }
    }

    private fun signedHeaders(signature: String?, deviceId: String): Headers {
        return Headers.Builder()
            .add("Authorization", "Bearer ${syncPreferences.aliyunPanAccessToken.get()}")
            .add("Content-Type", "application/json")
            .add("Referer", WEB_REFERER)
            .add("Origin", WEB_URL)
            .add("User-Agent", USER_AGENT)
            .apply {
                if (signature != null && deviceId.isNotBlank()) {
                    add("x-device-id", deviceId)
                    add("x-signature", signature)
                }
            }
            .build()
    }

    private fun basicHeaders(): Headers = Headers.Builder()
        .add("Content-Type", "application/json")
        .add("User-Agent", USER_AGENT)
        .build()

    private suspend fun uploadChunk(uploadUrl: String, bytes: ByteArray) = withIOContext {
        if (uploadUrl.isBlank()) {
            throw AliyunPanException("Upload URL is missing")
        }
        // NOTE: no Content-Type header on purpose — the presigned OSS URL is signed without it,
        // so adding one causes HTTP 403 (signature mismatch).
        val request = Request.Builder()
            .url(uploadUrl)
            .put(bytes.toRequestBody(null))
            .addHeader("Referer", WEB_REFERER)
            .build()
        val response = client.newCall(request).await()
        response.use {
            if (!it.isSuccessful) {
                debugLog("uploadChunk failed url=$uploadUrl code=${it.code} body=${it.body.string()}")
                logcat(LogPriority.ERROR) { "Aliyun Pan upload chunk failed: ${it.code} url=$uploadUrl" }
                throw AliyunPanException("Failed to upload data to Aliyun Pan: ${it.code}")
            }
        }
    }

    private suspend fun completeUpload(driveId: String, fileId: String, uploadId: String) {
        executeSignedApi(
            "$API_URL/v2/file/complete",
            buildJsonObject {
                put("ignoreError", true)
                put("drive_id", driveId)
                put("file_id", fileId)
                put("upload_id", uploadId)
            },
        ) { it }
    }

    // ----------------------------------------------------------------------------------
    // secp256k1 session signature (mirrors tickstep/aliyunpan-api aliyunpan_web/signature.go)
    // ----------------------------------------------------------------------------------

    private data class SessionKey(
        val publicKey: String,
        val signature: String,
    )

    private fun generateSessionKey(userId: String, deviceId: String): SessionKey {
        val x9 = SECNamedCurves.getByName("secp256k1")
            ?: throw AliyunPanException("secp256k1 curve is not available")
        val domain = ECDomainParameters(x9.curve, x9.g, x9.n, x9.h)

        val random = SecureRandom()
        val d = generatePrivateKey(x9.n, random)

        // Uncompressed/compressed point: the reference implementation sends
        // "04" + hex(compressed 33-byte key).
        val publicKeyPoint = x9.g.multiply(d).normalize()
        val compressed = publicKeyPoint.getEncoded(true)
        val publicKey = "04" + compressed.toHex()

        // Sign "appId:deviceId:userId:nonce" (nonce = 0) with SHA-256 + ECDSA.
        // Deterministic RFC6979 nonce (HMacDSAKCalculator) matches the btcec-based
        // reference implementation.
        val message = "$APP_ID:$deviceId:$userId:0".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(message)

        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(d, domain))
        val components = signer.generateSignature(digest)
        val r = components[0]
        var s = components[1]

        // Enforce lower-S to match btcec behaviour of the reference implementation.
        val halfOrder = x9.n.shiftRight(1)
        if (s > halfOrder) {
            s = x9.n.subtract(s)
        }

        val signature = r.toFixed32Bytes().toHex() + s.toFixed32Bytes().toHex() + "01"
        return SessionKey(publicKey, signature)
    }

    private fun generatePrivateKey(n: BigInteger, random: SecureRandom): BigInteger {
        while (true) {
            val candidate = BigInteger(256, random)
            if (candidate.signum() > 0 && candidate.compareTo(n) < 0) {
                return candidate
            }
        }
    }

    private fun BigInteger.toFixed32Bytes(): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size < 32 -> {
                val out = ByteArray(32)
                bytes.copyInto(out, 32 - bytes.size)
                out
            }
            bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
            else -> throw AliyunPanException("Signature component is too large")
        }
    }

    private fun ByteArray.toHex(): String {
        val hexChars = "0123456789abcdef"
        val builder = StringBuilder(size * 2)
        for (b in this) {
            builder.append(hexChars[(b.toInt() ushr 4) and 0xF])
            builder.append(hexChars[b.toInt() and 0xF])
        }
        return builder.toString()
    }

    private fun sha1Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-1").digest(bytes).toHex()
    }

    /**
     * Computes the upload proof code (防伪码) used by Aliyun Drive's rapid upload check.
     * Mirrors CalcProofCode in tickstep/aliyunpan-api: takes the first 16 hex chars of
     * md5(accessToken), mods it by the file size and base64-encodes the 8 bytes at that offset.
     */
    private fun calcProofCode(accessToken: String, bytes: ByteArray, fileSize: Long): String {
        if (fileSize == 0L) return ""
        val md5Hex = MessageDigest.getInstance("MD5")
            .digest(accessToken.toByteArray(Charsets.UTF_8))
            .toHex()
        val hashInteger = BigInteger(md5Hex.substring(0, 16), 16)
        val startPos = (hashInteger % fileSize.toBigInteger()).toLong()
        val endPos = minOf(startPos + 8, fileSize)
        val proofBytes = bytes.copyOfRange(startPos.toInt(), endPos.toInt())
        return Base64.getEncoder().encodeToString(proofBytes)
    }

    // ----------------------------------------------------------------------------------
    // DTOs
    // ----------------------------------------------------------------------------------

    @Serializable
    data class TokenResult(
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("refresh_token") val refreshToken: String = "",
        @SerialName("expires_in") val expiresIn: Long = 0,
        @SerialName("user_id") val userId: String = "",
        @SerialName("default_drive_id") val defaultDriveId: String = "",
        @SerialName("nick_name") val nickName: String = "",
    )

    @Serializable
    data class UserInfoResult(
        @SerialName("domain_id") val domainId: String = "",
        @SerialName("user_id") val userId: String = "",
        @SerialName("nick_name") val nickName: String = "",
        @SerialName("default_drive_id") val defaultDriveId: String = "",
    )

    @Serializable
    data class FileEntity(
        @SerialName("drive_id") val driveId: String = "",
        @SerialName("file_id") val fileId: String = "",
        @SerialName("parent_file_id") val parentFileId: String = "",
        val name: String = "",
        val type: String = "",
        val size: Long = 0,
        @SerialName("upload_id") val uploadId: String = "",
        @SerialName("created_at") val createdAt: String = "",
        @SerialName("updated_at") val updatedAt: String = "",
    )

    @Serializable
    data class FileListResult(
        val items: List<FileEntity> = emptyList(),
        @SerialName("next_marker") val nextMarker: String = "",
    )

    @Serializable
    data class PartInfo(
        @SerialName("part_number") val partNumber: Int = 0,
        @SerialName("upload_url") val uploadUrl: String = "",
    )

    @Serializable
    data class CreateFileResult(
        @SerialName("drive_id") val driveId: String = "",
        @SerialName("file_id") val fileId: String = "",
        @SerialName("upload_id") val uploadId: String = "",
        @SerialName("parent_file_id") val parentFileId: String = "",
        val name: String = "",
        val exist: FileEntity? = null,
        @SerialName("part_info_list") val partInfoList: List<PartInfo> = emptyList(),
    )

    @Serializable
    data class CreateSessionResult(
        val result: Boolean = false,
        val success: Boolean = false,
        val code: String = "",
        val message: String = "",
    )

    @Serializable
    data class DownloadUrlResult(
        val url: String = "",
        val expiration: String = "",
    )
}

internal class AliyunPanException(message: String) : Exception(message)

internal class AliyunPanSignatureInvalidException : Exception("Aliyun Pan session signature invalid")

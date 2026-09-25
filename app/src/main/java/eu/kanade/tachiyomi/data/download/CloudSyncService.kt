package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import logcat.LogPriority
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source
import org.w3c.dom.Element
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SY --> 从 mihon_img_upscale 移植：下载章节的 WebDAV 云同步。
 *
 * 只依赖 WebDAV 最基础的四个动词，兼容大多数网盘/自建服务：
 *  - PROPFIND（列目录、判定目录是否存在）
 *  - MKCOL（按需逐级创建目标目录）
 *  - PUT（上传 CBZ，带进度回调）
 *  - DELETE（开启「上传后删除本地」时清理）
 *
 * 与上游的差异仅限依赖注入方式：上游走 Injekt，这里改成 Metro 的
 * `@Inject @SingleIn(AppScope::class)`，行为与协议细节逐行保持一致。
 * SY <--
 */
@Inject
@SingleIn(AppScope::class)
class CloudSyncService(
    private val networkHelper: NetworkHelper,
) {
    private val uploadClient: OkHttpClient by lazy {
        networkHelper.client.newBuilder()
            .readTimeout(UPLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .writeTimeout(UPLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .callTimeout(UPLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()
    }

    suspend fun testConnection(config: CloudSyncConfig) {
        listDirectories(config, "/")
    }

    suspend fun listDirectories(config: CloudSyncConfig, path: String): List<CloudSyncDirectory> = withIOContext {
        val request = Request.Builder()
            .url(config.resolveUrl(path, trailingSlash = true))
            .headers(config.authHeaders())
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY)
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("WebDAV PROPFIND failed: HTTP ${response.code}")
            }
            response.body.byteStream().use { input ->
                parseDirectories(config, input.readBytes())
                    .filterNot { it.path == normalizePath(path) }
            }
        }
    }

    suspend fun uploadCbz(
        config: CloudSyncConfig,
        remoteDirectory: String,
        file: UniFile,
        fileName: String = file.name ?: error("Missing upload file name"),
        onProgress: (Int) -> Unit,
    ) = uploadFile(
        config = config,
        remoteDirectory = remoteDirectory,
        file = file,
        fileName = fileName,
        mediaType = CBZ_MEDIA_TYPE,
        overwrite = false,
        onProgress = onProgress,
    )

    suspend fun uploadFile(
        config: CloudSyncConfig,
        remoteDirectory: String,
        file: UniFile,
        fileName: String = file.name ?: error("Missing upload file name"),
        mediaType: MediaType = GENERIC_BINARY_MEDIA_TYPE,
        overwrite: Boolean = false,
        onProgress: (Int) -> Unit,
    ) = withIOContext {
        logcat(LogPriority.INFO) { "CloudSync: uploading via WebDAV" }
        uploadWebDavCbz(
            config = config,
            remoteDirectory = remoteDirectory,
            fileName = fileName,
            file = file,
            mediaType = mediaType,
            overwrite = overwrite,
            onProgress = onProgress,
        )
        logcat(LogPriority.INFO) { "CloudSync: WebDAV upload completed" }
    }

    /**
     * 上传漫画封面到远端漫画文件夹（与章节 CBZ 并列的 cover.jpg），
     * 供目录型阅读器（Komga / Ubooquity 等）当作系列封面显示。
     */
    suspend fun uploadCover(
        config: CloudSyncConfig,
        remoteDirectory: String,
        coverFile: File,
        onProgress: (Int) -> Unit = {},
    ) = withIOContext {
        logcat(LogPriority.INFO) { "CloudSync: uploading cover via WebDAV" }
        uploadWebDavFile(
            config = config,
            remoteDirectory = remoteDirectory,
            fileName = COVER_FILE_NAME,
            file = coverFile,
            mediaType = IMAGE_JPEG,
            onProgress = onProgress,
        )
        logcat(LogPriority.INFO) { "CloudSync: WebDAV cover upload completed" }
    }

    /** 服务端同名文件的 SHA-256；不存在返回 null。用于判断是否真的需要重传。 */
    suspend fun remoteFileSha256OrNull(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileName: String,
    ): String? = withIOContext {
        val effectiveRemoteDirectory = normalizePath(remoteDirectory)
        val request = Request.Builder()
            .url(config.resolveUrl(effectiveRemoteDirectory, fileName))
            .headers(config.authHeaders())
            .get()
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            if (response.code == 404) return@withIOContext null
            if (!response.isSuccessful) {
                throw IllegalStateException("WebDAV file lookup failed: HTTP ${response.code}")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            response.body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().toHexString()
        }
    }

    /**
     * 列出某个目录下的「文件」（非集合）条目，用于读取漫画章节 CBZ。
     * 与 [listDirectories] 互补：这里只返回非 collection 的条目。
     */
    suspend fun listFiles(config: CloudSyncConfig, path: String): List<CloudSyncEntry> = withIOContext {
        val request = Request.Builder()
            .url(config.resolveUrl(path, trailingSlash = true))
            .headers(config.authHeaders())
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY)
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("WebDAV PROPFIND failed: HTTP ${response.code}")
            }
            response.body.byteStream().use { input ->
                parseEntries(config, input.readBytes())
                    .filter { !it.isCollection && it.path != normalizePath(path) }
            }
        }
    }

    /**
     * 把远端文件流式 GET 到本地 [destFile]（先写 .tmp 再原子改名，避免半截文件）。
     * 复用长超时的 [uploadClient]，适合体积较大的 CBZ。
     */
    suspend fun downloadFile(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileName: String,
        destFile: File,
        onProgress: (Int) -> Unit = {},
    ) = withIOContext {
        val effectiveRemoteDirectory = normalizePath(remoteDirectory)
        val request = Request.Builder()
            .url(config.resolveUrl(effectiveRemoteDirectory, fileName))
            .headers(config.authHeaders())
            .get()
            .build()

        uploadClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("WebDAV download failed: HTTP ${response.code} for $fileName")
            }

            val parent = destFile.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val tmp = File(destFile.parentFile, destFile.name + ".tmp")
            response.body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(SEGMENT_SIZE.toInt())
                    val total = response.body.contentLength()
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
            if (destFile.exists()) destFile.delete()
            tmp.renameTo(destFile)
            onProgress(100)
        }
    }

    /**
     * 下载漫画封面：远端封面可能是 cover.jpg / cover.webp / cover.png / cover.jpeg，
     * 按序尝试（首个成功即返回）。用于 WebDavSource 与 WebDavCoverProvider 的封面加载，
     * 覆盖 webp 等非 jpg 封面（早期硬编码 cover.jpg 导致 webp 封面空白）。
     */
    suspend fun downloadCover(
        config: CloudSyncConfig,
        mangaUrl: String,
        coverDir: File,
    ): File? = withIOContext {
        coverDir.mkdirs()
        val baseName = mangaUrl.replace('/', '_')
        val exts = listOf("jpg", "webp", "png", "jpeg")
        for (ext in exts) {
            val file = File(coverDir, "$baseName.$ext")
            if (file.exists()) return@withIOContext file
            runCatching { downloadFile(config, mangaUrl, "cover.$ext", file) }
            if (file.exists()) return@withIOContext file
        }
        null
    }

    /** 远端文件是否存在（HEAD，404 视为不存在；鉴权失败抛异常）。 */
    suspend fun remoteFileExists(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileName: String,
    ): Boolean = withIOContext {
        val effectiveRemoteDirectory = normalizePath(remoteDirectory)
        val request = Request.Builder()
            .url(config.resolveUrl(effectiveRemoteDirectory, fileName))
            .headers(config.authHeaders())
            .head()
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            when {
                response.isSuccessful -> true
                response.code == 404 -> false
                response.code in AUTH_FAILURE_CODES -> throw IllegalStateException("WebDAV HEAD failed: HTTP ${response.code}")
                else -> false
            }
        }
    }

    suspend fun deleteRemoteFile(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileName: String,
    ) = withIOContext {
        val effectiveRemoteDirectory = normalizePath(remoteDirectory)
        val request = Request.Builder()
            .url(config.resolveUrl(effectiveRemoteDirectory, fileName))
            .headers(config.authHeaders())
            .delete()
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            if (response.code == 404) return@withIOContext
            if (response.code !in SUCCESS_CODES) {
                throw IllegalStateException("WebDAV delete failed: HTTP ${response.code}")
            }
        }
    }

    private suspend fun uploadWebDavCbz(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileName: String,
        file: UniFile,
        mediaType: MediaType,
        overwrite: Boolean,
        onProgress: (Int) -> Unit,
    ) {
        val effectiveRemoteDirectory = ensureDirectory(config, remoteDirectory)

        onProgress(0)
        val request = Request.Builder()
            .url(config.resolveUrl(effectiveRemoteDirectory, fileName))
            .headers(config.authHeaders())
            .put(UniFileRequestBody(file, mediaType, onProgress))
            .build()

        uploadClient.newCall(request).await().use { response ->
            if (response.code !in SUCCESS_CODES) {
                throw IllegalStateException("WebDAV upload failed: HTTP ${response.code}")
            }
        }
        onProgress(100)
    }

    /** 与 [uploadWebDavCbz] 同构，但数据源是 java.io.File（用于封面等本地缓存文件）。 */
    private suspend fun uploadWebDavFile(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileName: String,
        file: File,
        mediaType: MediaType,
        onProgress: (Int) -> Unit,
    ) {
        val effectiveRemoteDirectory = ensureDirectory(config, remoteDirectory)

        onProgress(0)
        val request = Request.Builder()
            .url(config.resolveUrl(effectiveRemoteDirectory, fileName))
            .headers(config.authHeaders())
            .put(FileRequestBody(file, mediaType, onProgress))
            .build()

        uploadClient.newCall(request).await().use { response ->
            if (response.code !in SUCCESS_CODES) {
                throw IllegalStateException("WebDAV cover upload failed: HTTP ${response.code}")
            }
        }
        onProgress(100)
    }

    /** 逐级 MKCOL；已存在的目录（405）视为成功。 */
    private suspend fun ensureDirectory(config: CloudSyncConfig, path: String): String {
        var currentPath = ""
        normalizePath(path)
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
            .forEach { segment ->
                val targetPath = normalizePath("$currentPath/$segment")
                when (val result = mkcol(config, targetPath)) {
                    is MkcolResult.Success -> currentPath = targetPath
                    is MkcolResult.Failure -> {
                        if (directoryExists(config, targetPath)) {
                            currentPath = targetPath
                        } else {
                            throw IllegalStateException(
                                "WebDAV MKCOL failed: HTTP ${result.code} for ${result.path}",
                            ).apply {
                                result.message?.let { addSuppressed(IllegalStateException(it)) }
                            }
                        }
                    }
                }
            }
        return currentPath.ifBlank { "/" }
    }

    private suspend fun directoryExists(config: CloudSyncConfig, path: String): Boolean {
        val request = Request.Builder()
            .url(config.resolveUrl(path, trailingSlash = true))
            .headers(config.authHeaders())
            .header("Depth", "0")
            .method("PROPFIND", PROPFIND_BODY)
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            return when {
                response.isSuccessful -> true
                response.code == 404 -> false
                response.code in AUTH_FAILURE_CODES -> throw IllegalStateException("WebDAV PROPFIND failed: HTTP ${response.code}")
                else -> false
            }
        }
    }

    private suspend fun mkcol(config: CloudSyncConfig, path: String): MkcolResult {
        val request = Request.Builder()
            .url(config.resolveUrl(path, trailingSlash = true))
            .headers(config.authHeaders())
            .method("MKCOL", EMPTY_BODY)
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            return when {
                response.code in MKCOL_SUCCESS_CODES -> MkcolResult.Success
                response.code in AUTH_FAILURE_CODES -> throw IllegalStateException("WebDAV MKCOL failed: HTTP ${response.code}")
                else -> MkcolResult.Failure(
                    path = path,
                    code = response.code,
                    message = response.body.string().takeIf { it.isNotBlank() },
                )
            }
        }
    }

    /** 通用解析：把 PROPFIND 响应拆成条目，区分 collection（目录）与文件。 */
    private fun parseEntries(config: CloudSyncConfig, bytes: ByteArray): List<CloudSyncEntry> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(bytes.inputStream())

        val responses = document.getElementsByTagNameNS("*", "response")
        return (0 until responses.length)
            .asSequence()
            .mapNotNull { index ->
                val node = responses.item(index)
                val element = node as? Element ?: return@mapNotNull null
                val href = element.firstTextByTagName("href") ?: return@mapNotNull null
                val isCollection = element.getElementsByTagNameNS("*", "collection").length > 0
                val path = config.relativePathFromHref(href)
                val name = element.firstTextByTagName("displayname")
                    ?.takeUnless { it.isBlank() }
                    ?: path.trim('/').substringAfterLast('/', missingDelimiterValue = path.trim('/'))
                val lastModified = parseLastModified(element.firstTextByTagName("getlastmodified"))
                CloudSyncEntry(
                    name = name,
                    path = path,
                    isCollection = isCollection,
                    lastModified = lastModified,
                )
            }
            .toList()
            .distinctBy { it.path }
            .sortedWith(compareBy({ it.isCollection }, { it.name.lowercase() }))
    }

    private fun parseDirectories(config: CloudSyncConfig, bytes: ByteArray): List<CloudSyncDirectory> {
        return parseEntries(config, bytes)
            .filter { it.isCollection }
            .map { entry ->
                CloudSyncDirectory(
                    name = if (entry.path == "/") "/" else entry.name,
                    path = entry.path,
                    lastModified = entry.lastModified,
                )
            }
            .distinctBy { it.path }
            .sortedWith(compareBy({ it.path != "/" }, { it.name.lowercase() }))
    }

    private fun Element.firstTextByTagName(localName: String): String? {
        return getElementsByTagNameNS("*", localName)
            .item(0)
            ?.textContent
            ?.trim()
    }

    private fun CloudSyncConfig.authHeaders(): Headers {
        return Headers.Builder()
            .add("Authorization", Credentials.basic(username, password))
            .build()
    }

    private fun CloudSyncConfig.resolveUrl(
        remotePath: String,
        fileName: String? = null,
        trailingSlash: Boolean = false,
    ): HttpUrl {
        val base = url.toHttpUrl()
        val segments = buildList {
            addAll(base.pathSegments.filter { it.isNotBlank() })
            addAll(normalizePath(remotePath).trim('/').split('/').filter { it.isNotBlank() })
            if (!fileName.isNullOrBlank()) {
                add(fileName)
            }
        }
        return base.newBuilder()
            .encodedPath("/")
            .apply {
                segments.forEach(::addPathSegment)
                if (trailingSlash) addPathSegment("")
            }
            .build()
    }

    /**
     * 解码 WebDAV 响应里的 href 路径。
     * 关键点：URLDecoder 会把 '+' 当成空格解码，但 WebDAV 路径中的 '+' 是目录/文件名里
     * 的字面量（空格由 %20 表示），必须保留——否则名字带 + 号的漫画其路径会被改成空格，
     * 导致封面/章节请求 404（封面空白、章节读不出）。这里先把 '+' 占位、解码 %XX 后再还原。
     */
    private fun decodeHrefPath(raw: String): String {
        return raw.replace("+", "\u0000")
            .let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
            .replace("\u0000", "+")
    }

    private fun CloudSyncConfig.relativePathFromHref(href: String): String {
        val hrefPath = runCatching {
            decodeHrefPath(java.net.URI(href).path ?: href)
        }.getOrElse {
            decodeHrefPath(href)
        }
        val basePath = "/" + url.toHttpUrl().pathSegments.filter { it.isNotBlank() }.joinToString("/")
        val relativePath = hrefPath
            .removePrefix(basePath)
            .trim('/')
        return normalizePath(relativePath)
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private class UniFileRequestBody(
        private val file: UniFile,
        private val mediaType: MediaType,
        private val onProgress: (Int) -> Unit,
    ) : RequestBody() {
        private var resolvedContentLength = Long.MIN_VALUE

        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long {
            if (resolvedContentLength != Long.MIN_VALUE) {
                return resolvedContentLength
            }

            resolvedContentLength = file.length().takeIf { it >= 0 } ?: file.openInputStream().source().use { source ->
                var total = 0L
                val buffer = Buffer()
                while (true) {
                    val read = source.read(buffer, SEGMENT_SIZE)
                    if (read == -1L) break
                    total += read
                    buffer.clear()
                }
                total
            }

            return resolvedContentLength
        }

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var uploaded = 0L
            val buffer = Buffer()

            file.openInputStream().source().use { source ->
                while (true) {
                    val read = source.read(buffer, SEGMENT_SIZE)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    uploaded += read
                    if (total > 0) {
                        onProgress((uploaded * 100 / total).toInt())
                    }
                }
            }
        }
    }

    /** 与 [UniFileRequestBody] 同构，但数据源是 java.io.File（磁盘缓存的封面文件等）。 */
    private class FileRequestBody(
        private val file: File,
        private val mediaType: MediaType,
        private val onProgress: (Int) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var uploaded = 0L
            val buffer = Buffer()

            file.source().use { source ->
                while (true) {
                    val read = source.read(buffer, SEGMENT_SIZE)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    uploaded += read
                    if (total > 0) {
                        onProgress((uploaded * 100 / total).toInt())
                    }
                }
            }
        }
    }

    companion object {
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname />
                <d:resourcetype />
                <d:getlastmodified />
              </d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val CBZ_MEDIA_TYPE = "application/vnd.comicbook+zip".toMediaType()
        private val GENERIC_BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private val IMAGE_JPEG = "image/jpeg".toMediaType()
        private const val COVER_FILE_NAME = "cover.jpg"
        private val SUCCESS_CODES = setOf(200, 201, 204)
        private val MKCOL_SUCCESS_CODES = setOf(200, 201, 204, 405)
        private val AUTH_FAILURE_CODES = setOf(401, 407)
        private const val UPLOAD_TIMEOUT_MINUTES = 15L
        private const val SEGMENT_SIZE = 8L * 1024L

        /**
         * 解析 WebDAV 的 getlastmodified（RFC1123，如 "Wed, 25 Sep 2026 10:00:00 GMT"）为毫秒。
         * 服务器未返回 / 格式异常时返回 0，让调用方保持原顺序而非崩溃。
         */
        private fun parseLastModified(text: String?): Long {
            if (text.isNullOrBlank()) return 0L
            return runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                    .parse(text)
                    ?.time
                    ?: 0L
            }.getOrDefault(0L)
        }
    }
}

private sealed class MkcolResult {
    data object Success : MkcolResult()

    data class Failure(
        val path: String,
        val code: Int,
        val message: String?,
    ) : MkcolResult()
}

data class CloudSyncConfig(
    val url: String,
    val username: String,
    val password: String,
) {
    val isValid: Boolean
        get() = url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

data class CloudSyncDirectory(
    val name: String,
    val path: String,
    /** 目录最后修改时间（毫秒）。WebDAV 的 getlastmodified，服务器未给或解析失败时为 0。 */
    val lastModified: Long = 0L,
)

/** PROPFIND 响应的一个条目；[isCollection]=true 表示目录，false 表示文件。 */
data class CloudSyncEntry(
    val name: String,
    val path: String,
    val isCollection: Boolean,
    /** 条目最后修改时间（毫秒）。 */
    val lastModified: Long = 0L,
)

fun normalizePath(path: String): String {
    val normalized = path
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString(separator = "/", prefix = "/")
    return normalized.takeUnless { it.isBlank() } ?: "/"
}

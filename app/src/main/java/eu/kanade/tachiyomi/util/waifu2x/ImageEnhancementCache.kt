package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Manages disk cache for enhanced images to reduce memory usage.
 */
object ImageEnhancementCache {
    // SY --> 原目录名为 realcugan_cache；超分只剩 waifu2x 后改为中性命名，
    // 旧目录在 init() 里就地清理（纯缓存，可再生）。
    private const val CACHE_DIR_NAME = "image_enhance_cache"
    private const val LEGACY_CACHE_DIR_NAME = "realcugan_cache"
    private const val MAX_CACHE_SIZE = 3L * 1024 * 1024 * 1024 // 3GB
    private var cacheDir: File? = null
    private var lastTrimTime = 0L
    private val cacheGeneration = AtomicInteger(0)
    private val pendingSaveKeys = ConcurrentHashMap<String, Int>()
    private val saveQueue = Channel<SaveRequest>(capacity = 1)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class SaveRequest(
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val configHash: String,
        val bitmap: Bitmap,
        val pageVariant: String,
        val generation: Int,
        val key: String,
    )

    init {
        saveScope.launch {
            for (request in saveQueue) {
                try {
                    if (request.generation == cacheGeneration.get()) {
                        val file = writeToCache(request)
                        if (file != null) {
                            android.util.Log.d("ImageEnhancementCache", "Saved page ${request.pageIndex}/${request.pageVariant} to ${file.absolutePath}")
                        } else {
                            android.util.Log.e("ImageEnhancementCache", "Failed to save page ${request.pageIndex}/${request.pageVariant}")
                        }
                    }
                } finally {
                    pendingSaveKeys.remove(request.key, request.generation)
                    if (!request.bitmap.isRecycled) request.bitmap.recycle()
                }
            }
        }
    }

    fun init(context: Context) {
        if (cacheDir == null) {
            // SY --> 丢弃旧版本留下的旧目录缓存，避免既占空间又永远命不中
            File(context.cacheDir, LEGACY_CACHE_DIR_NAME).takeIf(File::exists)?.deleteRecursively()
            // SY <--
            cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply {
                if (!exists()) mkdirs()
            }
        }
    }

    /**
     * Get the cache directory for a specific manga and chapter
     */
    private fun getChapterDir(mangaId: Long, chapterId: Long): File {
        val mangaDir = File(cacheDir, mangaId.toString())
        if (!mangaDir.exists()) mangaDir.mkdirs()
        val chapterDir = File(mangaDir, chapterId.toString())
        if (!chapterDir.exists()) chapterDir.mkdirs()
        return chapterDir
    }

    /**
     * Get cached file if it exists
     */
    fun getCachedImage(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): File? {
        val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant))
        return if (file.exists()) file else null
    }
    
    /**
     * Check if a file is already cached (helper for UI checks)
     */
    fun isCached(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant) != null
    }

    /**
     * Remove a cached enhanced image and its temporary file for the same page/config.
     */
    fun removeCachedImage(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return try {
            val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant))
            val tempFile = File(file.parent, "${file.name}.tmp")
            val removedFile = !file.exists() || file.delete()
            val removedTemp = !tempFile.exists() || tempFile.delete()
            removedFile && removedTemp
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to remove cached image for page $pageIndex", e)
            false
        }
    }

    fun removeSkipMarker(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return try {
            val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant) + ".skip")
            !file.exists() || file.delete()
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to remove skip marker for page $pageIndex", e)
            false
        }
    }

    /**
     * Transfers ownership of [bitmap] to the cache pipeline, including when the request is rejected.
     */
    suspend fun enqueueSaveToCache(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, bitmap: Bitmap, pageVariant: String = ""): Boolean {
        if (cacheDir == null || !isDisplayable(bitmap)) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return false
        }
        val key = pendingSaveKey(mangaId, chapterId, pageIndex, pageVariant)
        val generation = cacheGeneration.get()
        if (pendingSaveKeys.putIfAbsent(key, generation) != null) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return false
        }

        val request = SaveRequest(
            mangaId = mangaId,
            chapterId = chapterId,
            pageIndex = pageIndex,
            configHash = configHash,
            bitmap = bitmap,
            pageVariant = pageVariant,
            generation = generation,
            key = key,
        )
        try {
            saveQueue.send(request)
            return true
        } catch (t: Throwable) {
            pendingSaveKeys.remove(key, generation)
            if (!bitmap.isRecycled) bitmap.recycle()
            throw t
        }
    }

    fun isSavePending(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = ""): Boolean {
        return pendingSaveKeys.containsKey(pendingSaveKey(mangaId, chapterId, pageIndex, pageVariant))
    }

    private fun writeToCache(request: SaveRequest): File? {
        if (cacheDir == null) return null
        val bitmap = request.bitmap
        if (!isDisplayable(bitmap)) {
            android.util.Log.e("ImageEnhancementCache", "Refusing to cache nearly transparent enhanced image for page ${request.pageIndex}")
            return null
        }
        
        try {
            val file = File(
                getChapterDir(request.mangaId, request.chapterId),
                getFilename(request.pageIndex, request.configHash, request.pageVariant),
            )
            val tempFile = File(file.parent, "${file.name}.tmp")
            
            FileOutputStream(tempFile).use { out ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
                }
                out.flush()
            }

            if (request.generation != cacheGeneration.get()) {
                tempFile.delete()
                return null
            }
            
            if (tempFile.renameTo(file)) {
                return file
            } else {
                tempFile.delete()
                return null
            }
        } catch (t: Throwable) {
            android.util.Log.e("ImageEnhancementCache", "Failed to save to cache for page ${request.pageIndex}", t)
            return null
        }
    }

    fun isDisplayable(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        if (!bitmap.hasAlpha()) return true

        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var total = 0
        var visible = 0
        var alphaSum = 0L

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val alpha = bitmap.getPixel(x, y) ushr 24
                if (alpha > 16) visible++
                alphaSum += alpha.toLong()
                total++
                x += stepX
            }
            y += stepY
        }

        if (total == 0) return false
        return visible > total / 20 || alphaSum / total > 32
    }

    /**
     * Mark a page as skipped (too large to process) in the cache
     */
    fun saveSkippedToCache(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = "") {
        try {
            val file = File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant) + ".skip")
            if (!file.exists()) {
                file.createNewFile()
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to save skip marker", e)
        }
    }

    /**
     * Check if a page was marked as skipped in the cache
     */
    fun isSkipped(mangaId: Long, chapterId: Long, pageIndex: Int, configHash: String, pageVariant: String = ""): Boolean {
        return File(getChapterDir(mangaId, chapterId), getFilename(pageIndex, configHash, pageVariant) + ".skip").exists()
    }

    /**
     * Clear old cache files including skip markers
     */
    fun clearOldCache(mangaId: Long, chapterId: Long, currentPage: Int, keepRange: Int = 5) {
        getChapterDir(mangaId, chapterId).listFiles()?.forEach { file ->
            try {
                // filename format: pageIndex_configHash.webp
                val name = file.name
                val parts = name.split("_")
                if (parts.isNotEmpty()) {
                    val pageIndex = parts[0].toIntOrNull()
                    if (pageIndex != null) {
                        // Delete if page is too far behind or ahead
                        if (kotlin.math.abs(pageIndex - currentPage) > keepRange) {
                            file.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }
    
    /**
     * Delete all cache files
     */
    fun clear(context: Context) {
        init(context)
        cacheGeneration.incrementAndGet()
        pendingSaveKeys.clear()
        cacheDir?.deleteRecursively()
        cacheDir?.mkdirs()
    }

    private fun pendingSaveKey(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String): String {
        return "${mangaId}_${chapterId}_${pageIndex}_$pageVariant"
    }

    private fun getFilename(pageIndex: Int, configHash: String, pageVariant: String = ""): String {
        return buildString {
            append(pageIndex)
            append('_')
            append(configHash)
            if (pageVariant.isNotEmpty()) {
                append('_')
                append(pageVariant)
            }
            append(".webp")
        }
    }

    /**
     * Generate a unique hash string based on current settings
     *
     * SY --> 移除了 realEsrganStyle 与 processingBackend：前者只服务 Real-ESRGAN，
     * 后者随 SR 侧 Qualcomm NPU 后端一起删除，现在只有 Vulkan / GLSL 两条路径。
     *
     * anime4kPreset / thin / darken 也是**决定输出像素**的输入，必须进指纹：
     * 换预设或拨动线条开关后如果 hash 不变，就会继续命中上一套链的缓存图，
     * 表现为「改了设置但画面没变」。6/7 用不到这三个参数，传默认值即可。
     * SY <--
     */
    fun getConfigHash(
        noise: Int, 
        scale: Int, 
        model: Int = 0,
        maxWidth: Int = 0,
        maxHeight: Int = 0,
        skipMaxWidth: Int = 0,
        skipMaxHeight: Int = 0,
        tileSize: Int = 128,
        precision: Int = 0,
        fp16Arithmetic: Boolean = false,
        anime4kPreset: Int = 0,
        anime4kThinLines: Boolean = false,
        anime4kDarkenLines: Boolean = false,
    ): String {
        val effectiveScale = getEffectiveScale(model, scale)
        // SY --> 精度也走 sanitize：INT8/BF16 会被引擎收敛成 FP16，
        // 缓存键必须跟着收敛，否则「选了 INT8 又选回 FP16」会拿到两个
        // 实际上完全相同的配置、却各自算一遍的冗余缓存。
        val effectivePrecision = Waifu2x.sanitizePrecision(precision)
        // SY <--
        // SY --> 只有 GLSL 路线吃这三个参数；其它模型统一折叠成常量，
        // 免得用户切到 GLSL 调完预设、再切回来时白废一轮缓存。
        val isGlsl = model == Waifu2x.MODEL_ANIME4K_GLSL
        val a4kPreset = if (isGlsl) anime4kPreset else 0
        val a4kThin = if (isGlsl) anime4kThinLines else false
        val a4kDarken = if (isGlsl) anime4kDarkenLines else false
        // SY <--
        return "${noise}x${effectiveScale}_m${model}_w${maxWidth}_h${maxHeight}_sw${skipMaxWidth}_sh${skipMaxHeight}_t${tileSize}_p${effectivePrecision}_fa${if (fp16Arithmetic) 1 else 0}_a4k${a4kPreset}${if (a4kThin) "t" else ""}${if (a4kDarken) "d" else ""}"
    }

    /**
     * SY --> mihon-webdav 双引擎版：三款保留模型都只支持 2x
     * （Anime4K ACNet(6)、waifu2x CUnet(7)、Anime4K GLSL(8)），其余取值一律收敛到 2。
     * 也被用来兜住旧偏好里残留的、已被移除的模型编号
     * （Real-ESRGAN / Real-CUGAN / sudo UltraCompact / UpConv7 等 ——
     * 它们会先被 [Waifu2x.sanitizeModel] 收敛掉，走不到这里）。
     * SY <--
     */
    fun getEffectiveScale(model: Int, scale: Int): Int {
        return when (model) {
            Waifu2x.MODEL_WAIFU2X_CUNET,
            Waifu2x.MODEL_ANIME4K_ACNET,
            Waifu2x.MODEL_ANIME4K_GLSL,
            -> 2

            else -> scale
        }
    }

    
    /**
     * Clear all cache files for a specific chapter
     */
    fun clearChapterCache(mangaId: Long, chapterId: Long) {
        try {
            val chapterDir = getChapterDir(mangaId, chapterId)
            if (chapterDir.exists()) {
                chapterDir.deleteRecursively()
                android.util.Log.d("ImageEnhancementCache", "Cleared cache for manga $mangaId, chapter $chapterId")
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to clear chapter cache", e)
        }
    }

    /**
     * Check cache size and trim if it exceeds limit (3GB)
     * Should be called from background thread
     */
    fun checkAndTrim(context: Context) {
        // Debounce: only check once every 10 minutes
        if (System.currentTimeMillis() - lastTrimTime < 10 * 60 * 1000) return
        lastTrimTime = System.currentTimeMillis()

        init(context)
        val dir = cacheDir ?: return
        
        try {
            var size = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            if (size > MAX_CACHE_SIZE) {
                android.util.Log.d("ImageEnhancementCache", "Cache size ${size / 1024 / 1024}MB > 3GB, trimming...")
                
                // Get all files sorted by last modified (oldest first)
                val files = dir.walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.lastModified() }
                    .iterator()
                
                while (files.hasNext() && size > MAX_CACHE_SIZE * 0.9) { // Trim to 90%
                    val file = files.next()
                    val len = file.length()
                    if (file.delete()) {
                        size -= len
                    }
                }
                android.util.Log.d("ImageEnhancementCache", "Trim complete, new size: ${size / 1024 / 1024}MB")
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageEnhancementCache", "Failed to trim cache", e)
        }
    }
}

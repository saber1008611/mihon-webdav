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

    // SY --> 2026-09-23 缓存schema版本。
    // 只要「同一份设置」产出的像素语义发生变化（例如：ARM 不再强制 FP32、
    // 灰度兜底重新启用、灰度判据重标定），就必须 +1，否则老用户升级后
    // 命中的旧缓存会一直显示旧观感（画面上的绿点看起来「修了也没用」）。
    // 代价是升级后首次浏览会整章重算一遍，属于一次性的、可接受的。
    //
    // 2026-09-23（v3）：引擎从 MM 换成 TX。两套引擎产出的像素不同，
    // 且缓存键新增了 `_e{engine}` 段；旧缓存（无该段）自然失配，这里再 +1
    // 把旧的 MM 版缓存整体作废，避免「切了引擎却看到旧引擎的图」。
    //
    // 2026-09-23（v4，MihonAc 完美版）：只剩 Anime4K ACNet 一款模型、一套引擎，
    // 缓存键去掉了 `_e{engine}` 段，模型编号恒为 6。旧键与新键本来就不会碰撞，
    // 但仍 +1 明确作废掉双引擎时期留下的缓存（那批图由 sudo / TX 引擎产出，观感不同）。
    private const val CACHE_SCHEMA = 4
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
     * 后者随 SR 侧 Qualcomm NPU 后端一起删除，现在只有 Vulkan 一条路径。
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
    ): String {
        val effectiveScale = getEffectiveScale(model, scale)
        // SY --> 精度也走 sanitize：INT8/BF16 会被引擎收敛成 FP16，
        // 缓存键必须跟着收敛，否则「选了 INT8 又选回 FP16」会拿到两个
        // 实际上完全相同的配置、却各自算一遍的冗余缓存。
        val effectivePrecision = Waifu2x.sanitizePrecision(precision)
        // SY <--
        // SY --> 原先这里还有 `_e{engine}` 段（MM / TX 两套引擎产出的像素不一样，
        // 共用缓存会把 A 引擎的图当成 B 引擎的结果）。MihonAc 完美版只剩一套引擎，
        // 该段已删除，CACHE_SCHEMA 已 +1 作废旧键。
        return "v${CACHE_SCHEMA}_${noise}x${effectiveScale}_m${model}_w${maxWidth}_h${maxHeight}_sw${skipMaxWidth}_sh${skipMaxHeight}_t${tileSize}_p${effectivePrecision}_fa${if (fp16Arithmetic) 1 else 0}"
        // SY <--
    }

    /**
     * SY --> 本构建唯一的模型 Anime4K ACNet(6) 只支持 2x，所以命中时恒返回 2。
     * 旧偏好里残留的模型编号会先被 [Waifu2x.sanitizeModel] 收敛到 6，走不到 else。
     * SY <--
     */
    fun getEffectiveScale(model: Int, scale: Int): Int {
        return when (model) {
            Waifu2x.MODEL_ANIME4K_ACNET -> 2

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

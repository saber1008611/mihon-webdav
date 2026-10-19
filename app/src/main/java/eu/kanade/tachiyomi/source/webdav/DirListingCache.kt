package eu.kanade.tachiyomi.source.webdav

import android.content.Context
import eu.kanade.tachiyomi.data.download.CloudSyncDirectory
import eu.kanade.tachiyomi.data.download.CloudSyncEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import eu.kanade.tachiyomi.util.lang.Hash
import java.io.File

/**
 * SY --> 2026-09-30：目录列表缓存（云盘 WebDAV + 本地图源通用）。
 *
 * 目的：用户抱怨「进文件夹 / 点开一本书看章节列表」长时间转圈。这两处的耗时都在
 * **远端/跨进程的目录枚举**上（WebDAV 是一次 PROPFIND 往返，本地图源是一次跨进程
 * UniFile.listFiles + 逐项 stat）。枚举结果本身很小、变化也不频繁，所以缓存它：
 *
 *  - **新鲜窗口内（[FRESH_WINDOW_MS]）**：直接返回缓存 ⇒ 秒开，同时在后台静默刷新，
 *    让常看的目录一直保持新鲜（陈旧窗口最长 = 一个刷新周期，不会无限累积）；
 *  - **超出窗口或首次进入**：照常拉一次并写缓存（行为与改动前完全一致）；
 *  - **拉取失败时退回陈旧缓存**（有总比空白强），仍没有才返回空列表由调用方降级。
 *
 * ⚠️ 只用于**目录浏览与章节列表**。阅读时的页面列表（散图/PDF 页）刻意不缓存：
 * 那里一旦拿到旧列表会直接导致缺页/错页，缓存收益也远小于浏览路径。
 *
 * 存储：cacheDir/dir_list_cache/<md5(sourceId|path|kind)>.json，原子写（tmp + rename），
 * 带文件数与总字节上限（LRU：按最后修改时间删最旧的）。SY <--
 */
object DirListingCache {

    private const val DIR_NAME = "dir_list_cache"
    private const val KIND_DIRS = "d"
    private const val KIND_FILES = "f"

    private const val MAX_FILES = 3000
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024

    /** 缓存新鲜窗口：窗口内直接用缓存（秒开）+ 后台刷新；超出窗口才同步重新枚举。 */
    const val FRESH_WINDOW_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /**
     * 一次性「强制重新枚举」标记（见 [markStaleDirectory] / [markStaleEntries]）。
     * 源接口（Source.getPopularManga 等）没有 forceRefresh 参数，刷新意图只能由调用方显式登记，
     * 下一次读该键时跳过新鲜窗口、同步重拉并清除标记。
     */
    private val staleKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val lock = Any()
    private var baseDir: File? = null

    private fun base(context: Context): File? = synchronized(lock) {
        // SY --> 2026-10-01：缓存目录一旦创建成功就稳定存在，无需每次访问都做一次磁盘 stat。
        // `base` 在每次读/写缓存（即每次进目录/列章节）都被调用，`File.exists()` 是同步 stat，
        // 正是本类要消灭的那类 IO。仅在尚未创建（null）时才创建并缓存。
        baseDir?.let { return@synchronized it }
        val created = runCatching { File(context.cacheDir, DIR_NAME).apply { mkdirs() } }.getOrNull()
        baseDir = created
        created
        // SY <--
    }

    private fun cacheFile(context: Context, sourceId: Long, path: String, kind: String): File? {
        val dir = base(context) ?: return null
        // SY --> 2026-10-01：改用共享的 Hash（内部已手写 hex，避免每次 `joinToString{ "%02x".format }` 分配）。
        return File(dir, Hash.md5("$sourceId|$path|$kind") + ".json")
        // SY <--
    }

    private fun readObject(context: Context, sourceId: Long, path: String, kind: String): JSONObject? {
        val file = cacheFile(context, sourceId, path, kind) ?: return null
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }
            .onFailure { e -> logcat(LogPriority.WARN, e) { "DirListingCache: 读取失败 $path" } }
            .getOrNull()
    }

    private fun writeObject(
        context: Context,
        sourceId: Long,
        path: String,
        kind: String,
        obj: JSONObject,
    ) {
        runCatching {
            val file = cacheFile(context, sourceId, path, kind) ?: return@runCatching
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(file)) {
                tmp.delete()
            }
        }.onFailure { e -> logcat(LogPriority.WARN, e) { "DirListingCache: 写入失败 $path" } }
        trimIfNeeded(context)
    }

    /** 容量回收：按最后修改时间删最旧的，直到同时满足文件数与总字节上限。 */
    private fun trimIfNeeded(context: Context) {
        runCatching {
            val dir = base(context) ?: return@runCatching
            // ⚠️ 只统计 .json，排除写入途中的 .tmp（.tmp 由 writeObject 自己清理）。
            // 若把它算进去，会把正在写的临时文件当成"最旧"删掉，导致 rename 失败。
            val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
                ?.toMutableList() ?: return@runCatching
            var count = files.size
            var total = files.sumOf { it.length() }
            if (count <= MAX_FILES && total <= MAX_TOTAL_BYTES) return@runCatching
            files.sortBy { it.lastModified() }
            for (f in files) {
                if (count <= MAX_FILES && total <= MAX_TOTAL_BYTES) break
                // ⚠️ 必须同时递减 count（曾经只减 total ⇒ 文件数超限时会一直删到空目录）
                if (f.delete()) {
                    count--
                    total -= f.length()
                }
            }
        }.onFailure { e -> logcat(LogPriority.WARN, e) { "DirListingCache: 容量回收失败" } }
    }

    fun clearAll(context: Context) {
        runCatching {
            base(context)?.listFiles()?.forEach { it.delete() }
        }.onFailure { e -> logcat(LogPriority.WARN, e) { "DirListingCache: 清空失败" } }
        staleKeys.clear()
    }

    private fun keyOf(sourceId: Long, path: String, kind: String) = "$sourceId|$path|$kind"

    /** 标记「该目录的子目录枚举」下次读时强制重拉（下拉刷新 / 扫描封面用）。 */
    fun markStaleDirectory(sourceId: Long, path: String) {
        staleKeys.add(keyOf(sourceId, path, KIND_DIRS))
    }

    /** 标记「该目录的文件枚举」下次读时强制重拉（下拉刷新 / 扫描封面用）。 */
    fun markStaleEntries(sourceId: Long, path: String) {
        staleKeys.add(keyOf(sourceId, path, KIND_FILES))
    }

    // ---- 对外：目录（子文件夹） ----

    suspend fun directories(
        context: Context,
        sourceId: Long,
        path: String,
        fetch: suspend () -> List<CloudSyncDirectory>,
    ): List<CloudSyncDirectory> = read(
        context = context,
        sourceId = sourceId,
        path = path,
        kind = KIND_DIRS,
        decode = ::decodeDirectories,
        encode = ::encodeDirectories,
        fetch = fetch,
    )

    // ---- 对外：文件（章节文件 / 单文件漫画） ----

    suspend fun entries(
        context: Context,
        sourceId: Long,
        path: String,
        fetch: suspend () -> List<CloudSyncEntry>,
    ): List<CloudSyncEntry> = read(
        context = context,
        sourceId = sourceId,
        path = path,
        kind = KIND_FILES,
        decode = ::decodeEntries,
        encode = ::encodeEntries,
        fetch = fetch,
    )

    // ---- 通用读写 + 陈旧回退 ----

    private suspend fun <T> read(
        context: Context,
        sourceId: Long,
        path: String,
        kind: String,
        decode: (JSONObject) -> List<T>,
        encode: (List<T>) -> JSONObject,
        fetch: suspend () -> List<T>,
    ): List<T> {
        val snapshot: Pair<List<T>, Long>? = readObject(context, sourceId, path, kind)?.let { obj ->
            val ts = obj.optLong("t", 0L)
            val list = runCatching { decode(obj) }.getOrNull()
            if (list != null && ts > 0L) list to ts else null
        }

        // 调用方显式登记了「强制刷新」⇒ 跳过新鲜窗口，本次同步重拉（只生效一次）。
        val forced = staleKeys.remove(keyOf(sourceId, path, kind))

        if (snapshot != null && !forced) {
            val age = System.currentTimeMillis() - snapshot.second
            if (age >= 0L && age < FRESH_WINDOW_MS) {
                // SY --> 命中即秒开：降级 DEBUG（debug 构建 / `logcat -v debug` 可见），避免正常浏览刷屏。
                logcat(LogPriority.DEBUG) {
                    "DirListingCache: 命中 $path（${snapshot.first.size} 项，龄 ${age / 1000}s）"
                }
                // SY <--
                refreshInBackground(context, sourceId, path, kind, encode, fetch)
                return snapshot.first
            }
        }

        // SY --> 未命中 / 超窗 / 被强制刷新：同步重拉一次（这是本次浏览的耗时来源）。
        logcat(LogPriority.DEBUG) {
            "DirListingCache: 重拉 $path（${if (forced) "强制刷新" else if (snapshot == null) "首次" else "超窗"}）"
        }
        // SY <--
        val fresh = runCatching { fetch() }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "DirListingCache: 枚举失败 $path" }
            return snapshot?.first ?: emptyList()
        }
        writeObject(context, sourceId, path, kind, encode(fresh))
        return fresh
    }

    private fun <T> refreshInBackground(
        context: Context,
        sourceId: Long,
        path: String,
        kind: String,
        encode: (List<T>) -> JSONObject,
        fetch: suspend () -> List<T>,
    ) {
        val key = "$sourceId|$path|$kind"
        if (!inFlight.add(key)) return
        scope.launch {
            try {
                val fresh = fetch()
                writeObject(context, sourceId, path, kind, encode(fresh))
            } catch (_: Throwable) {
                // 后台刷新失败不影响本次展示，缓存留到下次再刷新
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun encodeDirectories(list: List<CloudSyncDirectory>): JSONObject {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(
                JSONObject()
                    .put("n", d.name)
                    .put("p", d.path)
                    .put("m", d.lastModified),
            )
        }
        return JSONObject().put("t", System.currentTimeMillis()).put("d", arr)
    }

    private fun decodeDirectories(obj: JSONObject): List<CloudSyncDirectory> {
        val arr = obj.optJSONArray("d") ?: return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    CloudSyncDirectory(
                        name = o.optString("n"),
                        path = o.optString("p"),
                        lastModified = o.optLong("m"),
                    ),
                )
            }
        }
    }

    private fun encodeEntries(list: List<CloudSyncEntry>): JSONObject {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("n", e.name)
                    .put("p", e.path)
                    .put("c", e.isCollection)
                    .put("m", e.lastModified),
            )
        }
        return JSONObject().put("t", System.currentTimeMillis()).put("e", arr)
    }

    private fun decodeEntries(obj: JSONObject): List<CloudSyncEntry> {
        val arr = obj.optJSONArray("e") ?: return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    CloudSyncEntry(
                        name = o.optString("n"),
                        path = o.optString("p"),
                        isCollection = o.optBoolean("c"),
                        lastModified = o.optLong("m"),
                    ),
                )
            }
        }
    }
}

package tachiyomi.domain.download.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class DownloadPreferences(
    preferenceStore: PreferenceStore,
) {

    val downloadOnlyOverWifi: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_download_only_over_wifi_key",
        true,
    )

    val saveChaptersAsCBZ: Preference<Boolean> = preferenceStore.getBoolean("save_chapter_as_cbz", true)

    val splitTallImages: Preference<Boolean> = preferenceStore.getBoolean("split_tall_images", true)

    val autoDownloadWhileReading: Preference<Int> = preferenceStore.getInt("auto_download_while_reading", 0)

    val removeAfterReadSlots: Preference<Int> = preferenceStore.getInt("remove_after_read_slots", -1)

    val removeAfterMarkedAsRead: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_remove_after_marked_as_read_key",
        false,
    )

    val removeBookmarkedChapters: Preference<Boolean> = preferenceStore.getBoolean("pref_remove_bookmarked", false)

    val removeExcludeCategories: Preference<Set<String>> = preferenceStore.getStringSet(
        REMOVE_EXCLUDE_CATEGORIES_PREF_KEY,
        emptySet(),
    )

    val downloadNewChapters: Preference<Boolean> = preferenceStore.getBoolean("download_new", false)

    val downloadNewChapterCategories: Preference<Set<String>> = preferenceStore.getStringSet(
        DOWNLOAD_NEW_CATEGORIES_PREF_KEY,
        emptySet(),
    )

    val downloadNewChapterCategoriesExclude: Preference<Set<String>> = preferenceStore.getStringSet(
        DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY,
        emptySet(),
    )

    val downloadNewUnreadChaptersOnly: Preference<Boolean> = preferenceStore.getBoolean(
        "download_new_unread_chapters_only",
        false,
    )

    val parallelSourceLimit: Preference<Int> = preferenceStore.getInt("download_parallel_source_limit", 5)

    val parallelPageLimit: Preference<Int> = preferenceStore.getInt("download_parallel_page_limit", 5)

    // SY --> 从 mihon_img_upscale 移植：下载章节的 WebDAV 云同步。
    // 键名沿用上游，便于两边的配置互相继承。
    val cloudSyncEnabled: Preference<Boolean> = preferenceStore.getBoolean("cloud_sync_enabled", false)

    val cloudSyncUrl: Preference<String> = preferenceStore.getString("cloud_sync_url", "")

    val cloudSyncUsername: Preference<String> = preferenceStore.getString("cloud_sync_username", "")

    val cloudSyncPassword: Preference<String> = preferenceStore.getString("cloud_sync_password", "")

    val cloudSyncDestination: Preference<String> = preferenceStore.getString("cloud_sync_destination", "")

    /** SY --> WebDAV 阅读源的文件夹视图：除默认 destination 外额外浏览的云端目录（相对 WebDAV 根）。 */
    val cloudSyncFolders: Preference<Set<String>> = preferenceStore.getStringSet(
        "cloud_sync_folders",
        emptySet(),
    )

    val cloudSyncDeleteAfterUpload: Preference<Boolean> = preferenceStore.getBoolean(
        "cloud_sync_delete_after_upload",
        false,
    )

    val parallelCloudUploadLimit: Preference<Int> = preferenceStore.getInt("cloud_sync_parallel_upload_limit", 5)

    val cloudUploadedChapterIds: Preference<Set<String>> =
        preferenceStore.getStringSet(CLOUD_UPLOADED_CHAPTER_IDS_PREF_KEY, emptySet())

    val cloudUploadedMetaInfoHashes: Preference<Set<String>> =
        preferenceStore.getStringSet(CLOUD_UPLOADED_META_INFO_HASHES_PREF_KEY, emptySet())

    /** 这一章是否已经同步到云端（重启后仍然有效，避免重复上传）。 */
    fun isChapterUploadedToCloud(chapterId: Long): Boolean {
        return chapterId.toString() in cloudUploadedChapterIds.get()
    }

    fun markChapterUploadedToCloud(chapterId: Long) {
        markChaptersUploadedToCloud(listOf(chapterId))
    }

    fun markChaptersUploadedToCloud(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        cloudUploadedChapterIds.set(cloudUploadedChapterIds.get() + chapterIds.map { it.toString() })
    }

    /** 某本漫画已上传的 meta 内容哈希；内容没变就不用重传。 */
    fun uploadedMetaInfoHash(mangaId: Long): String? {
        return cloudUploadedMetaInfoHashes.get()
            .firstOrNull { it.substringBefore(':') == mangaId.toString() }
            ?.substringAfter(':', "")
            ?.takeIf { it.isNotBlank() }
    }

    fun markMetaInfoUploadedToCloud(mangaId: Long, contentHash: String) {
        val prefix = "$mangaId:"
        val updated = cloudUploadedMetaInfoHashes.get()
            .filterNot { it.startsWith(prefix) }
            .toSet() + "$mangaId:$contentHash"
        cloudUploadedMetaInfoHashes.set(updated)
    }
    // SY <--

    companion object {
        private const val REMOVE_EXCLUDE_CATEGORIES_PREF_KEY = "remove_exclude_categories"
        private const val DOWNLOAD_NEW_CATEGORIES_PREF_KEY = "download_new_categories"
        private const val DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY = "download_new_categories_exclude"

        // SY --> 云同步记录（备份/恢复时按「运行时状态」处理，不随偏好一起导入）
        private const val CLOUD_UPLOADED_CHAPTER_IDS_PREF_KEY = "cloud_uploaded_chapter_ids"
        private const val CLOUD_UPLOADED_META_INFO_HASHES_PREF_KEY = "cloud_uploaded_meta_info_hashes"

        val cloudSyncRecordPreferenceKeys = setOf(
            CLOUD_UPLOADED_CHAPTER_IDS_PREF_KEY,
            CLOUD_UPLOADED_META_INFO_HASHES_PREF_KEY,
        )
        // SY <--

        val categoryPreferenceKeys = setOf(
            REMOVE_EXCLUDE_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY,
        )
    }
}

package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupCredentialGuard
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.sync.service.AliyunPanSyncService
import eu.kanade.tachiyomi.data.sync.service.SyncData
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import logcat.logcat
import kotlinx.serialization.protobuf.ProtoBuf
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.manga.MangaMapper.mapManga
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import java.io.File
import java.io.IOException
import java.util.Date
import kotlin.system.measureTimeMillis

/**
 * A manager to handle synchronization tasks in the app, such as updating
 * sync preferences and performing synchronization with a remote server.
 *
 * @property context The application context.
 */
@Inject
@SingleIn(AppScope::class)
class SyncManager(
    private val context: Context,
    private val database: Database,
    private val syncPreferences: SyncPreferences,
    private val getCategories: GetCategories,
    private val backupCreatorFactory: BackupCreator.Factory,
    private val mangaRestorer: MangaRestorer,
    private val notifier: SyncNotifier,
    private val aliyunPanSyncService: AliyunPanSyncService,
) {
    private val backupCreator: BackupCreator = backupCreatorFactory.create(false)

    enum class SyncService(val value: Int) {
        NONE(0),
        SYNCYOMI(1),
        GOOGLE_DRIVE(2),
        WEBDAV(3),
        ALIYUN_PAN(4),
        ;

        companion object {
            fun fromInt(value: Int) = entries.firstOrNull { it.value == value } ?: NONE
        }
    }

    /**
     * Syncs data with a sync service.
     *
     * This function retrieves local data (favorites, manga, extensions, and categories)
     * from the database using the BackupManager, then synchronizes the data with a sync service.
     */
    suspend fun syncData() {
        // Reset isSyncing in case it was left over or failed syncing during restore.
        database.transaction {
            database.mangasQueries.resetIsSyncing()
            database.chaptersQueries.resetIsSyncing()
            // 注：categories 表没有 isSyncing 列（mihonW 未做 SY 的分类同步标记），故不重置
        }

        val syncOptions = syncPreferences.getSyncSettings()
        val databaseManga = getAllMangaThatNeedsSync()

        val backupOptions = BackupOptions(
            libraryEntries = syncOptions.libraryEntries,
            categories = syncOptions.categories,
            chapters = syncOptions.chapters,
            tracking = syncOptions.tracking,
            history = syncOptions.history,
            extensionStores = syncOptions.extensionStores,
            appSettings = syncOptions.appSettings,
            sourceSettings = syncOptions.sourceSettings,
            privateSettings = syncOptions.privateSettings,

            // mihonW 的 BackupOptions 没有 customInfo / savedSearches（无「保存的搜索」功能）
            readEntries = syncOptions.readEntries,
        )

        logcat(LogPriority.DEBUG) { "Begin create backup" }
        val backupManga = backupCreator.backupMangas(databaseManga, backupOptions)
        val backup = Backup(
            backupManga = backupManga,
            backupCategories = backupCreator.backupCategories(backupOptions),
            backupSources = backupCreator.backupSources(backupManga),
            backupPreferences = backupCreator.backupAppPreferences(backupOptions),
            backupSourcePreferences = backupCreator.backupSourcePreferences(backupOptions),
            backupExtensionStores = backupCreator.backupExtensionStores(backupOptions),

            // mihonW 没有「保存的搜索」功能，该字段恒为空（仅为兼容云端备份格式）
            backupSavedSearches = emptyList(),
        )
        logcat(LogPriority.DEBUG) { "End create backup" }

        // Create the SyncData object
        val syncData = SyncData(
            deviceId = syncPreferences.uniqueDeviceID(),
            backup = backup,
        )

        // Handle sync based on the selected service
        // mihonW 只保留阿里云盘一种远端（WebDAV / GoogleDrive / SyncYomi 未移植）
        val syncService = when (val syncService = SyncService.fromInt(syncPreferences.syncService.get())) {
            SyncService.ALIYUN_PAN -> aliyunPanSyncService

            else -> {
                logcat(LogPriority.ERROR) { "Invalid sync service type: $syncService" }
                null
            }
        }

        val remoteBackup = syncService?.doSync(syncData)

        if (remoteBackup == null) {
            logcat(LogPriority.DEBUG) { "Skip restore due to network issues" }
            // should we call showSyncError?
            return
        }

        if (remoteBackup === syncData.backup) {
            // nothing changed
            logcat(LogPriority.DEBUG) { "Skip restore due to remote was overwrite from local" }
            syncPreferences.lastSyncTimestamp.set(Date().time)
            notifier.showSyncSuccess(context.stringResource(MR.strings.sync_success_completed))
            return
        }

        // Stop the sync early if the remote backup is null or empty
        if (remoteBackup.backupManga.isEmpty() && remoteBackup.backupCategories.isEmpty() && remoteBackup.backupSources.isEmpty()) {
            notifier.showSyncError(context.stringResource(MR.strings.sync_no_remote_data))
            return
        }

        // Check if it's first sync based on lastSyncTimestamp
        if (syncPreferences.lastSyncTimestamp.get() == 0L && databaseManga.isNotEmpty()) {
            // It's first sync no need to restore data. (just update remote data)
            syncPreferences.lastSyncTimestamp.set(Date().time)
            notifier.showSyncSuccess(context.stringResource(MR.strings.sync_updated_remote))
            return
        }

        val (filteredFavorites, nonFavorites) = filterFavoritesAndNonFavorites(remoteBackup)
        updateNonFavorites(nonFavorites)

        val newSyncData = backup.copy(
            backupManga = filteredFavorites,
            // SY -->
            // 这份合并结果是要原样推回远端的，所以「下载」在这里也要挡一道：
            // `BackupCreator` 只保证**本机新生成**的那份不含它，远端可能还留着
            // 以前版本同步上去的那一条，不挡的话它会被一直带着来回传。
            backupCategories = remoteBackup.backupCategories,
            // SY <--
            backupSources = remoteBackup.backupSources,
            backupPreferences = remoteBackup.backupPreferences
                // SY 2026-10-08：合并回推前先清洗凭据键——远端可能还留着旧版本
                // （凭据过滤上线前 / 1.0.29 泄漏 115 Open token 期间）同步上去的
                // Cookie / OAuth 令牌，不洗的话会被一直带着来回传并恢复到本机。
                .let(BackupCredentialGuard::withoutCredentials),
            backupSourcePreferences = remoteBackup.backupSourcePreferences,
            backupExtensionStores = remoteBackup.backupExtensionStores,

            // SY -->
            backupSavedSearches = remoteBackup.backupSavedSearches,
            // SY <--
        )

        val hasMangaChanges = filteredFavorites.isNotEmpty()
        val hasCategoryChanges = remoteBackup.backupCategories != backup.backupCategories
        val hasSourceChanges = remoteBackup.backupSources != backup.backupSources
        val hasPreferenceChanges = remoteBackup.backupPreferences != backup.backupPreferences
        val hasSourcePreferenceChanges = remoteBackup.backupSourcePreferences != backup.backupSourcePreferences
        val hasExtensionRepoChanges = remoteBackup.backupExtensionStores != backup.backupExtensionStores
        val hasSavedSearchChanges = remoteBackup.backupSavedSearches != backup.backupSavedSearches

        if (!hasMangaChanges && !hasCategoryChanges && !hasSourceChanges &&
            !hasPreferenceChanges && !hasSourcePreferenceChanges &&
            !hasExtensionRepoChanges && !hasSavedSearchChanges
        ) {
            // update the sync timestamp
            syncPreferences.lastSyncTimestamp.set(Date().time)
            notifier.showSyncSuccess(context.stringResource(MR.strings.sync_success_completed))
            return
        }

        // 注：上游此处会删除「远端没有的本地分类」以实现删除同步。
        // mihonW 的 Category 没有 uid / version / lastModifiedAt，且本项目不希望在双向同步中
        // 让一端删掉另一端仍在使用的内容（用户已确认：不做删除推断），故整段移除。
        // 分类以「并集合并」处理（见 SyncService.mergeCategoriesLists），永远只增不删。

        val backupUri = writeSyncDataToCache(context, newSyncData)
        logcat(LogPriority.DEBUG) { "Got Backup Uri: $backupUri" }
        if (backupUri != null) {
            Log.i(
                "WebDavSync",
                "starting restore job: isSync=true, options=[library=${syncOptions.libraryEntries}, categories=${syncOptions.categories}, " +
                    "appSettings=${syncOptions.appSettings}, sourceSettings=${syncOptions.sourceSettings}, " +
                    "extStores=${syncOptions.extensionStores}]",
            )
            BackupRestoreJob.start(
                context.workManager,
                backupUri,
                sync = true,
                options = RestoreOptions(
                    appSettings = syncOptions.appSettings,
                    sourceSettings = syncOptions.sourceSettings,
                    libraryEntries = syncOptions.libraryEntries,
                    categories = syncOptions.categories,
                    extensionStores = syncOptions.extensionStores,
                ),
            )

            // update the sync timestamp
            syncPreferences.lastSyncTimestamp.set(Date().time)
        } else {
            logcat(LogPriority.ERROR) { "Failed to write sync data to file" }
        }
    }

    private fun writeSyncDataToCache(context: Context, backup: Backup): Uri? {
        val cacheFile = File(context.cacheDir, "tachiyomi_sync_data.proto.gz")
        return try {
            cacheFile.outputStream().use { output ->
                output.write(ProtoBuf.encodeToByteArray(Backup.serializer(), backup))
                Uri.fromFile(cacheFile)
            }
        } catch (e: IOException) {
            logcat(LogPriority.ERROR) { "Failed to write sync data to cache: ${e.message}" }
            null
        }
    }

    /**
     * Retrieves all manga from the local database.
     *
     * @return a list of all manga stored in the database
     */
    private suspend fun getAllMangaFromDB(): List<Manga> {
        return database.mangasQueries
            .getAllManga(::mapManga)
            .awaitAsList()
    }

    private suspend fun getAllMangaThatNeedsSync(): List<Manga> {
        return database.mangasQueries
            .getMangasWithFavoriteTimestamp(::mapManga)
            .awaitAsList()
    }

    private suspend fun isMangaDifferent(localManga: Manga, remoteManga: BackupManga): Boolean {
        val localChapters = database.chaptersQueries.getChaptersByMangaId(localManga.id, 0).awaitAsList()
        val localCategories = getCategories.await(localManga.id).map { it.order }

        if (areChaptersDifferent(localChapters, remoteManga.chapters)) {
            return true
        }

        if (localManga.version != remoteManga.version) {
            return true
        }

        if (localCategories.toSet() != remoteManga.categories.toSet()) {
            return true
        }

        return false
    }

    private fun areChaptersDifferent(localChapters: List<Chapters>, remoteChapters: List<BackupChapter>): Boolean {
        val localChapterMap = localChapters.associateBy { it.url }
        val remoteChapterMap = remoteChapters.associateBy { it.url }

        if (localChapterMap.size != remoteChapterMap.size) {
            return true
        }

        for ((url, localChapter) in localChapterMap) {
            val remoteChapter = remoteChapterMap[url]

            // If a matching remote chapter doesn't exist, or the version numbers are different, consider them different
            if (remoteChapter == null || localChapter.version != remoteChapter.version) {
                return true
            }
        }

        return false
    }

    /**
     * Filters the favorite and non-favorite manga from the backup and checks
     * if the favorite manga is different from the local database.
     * @param backup the Backup object containing the backup data.
     * @return a Pair of lists, where the first list contains different favorite manga
     * and the second list contains non-favorite manga.
     */
    private suspend fun filterFavoritesAndNonFavorites(backup: Backup): Pair<List<BackupManga>, List<BackupManga>> {
        val favorites = mutableListOf<BackupManga>()
        val nonFavorites = mutableListOf<BackupManga>()
        val logTag = "filterFavoritesAndNonFavorites"

        val elapsedTimeMillis = measureTimeMillis {
            val databaseManga = getAllMangaFromDB()
            val localMangaMap = databaseManga.associateBy {
                Pair(it.source, it.url)
            }

            logcat(LogPriority.DEBUG, logTag) { "Starting to filter favorites and non-favorites from backup data." }

            backup.backupManga.forEach { remoteManga ->
                val compositeKey = Pair(remoteManga.source, remoteManga.url)
                val localManga = localMangaMap[compositeKey]
                when {
                    // Checks if the manga is in favorites and needs updating or adding
                    remoteManga.favorite -> {
                        if (localManga == null || isMangaDifferent(localManga, remoteManga)) {
                            logcat(LogPriority.DEBUG, logTag) { "Adding to favorites: ${remoteManga.title}" }
                            favorites.add(remoteManga)
                        } else {
                            logcat(LogPriority.DEBUG, logTag) { "Already up-to-date favorite: ${remoteManga.title}" }
                        }
                    }
                    // Handle non-favorites
                    !remoteManga.favorite -> {
                        logcat(LogPriority.DEBUG, logTag) { "Adding to non-favorites: ${remoteManga.title}" }
                        nonFavorites.add(remoteManga)
                    }
                }
            }
        }

        val minutes = elapsedTimeMillis / 60000
        val seconds = (elapsedTimeMillis % 60000) / 1000
        logcat(LogPriority.DEBUG, logTag) {
            "Filtering completed in ${minutes}m ${seconds}s. Favorites found: ${favorites.size}, " +
                "Non-favorites found: ${nonFavorites.size}"
        }

        return Pair(favorites, nonFavorites)
    }

    /**
     * Updates the non-favorite manga in the local database with their favorite status from the backup.
     * @param nonFavorites the list of non-favorite BackupManga objects from the backup.
     */
    private suspend fun updateNonFavorites(nonFavorites: List<BackupManga>) {
        val localMangaList = getAllMangaFromDB()

        val localMangaMap = localMangaList.associateBy { Pair(it.source, it.url) }

        nonFavorites.forEach { nonFavorite ->
            val key = Pair(nonFavorite.source, nonFavorite.url)
            localMangaMap[key]?.let { localManga ->
                if (localManga.favorite != nonFavorite.favorite) {
                    val updatedManga = localManga.copy(favorite = nonFavorite.favorite)
                    mangaRestorer.updateManga(updatedManga)
                }
            }
        }
    }
}

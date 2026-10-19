package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import android.util.Log
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Sync service that stores the backup in an Aliyun Pan (阿里云盘) account
 * using the web API exposed by [AliyunPanService].
 *
 * The file layout mirrors the WebDAV sync service: a gzipped protobuf backup plus
 * a small text file holding the device id of the last device that synced.
 */
@Inject
@SingleIn(AppScope::class)
class AliyunPanSyncService(
    context: Context,
    syncPreferences: SyncPreferences,
    private val aliyunPanService: AliyunPanService,
    private val protoBuf: ProtoBuf,
) : SyncService(
    context,
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
    syncPreferences,
) {
    private val tag = "AliyunPanSync"

    private val appName = context.stringResource(MR.strings.app_name)

    private val syncFolderName = "MIhonW"

    private val remoteFileName = "${appName}_sync.proto.gz"
    private val deviceFileName = "${appName}_sync.device"

    enum class DeleteSyncDataStatus {
        NOT_CONFIGURED,
        NO_FILES,
        SUCCESS,
        ERROR,
    }

    enum class ConnectionTestStatus {
        NOT_CONFIGURED,
        SUCCESS,
        FAILED,
    }

    override suspend fun doSync(syncData: SyncData): Backup? {
        beforeSync()
        debugLog("doSync start: local manga=${syncData.backup?.backupManga?.size}, categories=${syncData.backup?.backupCategories?.size}")
        Log.i(tag, "doSync start: local manga=${syncData.backup?.backupManga?.size}, categories=${syncData.backup?.backupCategories?.size}")

        try {
            val remoteSData = pullSyncData()

            if (remoteSData != null) {
                // Get local unique device ID
                val localDeviceId = syncPreferences.uniqueDeviceID()
                val lastSyncDeviceId = remoteSData.deviceId

                Log.i(tag, "device IDs: local=$localDeviceId, remote=$lastSyncDeviceId; remote manga=${remoteSData.backup?.backupManga?.size}")

                // Safety guard: never let an empty local library wipe a non-empty remote backup.
                // If this device has no manga but the server still has data, pull the remote data
                // instead of overwriting it (e.g. after clearing app data).
                if (syncData.backup?.backupManga.isNullOrEmpty() && !remoteSData.backup?.backupManga.isNullOrEmpty()) {
                    Log.i(tag, "branch=restore-from-remote (local empty, remote has data)")
                    val restored = remoteSData.copy(deviceId = localDeviceId)
                    pushSyncData(restored)
                    return restored.backup
                }

                // Check if the last sync was done by the same device; if so overwrite the
                // remote data with the local data, otherwise merge both.
                return if (lastSyncDeviceId == localDeviceId) {
                    Log.i(tag, "branch=overwrite-remote (same device)")
                    pushSyncData(syncData)
                    syncData.backup
                } else {
                    Log.i(tag, "branch=merge (different device)")
                    val mergedSyncData = mergeSyncData(syncData, remoteSData)
                    pushSyncData(mergedSyncData)
                    mergedSyncData.backup
                }
            }

            Log.i(tag, "branch=push-only (no remote data)")
            pushSyncData(syncData)
            return syncData.backup
        } catch (e: Exception) {
            debugLog("doSync error: ${e.stackTraceToString()}")
            Log.e(tag, "doSync error: ${e.message}", e)
            return null
        }
    }

    private fun debugLog(message: String) {
        try {
            val file = File(context.getExternalFilesDir(null), "aliyun_sync_debug.log")
            file.appendText("${System.currentTimeMillis()}: $message\n")
        } catch (_: Exception) {
            // diagnostics must never break sync
        }
    }

    private suspend fun beforeSync() {
        if (syncPreferences.aliyunPanRefreshToken.get().isBlank()) {
            throw Exception(context.stringResource(MR.strings.aliyun_pan_not_configured))
        }
    }

    /** Resolves the drive id and the sync folder id, creating the folder if needed. */
    private suspend fun syncFolderInfo(): Pair<String, String> = withIOContext {
        val driveId = syncPreferences.aliyunPanDriveId.get().ifBlank {
            aliyunPanService.getUserInfo().defaultDriveId
        }
        if (driveId.isBlank()) {
            throw Exception(context.stringResource(MR.strings.aliyun_pan_not_configured))
        }
        val folderId = aliyunPanService.ensureSyncFolder(driveId, syncFolderName)
        driveId to folderId
    }

    private suspend fun pullSyncData(): SyncData? = withIOContext {
        val (driveId, folderId) = syncFolderInfo()
        val files = aliyunPanService.listFiles(driveId, folderId)

        val remoteFile = files.firstOrNull { it.name == remoteFileName }
        if (remoteFile == null) {
            Log.i(tag, "pullSyncData: no remote file")
            return@withIOContext null
        }

        val deviceIdFile = files.firstOrNull { it.name == deviceFileName }
        val deviceId = if (deviceIdFile != null) {
            try {
                val deviceUrl = aliyunPanService.getDownloadUrl(driveId, deviceIdFile.fileId)
                aliyunPanService.download(deviceUrl).toString(Charsets.UTF_8).trim()
            } catch (e: Exception) {
                Log.w(tag, "pullSyncData: failed to read device id file: ${e.message}")
                ""
            }
        } else {
            ""
        }

        val backup = try {
            val url = aliyunPanService.getDownloadUrl(driveId, remoteFile.fileId)
            val bytes = aliyunPanService.download(url)
            GZIPInputStream(bytes.inputStream()).use { gzipInputStream ->
                protoBuf.decodeFromByteArray(Backup.serializer(), gzipInputStream.readBytes())
            }
        } catch (e: Exception) {
            Log.e(tag, "pullSyncData: decode error: ${e.message}", e)
            throw Exception("Failed to download sync data: ${e.message}", e)
        }

        Log.i(tag, "pullSyncData: got backup with ${backup.backupManga.size} manga, ${backup.backupCategories.size} categories, ${backup.backupSavedSearches.size} saved searches")

        SyncData(deviceId = deviceId, backup = backup)
    }

    private suspend fun pushSyncData(syncData: SyncData) = withIOContext {
        val backup = syncData.backup ?: return@withIOContext

        val byteArray = protoBuf.encodeToByteArray(Backup.serializer(), backup)
        if (byteArray.isEmpty()) {
            throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
        }

        val gzipped = ByteArrayOutputStream().use { outputStream ->
            GZIPOutputStream(outputStream).use { gzipOutputStream ->
                gzipOutputStream.write(byteArray)
            }
            outputStream.toByteArray()
        }

        Log.i(tag, "pushSyncData: uploading ${gzipped.size} bytes (deviceId=${syncData.deviceId})")

        val (driveId, folderId) = syncFolderInfo()
        aliyunPanService.uploadFile(driveId, folderId, remoteFileName, gzipped)
        aliyunPanService.uploadFile(driveId, folderId, deviceFileName, syncData.deviceId.toByteArray(Charsets.UTF_8))

        Log.i(tag, "pushSyncData: upload complete")
    }

    suspend fun deleteSyncDataFromAliyunPan(): DeleteSyncDataStatus {
        if (syncPreferences.aliyunPanRefreshToken.get().isBlank()) {
            return DeleteSyncDataStatus.NOT_CONFIGURED
        }

        return withIOContext {
            try {
                val (driveId, folderId) = syncFolderInfo()
                val files = aliyunPanService.listFiles(driveId, folderId)
                val syncFiles = files.filter { it.name == remoteFileName || it.name == deviceFileName }
                if (syncFiles.isEmpty()) {
                    DeleteSyncDataStatus.NO_FILES
                } else {
                    aliyunPanService.deleteFiles(driveId, syncFiles.map { it.fileId })
                    DeleteSyncDataStatus.SUCCESS
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Error purging Aliyun Pan sync data: ${e.message}" }
                DeleteSyncDataStatus.ERROR
            }
        }
    }

    suspend fun testConnection(): ConnectionTestStatus {
        if (syncPreferences.aliyunPanRefreshToken.get().isBlank()) {
            return ConnectionTestStatus.NOT_CONFIGURED
        }

        return withIOContext {
            try {
                val userInfo = aliyunPanService.getUserInfo()
                if (userInfo.userId.isNotBlank()) {
                    logcat(LogPriority.INFO) { "Aliyun Pan connection successful: ${userInfo.nickName}" }
                    ConnectionTestStatus.SUCCESS
                } else {
                    ConnectionTestStatus.FAILED
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Aliyun Pan connection test failed: ${e.message}" }
                ConnectionTestStatus.FAILED
            }
        }
    }
}

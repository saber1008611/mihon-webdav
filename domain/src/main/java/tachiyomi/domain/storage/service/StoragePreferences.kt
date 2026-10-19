package tachiyomi.domain.storage.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.FolderProvider

@Inject
@SingleIn(AppScope::class)
class StoragePreferences(
    folderProvider: FolderProvider,
    preferenceStore: PreferenceStore,
) {

    val baseStorageDirectory: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("storage_dir"),
        folderProvider.path(),
    )

    /** SY --> 本地图源额外根目录（SAF URI 集合）；默认 local 文件夹始终包含，不受此项影响。 */
    val localSourceExtraDirectories: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("local_source_extra_directories"),
        emptySet(),
    )
    // SY <--
}

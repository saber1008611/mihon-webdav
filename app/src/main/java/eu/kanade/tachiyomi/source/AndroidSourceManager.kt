package eu.kanade.tachiyomi.source

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.netdisk.aliyun.AliyunPanSource
import eu.kanade.tachiyomi.source.netdisk.cloud115open.Cloud115OpenSource
import eu.kanade.tachiyomi.source.netdisk.quark.QuarkSource
import eu.kanade.tachiyomi.source.webdav.LocalWebDavSource
import eu.kanade.tachiyomi.source.webdav.WebDavSource
import java.util.concurrent.ConcurrentHashMap

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidSourceManager(
    private val extensionManager: ExtensionManager,
    private val sourceRepository: StubSourceRepository,
    private val webDavSource: WebDavSource,
    private val localWebDavSource: LocalWebDavSource,
    private val quarkSource: QuarkSource,
    private val aliyunPanSource: AliyunPanSource,
    private val cloud115OpenSource: Cloud115OpenSource,
    private val downloadManager: Lazy<DownloadManager>,
) : SourceManager {

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    /**
     * Null until the extensions have loaded, so that nothing observes the empty seed value.
     */
    private val sourcesMapFlow = MutableStateFlow<Map<Long, Source>?>(null)

    private val stubSourcesMap = ConcurrentHashMap<Long, StubSource>()

    override val sources: Flow<List<Source>> = sourcesMapFlow
        .filterNotNull()
        .map { it.values.toList() }

    init {
        scope.launch {
            extensionManager.loadedExtensionsFlow
                .collectLatest { extensions ->
                    val mutableMap = ConcurrentHashMap<Long, Source>(
                        mapOf(
                            // SY --> 本地文件夹版 WebDAV 图源：接管 LocalSource.ID 槽位，
                            // 旧 LocalSource 不再注册，书架里已有的本地漫画（source=LocalSource.ID）自动归到本源。
                            LocalSource.ID to localWebDavSource,
                            // SY --> 路线 A 方案 1：把 WebDAV 云盘源注册进源列表，使其可浏览/阅读。
                            WebDavSource.ID to webDavSource,
                            // SY --> 2026-10-05：网盘源（115 / 夸克）——与 WebDAV 共用「文件夹样式」浏览逻辑。
                            QuarkSource.ID to quarkSource,
                            AliyunPanSource.ID to aliyunPanSource,
                            // SY --> 2026-10-08：115 开放平台（OAuth）源，与 Cookie 模式双轨并存。
                            Cloud115OpenSource.ID to cloud115OpenSource,
                            // SY <--
                        ),
                    )
                    extensions.forEach { extension ->
                        extension.sources.forEach {
                            mutableMap[it.id] = it
                            registerStubSource(StubSource.from(it))
                        }
                    }
                    sourcesMapFlow.value = mutableMap
                }
        }

        scope.launch {
            sourceRepository.subscribeAll()
                .collectLatest { sources ->
                    val mutableMap = stubSourcesMap.toMutableMap()
                    sources.forEach {
                        mutableMap[it.id] = it
                    }
                }
        }
    }

    /**
     * Awaits the extensions to have loaded before returning the sources.
     */
    private suspend fun sourcesMap(): Map<Long, Source> = sourcesMapFlow.filterNotNull().first()

    override suspend fun get(sourceKey: Long): Source? {
        return sourcesMap()[sourceKey]
    }

    override suspend fun getOrStub(sourceKey: Long): Source {
        return sourcesMap()[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            createStubSource(sourceKey)
        }
    }

    override suspend fun getAll(): List<Source> {
        return sourcesMap().values.toList()
    }

    override suspend fun getOnlineSources(): List<HttpSource> {
        return sourcesMap().values.filterIsInstance<HttpSource>()
    }

    override suspend fun getStubSources(): List<StubSource> {
        val onlineSourceIds = getOnlineSources().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    private fun registerStubSource(source: StubSource) {
        scope.launch {
            val dbSource = sourceRepository.getStubSource(source.id)
            if (dbSource == source) return@launch
            sourceRepository.upsertStubSource(source.id, source.lang, source.name)
            if (dbSource != null) {
                downloadManager.value.renameSource(dbSource, source)
            }
        }
    }

    private suspend fun createStubSource(id: Long): StubSource {
        sourceRepository.getStubSource(id)?.let {
            return it
        }
        extensionManager.getSourceData(id)?.let {
            registerStubSource(it)
            return it
        }
        return StubSource(id = id, lang = "", name = "")
    }
}

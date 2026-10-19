package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.net.Uri
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.source.HeavyChapterLoadSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangasBySource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.isLocal
import java.io.File
import java.util.Date
import kotlin.time.Clock

/**
 * Presenter used by the activity to perform background operations.
 */
@AssistedInject
class ReaderViewModel(
    @Assisted private val savedState: SavedStateHandle,
    private val context: Context,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val imageSaver: ImageSaver,
    val readerPreferences: ReaderPreferences,
    private val basePreferences: BasePreferences,
    private val downloadPreferences: DownloadPreferences,
    private val trackPreferences: TrackPreferences,
    private val trackChapter: TrackChapter,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getNextChapters: GetNextChapters,
    private val upsertHistory: UpsertHistory,
    private val updateChapter: UpdateChapter,
    private val setMangaViewerFlags: SetMangaViewerFlags,
    private val getIncognitoState: GetIncognitoState,
    private val libraryPreferences: LibraryPreferences,
    private val coverManager: LocalCoverManager,
    private val updateManga: UpdateManga,
    private val coverCache: CoverCache,
    private val chapterCache: ChapterCache,
    private val downloadCache: DownloadCache,
    // SY --> 2026-09-29 Part 2：整部看完自动衔接下一部漫画
    private val getLibraryManga: GetLibraryManga,
    private val getMangasBySource: GetMangasBySource,
    private val updateMangaFromRemote: mihon.domain.source.interactor.UpdateMangaFromRemote,
    // SY <--
    // SY --> 阿里云盘双向备份同步（移植自 TachiyomiX）
    private val syncPreferences: eu.kanade.domain.sync.SyncPreferences,
    // SY <--
) : ViewModel() {

    @AssistedFactory
    @ViewModelAssistedFactoryKey(ReaderViewModel::class)
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): ReaderViewModel {
            return create(extras.createSavedStateHandle())
        }

        fun create(@Assisted savedState: SavedStateHandle): ReaderViewModel
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    /**
     * Ids of the manga and chapter the reader was launched with, taken from the activity intent.
     */
    // SY --> 2026-09-29 Part 2：原地换书（本作读完 → 下一部）需要能改写当前 manga id。
    var mangaId = savedState.get<Long>("manga") ?: -1L
        private set
    // SY <--
    private val initialChapterId = savedState.get<Long>("chapter") ?: -1L

    val hasValidArgs = mangaId != -1L && initialChapterId != -1L

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    /**
     * The source of the manga loaded in the reader. Null until it has been resolved.
     */
    val source: Source?
        get() = state.value.source

    // SY --> 2026-10-18：阅读器「加载期背景」用的封面文件（自定义封面优先）。
    //
    // 打开章节时要先等源返回页列表 + 第一页数据，这期间屏幕上既没有 holder 也没有页面数据，
    // 只有 readerContainer 的底色（深色主题 = 纯黑）。用本作封面做底，既不是纯黑也不是纯白，
    // 且与当前作品相关。
    fun loadingBackdropFile(): File? {
        val manga = manga ?: return null
        return coverCache.getCustomCoverFile(manga.id).takeIf { it.exists() }
            ?: coverCache.getCoverFile(manga.thumbnailUrl)?.takeIf { it.exists() }
    }
    // SY <--

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    private var chapterToDownload: Download? = null

    private val unfilteredChapterList by lazy {
        val manga = manga!!
        runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = false) }
    }

    /**
     * Chapter list for the active manga. It's retrieved lazily and should be accessed for the first
     * time in a background thread to avoid blocking the UI.
     *
     * SY --> 2026-09-29 Part 2：由 `by lazy` 改为**显式赋值** —— lazy 只算一次，原地换书后
     * 会一直沿用旧 manga 的章节列表。现在由 [buildChapterList] 构建、在 [init] 与
     * [switchToManga] 里各赋一次。
     * SY <--
     */
    private var chapterList: List<ReaderChapter> = emptyList()

    /** SY 2026-09-29 Part 2：原 `chapterList` lazy 块的内容，逐字搬迁为可重复调用的构建函数。 */
    private fun buildChapterList(): List<ReaderChapter> {
        val manga = manga!!
        val chapters = runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) }

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader = when {
            (readerPreferences.skipRead.get() || readerPreferences.skipFiltered.get()) -> {
                val filteredChapters = chapters.filterNot {
                    when {
                        readerPreferences.skipRead.get() && it.read -> true
                        readerPreferences.skipFiltered.get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                                        !downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                                        downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark)
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        return chaptersForReader
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(selectedChapter)
                } else {
                    this
                }
            }
            .run {
                if (basePreferences.downloadedOnly.get()) {
                    filterDownloaded(manga, downloadCache)
                } else {
                    this
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    private var incognitoMode: Boolean = false
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading.get()

    init {
        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { currentChapter ->
                if (chapterPageIndex >= 0) {
                    // Restore from SavedState
                    currentChapter.requestedPage = chapterPageIndex
                } else if (!currentChapter.chapter.read) {
                    currentChapter.requestedPage = currentChapter.chapter.last_page_read
                }
                chapterId = currentChapter.chapter.id!!
            }
            .launchIn(viewModelScope)

        if (hasValidArgs) {
            viewModelScope.launch { init() }
        }
    }

    override fun onCleared() {
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
        // SY --> 超分移植：离开阅读器时把后台增强队列彻底清空，避免已销毁的章节继续占 GPU。
        ImageEnhancer.cancelAll("reader view model cleared")
        // SY <--
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        deletePendingChapters()
    }

    /**
     * Initializes this presenter with the [mangaId] and [initialChapterId] the reader was launched
     * with. This method will fetch the manga from the database and initialize the initial chapter.
     * Failures are reported through [State.initError].
     */
    private suspend fun init() {
        withIOContext {
            try {
                val manga = getManga.await(mangaId) ?: error("Requested manga of id $mangaId not found")
                val source = sourceManager.getOrStub(manga.source)
                incognitoMode = getIncognitoState.await(manga.source)
                mutableState.update { it.copy(manga = manga, source = source) }
                if (chapterId == -1L) chapterId = initialChapterId

                loader = ChapterLoader(context, downloadManager, downloadProvider, chapterCache, manga, source)

                // SY --> 超分移植：进入阅读器先把增强队列与目标页重置到本章起始页。
                // 上一个会话可能还留着别的章节的任务，不清掉会和新章节抢 GPU。
                ImageEnhancer.reset(0)
                // SY <--

                // SY 2026-09-29 Part 2：chapterList 由 lazy 改为显式构建（见字段注释）。
                chapterList = buildChapterList()

                loadChapter(loader!!, chapterList.first { chapterId == it.chapter.id })
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                mutableState.update { it.copy(initError = e) }
            }
        }
    }

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter,
    ): ViewerChapters {
        loader.loadChapter(chapter)

        // SY --> 2026-09-29：把「当前页」（本章 last_page_read）先设为增强目标页。
        // （原「整话超分」开关及其整章入队钩子已按用户要求删除，其余增强行为不变。）
        val enhanceStartPage = chapter.requestedPage.coerceAtLeast(0)
        ImageEnhancer.setTargetPage(enhanceStartPage)
        // SY <--

        val chapterPos = chapterList.indexOf(chapter)
        val newChapters = ViewerChapters(
            chapter,
            chapterList.getOrNull(chapterPos - 1),
            chapterList.getOrNull(chapterPos + 1),
        )

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
                it.copy(
                    viewerChapters = newChapters,
                    bookmarked = newChapters.currChapter.chapter.bookmark,
                )
            }
        }
        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            updateHistory()
            restartReadTimer()

            try {
                loadChapter(loader, chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            return
        }

        // SY --> 2026-09-28 修订（原为「一律跳过」，现放开）：
        //
        // 原先对 HeavyChapterLoadSource（WebDAV 云盘 / 本地文件夹图源）**一刀切跳过**预载，
        // 理由是「一章 = 220–370MB 整本，预载相邻章 = 再下一整本」。
        // 但代价是：这类源滑到章末的「下一话」过渡页时，目标章**从未被加载** —— 用户表现为
        // 「WebDAV / 本地图源不像在线漫画那样无缝衔接下一话」（正是本次要修的问题）。
        //
        // 现在恢复预载，但**只在调用方已收敛到章末的时机**才真的发生，不会一进章就整本预拉：
        //   · 条漫：本章剩余 ≤5 页（WebtoonViewer.onPageSelected 的 inPreloadRange）
        //   · 翻页：过渡页被选中时（PagerViewer / 过渡 holder 的 requestPreloadChapter）
        // 即「读到章末才开始取下一话」，兼顾无缝衔接与带宽/缓存。
        // HttpSource 预载只取页列表 + 少量图，本就便宜，不受影响。
        // SY <--

        if (chapter.pageLoader?.isLocal == false) {
            val manga = manga ?: return
            val dbChapter = chapter.chapter
            val source = state.value.source ?: return
            val isDownloaded = downloadManager.isChapterDownloadedOnDisk(
                dbChapter.name,
                dbChapter.scanlator,
                dbChapter.url,
                manga.title,
                source,
            )
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    fun onViewerLoaded(viewer: Viewer?) {
        mutableState.update {
            it.copy(viewer = viewer)
        }
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage) {
        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return

        // Save last page read and mark as read if needed
        viewModelScope.launchNonCancellable {
            updateChapterProgress(selectedChapter, page)
        }

        if (selectedChapter != getCurrentChapter()) {
            logcat { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter)
        }

        val inDownloadRange = page.number.toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        eventChannel.trySend(Event.PageChanged)
    }

    private fun downloadNextChapters() {
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        viewModelScope.launchIO {
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                nextChapter.url,
                manga.title,
                manga.source,
            )
            if (!isNextChapterDownloaded) return@launchIO

            val chaptersToDownload = getNextChapters.await(manga.id, nextChapter.id!!).run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(nextChapter.toDomainChapter()!!)
                } else {
                    this
                }
            }.take(downloadAheadAmount)

            downloadManager.downloadChapters(
                manga,
                chaptersToDownload,
            )
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots.get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        val currentChapterPosition = chapterList.indexOf(currentChapter)
        val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete)
        }
    }

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(readerChapter: ReaderChapter, page: Page) {
        val pageIndex = page.index

        mutableState.update {
            it.copy(currentPage = pageIndex + 1)
        }
        readerChapter.requestedPage = pageIndex
        chapterPageIndex = pageIndex

        if (!incognitoMode && page.status !is Page.State.Error) {
            readerChapter.chapter.last_page_read = pageIndex

            // SY --> 阿里云盘双向备份同步：读完一章后触发同步
            val isSyncEnabled = syncPreferences.isSyncEnabled()
            val syncTriggerOpt = syncPreferences.getSyncTriggerOptions()
            // SY <--

            if (readerChapter.pages?.lastIndex == pageIndex) {
                updateChapterProgressOnComplete(readerChapter)

                // SY --> 读完一章后同步
                if (isSyncEnabled && syncTriggerOpt.syncOnChapterRead) {
                    eu.kanade.tachiyomi.data.sync.SyncDataJob.startNow(context)
                }
                // SY <--
            }

            updateChapter.await(
                ChapterUpdate(
                    id = readerChapter.chapter.id!!,
                    read = readerChapter.chapter.read,
                    lastPageRead = readerChapter.chapter.last_page_read.toLong(),
                ),
            )

            // SY --> 打开未读章节后同步
            if (isSyncEnabled && syncTriggerOpt.syncOnChapterOpen && readerChapter.chapter.last_page_read == 0) {
                eu.kanade.tachiyomi.data.sync.SyncDataJob.startNow(context)
            }
            // SY <--
        }
    }

    private suspend fun updateChapterProgressOnComplete(readerChapter: ReaderChapter) {
        readerChapter.chapter.read = true
        updateTrackChapterRead(readerChapter)
        deleteChapterIfNeeded(readerChapter)

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return

        val duplicateUnreadChapters = unfilteredChapterList
            .mapNotNull { chapter ->
                if (
                    !chapter.read &&
                    chapter.isRecognizedNumber &&
                    chapter.chapterNumber.toFloat() == readerChapter.chapter.chapter_number
                ) {
                    ChapterUpdate(id = chapter.id, read = true)
                } else {
                    null
                }
            }
        updateChapter.awaitAll(duplicateUnreadChapters)
    }

    fun restartReadTimer() {
        chapterReadStartTime = Clock.System.now().toEpochMilliseconds()
    }

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    suspend fun updateHistory() {
        getCurrentChapter()?.let { readerChapter ->
            if (incognitoMode) return@let

            val chapterId = readerChapter.chapter.id!!
            val endTime = Date()
            val sessionReadDuration = chapterReadStartTime?.let { endTime.time - it } ?: 0

            upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
            chapterReadStartTime = null
        }
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter() {
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return
        loadAdjacent(nextChapter)
    }

    // SY --> 2026-09-29 Part 2：整部看完 → 自动衔接「下一部漫画」。
    //
    // 需求（用户定口径）：**同文件夹优先，没有了再取书架**；**滑到末尾自动打开**。
    //
    // 为什么「同文件夹」只能基于书架过滤：
    //   [eu.kanade.tachiyomi.source.FolderSource] 只暴露 getFolders / setActiveFolder /
    //   addFolder —— 没有公开的「列文件夹内漫画」方法（WebDavSource 的 listMangaInDirectory
    //   是 private，而 setActiveFolder 会改源状态、有副作用，不能在阅读器里调）。
    //   因此同文件夹候选取自书架：同 source + url 落在同一父路径下。
    //   ⚠️ 推论：未加入书架的兄弟漫画不会出现在同文件夹候选里（会落到书架兜底）。
    //
    // 排序用「自然排序」（数字按数值而非字典序），与文件夹/书架里看到的顺序一致。
    // SY <--
    data class NextMangaTarget(val mangaId: Long, val chapterId: Long, val title: String)

    /**
     * 找出「下一部漫画」及其该读的章节；没有则返回 null。
     * 章节选取：优先第一个**未读**章节，全部读过则取第一章。
     */
    suspend fun findNextMangaAfterFinish(): NextMangaTarget? = withIOContext {
        val currentManga = manga
        if (currentManga == null) {
            logcat(LogPriority.INFO) { "Part2 诊断：manga 为 null，放弃" }
            return@withIOContext null
        }

        val currentUrl = currentManga.url
        // 父路径：WebDAV/本地图源的 url 就是相对路径（如 `长篇漫画/魔都` → `长篇漫画`）
        val parent = currentUrl.substringBeforeLast('/', "")

        // ---------------------------------------------------------------- ① 同文件夹优先
        //
        // ⚠️ 候选必须来自**数据库里该源的全部漫画**，而不是书架：
        // 云盘 / 本地图源里绝大多数漫画从不加入书架（真机实测：某源 3501 本、书架 0 本，
        // 只有 15 本是**在线源**的收藏），只查书架会导致 `同源 = 0` ⇒ 永远找不到下一部。
        // 而这些漫画在浏览时早已入库，所以按 `source` 全量取即可。
        var next: Manga? = null
        var src = "无"
        var siblingCount = 0
        var sIdx = -1
        val sourceAll = runCatching { getMangasBySource.await(currentManga.source) }.getOrNull()
        if (sourceAll != null && parent.isNotEmpty()) {
            val siblings = sourceAll
                .filter { it.url.startsWith("$parent/") }
                .sortedWith { a, b -> a.url.compareToCaseInsensitiveNaturalOrder(b.url) }
            siblingCount = siblings.size
            sIdx = siblings.indexOfFirst { it.id == currentManga.id }
            next = if (sIdx >= 0) {
                // 严格取「下一部」；本文件夹已看完则保持 null，交给下面的书架兜底
                siblings.getOrNull(sIdx + 1)
            } else {
                siblings.firstOrNull { it.url.compareToCaseInsensitiveNaturalOrder(currentUrl) > 0 }
            }
            if (next != null) src = "同文件夹"
        }

        // ---------------------------------------------------------------- ② 书架兜底
        var libSize = -1
        var sameSrcLib = 0
        if (next == null) {
            val library = runCatching { getLibraryManga.await() }.getOrNull()
            if (library != null) {
                libSize = library.size
                // 只认**同源**的书架条目：跨源兜底会把「在线漫画」当成云盘漫画的下一部，
                // 那是驴唇不对马嘴（旧版实测就这样跳到过一部在线条漫；见 09-28 记忆）。
                val sameSource = library
                    .filter { it.manga.source == currentManga.source }
                    .sortedWith { a, b ->
                        a.manga.url.compareToCaseInsensitiveNaturalOrder(b.manga.url)
                    }
                sameSrcLib = sameSource.size
                // 注意：列表里**要保留自己**，否则 indexOfFirst 恒为 -1、每次都取第一条。
                val idx = sameSource.indexOfFirst { it.id == currentManga.id }
                next = if (idx >= 0) {
                    sameSource.getOrNull(idx + 1)?.manga
                } else {
                    sameSource.firstOrNull { it.manga.url.compareToCaseInsensitiveNaturalOrder(currentUrl) > 0 }?.manga
                }
                if (next != null) src = "书架兜底"
            }
        }

        // SY 2026-09-29：整条查找链**必须留痕**。此前任一环节 null 都是静默 return，
        // 现场只看到「什么都没发生」，无从判断是触发没到还是这里没找到（排查踩过）。
        logcat(LogPriority.INFO) {
            "Part2 诊断：本作 url='$currentUrl' 父路径='$parent' 同源全库=${sourceAll?.size ?: -1} " +
                "同文件夹=$siblingCount(下标=$sIdx) 书架=$libSize(同源=$sameSrcLib) " +
                "→ 命中=$src url='${next?.url}'"
        }
        val target = next ?: return@withIOContext null

        // 该读哪一章：第一个未读，全读过则第一章
        //
        // ⚠️ 云盘 / 本地图源里「一部 = 一个 zip」的漫画，**只有被打开过才会有章节行**。
        // 真机 DB 实测：选中的下一部 `_id=7323` 在 chapters 表里 0 行 ⇒ 直接查库必然拿不到
        // chapterId，最终依然「接不上」。所以库里没有章节时，主动向源拉一次并入库
        // （`UpdateMangaFromRemote` 就是 MangaScreen 打开漫画时用的那条路，含
        //  `source.getMangaUpdate(fetchChapters = true)` + `SyncChaptersWithSource`）。
        var chapters = runCatching { getChaptersByMangaId.await(target.id) }.getOrNull().orEmpty()
        if (chapters.isEmpty()) {
            logcat(LogPriority.INFO) { "Part2：目标《${target.title}》库里无章节，向源拉取一次…" }
            runCatching {
                withTimeoutOrNull(nextMangaFetchTimeoutMs) {
                    updateMangaFromRemote(target, fetchChapters = true)
                }
            }.onFailure { logcat(LogPriority.WARN, it) { "Part2：拉取目标章节失败" } }
            chapters = runCatching { getChaptersByMangaId.await(target.id) }.getOrNull().orEmpty()
        }
        if (chapters.isEmpty()) {
            logcat(LogPriority.INFO) { "Part2 诊断：下一部仍然没有章节，放弃" }
            return@withIOContext null
        }
        val chapter = chapters.firstOrNull { !it.read } ?: chapters.first()

        NextMangaTarget(target.id, chapter.id, target.title)
    }

    /**
     * 由阅读器在「滑到末尾、且已无下一话」时调用：找到下一部漫画后发事件让 Activity 打开它。
     * [lastNextMangaProbeAt] 用于节流 —— 触发点在 viewer 的滚动回调里，每帧都会调过来。
     */
    private var lastNextMangaProbeAt = 0L

    /** SY 2026-09-29 Part 2：向源拉取「下一部」章节列表的超时上限（避免卡在末尾干等）。 */
    private val nextMangaFetchTimeoutMs = 20_000L

    fun requestNextMangaOnFinish() {
        viewModelScope.launchIO {
            // 触发点在 viewer 的滚动回调里，每帧都会调过来；节流避免连续 DB 查询 + 重复发事件。
            val now = System.currentTimeMillis()
            if (now - lastNextMangaProbeAt < 1500L) return@launchIO
            lastNextMangaProbeAt = now

            val target = findNextMangaAfterFinish()
            if (target == null) {
                // 触发已到、但没找到可接的下一部（本文件夹 + 书架都没有「之后」的一部）。
                // 用户此时会看到末尾菜单，属于「明确无事可做」，不是静默失败。
                logcat(LogPriority.INFO) { "Part2：未找到下一部漫画（详见上一条诊断），保持原地" }
                return@launchIO
            }
            logcat(LogPriority.INFO) {
                "Part2：找到下一部《${target.title}》mangaId=${target.mangaId} chapterId=${target.chapterId}"
            }
            eventChannel.trySend(Event.OpenNextManga(target.mangaId, target.chapterId))
        }
    }

    /**
     * SY --> 2026-09-29 Part 2：**原地**切换到另一部漫画（不重启 Activity）。
     *
     * 为什么必须原地切：ReaderActivity 是 `launchMode="singleTask"`，「本作读完」时用
     * [ReaderActivity.newIntent] 再 startActivity 会被系统判为 `START_DELIVERED_TO_TOP`
     * （真机 ATM 日志实测 `result code=3`）—— intent 交给已有实例的 `onNewIntent`，
     * **不新建实例**。旧实现没覆写 onNewIntent ⇒ 新 manga/chapter 参数被静默丢弃 ⇒
     * 「完全没有自动接上」。现在由 Activity 的 onNewIntent 调到这里。
     *
     * 切换后 UI 会自动跟上，无需额外通知：Activity 对 `state.manga` / `state.viewerChapters`
     * 各有 `distinctUntilChanged` 订阅，前者触发 `updateViewer()`（按新 manga 的阅读模式重建
     * viewer），后者触发 `setChapters()`（喂新章节）。
     * SY <--
     */
    fun switchToManga(newMangaId: Long, newChapterId: Long) {
        viewModelScope.launchIO {
            try {
                val newManga = getManga.await(newMangaId)
                    ?: error("Requested manga of id $newMangaId not found")
                val newSource = sourceManager.getOrStub(newManga.source)

                // 先落 id / chapterId，再建章节列表（[buildChapterList] 依赖这两者）
                mangaId = newMangaId
                savedState["manga"] = newMangaId
                chapterId = newChapterId
                chapterPageIndex = -1

                incognitoMode = getIncognitoState.await(newManga.source)
                // 只改 manga/source：**不要**把 viewerChapters 置空 —— 让随后的 loadChapter
                // 按原生流程 `it.viewerChapters?.unref()` 释放上一部的章节引用，
                // 直接置空会漏掉 unref，旧章节的页引用会一直挂住不回收。
                mutableState.update {
                    it.copy(
                        manga = newManga,
                        source = newSource,
                        currentPage = -1,
                        initError = null,
                    )
                }

                chapterList = buildChapterList()
                loader = ChapterLoader(context, downloadManager, downloadProvider, chapterCache, newManga, newSource)
                // 与 init() 一致：清掉上一部的增强队列，避免和新章节抢 GPU
                ImageEnhancer.reset(0)

                loadChapter(loader!!, chapterList.first { newChapterId == it.chapter.id })
                logcat(LogPriority.INFO) {
                    "ReaderViewModel: 已换到下一部《${newManga.title}》章节 $newChapterId"
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                // 刻意不写 initError：那会让 Activity 直接 finish()，用户会莫名被踢出阅读器。
                // 这里只记日志；调用方（onNewIntent）已经不会再触发，用户仍可看到菜单自己操作。
                logcat(LogPriority.ERROR, e) { "ReaderViewModel: 切换下一部漫画失败" }
            }
        }
    }
    // SY <--

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter() {
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    fun getSource() = state.value.source as? HttpSource

    fun getChapterUrl(): String? {
        val sChapter = getCurrentChapter()?.chapter ?: return null
        val source = getSource() ?: return null

        return try {
            source.getChapterUrl(sChapter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    /**
     * Bookmarks the currently active chapter.
     */
    fun toggleChapterBookmark() {
        val chapter = getCurrentChapter()?.chapter ?: return
        val bookmarked = !chapter.bookmark
        chapter.bookmark = bookmarked

        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(
                    id = chapter.id!!,
                    bookmark = bookmarked,
                ),
            )
        }

        mutableState.update {
            it.copy(
                bookmarked = bookmarked,
            )
        }
    }

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultReadingMode.get()
        val readingMode = ReadingMode.fromPreference(manga?.readingMode?.toInt())
        return when {
            resolveDefault && readingMode == ReadingMode.DEFAULT -> default
            else -> manga?.readingMode?.toInt() ?: default
        }
    }

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingMode: ReadingMode) {
        val manga = manga ?: return
        runBlocking(Dispatchers.IO) {
            setMangaViewerFlags.awaitSetReadingMode(manga.id, readingMode.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType.get()
        val orientation = ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt())
        return when {
            resolveDefault && orientation == ReaderOrientation.DEFAULT -> default
            else -> manga?.readerOrientation?.toInt() ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val manga = manga ?: return
        viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetOrientation(manga.id, orientation.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.SetOrientation(getMangaOrientation()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    fun toggleCropBorders(): Boolean {
        val isPagerType = ReadingMode.isPagerType(getMangaReadingMode())
        return if (isPagerType) {
            readerPreferences.cropBorders.toggle()
        } else {
            readerPreferences.cropBordersWebtoon.toggle()
        }
    }

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    fun showLoadingDialog() {
        mutableState.update { it.copy(dialog = Dialog.Loading) }
    }

    fun openReadingModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.ReadingModeSelect) }
    }

    fun openOrientationModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.OrientationModeSelect) }
    }

    fun openPageDialog(page: ReaderPage) {
        mutableState.update { it.copy(dialog = Dialog.PageActions(page)) }
    }

    fun openSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Settings) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, page)

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga.get()) {
            DiskUtil.buildValidFilename(
                manga.title,
            )
        } else {
            ""
        }

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(copyToClipboard: Boolean) {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, page)

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                manga.editCover(coverManager, stream(), updateManga, coverCache)
                if (manga.isLocal() || manga.favorite) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack.get()) return

        val manga = manga ?: return

        viewModelScope.launchNonCancellable {
            trackChapter.await(context, manga.id, readerChapter.chapter.chapter_number.toDouble())
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        val manga = manga ?: return

        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), manga)
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingChapters()
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val source: Source? = null,
        val initError: Throwable? = null,
        val viewerChapters: ViewerChapters? = null,
        val bookmarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        val currentPage: Int = -1,

        /**
         * Viewer used to display the pages (pager, webtoon, ...).
         */
        val viewer: Viewer? = null,
        val dialog: Dialog? = null,
        val menuVisible: Boolean = false,
        @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    sealed interface Dialog {
        data object Loading : Dialog
        data object Settings : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog
        data class PageActions(val page: ReaderPage) : Dialog
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        data object PageChanged : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        // SY --> 2026-09-29 Part 2：整部看完，打开下一部漫画
        data class OpenNextManga(val mangaId: Long, val chapterId: Long) : Event
        // SY <--

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(val uri: Uri, val page: ReaderPage) : Event
        data class CopyImage(val uri: Uri) : Event
    }
}

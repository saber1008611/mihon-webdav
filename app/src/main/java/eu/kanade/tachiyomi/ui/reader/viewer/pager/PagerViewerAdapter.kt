package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import tachiyomi.core.common.util.system.logcat

/**
 * Pager adapter used by this [viewer] to where [ViewerChapters] updates are posted.
 *
 * SY 2026-10-09 双页移植（来自上游 mihon_img_upscale）：
 * 单页模式下 [joinedItems] 每屏一项；双页模式下每屏两项（左页 + 右页），由
 * [PagerPageHolder] 合成一张图显示。
 */
class PagerViewerAdapter(private val viewer: PagerViewer) : ViewPagerAdapter() {

    /**
     * Paired list of currently set items.
     */
    var joinedItems: MutableList<Pair<ReaderItem, ReaderItem?>> = mutableListOf()
        private set

    /**
     * Single list of items (未配对).
     */
    private var subItems: MutableList<ReaderItem> = mutableListOf()

    /**
     * Holds preprocessed items so they don't get removed when changing chapter
     */
    private var preprocessed: MutableMap<Int, InsertPage> = mutableMapOf()

    var nextTransition: ChapterTransition.Next? = null
        private set

    var currentChapter: ReaderChapter? = null

    /** 本次 setChapters 是否成功恢复到某一页的位置。 */
    var restoredPosition = false
        private set

    /**
     * Context that has been wrapped to use the correct theme values based on the
     * current app theme and reader background color
     */
    private var readerThemedContext = viewer.activity.createReaderThemeContext()

    var forceTransition = false

    /** 上一次配对时是否处于双页模式，用于判断「退出双页」时要不要停在第二页上。 */
    private var doubledUp = viewer.config.doublePages

    private val displayReaderPages: List<ReaderPage>
        get() = subItems.filterIsInstance<ReaderPage>().filterNot { it is InsertPage }

    /**
     * Updates this adapter with the given [chapters]. It handles setting a few pages of the
     * next/previous chapter to allow seamless transitions and inverting the pages if the viewer
     * has R2L direction.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean, anchorPage: ReaderPage? = null) {
        restoredPosition = false

        // 偏好变化会重建列表：只要「拆分宽页」还开着，就保留已有拆分页，
        // 否则当前页会短暂丢掉右半边。
        val existingSplitPages = subItems.filterIsInstance<InsertPage>()
            .mapNotNull { splitPageKey(it.parent) }
            .toSet()

        val newItems = mutableListOf<ReaderItem>()

        // Forces chapter transition if there is missing chapters
        val prevHasMissingChapters = calculateChapterGap(chapters.currChapter, chapters.prevChapter) > 0
        val nextHasMissingChapters = calculateChapterGap(chapters.nextChapter, chapters.currChapter) > 0

        this.forceTransition = forceTransition

        // Add previous chapter pages and transition
        chapters.prevChapter?.pages?.let { newItems.addAll(it) }

        // Skip transition page if the chapter is loaded & current page is not a transition page
        if (prevHasMissingChapters || forceTransition || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        }

        // Add current chapter.
        val currPages = chapters.currChapter.pages
        if (currPages != null) {
            val pages = currPages.toMutableList()

            // Insert preprocessed pages into current page list
            preprocessed.keys.sortedDescending()
                .forEach { key ->
                    preprocessed[key]?.let { pages.add(key + 1, it) }
                }

            newItems.addAll(pages)
        }

        currentChapter = chapters.currChapter

        // Add next chapter transition and pages.
        nextTransition = ChapterTransition.Next(chapters.currChapter, chapters.nextChapter)
            .also {
                if (
                    nextHasMissingChapters ||
                    forceTransition ||
                    chapters.nextChapter?.state !is ReaderChapter.State.Loaded
                ) {
                    newItems.add(it)
                }
            }

        chapters.nextChapter?.pages?.let(newItems::addAll)

        subItems = newItems
        preprocessed = mutableMapOf()
        restoreSplitPages(existingSplitPages)

        // 从双页退回单页时，停留在用户当时正在看的那一页（第二页）上。
        var useSecondPage = false
        val enteringDoublePages = !doubledUp && viewer.config.doublePages
        if (doubledUp != viewer.config.doublePages && doubledUp) {
            useSecondPage = true
        }
        doubledUp = viewer.config.doublePages

        restoredPosition = setJoinedItems(
            useSecondPage = useSecondPage,
            preferCurrentAsFirstPage = enteringDoublePages,
            anchorPage = anchorPage,
        )
    }

    private fun setJoinedItems(
        useSecondPage: Boolean = false,
        preferCurrentAsFirstPage: Boolean = false,
        anchorPage: ReaderPage? = null,
    ): Boolean {
        val oldCurrent = joinedItems.getOrNull(viewer.pager.currentItem)
        if (!viewer.config.doublePages) {
            // If not in double mode, set up items like before
            subItems.forEach {
                (it as? ReaderPage)?.apply {
                    shiftedPage = false
                    firstHalf = null
                }
            }
            this.joinedItems = subItems.map { it to null }.toMutableList()
            if (viewer is R2LPagerViewer) {
                joinedItems.reverse()
            }
        } else {
            val pagedItems = mutableListOf<MutableList<ReaderPage?>>()
            val otherItems = mutableListOf<ReaderItem>()
            pagedItems.add(mutableListOf())

            // Step 1: segment the pages and transition pages
            subItems.forEach {
                if (it is ReaderPage) {
                    if (pagedItems.last().lastOrNull() != null &&
                        pagedItems.last().last()?.chapter?.chapter?.id != it.chapter.chapter.id
                    ) {
                        pagedItems.add(mutableListOf())
                    }
                    pagedItems.last().add(it)
                } else {
                    otherItems.add(it)
                    pagedItems.add(mutableListOf())
                }
            }

            var pagedIndex = 0
            val subJoinedItems = mutableListOf<Pair<ReaderItem, ReaderItem?>>()
            // Step 2: run through each set of pages
            pagedItems.forEach { items ->
                items.forEach {
                    it?.shiftedPage = false
                    it?.firstHalf = null
                }

                // Step 3: 跨页大图 / 拆分页要独占一屏 —— 在它后面插一个空位
                var itemIndex = 0
                while (itemIndex < items.size) {
                    items[itemIndex]?.isolatedPage = false
                    if (
                        items[itemIndex]?.fullPage == true ||
                        items[itemIndex] is InsertPage
                    ) {
                        items.add(itemIndex + 1, null)
                        if (items[itemIndex]?.fullPage == true && itemIndex > 0 &&
                            items[itemIndex - 1] != null && (itemIndex - 1) % 2 == 0
                        ) {
                            // 跨页大图前面那页要单独一屏（否则奇偶错位）
                            items[itemIndex - 1]?.isolatedPage = true
                            items.add(itemIndex, null)
                            itemIndex++
                        }
                        itemIndex++
                    }
                    itemIndex++
                }

                // Step 4: chunk em
                if (items.isNotEmpty()) {
                    subJoinedItems.addAll(
                        items.chunked(2).map { it.first()!! to it.getOrNull(1) },
                    )
                }

                otherItems.getOrNull(pagedIndex)?.let {
                    val lastPage = subJoinedItems.lastOrNull()?.first as? ReaderPage
                    if (lastPage == null || (
                        if (it is ChapterTransition.Next) {
                            it.from.chapter.id == lastPage.chapter.chapter.id
                        } else {
                            true
                        }
                        )
                    ) {
                        subJoinedItems.add(it to null)
                        pagedIndex++
                    }
                }
            }

            if (viewer is R2LPagerViewer) {
                subJoinedItems.reverse()
            }

            this.joinedItems = subJoinedItems
        }
        notifyDataSetChanged()

        val requestedPage = currentChapter?.pages
            ?.getOrNull(currentChapter?.requestedPage ?: -1)
            ?.let(::findReaderPage)

        val desiredPage = findReaderPage(anchorPage)
            ?: requestedPage
            ?: resolveFallbackPage(oldCurrent, useSecondPage)

        val desiredPosition = when {
            desiredPage != null -> getPositionForPage(desiredPage, preferCurrentAsFirstPage)
            oldCurrent?.first is ChapterTransition -> getPositionForTransition(oldCurrent.first as ChapterTransition)
            else -> -1
        }

        logcat {
            "PagerViewerAdapter.setJoinedItems: doublePages=${viewer.config.doublePages}, " +
                "anchor=${anchorPage?.number}/${anchorPage?.index}, " +
                "desired=${desiredPage?.number}/${desiredPage?.index}, " +
                "desiredPosition=$desiredPosition, currentItem=${viewer.pager.currentItem}"
        }

        if (desiredPosition > -1) {
            viewer.setPendingSelectedPage(desiredPage)
            viewer.pager.setCurrentItem(desiredPosition, false)
            return true
        }

        return false
    }

    private fun resolveFallbackPage(
        oldCurrent: Pair<ReaderItem, ReaderItem?>?,
        useSecondPage: Boolean,
    ): ReaderPage? {
        val oldPages = listOfNotNull(oldCurrent?.first as? ReaderPage, oldCurrent?.second as? ReaderPage)
        val preferredPage = when {
            useSecondPage -> oldPages.lastOrNull()
            else -> oldPages.maxByOrNull { it.index }
        }
        return findReaderPage(preferredPage)
    }

    private fun findReaderPage(page: ReaderPage?): ReaderPage? {
        page ?: return null
        if (page is InsertPage) {
            return findCurrentInsertPage(subItems, page)
        }
        return displayReaderPages.firstOrNull { candidate -> candidate.isFromSamePage(page) }
    }

    fun getPositionForPage(page: ReaderPage, preferCurrentAsFirstPage: Boolean = false): Int {
        if (page is InsertPage) {
            val currentInsertPage = findReaderPage(page) ?: return -1
            return joinedItems.indexOfFirst { pair ->
                pair.first === currentInsertPage || pair.second === currentInsertPage
            }
        }
        var index = if (preferCurrentAsFirstPage) {
            joinedItems.indexOfFirst {
                val firstPage = it.first as? ReaderPage
                firstPage !is InsertPage && firstPage?.isFromSamePage(page) == true
            }
        } else {
            -1
        }

        if (index == -1) {
            index = joinedItems.indexOfFirst { pair ->
                visibleReaderPages(pair).any { it.isFromSamePage(page) }
            }
        }

        return index
    }

    private fun getPositionForTransition(transition: ChapterTransition): Int {
        val index = joinedItems.indexOfFirst { it.first == transition }
        if (index != -1 || forceTransition) {
            return index
        }

        val fallbackPage = if (transition is ChapterTransition.Next) {
            joinedItems.asSequence()
                .flatMap { visibleReaderPages(it).asSequence() }
                .filter { it.chapter == transition.to }
                .minByOrNull { it.index }
        } else {
            joinedItems.asSequence()
                .flatMap { visibleReaderPages(it).asSequence() }
                .filter { it.chapter == transition.to }
                .maxByOrNull { it.index }
        }

        return fallbackPage?.let(::getPositionForPage) ?: -1
    }

    private fun visibleReaderPages(item: Pair<ReaderItem, ReaderItem?>): List<ReaderPage> {
        return listOfNotNull(item.first as? ReaderPage, item.second as? ReaderPage)
            .filterNot { it is InsertPage }
    }

    /**
     * 该屏上「正在读」的那一页：R2L 时右边那页先读，LTR 时左边那页先读。
     */
    fun getActiveReaderPage(position: Int): ReaderPage? {
        return joinedItems.getOrNull(position)
            ?.let(::visibleReaderPages)
            ?.let { pages ->
                if (viewer is R2LPagerViewer) pages.lastOrNull() else pages.firstOrNull()
            }
    }

    fun hasSplitPage(page: ReaderPage): Boolean {
        return page is InsertPage || subItems.any { item ->
            item is InsertPage && item.parent === page
        }
    }

    /**
     * Returns the amount of items of the adapter.
     */
    override fun getCount(): Int {
        return joinedItems.size
    }

    /**
     * Creates a new view for the item at the given [position].
     */
    override fun createView(container: ViewGroup, position: Int): View {
        val (item, extraPage) = joinedItems[position]
        return when (item) {
            is ReaderPage -> PagerPageHolder(readerThemedContext, viewer, item, extraPage as? ReaderPage)
            is ChapterTransition -> PagerTransitionHolder(readerThemedContext, viewer, item)
            else -> throw NotImplementedError("Holder for ${item.javaClass} not implemented")
        }
    }

    /**
     * Returns the current position of the given [view] on the adapter.
     */
    override fun getItemPosition(view: Any): Int {
        if (view is PositionableView) {
            val position = joinedItems.indexOfFirst {
                it.first == view.item || (it.first to it.second) == view.item
            }
            if (position != -1) {
                return position
            } else {
                logcat { "Position for ${view.item} not found" }
            }
        }
        return POSITION_NONE
    }

    fun onPageSplit(currentPage: Any?, newPage: InsertPage) {
        if (currentPage !is ReaderPage) return

        val currentIndex = subItems.indexOf(currentPage)
        if (currentIndex == -1) return

        // Put aside preprocessed pages for next chapter so they don't get removed when changing chapter
        if (currentPage.chapter.chapter.id != currentChapter?.chapter?.id) {
            preprocessed[newPage.index] = newPage
            return
        }

        val placeAtIndex = when (viewer) {
            is L2RPagerViewer,
            is VerticalPagerViewer,
            -> currentIndex + 1
            else -> currentIndex
        }

        // It will enter a endless cycle of insert pages
        if (viewer is R2LPagerViewer && placeAtIndex - 1 >= 0 && subItems[placeAtIndex - 1] is InsertPage) {
            return
        }

        // Same here it will enter a endless cycle of insert pages
        if (placeAtIndex < subItems.size && subItems[placeAtIndex] is InsertPage) {
            return
        }

        val currentReaderPage = viewer.getCurrentReaderPage()
        subItems.add(placeAtIndex, newPage)

        // R2L 下插入的是右半页，应保持停在它上面；用父页当锚点会导致拆分后往回跳。
        val currentPageIsDisplayed = when (currentReaderPage) {
            is InsertPage -> currentReaderPage.parent.isFromSamePage(currentPage)
            null -> false
            else -> currentReaderPage.isFromSamePage(currentPage)
        }
        val anchorPage = when {
            viewer is R2LPagerViewer && currentPageIsDisplayed -> newPage
            currentReaderPage != null -> currentReaderPage
            viewer is R2LPagerViewer -> newPage
            else -> currentPage
        }
        restoredPosition = setJoinedItems(anchorPage = anchorPage)
    }

    private fun restoreSplitPages(splitPages: Set<SplitPageKey>) {
        if (!shouldKeepSplitPages()) return

        splitPages.forEach { key ->
            val parentIndex = subItems.indexOfFirst { item ->
                val page = item as? ReaderPage
                page !is InsertPage && splitPageKey(page) == key
            }
            if (parentIndex == -1) return@forEach

            val parent = subItems[parentIndex] as ReaderPage
            if (subItems.any { item -> item is InsertPage && item.parent === parent }) return@forEach

            val insertIndex = when (viewer) {
                is L2RPagerViewer,
                is VerticalPagerViewer,
                -> parentIndex + 1
                else -> parentIndex
            }
            subItems.add(insertIndex, InsertPage(parent))
        }
    }

    private fun shouldKeepSplitPages(): Boolean {
        return !viewer.config.doublePages && viewer.config.autoSplitPages
    }

    /**
     * 双页模式下发现一页本身就是跨页大图 —— 标记后重新配对，让它独占一屏。
     */
    fun onWidePageDetected(page: ReaderPage) {
        if (page.fullPage == true) return
        page.fullPage = true
        restoredPosition = setJoinedItems(anchorPage = viewer.getCurrentReaderPage() ?: page)
    }

    fun cleanupPageSplit() {
        val insertPages = subItems.filterIsInstance(InsertPage::class.java)
        subItems.removeAll(insertPages)
        subItems.filterIsInstance<ReaderPage>().forEach {
            it.fullPage = null
            it.isolatedPage = false
            it.shiftedPage = false
            it.firstHalf = null
        }
        restoredPosition = setJoinedItems(anchorPage = viewer.getCurrentReaderPage())
    }

    fun getDisplayPage(index: Int): ReaderPage? {
        return displayReaderPages.getOrNull(index)
    }

    fun getDisplayPageCount(): Int {
        return displayReaderPages.size
    }

    fun getDisplayPageNumber(page: ReaderPage): Int {
        return displayReaderPages.indexOfFirst { item ->
            item === page || item.isFromSamePage(page)
        }.let { if (it >= 0) it + 1 else page.number }
    }

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }

    private data class SplitPageKey(
        val chapterId: Long,
        val pageIndex: Int,
    )

    private fun splitPageKey(page: ReaderPage?): SplitPageKey? {
        page ?: return null
        val chapterId = page.chapter.chapter.id ?: return null
        return SplitPageKey(chapterId, page.index)
    }
}

internal fun findCurrentInsertPage(items: List<ReaderItem>, anchor: InsertPage): InsertPage? {
    return items.filterIsInstance<InsertPage>().firstOrNull { candidate ->
        candidate === anchor || candidate.parent.isFromSamePage(anchor.parent)
    }
}

internal fun isSameDisplayedPage(first: ReaderPage?, second: ReaderPage?): Boolean {
    if (first == null || second == null) return false
    if ((first is InsertPage) != (second is InsertPage)) return false
    return if (first is InsertPage && second is InsertPage) {
        first.parent.isFromSamePage(second.parent)
    } else {
        first.isFromSamePage(second)
    }
}

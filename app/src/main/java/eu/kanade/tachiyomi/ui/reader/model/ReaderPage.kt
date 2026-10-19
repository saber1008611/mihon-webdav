package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null), ReaderItem {

    open lateinit var chapter: ReaderChapter

    /**
     * SY --> 条漫预占位：本页图片的已知高宽比 (height / width)，0 = 未知。
     *
     * 条漫每页 item 的高度要等图片解码才确定：SSIV 在「未就绪」(sWidth<=0) 时 onMeasure 把
     * 高度算成测量上限（≈1 屏），就绪后才变成自然高度 `宽 × 高宽比` —— 高度这一次突变，
     * 若该页在视口上方就会把可见内容顶动。往回滑时尤其明显：离屏 holder（缓存仅 3 个）被
     * 回收重绑，高度先退回 1 屏、再突变回自然高度。
     *
     * 这里在**首次解码完成后**记下比例，重绑时由 WebtoonPageHolder 提前把高度撑到最终值，
     * 使「就绪前 / 就绪后」高度一致，消除跳动。放在页对象上 ⇒ 随页生命周期，无键冲突。
     * SY <--
     */
    var knownAspectRatio: Float = 0f

    // SY --> 2026-10-09 双页移植（来自上游 mihon_img_upscale）
    /** 该页本身是跨页大图（宽页），双页模式下应独占一屏。null = 还没检测过。 */
    var fullPage: Boolean? = null

    /** 因前后出现跨页大图而被单独隔离在这一屏。 */
    var isolatedPage: Boolean = false

    /** 双页模式下被「偏移一位」，用来让跨页大图对齐到整屏。 */
    var shiftedPage: Boolean = false

    /** 宽页被拆分后，这一半是左半还是右半（true = 左半）。 */
    var firstHalf: Boolean? = null
    // SY <--

    /**
     * SY 2026-10-09：判断两页是否为「同一张源图」（拆分出来的左右半页与原页同源）。
     * 拆分页与原页 index 相同，因此必须连章节 id 一起比。
     */
    fun isFromSamePage(other: ReaderPage): Boolean {
        return index == other.index && chapter.chapter.id == other.chapter.chapter.id
    }
}

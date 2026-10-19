package eu.kanade.tachiyomi.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * SY 2026-10-09：双页阅读（页面布局）—— 移植自上游 mihon_img_upscale。
 *
 * - [SINGLE_PAGE]：一屏一页（本 fork 原有行为）。
 * - [DOUBLE_PAGES]：强制双页，左右两页拼成一屏。
 * - [AUTOMATIC]：横屏时双页，竖屏时单页。
 */
enum class PageLayout(
    val value: Int,
    val stringRes: StringResource,
) {
    SINGLE_PAGE(0, MR.strings.page_layout_single),
    DOUBLE_PAGES(1, MR.strings.page_layout_double),
    AUTOMATIC(2, MR.strings.page_layout_automatic),
    ;

    /**
     * SY 2026-10-19：映射到 WebGpu 渲染器的双页偏好 [ReaderPreferences.DualPageView]。
     *
     * WebGpu 路径不读 [ReaderPreferences.pageLayout]（它只驱动经典 Pager 的 `doublePages`），
     * 双页完全由 `dualPageView` 决定（见 `WebGpuViewer.isDualPageMode`）。两者三态语义同构：
     * 单页 ↔ NEVER、双页 ↔ ALWAYS、自动（宽屏才双页）↔ WIDE。
     */
    fun toDualPageView(): ReaderPreferences.DualPageView = when (this) {
        SINGLE_PAGE -> ReaderPreferences.DualPageView.NEVER
        DOUBLE_PAGES -> ReaderPreferences.DualPageView.ALWAYS
        AUTOMATIC -> ReaderPreferences.DualPageView.WIDE
    }

    companion object {
        val selectableEntries = entries.toList()

        fun fromPreference(preference: Int): PageLayout =
            selectableEntries.find { it.value == preference } ?: SINGLE_PAGE

        fun fromDualPageView(dualPageView: ReaderPreferences.DualPageView): PageLayout =
            when (dualPageView) {
                ReaderPreferences.DualPageView.NEVER -> SINGLE_PAGE
                ReaderPreferences.DualPageView.ALWAYS -> DOUBLE_PAGES
                ReaderPreferences.DualPageView.WIDE -> AUTOMATIC
            }
    }
}

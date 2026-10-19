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

    companion object {
        val selectableEntries = entries.toList()

        fun fromPreference(preference: Int): PageLayout =
            selectableEntries.find { it.value == preference } ?: SINGLE_PAGE
    }
}

package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewPropertyAnimator
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import tachiyomi.core.common.i18n.stringResource
import kotlin.math.abs

class ReaderNavigationOverlayView(context: Context, attributeSet: AttributeSet) : View(context, attributeSet) {

    private var viewPropertyAnimator: ViewPropertyAnimator? = null

    private var navigation: ViewerNavigation? = null

    fun setNavigation(navigation: ViewerNavigation, showOnStart: Boolean) {
        val previous = this.navigation
        this.navigation = navigation
        invalidate()

        // SY --> 2026-09-29：只在「导航方案**真的变了**」时才自动浮现。
        //
        // 原判据是 `firstLaunch = this.navigation == null`，本意是「首次进阅读器才弹」；
        // 但本 overlay 挂在 **Activity** 上（`binding.navigationOverlay`），而「本作读完 →
        // 接下一部」会**原地重建 viewer**（`ReaderActivity.updateViewer()`），
        // 新区间的 `WebtoonConfig.init` 又会触发一次 `navigationModeChangedListener`
        // ⇒ `setNavigation` 第二次被调 ⇒ `firstLaunch = false` ⇒ **每次自动换书都白弹一遍
        // 「上一页 / 下一页」的点按区域提示**（真机实测，`reader_navigation_overlay_on_start`
        // 与 `_new_user` 均为 false，唯一成因就是这条）。
        //
        // 上游能这么写，是因为它只在「首次进入」和「用户改了导航模式」时重建 viewer。
        // 现在改成：`showOnStart`（偏好 / 新用户引导）或**导航器类型变了**才浮现，
        // 顺手把「重建 viewer」这类非用户意图的浮现一并挡掉（对翻页模式也适用）。
        val changed = previous != null && previous.javaClass != navigation.javaClass
        // SY <--

        if (isVisible || (!showOnStart && !changed) || navigation is DisabledNavigation) {
            return
        }

        viewPropertyAnimator = animate()
            .alpha(1f)
            .setDuration(FADE_DURATION)
            .withStartAction {
                isVisible = true
            }
            .withEndAction {
                viewPropertyAnimator = null
            }
        viewPropertyAnimator?.start()
    }

    private val regionPaint = Paint()

    private val textPaint = Paint().apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        textSize = 64f
    }

    private val textBorderPaint = Paint().apply {
        textAlign = Paint.Align.CENTER
        color = Color.BLACK
        textSize = 64f
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    override fun onDraw(canvas: Canvas) {
        if (navigation == null) return

        navigation?.getRegions()?.forEach { region ->
            val rect = region.rectF

            // Scale rect from 1f,1f to screen width and height
            canvas.withScale(width.toFloat(), height.toFloat()) {
                regionPaint.color = region.type.color
                drawRect(rect, regionPaint)
            }

            // Don't want scale anymore because it messes with drawText
            // Translate origin to rect start (left, top)
            canvas.withTranslation(x = (width * rect.left), y = (height * rect.top)) {
                // Calculate center of rect width on screen
                val x = width * (abs(rect.left - rect.right) / 2)

                // Calculate center of rect height on screen
                val y = height * (abs(rect.top - rect.bottom) / 2)

                drawText(context.stringResource(region.type.nameRes), x, y, textBorderPaint)
                drawText(context.stringResource(region.type.nameRes), x, y, textPaint)
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()

        if (viewPropertyAnimator == null && isVisible) {
            viewPropertyAnimator = animate()
                .alpha(0f)
                .setDuration(FADE_DURATION)
                .withEndAction {
                    isVisible = false
                    viewPropertyAnimator = null
                }
            viewPropertyAnimator?.start()
        }

        return true
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // Hide overlay if user start tapping or swiping
        performClick()
        return super.onTouchEvent(event)
    }
}

private const val FADE_DURATION = 1000L

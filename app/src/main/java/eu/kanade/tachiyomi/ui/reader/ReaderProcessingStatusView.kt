package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * SY --> 移植自 TachiyomiX（`ReaderPageIndicatorKt.ReaderProcessingStatusIndicator`）：
 * 阅读器左下角的**全局**超分状态显示。
 *
 * 与「逐页状态」的关键区别：这里显示的是**整个超分 worker 现在在干什么**，而不是
 * 「当前这一页处于什么状态」。因为只有一个 worker、且严格按页码顺序消费，
 * 逐页状态必然会出现「增强中 → 排队中 → 排队中 → 已增强」这种来回跳；
 * 全局状态则天然单调，并且**空闲时整条隐藏**（等价于参考实现的
 * `if (activePageIndex < 0) return`）。
 *
 * 版式与参考实现逐项对齐：
 *   · Column，子项间距 3dp；
 *   · 第一行文案：有排队 → 「增强中 第 N 页 · P% · 待处理 Q 页」，否则「增强中 第 N 页 · P%」；
 *   · 第二条：本页进度条（宽 180dp / 高 3dp），比例 = P / 100；
 *   · 第三条：整批进度条（同尺寸），比例 = sessionProcessedCount / sessionBatchCount；
 *   · 进度条颜色 #FFEBEBEB，轨道 #AA2D2D2D。
 * SY <--
 */
class ReaderProcessingStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val textView = TextView(context).apply {
        setTextColor(Color.WHITE)
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
    }

    private val pageProgressBar = BarView(context)

    private val batchProgressBar = BarView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        addView(
            textView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        addView(
            pageProgressBar,
            LinearLayout.LayoutParams(dp(BAR_WIDTH_DP), dp(BAR_HEIGHT_DP)).apply {
                topMargin = dp(BAR_GAP_DP)
            },
        )
        addView(
            batchProgressBar,
            LinearLayout.LayoutParams(dp(BAR_WIDTH_DP), dp(BAR_HEIGHT_DP)).apply {
                topMargin = dp(BAR_GAP_DP)
            },
        )
    }

    /**
     * 按当次状态刷新显示。[pagePercent] 由调用方每 200ms 从
     * `Waifu2x.getProgressPercent()` 取（与参考实现的 200ms 轮询一致）。
     */
    fun render(status: ImageEnhancer.ProcessingStatus, pagePercent: Int) {
        val page = status.activePageIndex
        val percent = pagePercent.coerceIn(0, 100)

        textView.text = if (status.queuedCount > 0) {
            context.stringResource(
                MR.strings.reader_enhancing_page_queued,
                page + 1,
                percent,
                status.queuedCount,
            )
        } else {
            context.stringResource(MR.strings.reader_enhancing_page, page + 1, percent)
        }

        pageProgressBar.setFraction(percent / 100f)
        // 参考实现：sessionBatchCount <= 0 时比例取 0（避免除零）。
        val batchFraction = if (status.sessionBatchCount > 0) {
            status.sessionProcessedCount.toFloat() / status.sessionBatchCount
        } else {
            0f
        }
        batchProgressBar.setFraction(batchFraction)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 一条 3dp 细进度条：底色为轨道，前景为已完成比例。 */
    private class BarView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TRACK_COLOR
        }

        private var fraction = 0f

        fun setFraction(value: Float) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped == fraction) return
            fraction = clamped
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.color = TRACK_COLOR
            canvas.drawRect(0f, 0f, w, h, paint)
            if (fraction > 0f) {
                paint.color = BAR_COLOR
                canvas.drawRect(0f, 0f, w * fraction, h, paint)
            }
        }

        private companion object {
            /** 对齐 TachiyomiX 的 PROGRESS_BAR_TRACK_COLOR = Color(0x2d,0x2d,0x2d,0xaa)。 */
            const val TRACK_COLOR = 0xAA2D2D2D.toInt()

            /** 对齐 TachiyomiX 的 PROGRESS_BAR_COLOR = Color(0xeb,0xeb,0xeb,0xff)。 */
            const val BAR_COLOR = 0xFFEBEBEB.toInt()
        }
    }

    private companion object {
        /** 对齐参考实现的 SizeKt.width(180.dp) / height(3.dp) / spacedBy(3.dp)。 */
        const val BAR_WIDTH_DP = 180
        const val BAR_HEIGHT_DP = 3
        const val BAR_GAP_DP = 3
    }
}

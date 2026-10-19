package eu.kanade.tachiyomi.ui.reader

import android.os.SystemClock

/**
 * SY --> 2026-09-28：滑动门控 —— 供网络取图（WebDAV 云盘 / 本地文件夹图源）判断
 * 「用户此刻是不是正在滑动」。
 *
 * 需求（用户原话）：「让 webdav 按顺序取图，取完图再滑，**滑动时不取图**」。
 *
 * 目的：滑动（含惯性）期间**不做后台取图**，把这期间的 CPU / IO / 内存带宽
 * 全部让给滚动渲染，消除「边滑边下载」造成的掉帧；滑动停下后再补取。
 *
 * 为什么用「时间戳 + 超时」而不是显式的起止事件：
 * RecyclerView 的 `onScrolled` 只在**真的滚动了**时回调，滚动自然停止时
 * **不会**发出"结束"事件；靠 [SCROLL_SETTLE_MS] 判定静止最省事也最稳，
 * 而且惯性滑动的尾巴同样被这段静止窗口盖住。
 */
object ReaderScrollGate {
    /** 最后一次发生滚动的时刻（uptimeMillis）。 */
    @Volatile
    private var lastScrollMs = 0L

    /** 判定"已停下"所需的静止时长。 */
    private const val SCROLL_SETTLE_MS = 350L

    /**
     * 由阅读器在每次滚动时调用
     * （[eu.kanade.tachiyomi.ui.reader.ReaderActivity.notifyViewerScrolling]）。
     */
    fun notifyScrolling() {
        lastScrollMs = SystemClock.uptimeMillis()
    }

    /** 当前是否正在滑动（含惯性尾巴）。 */
    fun isScrolling(): Boolean {
        val last = lastScrollMs
        return last != 0L && SystemClock.uptimeMillis() - last < SCROLL_SETTLE_MS
    }

    /** 供需要彻底复位时使用（如阅读器销毁 / 切换章节）。 */
    fun reset() {
        lastScrollMs = 0L
    }
}
// SY <--

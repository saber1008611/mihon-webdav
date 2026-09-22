package tachiyomi.data.source

// SY --> 记录某个 manga 所属的真实源页码，以及它来自哪一次 Pager 重建（generation），
// 供图源界面滚动时反查首个可见项对应的页码，联动右下角 FAB 显示。
data class SourcePage(
    val generation: Long,
    val page: Int,
)
// SY <--

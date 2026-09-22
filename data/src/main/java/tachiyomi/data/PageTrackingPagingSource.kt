package tachiyomi.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import tachiyomi.domain.manga.model.Manga

// SY --> 包装任意 PagingSource：在每一页真正加载完成后，把该页的真实源页码回传给调用方，
// 从而让 UI 能在滚动时知道当前可见项属于第几页（用于 FAB 上 "# N" 的跟随显示）。
// 注意：LoadParams / LoadResult 在本模块按 QueryPagingSource.kt 的同款写法（不写显式 import）使用。
class PageTrackingPagingSource(
    private val delegate: PagingSource<Long, Manga>,
    private val onPageLoaded: (page: Int, mangas: List<Manga>) -> Unit,
) : PagingSource<Long, Manga>() {

    init {
        delegate.registerInvalidatedCallback(::invalidate)
        registerInvalidatedCallback(delegate::invalidate)
    }

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> {
        return delegate.load(params).also { result ->
            if (result is LoadResult.Page) {
                val page = params.key
                    ?: result.prevKey?.plus(1)
                    ?: result.nextKey?.minus(1)
                    ?: 1
                onPageLoaded(page.toInt(), result.data)
            }
        }
    }

    override fun getRefreshKey(state: PagingState<Long, Manga>): Long? {
        return delegate.getRefreshKey(state)
    }
}
// SY <--

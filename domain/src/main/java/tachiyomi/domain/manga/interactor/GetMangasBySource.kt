package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * SY 2026-09-29 Part 2：取某源下的**全部**漫画（含未加入书架的）。
 *
 * 与 [GetLibraryManga] 的区别：书架只含 `favorite = 1` 的条目，而云盘 / 本地图源里
 * 绝大多数漫画从不加入书架（实测某源 3501 本、书架 0 本）。
 * 阅读器要「读完这部接着看同文件夹下一部」，候选必须来自本接口。
 */
@Inject
class GetMangasBySource(
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(sourceId: Long): List<Manga> {
        return mangaRepository.getMangasBySource(sourceId)
    }
}

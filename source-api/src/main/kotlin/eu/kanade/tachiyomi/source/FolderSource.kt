package eu.kanade.tachiyomi.source

/**
 * SY --> 支持「文件夹视图」的源（本 fork：本地图源 / WebDAV 云盘）。
 *
 * 进源后先展示一层文件夹网格（每个文件夹取内部首图作为封面），点进文件夹后
 * 才是该文件夹内的漫画列表；右上角菜单支持「添加文件夹」。
 *
 * 契约：
 *  - [getFolders] 返回文件夹视图条目；[SourceFolder.url] 是文件夹标识（本地图源 = 目录 URI，
 *    WebDAV = 云端相对路径），[SourceFolder.coverUrl] 直接给 Coil 加载（懒加载）。
 *  - [setActiveFolder] 限定之后的 getPopularManga / getLatestUpdates / getSearchManga
 *    只在该文件夹内列出漫画；传 null 表示不限定（列出全部）。
 *  - [addFolder] 添加一个文件夹：本地图源传 SAF 目录 URI，WebDAV 传云端目录路径。
 *    返回 false 表示添加失败（不存在 / 重复等）。
 */
interface FolderSource {

    suspend fun getFolders(): List<SourceFolder>

    suspend fun setActiveFolder(folderUrl: String?)

    suspend fun addFolder(identifier: String): Boolean
}

data class SourceFolder(
    val url: String,
    val name: String,
    val coverUrl: String?,
)

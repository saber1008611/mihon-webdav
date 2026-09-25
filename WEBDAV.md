# WebDAV 云盘源（阅读端）架构说明

> 本文档描述的是**当前**代码里实现的 WebDAV 能力：一个内置的、**读取**阅读源（"WebDAV 云盘"），
> 用来在 Mihon 里直接浏览/阅读存放在 WebDAV 服务器上的漫画。
> （早期版本曾把"下载章节自动上传到 WebDAV"作为独立功能，现已演进为对称的读取源，本文以当前为准。）

基于官方 Mihon `0.20.4`（`app.mihon`，debug keystore 签名）。

## 它是什么

一个自包含的内置 `Source`（`WebDavSource`，同时实现 `UnmeteredSource`），与上传侧
（`CloudSyncService` 把 CBZ 推到网盘）对称 —— 这里负责把网盘上的漫画在 Mihon 里读回来。

- 源 ID：`-20260924L`（与 `LocalSource.ID=0` 区分，避免与扩展源 ID 冲突）。
- 名称：`WebDAV 云盘`，语言 `other`，默认在「浏览」页出现、无需安装扩展。
- 复用同一套 WebDAV 配置（`DownloadPreferences` 里的 `cloudSync*` 偏好）与同一个
  `CloudSyncService`，因此"配置一次，上传/读取共用"。

## 远端目录约定

```
目标目录 (cloudSyncDestination)/
└── {漫画文件夹}/
    ├── cover.jpg            # 可选，系列封面（与章节并列）
    ├── 第01话.cbz
    ├── 第02话.cbz
    └── ...
```

- `目标目录` 的**直接子目录**即漫画文件夹（单层，不递归）。
- 每个漫画文件夹内的 `*.cbz` 即章节；`cover.{jpg,jpeg,png,webp}` 作为封面。

## 关键设计

| 项 | 实现 |
|---|---|
| 列表浏览 | `listDirectories(config, destination)` 列直接子目录 → 每个子目录是一本书；**只列目录、不预拉封面**，几十上百本也不转圈 |
| 章节列表 | `listFiles(config, manga.url)` 过滤 `.cbz`，用 `ChapterRecognition` 解析话数 |
| 阅读 | `ChapterLoader` 在 `source is WebDavSource` 分支调用 `getArchiveFile()` 把 CBZ **流式下载到缓存**，再交给现成的 `ArchivePageLoader` 读页（翻页/封面/ComicInfo 全复用）；`getPageList()` 抛 `UnsupportedOperationException`（与 `LocalSource` 同一契约） |
| 封面懒加载 | 列表项填 `content://eu.kanade.tachiyomi.webdav.cover/<编码路径>`（`WebDavCoverProvider`）。Coil 在网格项滚到可见时才各下载一次，天然懒加载、不阻塞列表；且走与上传同源、带鉴权的 `CloudSyncService`，绕开 Mihon 全局图片加载器对 WebDAV 鉴权的限制 |
| 详情不污染 | `getMangaUpdate` **不改写** `thumbnail_url`：列表里填的 `content://` 直链在详情页同样由 `MangaCoverFetcher` 走 `Type.URI` 加载；若改成 `file://` 会被详情页 `upsert` 写进数据库，返回列表时污染封面 |
| 排序/筛选 | 稳定单例 `sortFilter`（`Filter.Sort("排序", ["最近更新","名称"])`）。`热门/最新` tab 的 `GetRemoteManga` 不带 `filters` 参数，因此排序 Filter **必须返回稳定单例**，界面改 state 源内部才读得到。最新 tab 默认按目录修改时间倒序；`SManga` 在本 fork 无 `date_upload` 字段，故用内部 `WebDavMangaListItem(manga, lastModified)` 携带时间 |
| 未配置降级 | `configOrNull()` 在 `cloudSyncEnabled==false` 或 URL 为空时返回 `null`，所有浏览/详情方法据此**优雅返回空**，不抛异常连累全局搜索等调用方 |

## 涉及文件

| 文件 | 作用 |
|---|---|
| `app/src/main/java/eu/kanade/tachiyomi/source/webdav/WebDavSource.kt` | 源本体：列表/章节/排序/归档读取 |
| `app/src/main/java/eu/kanade/tachiyomi/source/webdav/WebDavCoverProvider.kt` | `content://` 封面通道（可见才带鉴权下载） |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/CloudSyncService.kt` | WebDAV 客户端：`PROPFIND`/`GET`、目录列举、封面上传/下载、时间解析 |
| `app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt` 与 `domain/.../GetEnabledSources.kt` | 把 `WebDavSource` 注入内置源列表 |
| `app/src/main/AndroidManifest.xml` | 注册 `WebDavCoverProvider`（authorities=`eu.kanade.tachiyomi.webdav.cover`，`exported=false`，`grantUriPermissions=true`） |

## 协议与认证

- 只读用到 `PROPFIND`（列目录、取 `getlastmodified` 用于排序）与 `GET`（下载 CBZ / 封面）。
- 认证走 HTTP Basic（`okhttp3.Credentials`），由 `CloudSyncConfig(url, username, password)` 承载。
- 时间字段解析 RFC1123（`parseLastModified`，失败回退 0）。

## 与超分（Anime4K ACNet）的关系

超分与 WebDAV 是正交的两套能力，互不影响：WebDAV 源读出的页在进入 `ArchivePageLoader` 后，
与其它来源（本地/在线/下载）一样，都会被 `ImageEnhancer` 按需超分（阅读器 *色彩/滤镜* 页开关）。
超分模型内置在 `app/src/main/assets/anime4k-acnet-models/`，纯本地 GPU，不上传图片。

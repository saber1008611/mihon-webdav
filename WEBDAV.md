# Mihon 0.20.4 + WebDAV 云同步（上传）

在**官方 Mihon**（`mihonapp/mihon`，tag/版本 0.20.4）的基础上，加入「下载章节自动上传到 WebDAV」的云同步功能。
超分（waifu2x / ncnn / Anime4K）**不在本工程范围内**，这里只有 WebDAV。

- 工程路径：`G:\code\mihon-webdav`
- 基线：官方 Mihon 0.20.4（`versionCode 30` / `versionName 0.20.4` / `applicationId app.mihon`）
- 基线来源：`G:\code\mihon-upscale` 的 git 提交 `ebc4ed0 mihon 0.20.4 baseline`（该提交即官方 0.20.4 原版，
  后续改动都是工作区未提交内容），用 `git archive HEAD` 导出，保证零污染。

## 功能行为

| 项目 | 行为 |
|---|---|
| 触发时机 | 章节**下载完成**、并且开启了「保存为 CBZ」时，拿刚生成的那个 `.cbz` 直接上传 |
| 远端目录结构 | `目标目录/{来源目录名}/{漫画标题}/` — 与本地下载目录同构 |
| 去重 | 已上传章节记录在偏好 `cloud_uploaded_chapter_ids`，重启后不重复上传 |
| 并发 | 可配 1–5（偏好 `cloud_sync_parallel_upload_limit`），设置改动**热生效**（重建 worker） |
| 单章控制 | 支持暂停 / 继续 / 取消，API 在 `Downloader`（`pauseCloudUpload` / `resumeCloudUpload` / `cancelCloudUpload`） |
| 上传后删除本地 | 可选（`cloud_sync_delete_after_upload`） |
| 失败处理 | 写错误日志 + 弹 warning 通知；任务出队，下次该章仍可重试 |
| 前置条件 | 「保存为 CBZ」必须开启 + WebDAV 地址/账号/目标目录都填好，否则 `isCloudSyncAvailable() == false`，**静默不上传** |

协议只用 WebDAV 最基础的四个动词，兼容绝大多数网盘/自建服务：

- `PROPFIND` — 列目录、判断目录是否存在
- `MKCOL` — 按需逐级创建目标目录
- `PUT` — 上传 CBZ（带进度回调）
- `DELETE` — 开启「上传后删除本地」时清理远端

认证走 HTTP Basic（`okhttp3.Credentials`），上传走独立的 OkHttpClient（读写/整体超时都放宽到分钟级），
避免大文件被默认 10s 超时掐断。

## 使用方法

1. 装 APK（见下方安装约束）。
2. 设置 → 下载 → **云盘同步** → 点进去填 `WebDAV 地址` / 用户名 / 密码 → 会先**测试连接**
   （对根目录发一次 PROPFIND），成功才落盘并自动置 `cloudSyncEnabled = true`。
3. 回到同页选 **同步目标位置** → 进入远端目录浏览器（懒加载目录树）挑一个目录。
4. 确认「保存为 CBZ」已开启（否则云同步整组不可用，页面上会提示 `需要先开启「保存为 CBZ 压缩包」`）。
5. 之后下载章节就会自动上传。设置页顶部会显示「已连接为 {用户名}」或「未配置」。

## 改动清单（7 个文件）

新增：

| 文件 | 行数 | 作用 |
|---|---|---|
| `app/src/main/java/eu/kanade/tachiyomi/data/download/CloudSyncService.kt` | 443 | WebDAV 客户端：PROPFIND/MKCOL/PUT/DELETE + `CloudSyncConfig`/`CloudSyncDirectory` |
| `app/src/main/java/eu/kanade/presentation/more/settings/screen/CloudSyncDestinationScreen.kt` | 256 | 远端目标目录选择器（懒加载目录树） |

改造：

| 文件 | 增删 | 作用 |
|---|---|---|
| `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt` | +252 −2 | 上传子系统：`Channel` 队列 + 并发 worker + 进度 StateFlow + 暂停/继续/取消 |
| `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsDownloadScreen.kt` | +264 | 「云盘同步」设置组 + 登录对话框（含测试连接） |
| `domain/src/main/java/tachiyomi/domain/download/service/DownloadPreferences.kt` | +67 | 8 个偏好项 + 已上传章节/元信息哈希记录 |
| `app/src/main/java/mihon/app/di/AppGraph.kt` | +5 | 暴露 `cloudSyncService` |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadManager.kt` | +15 | 把上传状态与控制入口转出给 UI |

i18n：`base` / `zh-rCN` / `zh-rTW` 各 +15 条（`cloud_sync*` 13 条 + `action_select` + `pref_cloud_sync_concurrent_uploads`）。
三语同步写入是硬要求 —— 只写 `base` 时中文环境会静默回退英文。

## 与「超分版」（`G:\code\mihon-upscale`）的关系

本工程是那份工程的**子集**：同样的 WebDAV 代码，但剥掉了全部超分（`util/waifu2x/`、`app/src/main/cpp/`、
`ColorFilterPage`、reader 侧的一堆改动等），基线直接取官方 0.20.4 原版。

差异仅在依赖注入：上游（HaoweiLi97/mihon_img_upscale）走 Injekt，这里适配官方 Mihon 的 **Metro**
（`@Inject @SingleIn(AppScope::class)`），协议细节逐行一致。

顺带保留了「构建环境修复」（非功能改动，本机编译必需）：

- `settings.gradle.kts` / `gradle/build-logic/settings.gradle.kts` — 国内镜像优先 + `dev.zacsweers.*` 定向 Maven Central + `com.github.*` 定向 jitpack（镜像会「有元数据无构件」，Gradle 不跨仓库 fallback）
- `gradle.properties` / `gradle/build-logic/gradle.properties` — 关掉 AGP 自动下载 SDK、放宽依赖下载超时

## 构建

```bash
cd /g/code/mihon-webdav
bash build_release.sh
```

脚本内容：JDK21（`D:/build_tools/jdk21/jdk-21.0.12.1+1`）、`GRADLE_USER_HOME=G:/code/gradle-home`、
`arm64-v8a` only、显式 `-Pandroid.injected.testOnly=false`。

> 坑：一旦带上 `android.injected.*` 前缀，AGP 会按「IDE 注入构建」处理并把 `android:testOnly` 打开，
> 产物装真机时会被 `INSTALL_FAILED_TEST_ONLY` 拒绝。另外带 `build.abi` 时产物**不在**
> `app/build/outputs/apk/`，而在 `app/build/intermediates/apk/release/`。

## 安装约束（重要）

本仓库用 AGP 默认 **debug keystore** 签名（官方 `app/build.gradle.kts` 的 release 是
`signingConfig = debug.signingConfig`，仓库里没有 `keystore.properties`，那是官方 CI 专有）：

- 证书 `CN=Android Debug, O=Android, C=US`，SHA-1 `9ee1f00a3613dc63f1a545a32c3304ddcf7c2121`
- **装不上 Google Play / GitHub 官方发的 Mihon**（签名不同，`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），
  需要先卸载官方版
- 但和之前那批本机构建（`mihon-upscale` 系列）**同签名**，可以原地覆盖升级

## 已知缺口

「单章上传进度」这套 API 已经通了（`DownloadManager.cloudUploadProgress` / `resumeCloudUpload` …），
但**下载列表 UI 还没有消费它** —— 也就是上传过程在界面上不可见，只有日志
（`CloudSync: chapter <id> (<标题>) uploaded`）和失败时的 warning 通知。
`cloud_sync_uploading_progress`（「正在上传 %d%%」）这条文案也已备好但暂未使用。
这是上一版实现就有的状态，本工程忠实保留，未擅自扩范围。

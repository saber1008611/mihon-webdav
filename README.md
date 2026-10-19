<div align="center">

# mihonW · 云盘 / WebDAV / 本地漫画阅读器

### 基于 Mihon 0.20.4 的派生版：把 115、夸克、阿里云盘、WebDAV、本地文件夹里的漫画，用一个书架读完

> 🐞 **BUG 反馈 QQ 群：1131187159** —— 装不上、连不上、某本书打不开、封面不对，都可以进群反馈

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84.svg)](#安装)
[![Base](https://img.shields.io/badge/base-Mihon%200.20.4-blue.svg)](https://github.com/mihonapp/mihon)
[![Version](https://img.shields.io/badge/version-1.0.35-orange.svg)](#安装)
[![BUG 反馈群](https://img.shields.io/badge/QQ%E7%BE%A4-1131187159-12B7F5.svg)](#bug-反馈)

[功能](#功能总览) · [网盘](#一网盘三个原生网盘源) · [WebDAV](#二webdav) · [本地文件夹](#三本地文件夹) · [文件夹视图](#四文件夹视图一个书架看全部) · [格式](#支持的漫画格式) · [安装](#安装) · [构建](#从源码构建) · [FAQ](#常见问题) · [BUG 反馈](#bug-反馈) · [鸣谢](#鸣谢上游)

</div>

---

## 这是什么

这是 **[Mihon](https://github.com/mihonapp/mihon)**（Android 漫画阅读器，原 Tachiyomi）的派生版，基线为官方 `0.20.4`。上游的全部功能（图源扩展、书架、更新、历史、追踪、备份、阅读器各模式）都保留，在此之上做了一件上游没有的事：

> **把「自己的漫画库」直接变成一个内置图源** —— 不管它躺在 115 网盘、夸克网盘、阿里云盘、自建 WebDAV 服务器，还是手机本地文件夹里，都能在应用内**用文件夹视图浏览、点开就读**，不需要下载整本，不需要额外装扩展，也不需要先建库。

三种接入方式共用同一套浏览、封面、流式读取与阅读器逻辑，所以界面和手感完全一致。

| | 网盘 | WebDAV | 本地文件夹 |
|---|---|---|---|
| 是否需登录 | 需要（扫码 / 令牌） | 需要（地址 + 账号密码） | 不需要（系统选择目录） |
| 是否需要额外服务 | 不需要 | 需要你自己的 WebDAV 服务 | 不需要 |
| 支持流式读（不整本下载） | ✅ | ✅ | ✅（本地随机读，零成本） |
| 封面懒加载 | ✅ | ✅ | ✅ |
| 入口 | 书架顶栏「存储」图标 | 同左 | 书架顶栏「文件夹」图标 |

> ⚠️ 这是**非官方**派生版，与 Mihon 官方无隶属关系。包名为 `app.mihonW`（官方是 `app.mihon`），两者**可以并存**；安装包使用默认 **debug keystore** 签名，所以只能覆盖本项目自己的旧版本，不能覆盖官方 Mihon。

---

## 功能总览

- **三个原生网盘源**：115 网盘（开放平台）、夸克网盘、阿里云盘 —— 扫码或贴令牌即登录，直接浏览网盘目录。
- **WebDAV 源**：Nextcloud、群晖/威联通 NAS、Alist、CloudDrive2 挂载等任意标准 WebDAV 服务。
- **本地文件夹源**：用系统文件选择器（SAF）添加任意本地目录，包括 SD 卡与 U 盘。
- **文件夹视图**：不进书架也能像文件管理器一样逐层浏览，一个页面看完全部内容。
- **流式读取**：ZIP/CBZ 只拉中央目录（实测打开 47ms）、PDF 走 Range 按需取页、MOBI/AZW 只读记录表、散图目录按页取图 —— **都不用整本下载**。
- **封面懒加载**：滑到可见才下载，且只在设备上降采样到 ≤450px JPEG（q85）落盘缓存，网格不转圈、不烧流量。
- **网盘风控友好**：封面扫描默认 4 线程并限并发，115 等平台不会因为刷封面被限速。
- **离线 Anime4K ACNet 超分**：内置 ncnn 模型，GPU 端 2× 放大，不上传任何图片。
- **其它**：双页/条漫/竖向连续阅读、整本下载、WebDAV 上传、阿里云盘备份同步。

---

## 一、网盘（三个原生网盘源）

网盘**不是扩展**，是编译进应用的内置源。首次使用前需要先在设置里登录一次，之后网盘目录会以「挂载」的形式出现在**云盘**列表里。

### 支持的网盘

| 网盘 | 登录方式 | 需要准备什么 | 说明 |
|---|---|---|---|
| **115 网盘（开放平台）** | 粘贴令牌 | 在 [api.oplist.org](https://api.oplist.org) 获取 Access Token / Refresh Token | 走 115 官方开放平台 OAuth，过期自动刷新。⚠️ 每个账号在同一应用**最多 2 个刷新令牌**，第 3 次获取会顶掉最早的 |
| **夸克网盘** | 扫码 | 手机上的夸克 App | 设置里点「扫码登录」，用夸克 App 扫码确认，Cookie 自动保存，无需手动复制 |
| **阿里云盘** | 扫码 | 手机上的阿里云盘 App | 设置里点「扫码登录」；同一套凭据也用于「备份同步」 |

### 登录步骤

**更多 → 设置 → 网盘 → 网盘（阅读）**

- **115**：点「115 开放平台：获取令牌」跳到 oplist.org → 拿到令牌后，分别粘贴到「访问令牌（Access Token）」与「刷新令牌（Refresh Token）」→ 点「115 开放平台：测试连接」确认。
- **夸克**：点「夸克网盘：扫码登录」→ 用夸克 App 扫码 → 回到设置点「夸克网盘：测试连接」。
- **阿里云盘**：点「阿里云盘：扫码登录」→ 扫码确认 → 点「阿里云盘：测试连接」。

> 设置项副标题会直接显示状态（`已配置` / `未配置`），不用猜有没有成功。

### 挂载网盘目录

登录只完成"能连上"，还要告诉应用**看网盘的哪个目录**：

1. 打开**云盘**（书架顶栏「存储」图标）。
2. 右上角 **⋮ → 添加网盘文件夹**。
3. 在弹出的对话框里选一个网盘（未登录的会显示「（未登录）」并置灰）。
4. 进入目录选择器，**逐层点选**到目标文件夹，确认。
5. 挂载完成后会自动刷新，并在后台**用 4 线程扫描封面**（防止触发网盘风控）。

挂载的文件夹会出现在「云盘」列表里，之后的阅读、封面、流式读取与 WebDAV 完全一致。

> 已挂载的目录在内部以 `@<网盘标识>:<目录 id>` 的形式存在，所以**同一个云盘列表里可以同时混装多个网盘的多个目录**，互不干扰。

---

## 二、WebDAV

适用于任何标准 WebDAV 服务：Nextcloud、群晖/威联通 NAS、Alist、CloudDrive2 挂载、自建 Nginx+WebDAV 等。

### 配置步骤

**更多 → 设置 → 网盘 → WebDAV（网盘同步） → WebDAV 登录**

1. 打开总开关。
2. 填 **WebDAV 地址**（完整 URL，含协议与端口，例如 `https://nas.example.com/dav`）。
3. 填**用户名**与**密码**。
4. 点**测试连接** —— 会真连一次服务器，连不上不会保存。
5. 选择**同步目标位置**（目录树懒加载选择器，逐层点选）。

> 这个账号同时用于两件事：**读**（云盘源浏览/阅读）和**写**（把下载好的漫画上传回服务器）。上传需要先在下载设置里开启「保存为 CBZ 压缩包」，否则会提示 `需要先开启「保存为 CBZ 压缩包」才能使用 WebDAV 上传`。

### 目录结构约定

```
<你选的目标目录>/
├── 某部漫画/
│   ├── cover.jpg          ← 封面（可选，见下）
│   ├── 第01话.cbz
│   ├── 第02话.cbz
│   └── 第03话.zip
└── 另一部漫画/
    ├── cover.webp
    └── ...
```

- **一个漫画 = 一个文件夹**，文件夹名就是作品名。
- **一个章节 = 一个压缩包文件**（或 PDF / EPUB / MOBI，见[格式](#支持的漫画格式)）。
- **封面**：与章节并列放 `cover.jpg`（也支持 `cover.webp` / `cover.png` / `cover.jpeg`）。没有封面文件时会**自动从第一话里抽第一张图**当封面，所以不强制准备。
- **散图文件夹**：某个目录里直接堆着图片而没有章节文件时，整目录会被当成**一个章节**来读，图片按自然顺序排列。

### 设置里的三个位置别搞混

| 设置项 | 位置 | 作用 |
|---|---|---|
| **网盘** | 更多 → 设置 → 网盘 | 三个网盘的登录、WebDAV 登录、备份同步入口 |
| **WebDAV 登录** | 更多 → 设置 → 网盘 → WebDAV（网盘同步） | 地址/账号/密码/目标目录（实为「下载」设置里的云同步分组） |
| **备份同步（阿里云盘）** | 更多 → 设置 → 网盘 → 备份同步 | 跳转到「数据与备份」，只有阿里云盘一种远端 |

---

## 三、本地文件夹

不需要任何账号，用 Android 的系统文件选择器（SAF）添加本机目录，SD 卡、U 盘、下载目录都行。

**书架 → 顶栏「文件夹」图标（本地图源） → 右上角 ⋮ → 添加文件夹**

添加后该目录出现在本地文件夹列表里，逐层浏览、点开即读。本地目录的读取是**直接随机读文件**，所以 ZIP/CBZ/PDF 都是流式的，不会先把几百 MB 拷进缓存。

> 本地源与云盘源的界面、封面规则、排序筛选、阅读器完全同构 —— 学会一个就会另一个。

---

## 四、文件夹视图：一个书架看全部

这是本派生版最核心的用法：**不进书架、不建库，直接像文件管理器一样浏览你的全部内容**。

### 云盘入口在哪

| 步骤 | 位置 | 说明 |
|---|---|---|
| ① | 底部导航 **书架** | 打开书架页 |
| ② | 顶栏右侧 **存储图标**（圆柱体/硬盘样式，`Storage` 图标） | 这就是**云盘入口**，源名即「云盘」 |
| ③ | 顶栏右侧 **文件夹图标** | 这是**本地文件夹入口**，源名即「本地图源（本地文件夹）」 |

书架顶栏右侧动作区从左到右依次是：

```
[📁 文件夹图标]  [▤ 存储图标]  [筛选 ≡]  [⋮ 更多]        （左侧是搜索 🔍）
         本地            云盘
```

两个图标下方还各有一行小字说明：**「本地」** 与 **「云盘」** —— 看到这两个字就是入口。

> 图标位置可能随上游版本微调，但**「文件夹」和「存储」两个图标永远在书架顶栏的右侧动作区**，分别对应「本地图源（本地文件夹）」与「云盘」两个内置源。

点进去就是**文件夹视图**：

- 网格默认自适应（`GridCells.Adaptive(128.dp)`，手机上一般 3 列），可在书架设置里改成固定列数、或切换紧凑/舒适/列表；每个格子是一个**文件夹**，显示文件夹名 + 封面（取该目录下第一个漫画目录的封面，没有则回退第一话首页）。
- 点文件夹 → 进入下一层；点到底就是章节，点章节 → 直接进阅读器。
- 长按进入多选，可批量操作。
- 顶栏同样有**搜索**（按标题过滤）和**筛选**（排序：最近更新 / 名称）。
- 右上角 **⋮** 菜单提供：添加文件夹 / 添加 WebDAV 文件夹 / **添加网盘文件夹** / 扫描封面 / 慢速封面4线程 / 单线程扫描封面 / 更新书架 / 更新分类 / 随机打开作品。

### 扫描封面

封面默认懒加载。如果想让整个目录的封面一次性就位，菜单里有三档：

| 菜单项 | 并发 | 每本之间间隔 | 适用场景 |
|---|---|---|---|
| **扫描封面** | 4 线程 | 0 | 本地 / WebDAV，最快 |
| **慢速封面4线程** | 4 线程 | 800ms 错峰 | 网盘风控偏严 |
| **单线程扫描封面** | 1 线程 | 1500ms 错峰 | 专治 115 踢下线 |

> 115 的风控盯的是「短时间内的调用次数」，所以**把请求在时间轴上摊开**比单纯降并发更有效 —— 单线程档实测能救回「一扫封面就被踢」的账号。

扫描时顶栏标题会变成进度文字：`正在扫描封面 12/56`。

---

## 支持的漫画格式

### 章节容器

| 格式 | 扩展名 | 读取方式 | 要不要整本下载 |
|---|---|---|---|
| ZIP / CBZ | `zip` `cbz` | 只拉压缩包中央目录，按需 Range 取图 | **不用**，流式 |
| PDF | `pdf` | pdfium 引擎，走 HTTP Range 按需取页 | **不用**，流式（深链自动回退，见下） |
| MOBI / AZW / AZW3 | `mobi` `azw` `azw3` | 只读 PDB 记录表，图片 record 按页拉取 | **不用**，流式 |
| 散图文件夹 | — | 目录内图片按自然顺序成页 | **不用**，流式 |
| EPUB | `epub` | 整本下载后用 epub 解析器打开 | 要 |
| RAR / CBR | `rar` `cbr` | 整本下载后解压 | 要（libarchive 需真实文件） |
| 7Z / CB7 | `7z` `cb7` | 整本下载后解压 | 要（同上） |
| TAR / CBT | `tar` `cbt` | 整本下载后解压 | 要（同上） |

> **PDF 说明**：PDF 用 **pdfium**（不是 Android 内置的 PdfRenderer）。打开时会先探一遍 `/Prev` 增量更新链 —— 链浅就流式 Range 读；链太深（实测遇到过 205 跳）则自动回退为整本下载，因为逐块 Range 反而更慢（同一个 222MB 的 PDF：整本 11.4s vs 逐块 Range 31.4s）。这个切换是自动的，不用你选。

### 图片格式

散图章节与压缩包内图片支持：`jpg` `jpeg` `png` `webp` `gif` `bmp` `avif` `heic` `heif` `tif` `tiff`

### 封面文件名

`cover.jpg` > `cover.webp` > `cover.png` > `cover.jpeg`（按此优先级查找）

---

## 超分（Anime4K ACNet）

内置 **Anime4K v4 ACNet**（bloc97/Anime4K，MIT）的 ncnn 移植版，**离线**在设备 GPU 上把阅读页做 **2× 放大**，不上网、不上传、不联网也能用。

- 打开任意章节 → 阅读器**齿轮** → **色彩/滤镜** 页 → 打开 **画面增强**。
- 相关选项：预载页数、GPU 性能模式（全速 / 50% / 30%）、分块大小、精度（FP16 / FP32）、处理分辨率上限、超过分辨率跳过、显示处理状态。
- **超分让路**：阅读/翻页时自动降低超分优先级，保证翻页不卡。
- 只在 **luma（亮度）通道**推理，色度用原图合成，所以**不会改变原图颜色**。

> 模型固定 2×、噪声等级固定 0。ARM GPU（Mali/Immortalis）上会强制收敛 FP16，因为 FP16 算术在该类 GPU 上会产生规则噪点场（快 1.4–1.5× 但画质不可接受）。

---

## 安装

### 直接安装 APK

1. 从 [Releases](../../releases/latest) 下载 `app-arm64-v8a-release.apk`（本仓库还没有发布 Release 时，见下方[从源码构建](#从源码构建)）。
2. 直接安装即可 —— 包名是 `app.mihonW`，与官方 Mihon（`app.mihon`）**不同包名，可以并存**，不会覆盖官方版。
3. 只有在**安装过本项目的旧签名包**时才需要先卸载（同一包名、不同签名 → `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。本项目自 `1.0.x` 起一直用同一套 debug keystore，正常可直接覆盖升级。

系统要求：**Android 8.0（API 26）及以上**，arm64-v8a（`build_release.sh` 只打这个 ABI；若自己用 `./gradlew assembleRelease` 则默认产出 universal 包，含 `armeabi-v7a` / `x86` / `x86_64`）。

### 从源码构建

```bash
# 依赖：JDK 21、Android SDK（platform 37.1 + build-tools）、NDK 28.2.13676358
./gradlew assembleRelease

# 或使用自带脚本（已配好 JDK / GRADLE_USER_HOME，只打 arm64-v8a）：
bash build_release.sh
```

产物：`app/build/outputs/apk/release/app-arm64-v8a-release.apk`

> ⚠️ **构建超分需要 ncnn SDK**。仓库内不含 `third_party/`，需自行提供以下任一：
> - `local.properties` 里写 `ncnn.sdk.dir=/path/to/ncnn-20260113-android-vulkan`
> - 命令行 `-PncnnSdkDir=/path/to/...`
> - 环境变量 `NCNN_SDK_DIR`
> - 或把 SDK 放到仓库下的 `third_party/ncnn-20260113-android-vulkan/`
>
> 缺失时 CMake 会直接 `FATAL_ERROR`。

---

## 目录结构（本 fork 主要改动）

| 路径 | 作用 |
|---|---|
| `app/src/main/java/eu/kanade/tachiyomi/source/webdav/BaseWebDavSource.kt` | 云盘/WebDAV/本地三种后端的**公共基类**：浏览、章节、封面、文件夹视图、排序全在这里 |
| `app/src/main/java/eu/kanade/tachiyomi/source/webdav/WebDavSource.kt` | 「云盘」源 + 网盘挂载门面（把 115/夸克/阿里路由到同一套逻辑） |
| `app/src/main/java/eu/kanade/tachiyomi/source/webdav/LocalWebDavSource.kt` | 「本地图源（本地文件夹）」 |
| `app/src/main/java/eu/kanade/tachiyomi/source/netdisk/` | 三个网盘后端：`cloud115open/`、`quark/`、`aliyun/` |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/CloudSyncService.kt` | WebDAV 客户端（PROPFIND / MKCOL / PUT / 鉴权下载） |
| `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/FolderLibraryScreen.kt` | **文件夹视图** |
| `app/src/main/java/eu/kanade/tachiyomi/util/pdf/PdfiumRenderer.kt` | pdfium 封装（支持 Range 随机读） |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/` | 各类页加载器：Archive / Pdf / Epub / Mobi / ImageFolder |
| `app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/` | 超分引擎封装（ncnn Anime4K ACNet） |
| `app/src/main/cpp/` | 原生 ncnn 推理、Vulkan 融合着色器、纹理防护 |
| `app/src/main/assets/anime4k-acnet-models/` | 内置模型（`anime4k_acnet.param` / `.bin`） |
| `WEBDAV.md` | 开发者向：云盘源架构说明 |
| `docs/使用说明.md` | 中文用户手册（部分内容较旧，以本 README 为准） |

---

## 常见问题

> 下面没覆盖到的问题，欢迎进 **BUG 反馈 QQ 群：1131187159** 反馈；提问题时附上机型、Android 版本和出问题的目录/文件类型，排查会快很多。

<details>
<summary><b>网盘登录成功，但「云盘」里看不到内容</b></summary>

登录只代表能连上，还需要**挂载目录**：云盘 → ⋮ → 添加网盘文件夹 → 选网盘 → 逐层点选目标目录。
</details>

<details>
<summary><b>点「添加网盘文件夹」，某个网盘是灰的并写着「（未登录）」</b></summary>

该网盘的凭据没配好。去 **更多 → 设置 → 网盘 → 网盘（阅读）** 重新登录，并用「测试连接」确认。
</details>

<details>
<summary><b>115 的令牌过一段时间就失效</b></summary>

115 开放平台每个账号在同一应用**最多 2 个刷新令牌**，第 3 次获取会顶掉最早的。如果同时在别的工具里登了 115，令牌会互相顶掉 —— 只保留一个地方使用即可。
</details>

<details>
<summary><b>封面一直是空白</b></summary>

封面是懒加载的，滑到可见才会下载。想一次性补齐：文件夹视图 → ⋮ → 扫描封面。若网盘风控严格，改用「慢速封面4线程」或「单线程扫描封面」。
</details>

<details>
<summary><b>WebDAV 上传失败，提示「需要先开启「保存为 CBZ 压缩包」」</b></summary>

上传只支持 CBZ。去 **设置 → 下载** 打开「保存为 CBZ 压缩包」。
</details>

<details>
<summary><b>打开大 PDF 很慢</b></summary>

PDF 默认会探一次增量更新链，链太深时自动回退整本下载（这个回退比逐块 Range 快得多）。如果单本 PDF 特别大，可以在详情页用「整本下载」先落到本地，之后再打开就是纯本地读取。
</details>

<details>
<summary><b>装不上，报 INSTALL_FAILED_UPDATE_INCOMPATIBLE</b></summary>

说明你装过**同一个包名 `app.mihonW` 但签名不同**的旧版本（比如自己重新编译过）。卸载那个旧版本再装即可。官方 Mihon 是另一个包名，不受影响、不用卸载。
</details>

<details>
<summary><b>超分开着但页面没变化</b></summary>

检查三处：① 阅读器齿轮 → 色彩/滤镜 → 画面增强是否打开；② 当前页分辨率是否超过「处理分辨率上限」而被跳过；③ 是否打开了「显示处理状态」，右上角会显示 `增强中` / `已增强`。
</details>

---

## BUG 反馈

| 渠道 | 用途 |
|---|---|
| **QQ 群：1131187159** | 首选。装不上、连不上网盘/WebDAV、某本书打不开、封面不显示、超分异常，都可以直接问 |
| [GitHub Issues](https://github.com/saber1008611/mihon-webdav/issues) | 适合能稳定复现、需要贴长日志的问题 |

提问时尽量带上这几项，能省掉大半来回：

- 手机型号 + Android 版本（例：Redmi K30 Pro / Android 12）
- 出问题的**接入方式**：115 网盘 / 夸克 / 阿里云盘 / WebDAV / 本地文件夹
- 出问题的**文件类型和大小**：例 `cbz 320MB`、`pdf 222MB（扫描版）`、`散图文件夹 800 张`
- 复现步骤：从哪个入口进去、点了什么、期望什么、实际什么
- 有条件的话附日志：`adb logcat -d | findstr /i "mihonW FATAL AndroidRuntime"`

---

## 鸣谢上游

这个项目**绝大部分代码不是本仓库写的**。它站在一串上游项目的肩膀上，下面按「继承 / 移植 / 依赖」三类分别说明。

### 一、直接继承的上游

| 项目 | 关系 | 许可证 |
|---|---|---|
| [**Mihon**](https://github.com/mihonapp/mihon) | **本项目的基线**，代码取自官方 `0.20.4`。图源框架、书架、更新、历史、追踪、备份、阅读器（单页/双页/条漫/竖向连续）、主题、i18n、扩展体系全部来自它 | Apache-2.0 |
| [**Tachiyomi**](https://github.com/tachiyomiorg/tachiyomi) | Mihon 的前身，整个架构与生态的源头；Mihon 本身即其继任者 | Apache-2.0 |
| **Mihon / Tachiyomi 全体贡献者与译者** | 本仓库只在其上加了「网盘 / WebDAV / 本地文件夹」这一层图源与一套离线超分，其余每一行都来自他们多年的工作 | — |

> 本仓库是 Mihon 的**非官方**修改版，名称、图标与 Mihon 官方无隶属关系；`app/google-services.json` 是上游 Mihon 公开的 Firebase 客户端配置（公有 API key），仅用于构建，不含私密凭据。

### 二、移植或参考过的实现

| 来源 | 用在哪 | 许可证 |
|---|---|---|
| [**Komiho**](https://github.com/ruzhe85/Komiho) | 封面保存策略移植自其 `WebDavCoverCache`（按漫画粒度落盘、≤450px / JPEG q80 降采样、未生成时不发请求）；超分原生的 tile 尺寸推导、prepadding、自适应批量、连续滚动让出（`Phase 9`–`Phase 12`）也沿用其思路 | Apache-2.0 |
| [**Anime4K**](https://github.com/bloc97/Anime4K) | 超分算法本体 —— v4.0 `ACNet` 网络结构（9×Conv3x3 + ReLU + ConvTranspose 2×，单通道 luma） | MIT |
| [**Venera-SSR**](https://github.com/Kiastr/Venera-SSR) | 提供可直接使用的 `anime4k_acnet.onnx` 权重；本仓库手写脚本转成 ncnn 的 `param`/`bin`，并用 onnxruntime 逐层对齐校验。**只取了这一个权重文件，未使用其任何代码** | GPL-3.0（仅权重文件） |
| [**OpenList**](https://github.com/OpenListTeam/OpenList) | 115 开放平台接入的**协议行为**参考：`authRequest` 的 401 刷新重试、`Link()` 把取链 UA 绑定到下载、`pick_code` 合并并发取链、限速礼貌。按协议独立实现，**未引入其代码** | AGPL-3.0（仅参考） |
| [**api.oplist.org**](https://api.oplist.org) | 115 令牌助手：用它走一次 OAuth，把 access/refresh token 贴回本应用 | — |

### 三、第三方依赖

| 依赖 | 用途 | 许可证 |
|---|---|---|
| [**ncnn**](https://github.com/Tencent/ncnn)（Tencent） | 超分推理引擎，Vulkan GPU 后端 + 自写融合着色器 | BSD-3-Clause |
| [**pdfium / PdfiumAndroidKt**](https://github.com/johngray1965/PdfiumAndroidKt) | PDF 渲染（替换 Android 内置 `PdfRenderer`），支持随机读回调 | Apache-2.0 |
| [**libarchive-android**](https://github.com/zhanghai/libarchive-android) | RAR / 7z / TAR 解压 | Apache-2.0 |
| [**Coil**](https://github.com/coil-kt/coil) | 图片加载与封面懒加载 | Apache-2.0 |
| [**OkHttp**](https://square.github.io/okhttp/) | 全部网络请求（WebDAV、网盘、直链 Range） | Apache-2.0 |
| [**Voyager**](https://github.com/adrielcafe/voyager) | 导航 | MIT |
| [**SQLDelight**](https://github.com/sqldelight/sqldelight/) | 数据库 | Apache-2.0 |
| [**moko-resources**](https://github.com/icerockdev/moko-resources) | 多语言资源 | Apache-2.0 |
| [**jsoup**](https://jsoup.org/) | HTML 解析 | MIT |
| [**Injekt**](https://github.com/kohesive/injekt) | 依赖注入 | MIT |

### 四、许可证

- 本仓库代码以 **Apache-2.0** 发布（与上游 Mihon 一致），见 [LICENSE](LICENSE)。
- 内置模型 `app/src/main/assets/anime4k-acnet-models/` 附有 [NOTICE.txt](app/src/main/assets/anime4k-acnet-models/NOTICE.txt)，记录来源与转换方式；算法版权归 bloc97（MIT）。
- ncnn 为 BSD-3-Clause，其分发内含若干第三方组件（zlib / BSD-2 / BSD-3 等），详见其 `LICENSE.txt`。
- 各依赖的完整许可证以各自仓库为准；本表仅作索引。
- 本项目不附带任何漫画内容，也不提供内容来源；请自行确保你阅读的作品有合法授权。

---

<div align="center">

**English**: An unofficial Mihon 0.20.4 fork that turns your personal manga library into a built-in source.
Browse and read from **115 Cloud / Quark / Aliyun Drive / WebDAV / local folders** directly, with a
folder-browser view, streaming ZIP/PDF/MOBI reading (no full download), lazy covers, and offline
Anime4K ACNet 2× super-resolution. Entry point: the **folder** and **storage** icons in the Library
top bar.

Built on [Mihon](https://github.com/mihonapp/mihon) (Apache-2.0) and [Tachiyomi](https://github.com/tachiyomiorg/tachiyomi);
super-resolution powered by [Anime4K](https://github.com/bloc97/Anime4K) (MIT) via [ncnn](https://github.com/Tencent/ncnn) (BSD-3).
Full credits in [鸣谢上游](#鸣谢上游).

**Bug reports**: QQ group **1131187159** (Chinese).

</div>

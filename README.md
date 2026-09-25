<div align="center">

# Mihon · WebDAV 云盘源 + 超分版

### 基于 Mihon 0.20.4 的派生版，内置 WebDAV 云盘阅读源与离线 Anime4K ACNet 超分辨率

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](/LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208%2B-green.svg)](#)
[中文使用说明](docs/使用说明.md)

</div>

---

## English

This is a **fork of [Mihon](https://github.com/mihonapp/mihon)** (the Android manga reader,
formerly Tachiyomi), based on the official `0.20.4` release. It adds two self-contained features
and otherwise tracks the upstream UI/behavior:

1. **Built-in WebDAV cloud source ("WebDAV 云盘")** — browse and read manga that lives on your own
   WebDAV server (Nextcloud, NAS, self-hosted, etc.) directly inside Mihon, without any extension.
   The remote layout is `destination/{manga folder}/{chapter}.cbz`, with `cover.jpg` sitting next
   to the chapters as the series cover. Covers are lazily fetched only when scrolled into view.
2. **Offline Anime4K ACNet super-resolution** — a bundled ncnn model sharpens reader pages on-device
   via the GPU. No network, no uploads. Toggle it per-reader and optionally pre-enhance a whole
   chapter.

> ⚠️ This is an **unofficial** fork, not affiliated with the Mihon project. It is signed with the
> default debug keystore, so you must **uninstall the official Mihon** before installing this build
> (signature mismatch → `INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

### Build from source

```bash
# Requirements: JDK 21, Android SDK (platform + build-tools), NDK for the native ncnn engine.
./gradlew assembleRelease
# or use the provided wrapper script (configures JDK/GRADLE_USER_HOME, arm64-v8a only):
bash build_release.sh
```

The release APK lands at `app/build/outputs/apk/release/app-arm64-v8a-release.apk`.

### Configure

See **[docs/使用说明.md](docs/使用说明.md)** for the full setup walkthrough (WebDAV config,
super-resolution settings, FAQ). In short:

- **WebDAV**: *More → Settings → Downloads → Cloud sync* — turn it on, fill URL / user / password,
  test the connection, then pick the target directory. The "WebDAV 云盘" source then appears in
  the **Browse** tab.
- **Super-resolution**: open any chapter → reader gear → *Color/Filter* page → enable
  **画面增强 (Image enhancement)**; optionally enable **整话超分 (Whole-chapter enhance)**.

---

## 中文

本仓库是 **[Mihon](https://github.com/mihonapp/mihon)**（Android 漫画阅读器，原 Tachiyomi）的派生版，
基于官方 `0.20.4` 版本，在保留上游全部功能与界面的基础上，额外内置了两项**自包含**能力：

1. **内置 WebDAV 云盘阅读源（"WebDAV 云盘"）** —— 直接在 Mihon 内浏览、阅读存放在你自己的 WebDAV
   服务器（Nextcloud、群晖/威联通 NAS、自建等）上的漫画，**无需安装任何扩展**。
   远端目录结构为 `目标目录/{漫画文件夹}/{章节}.cbz`，漫画封面为与章节并列的 `cover.jpg`。
   封面采用懒加载（滑到可见才下载），列表不转圈。
2. **离线 Anime4K ACNet 超分辨率** —— 内置 ncnn 模型，在**设备本地**用 GPU 把阅读页锐化放大，
   不上网、不上传任何图片。可在阅读器内按章开关，并支持「整话超分」预增强并缓存。

> ⚠️ 这是**非官方**派生版，与 Mihon 官方无关。安装包使用 AGP 默认的 **debug keystore** 签名，
> 因此安装前请先**卸载官方 Mihon**（签名不一致会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
> 与本项目此前构建的同签名包可原地覆盖升级。

### 从源码构建

```bash
# 依赖：JDK 21、Android SDK（platform + build-tools）、用于编译原生 ncnn 引擎的 NDK。
./gradlew assembleRelease
# 或使用自带脚本（已配好 JDK / GRADLE_USER_HOME，仅打 arm64-v8a）：
bash build_release.sh
```

release 包输出在 `app/build/outputs/apk/release/app-arm64-v8a-release.apk`。

### 配置方法

完整图文步骤见 **[docs/使用说明.md](docs/使用说明.md)**（WebDAV 配置、超分设置、常见问题）。
简述：

- **WebDAV**：*更多 → 设置 → 下载 → 云同步* —— 开启总开关，填地址 / 账号 / 密码，点「测试连接」，
  再选择「云同步目录」。随后在**浏览**页会出现内置源「WebDAV 云盘」。
- **超分**：打开任意章节 → 阅读器齿轮 → *色彩/滤镜* 页 → 打开 **画面增强**；如需整章预超分再打开
  **整话超分**。

### 目录结构（本 fork 新增/改动要点）

| 路径 | 作用 |
|---|---|
| `app/src/main/java/eu/kanade/tachiyomi/source/webdav/` | WebDAV 云盘源（`WebDavSource` + `WebDavCoverProvider`） |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/CloudSyncService.kt` | WebDAV 客户端（PROPFIND / 下载 / 鉴权封面） |
| `app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/` | 超分引擎封装（ncnn Anime4K ACNet） |
| `app/src/main/cpp/waifu2x*.{cpp,h}`、`periodic_texture_guard.h` | 原生 ncnn 推理与纹理防护 |
| `source-local/.../LocalCoverProvider.kt` | 本地图源封面懒加载（与 WebDAV 同构的 content:// 方案） |
| `WEBDAV.md` | 开发者向：WebDAV 源架构说明 |

### 许可证与免责声明

- 代码以 **Apache-2.0** 许可证发布（与上游 Mihon 一致）。
- 本仓库是 Mihon 的**非官方**修改版，名称、图标与 Mihon 官方无隶属关系。
- 超分模型（Anime4K ACNet）以 `app/src/main/assets/anime4k-acnet-models/` 形式内置，
  其原始版权归 Anime4K 项目所有，遵循其对应许可证。
- `app/google-services.json` 为上游 Mihon 公开的 Firebase 客户端配置（公有 API key），
  仅用于构建，不含任何私密凭据。

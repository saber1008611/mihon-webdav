pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        // SY --> dev.zacsweers.*（Metro 的 gradle-plugin / compiler / graph-viewer-jvm 等）
        // 固定走 Maven Central。原因：这组构件在国内镜像上会「瞬时 404」，而 Gradle 一旦在某个仓库
        // 拿到模块元数据，就只在那个仓库找构件、不 fallback，找不到直接构建失败，还会把缺失状态
        // 写进 metadata 缓存（下次重试仍失败）。该组构件只有十来个、合计约 10MB，走权威源代价可控。
        maven(url = "https://repo1.maven.org/maven2") {
            content {
                includeGroupByRegex("dev\\.zacsweers(\\..*)*")
            }
        }
        // 国内镜像优先。实测：dl.google.com 直连约 130KB/s；腾讯云约 23MB/s。
        // 元数据与构件必须同仓库，故以腾讯云为主、阿里云兜底、官方源最后。
        maven(url = "https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        // SY <--
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("mihonx") {
            from(files("gradle/mihon.versions.toml"))
        }
    }

    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    @Suppress("UnstableApiUsage")
    repositories {
        // SY --> 同 pluginManagement：dev.zacsweers.* 全部走 Maven Central，避免镜像瞬时 404
        // 被记成「构件缺失」后反复失败。
        maven(url = "https://repo1.maven.org/maven2") {
            content {
                includeGroupByRegex("dev\\.zacsweers(\\..*)*")
            }
        }
        // SY --> jitpack 构件（unifile / PhotoView / quickjs / DirectionalViewPager /
        // subsampling-scale-image-view / java-nat-sort）在国内镜像上「有元数据无构件」，
        // 镜像一抢答 Gradle 就不再去 jitpack 找，直接报 Could not find xxx.jar。
        // 所以让 jitpack 先接管 com.github.*，并把它从两个镜像里排除掉。
        maven(url = "https://www.jitpack.io") {
            content {
                includeGroupByRegex("com\\.github(\\..*)*")
            }
        }
        // 腾讯云为主。腾讯云缺 ca.mpreg:imagedecoder 的 AAR（有元数据无构件），
        // 用 content filter 把 ca.mpreg 组定向到阿里云。
        // 同理腾讯云/阿里云均缺 io.legere:pdfiumandroid-core 的 AAR（新发布构件未同步），
        // 把 io.legere 排除出镜像、直连 mavenCentral。
        maven(url = "https://mirrors.cloud.tencent.com/nexus/repository/maven-public") {
            content {
                excludeGroup("ca.mpreg")
                excludeGroup("io.legere")
                excludeGroupByRegex("com\\.github(\\..*)*")
            }
        }
        maven(url = "https://maven.aliyun.com/repository/public") {
            content {
                excludeGroup("io.legere")
                excludeGroupByRegex("com\\.github(\\..*)*")
            }
        }
        // SY <--
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Mihon"
include(":app")
include(":baseline-profile")
include(":core-metadata")
include(":core:archive")
include(":core:common")
include(":core:metro")
include(":data")
include(":domain")
include(":i18n")
include(":icons:material-symbols")
include(":icons:simple-icons")
include(":presentation-core")
include(":presentation-widget")
include(":source-api")
include(":source-local")
include(":telemetry")

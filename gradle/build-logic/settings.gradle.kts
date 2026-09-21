dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        // SY --> dev.zacsweers.* 固定走 Maven Central（原因见根 settings.gradle.kts 的同类注释：
        // 该组构件在镜像上会瞬时 404，而 Gradle 不跨仓库 fallback 找构件）。
        maven(url = "https://repo1.maven.org/maven2") {
            content {
                includeGroupByRegex("dev\\.zacsweers(\\..*)*")
            }
        }
        // 国内镜像优先（build-logic 是 included build，仓库要单独声明一次）。
        // 腾讯云为主（稳定且快），阿里云兜底，官方源最后。
        maven(url = "https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        // SY <--
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../libs.versions.toml"))
        }
        create("mihonx") {
            from(files("../mihon.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

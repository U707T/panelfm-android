pluginManagement {
    repositories {
        // 本机网络下 dl.google.com 不可达 → 优先走镜像（阿里云 google / central）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "org.jetbrains.kotlin.android" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "panelfm"
include(
    ":app",
    ":core:common",
    ":core:model",
    ":core:vfs-api",
    ":core:vfs-local",
    ":core:vfs-webdav",
    ":core:vfs-ftp",
    ":core:vfs-sftp",
    ":core:transfer",
    ":core:data",
    ":core:ui",
)

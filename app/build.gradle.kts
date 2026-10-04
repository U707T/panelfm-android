import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 发布签名：优先读 android/key.properties（本地或 CI 注入）；缺失时回退 debug 签名，保证随时可构建。
val keystoreProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.u707t.panelfm"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.u707t.panelfm"
        minSdk = 26
        targetSdk = 37
        versionCode = 21
        versionName = "0.13.0-rc.9"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val stable = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        release {
            signingConfig = stable
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            signingConfig = stable
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 分 ABI 出包（CI 出 3 个 release APK，与 panelfm / mp4fix 一致）
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // sshd 等 Java 库带的重复元数据文件（APK 合并时会冲突）
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/INDEX.LIST",
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:vfs-api"))
    implementation(project(":core:vfs-local"))
    implementation(project(":core:vfs-webdav"))
    implementation(project(":core:vfs-ftp"))
    implementation(project(":core:vfs-sftp"))
    implementation(project(":core:vfs-smb"))
    implementation(project(":core:vfs-s3"))
    implementation(project(":core:vfs-archive"))
    implementation(project(":core:transfer"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.commons.net)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

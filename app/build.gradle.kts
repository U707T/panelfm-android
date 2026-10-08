import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 发布签名：优先读 android/key.properties（本地或 CI 注入）；本地可回退 debug，CI 缺 key 时硬失败，禁止发布 debug 签名 APK。
val keystoreProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val configuredStoreFile = keystoreProps.getProperty("storeFile")
val releaseStoreFile = configuredStoreFile?.let { path ->
    val file = File(path)
    (if (file.isAbsolute) file else File(projectDir, path)).canonicalFile
}
val hasReleaseKey = releaseStoreFile?.isFile == true
val isCi = System.getenv("CI")?.equals("true", ignoreCase = true) == true

// CI 缺 keystore 时必须硬失败（禁止发布 debug 签名的 release APK），
// 但**只能在真的要出 release 时才失败**。
//
// 旧实现把校验写在 `buildTypes { release { ... } }` 里 = 配置期无条件执行：
// CI 的 `testDebugUnitTest` job 并不解码 keystore（那是 android job 的事），
// 于是「跑单元测试」也会直接抛 GradleException —— 整个 CI 因为一个跟测试无关的
// 签名问题全红。改为在任务图解析完成后、且图里确实有 release 任务时才失败。
gradle.taskGraph.whenReady {
    if (isCi && !hasReleaseKey && allTasks.any { it.name.contains("Release", ignoreCase = true) }) {
        throw GradleException(
            "CI release build requires key.properties and an existing release keystore",
        )
    }
}

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
        versionCode = 80
        versionName = "2.0.10"
    }

    // 单元测试里 call android.util.Log / org.json 桩不抛「not mocked」；
    // org.json 另用真实实现覆盖（见 dependencies 的 libs.json）——回收站索引测试需要。
    testOptions { unitTests { isReturnDefaultValues = true } }

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
            // 缺 keystore 的 CI 硬失败已上移到 taskGraph.whenReady（见文件头注释）
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

    // 文本编辑器引擎（LGPL-2.1，替代自研 BasicTextField 编辑器）
    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)

    // SVG / SVGZ 渲染（Apache-2.0；BitmapFactory 不支持 SVG）
    implementation(libs.androidsvg)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

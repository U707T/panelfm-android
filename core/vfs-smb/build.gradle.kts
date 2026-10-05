import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.u707t.panelfm.core.vfs.smb"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    testOptions { unitTests { isReturnDefaultValues = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(project(":core:vfs-api"))
    implementation(libs.kotlinx.coroutines.android)
    // SMB2/3：SMBJ（DFS 关闭 → 不触发 JNA 的本地库加载）
    implementation(libs.smbj)
    // SMBJ 0.13.0 拉入旧版 Bouncy Castle；显式约束到已修复安全版本。
    implementation(libs.bouncycastle)
    implementation(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

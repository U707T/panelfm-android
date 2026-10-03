import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.u707t.panelfm.core.vfs.sftp"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    testOptions { unitTests { isReturnDefaultValues = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(project(":core:vfs-api"))
    implementation(libs.kotlinx.coroutines.android)
    // SFTP：Apache MINA SSHD（纯 Java，内置 chacha20-poly1305 / curve25519 / ed25519，无需 BouncyCastle）
    implementation(libs.sshd.core)
    implementation(libs.sshd.sftp)
    implementation(libs.slf4j.nop)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // 测试里起一个真的 SFTP 服务端（端到端验证）
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.slf4j.nop)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.u707t.panelfm.core.apk"
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
    // APK 签名（v2/v3 + 对齐）。纯 Java、零传递依赖，Android 上可用（只用 java.base）。
    implementation(libs.apksig)
    // 内置测试证书：用 Bouncy Castle 的 ASN.1 直出 X.509（bcprov 已在 vfs-smb 里，不引入 bcpkix）。
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.u707t.panelfm.core.vfs.archive"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    testOptions { unitTests { isReturnDefaultValues = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(project(":core:vfs-api"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.xz)          // 7z / xz 支持
    implementation(libs.junrar)      // RAR 解压（只读：RAR4 / RAR5 / RAR7、口令、分卷）
    // junrar 经 slf4j 打日志：与 sftp/smb 模块同款，挂 no-op provider（不注入日志实现）
    implementation(libs.slf4j.nop)
    // commons-compress 传递引入 commons-lang3 3.16.0（< 3.18.0 有非受控递归告警）；
    // 显式抬到修复版本，避免依赖树里被传递版本钉死。
    implementation(libs.commons.lang3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

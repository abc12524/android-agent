plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.androidagent"
    compileSdk = 37

    signingConfigs {
        create("release") {
            storeFile = file("keystore.jks")
            storePassword = "android123"
            keyAlias = "androidagent"
            keyPassword = "android123"
        }
    }

    defaultConfig {
        applicationId = "com.androidagent"
        minSdk = 26
        targetSdk = 37
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // minSdk >= 28 时 AGP 默认把 dex 以「未压缩」形式打进 APK（装到手机更小，但 APK 文件更大）。
    // 这里是直接分发 APK，故强制压缩 dex，把包体降下来。
    packaging {
        dex {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Compose BOM (Compose 1.12.1 / Material3 1.4.0，需 compileSdk 37)
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 图标库已冻结在 1.7.8（不再随 BOM 更新），显式指定版本
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")

    // Core
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

    // Room (SQLite)
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // SSH (JSch fork — 支持 OpenSSH 密钥格式和 ED25519)
    implementation("com.github.mwiede:jsch:2.28.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Markdown (原生 Compose 渲染：GFM 表格 / 代码高亮 / 链接 / 图片)
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.45.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0")
}

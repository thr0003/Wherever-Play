plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// WP: 使用 AGP 9 的 compileSdk 块式 DSL（与本机 demo 工程保持一致）
android {
    namespace = "com.whereverplay.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.whereverplay.app"
        minSdk = 24
        targetSdk = 37
        // WP: versionCode 必须严格单调递增（覆盖安装靠它判断升级），versionName 给人看。
        //     首个公开版本定为 0.1.0，对应 versionCode 100；之后按 0.x 递增（0.1.1 -> 101）。
        versionCode = 100
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        // 设置页要显示版本号，从 BuildConfig 读，避免再出现"代码里写死一个版本"的情况
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
}

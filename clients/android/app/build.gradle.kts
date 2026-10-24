plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.clipbridge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clipbridge.app"
        // 24 = Android 7.0，WebSocket + TLS 的最低舒适区
        minSdk = 24
        // targetSdk 保持 34，规避 Android 15 对前台服务(dataSync)的 6 小时时长上限
        targetSdk = 34
        // versionCode 必须单调递增，否则覆盖安装会被系统拒绝。
        // 编码规则：主版本*10000 + 次版本*100 + 修订号（1.0.0 → 10000）。
        // 从 0.9.9(909) 跳到 1.0.0 用 10000 而不是 1000 —— 后者会让 1.0.0 与
        // 将来的 0.10.0 在同一区间，留出进位空间才不会撞车。
        versionCode = 10000
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ---- AndroidX 基础 ----
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // ---- Compose UI ----
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")

    // ---- 网络（JSON 用 Android 内置 org.json，不引入额外序列化库）----
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

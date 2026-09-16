plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.genui.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.genui.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 56
        versionName = "0.16.1"
    }

    // 项目固定签名：signing/genui.keystore（口令直接写在本文件，debug 级可接受）。
    // 为什么固定：debug 密钥跟随构建环境走，换机器/换沙箱必冲突（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
    // 项目内固定一把钥匙后，任何环境构建的包都能覆盖安装。
    signingConfigs {
        create("genui") {
            storeFile = rootProject.file("signing/genui.keystore")
            storePassword = "genui2026"
            keyAlias = "genui"
            keyPassword = "genui2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("genui")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("genui")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 启动自检用 BuildConfig.DEBUG 决定是否提示资源缺失
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// 调试包不需要剥离 native 符号。沙箱环境无 NDK，Gradle 9.3 下 stripDebugDebugSymbols
// 会因无法对 .so 计算 MD5 而直接失败；关闭该任务即可（仅保留未剥离的 .so，不影响功能）。
tasks.configureEach {
    if (name == "stripDebugDebugSymbols") {
        enabled = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // vendored A2UI-Android 使用实验性 Material3 API（SearchBar 等）
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material.ExperimentalMaterialApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // WebView 增强库：WebMessageListener（AI 的 UI ↔ 原生功能桥）
    implementation("androidx.webkit:webkit:1.15.0")

    // 网络与流式（SSE）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    // ---- GenCanvas 原生绘制通道依赖 ----
    // JSON 绘制指令的解析（core-model / Sanitizer 依赖）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Flexbox 布局引擎（C 实现，AI 写 flex 的准确率远高于自造布局 DSL）
    implementation("com.facebook.yoga:yoga:2.0.1")
    // SVG 渲染（AI 画不出的复杂图形一律退化成 svg op，由它兜底）
    implementation("com.caverock:androidsvg:1.4")
    // 图片加载（image op 的 url / 网络图）
    implementation("io.coil-kt:coil-compose:2.6.0")
}

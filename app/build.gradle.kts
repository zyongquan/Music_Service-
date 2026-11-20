// 实验七「基于 Service 的音乐播放应用」应用模块
//
// 实验要求对应的实现位置：
//   1. ContentResolver 读取音乐列表 ......... SongRepository
//   2. Service 内完成全部播放控制 ........... MusicPlayerService（MediaPlayer + Binder）
//      Activity 通过 Binder 获取歌曲名/进度 .. MainActivity 的 ServiceConnection
//   3. SeekBar 实时进度（多线程）+ 拖动定位 .. MusicPlayerService 的进度线程 → 回调 UI
//   4. 换歌时 Notification 显示歌曲名 ....... MusicPlayerService.updateNotification + MediaStyle
//
// AGP 9 已内置 Kotlin 支持（built-in Kotlin），不需要额外应用 org.jetbrains.kotlin.android。
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cn.edu.sicnu.musicplayer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cn.edu.sicnu.musicplayer"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    // 打开视图绑定：布局里的 id 会自动生成类型安全的 binding 类
    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.recyclerview)
    // MediaSessionCompat + MediaStyle 通知
    implementation(libs.androidx.media)

    // 本地单元测试（JVM）：测试 Song 的格式化与文件名解析等纯逻辑
    testImplementation(libs.junit)

    // 仪器化 UI 测试：验证 Service 绑定、播放控制与通知
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}

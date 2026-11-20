// 实验七「基于 Service 的音乐播放应用」顶层构建脚本
// AGP 9 内置 Kotlin 支持，Kotlin 编译由 com.android.application 插件自动完成，
// 因此这里不需要再显式应用 org.jetbrains.kotlin.android 插件。
plugins {
    alias(libs.plugins.android.application) apply false
}

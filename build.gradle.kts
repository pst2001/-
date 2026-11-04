// 顶层构建脚本：只声明插件版本，不在根工程应用
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

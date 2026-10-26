// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/*.kt", "**/*.kts")
        // third_party/ 是搬入的第三方／實驗素材（t9-ime 搬入的 fcitx5-t9、trime-9key-config 等），
        // 保留原貌、不受本倉格式規則管。
        targetExclude("third_party/**")
        // Compose @Composable 函數按慣例大寫開頭（PascalCase），ktlint 的
        // function-naming 不認 Compose 註解——經 editorConfig 覆蓋關閉此條。
        ktlint("1.8.0").editorConfigOverride(
            mapOf("ktlint_standard_function-naming" to "disabled"),
        )
    }
}

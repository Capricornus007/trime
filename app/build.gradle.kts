/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:Suppress("UnstableApiUsage")

plugins {
    id("com.osfans.trime.app-convention")
    id("com.osfans.trime.native-app-convention")
    id("com.osfans.trime.data-checksums")
    id("com.osfans.trime.native-cache-hash")
    id("com.osfans.trime.opencc-data")
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    // Compose 編譯器外掛（九鍵鍵盤層用；AGP 9 內建 Kotlin 支援，不能再套 kotlin.android）
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.osfans.trime"
    compileSdk = 37
    // Compose ui 1.13.0-beta01 的 AAR metadata 要求 compileSdk ≥ 37.1；AGP 9 用兩段式表示。
    compileSdkMinor = 2
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // 使用者一貫規則（三次前例：MapAdKiller「包名加爲 io.github.我的githubid」09-19、
        // metube「把包名改成我的」10-12、u1s1「改成 u1s1-cn-bin」10-21）＋ 本次明令
        // 「我說 org.capricornus007.trime」：名字就叫 Trime，但身份走自己的帳號命名空間，
        // 不佔上游的 com.osfans.trime（否則手機上兩支同包名、憑證不同，install -r 直接
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE，而且上游一更新就把我們這支蓋掉）。
        applicationId = "org.capricornus007.trime"
        // 2026-10-27 抬到 24：Compose 九鍵鍵盤（t9-ime 搬入）所依賴的 material3
        // 1.5.0-beta01 系列宣告 minSdk 24，manifest merger 不放行更低值。
        // t9-ime 原版就是 26；fork 收在 24 保住 Android 7.0+。
        minSdk = 24
        targetSdk = 37
        versionCode = 20261101
        versionName = "3.3.13"

        multiDexEnabled = true
        buildConfigField("String", "BUILDER", "\"${project.builder}\"")
        buildConfigField("long", "BUILD_TIMESTAMP", project.buildTimestamp)
        buildConfigField("String", "BUILD_COMMIT_HASH", "\"${project.buildCommitHash}\"")
        buildConfigField("String", "BUILD_GIT_REPO", "\"${project.buildGitRepo}\"")
        buildConfigField("String", "BUILD_VERSION_NAME", "\"${project.buildVersionName}\"")
    }

    base {
        // https://www.norio.be/blog/archivesBaseName-removed-from-gradle9.html
        archivesName = "${android.defaultConfig.applicationId}-$buildVersionName"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        resValues = true
        // 九鍵鍵盤（com.osfans.trime.t9）是 Compose 層
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                project.signKeyFile?.let {
                    signingConfigs.create("release") {
                        storeFile = it
                        storePassword = project.signKeyStorePwd
                        keyAlias = project.signKeyAlias
                        keyPassword = project.signKeyPwd
                        // 全部三個簽名區塊都要開：v1 給舊系統（minSdk 21 這一臺实測就是 Android 9）、
                        // v2/v3 給新系統。AGP 的預設會隨 minSdk 跳過 v1，等於換臺機器裝不上。
                        // 這一路只看 DSL 不夠，收口在 release-ci 的「產物未簽名就紅」那步。
                        enableV1Signing = true
                        enableV2Signing = true
                        enableV3Signing = true
                    }
                }

            resValue("string", "trime_app_name", "@string/app_name_release")
        }
        debug {
            applicationIdSuffix = ".debug"

            resValue("string", "trime_app_name", "@string/app_name_debug")
        }
        all {
            // remove META-INF/version-control-info.textproto
            @Suppress("UnstableApiUsage")
            vcsInfo.include = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/*.version",
                    "/META-INF/*.kotlin_module", // cannot be excluded actually
                    "/META-INF/androidx/**",
                    "/DebugProbesKt.bin",
                    "/kotlin-tooling-metadata.json",
                )
        }
    }
}

aboutLibraries {
    collect {
        configPath.set(file("licenses").takeIf { it.exists() })
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
        includePlatform.set(false)
    }
    export {
        excludeFields.set(
            setOf("generated", "developers", "organization", "scm", "funding", "content"),
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    // Compose 九鍵鍵盤（t9-ime 搬入）：ui/foundation/material3 成套尖端版本，見 toml 註解。
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // IME 不是 Activity，T9Service 要自己當 lifecycle／savedstate 的 ViewTree 宿主。
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.flexbox)
    implementation(libs.bravh)
    implementation(libs.timber)
    implementation(libs.xxpermissions)
    implementation(libs.kodein.di)
    implementation(libs.kaml)
    implementation(libs.storageaccess)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.systemservices)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    implementation(libs.iconics.core)
    implementation(libs.community.material.typeface) {
        artifact { type = "aar" }
    }

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    }
}

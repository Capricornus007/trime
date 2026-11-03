// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

import com.osfans.trime.util.RimeStoragePermissionPolicy.SettingsDestination
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class RimeStoragePermissionPolicyTest :
    StringSpec({
        "Android 11 and newer require All files access" {
            RimeStoragePermissionPolicy.isGranted(
                sdkInt = 36,
                hasAllFilesAccess = false,
                hasLegacyWriteAccess = true,
            ) shouldBe false
            RimeStoragePermissionPolicy.isGranted(
                sdkInt = 36,
                hasAllFilesAccess = true,
                hasLegacyWriteAccess = false,
            ) shouldBe true
        }

        "Android 10 and older require legacy write access" {
            RimeStoragePermissionPolicy.isGranted(
                sdkInt = 29,
                hasAllFilesAccess = true,
                hasLegacyWriteAccess = false,
            ) shouldBe false
            RimeStoragePermissionPolicy.isGranted(
                sdkInt = 29,
                hasAllFilesAccess = false,
                hasLegacyWriteAccess = true,
            ) shouldBe true
        }

        "Android 11 settings fall back from app page to general page and app details" {
            RimeStoragePermissionPolicy.settingsDestinations(36) shouldContainExactly
                listOf(
                    SettingsDestination.AppAllFilesAccess,
                    SettingsDestination.AllFilesAccess,
                    SettingsDestination.AppDetails,
                )
        }

        "legacy Android opens app details" {
            RimeStoragePermissionPolicy.settingsDestinations(29) shouldContainExactly
                listOf(SettingsDestination.AppDetails)
        }

        "denied permission prevents startup work and a later grant can retry" {
            var starts = 0
            RimeStoragePermissionPolicy.runIfGranted(granted = false) { starts++ } shouldBe false
            starts shouldBe 0

            RimeStoragePermissionPolicy.runIfGranted(granted = true) { starts++ } shouldBe true
            starts shouldBe 1
        }
    })

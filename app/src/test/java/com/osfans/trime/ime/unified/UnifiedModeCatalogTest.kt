/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class UnifiedModeCatalogTest :
    StringSpec({
        "catalog contains exactly the six product modes" {
            UnifiedModeCatalog.modes shouldBe listOf(
                UnifiedInputMode.CHINESE_QWERTY,
                UnifiedInputMode.CHINESE_T9,
                UnifiedInputMode.CHINESE_HANDWRITING,
                UnifiedInputMode.JAPANESE_QWERTY,
                UnifiedInputMode.JAPANESE_HANDWRITING,
                UnifiedInputMode.ENGLISH_QWERTY,
            )
        }

        "persisted values round trip and invalid values recover" {
            UnifiedInputMode.entries.forEach { UnifiedInputMode.fromPersisted(it.name) shouldBe it }
            ChineseScript.entries.forEach { ChineseScript.fromPersisted(it.name) shouldBe it }
            UnifiedInputMode.fromPersisted("GODAN") shouldBe UnifiedInputMode.CHINESE_QWERTY
            ChineseScript.fromPersisted(null) shouldBe ChineseScript.SIMPLIFIED
        }

        "script choice maps to matching Rime options and Chinese models" {
            val simplified = UnifiedModeCatalog.decision(UnifiedInputMode.CHINESE_HANDWRITING, ChineseScript.SIMPLIFIED)
            val traditional = UnifiedModeCatalog.decision(UnifiedInputMode.CHINESE_HANDWRITING, ChineseScript.TRADITIONAL)
            simplified.modelLanguageTag shouldBe "zh-Hani-CN"
            traditional.modelLanguageTag shouldBe "zh-Hani-TW"
            simplified.runtimeOptions["zh_hans"] shouldBe true
            simplified.runtimeOptions["zh_hant"] shouldBe false
            traditional.runtimeOptions["zh_hans"] shouldBe false
            traditional.runtimeOptions["zh_hant"] shouldBe true
            UnifiedModeCatalog.decision(UnifiedInputMode.CHINESE_T9, ChineseScript.TRADITIONAL)
                .runtimeOptions shouldBe mapOf("simplification" to false)
        }

        "selection persists through recreation" {
            val store = object : UnifiedModeStore {
                override var mode = UnifiedInputMode.CHINESE_QWERTY
                override var script = ChineseScript.SIMPLIFIED
            }
            UnifiedSelection(store).apply {
                select(UnifiedInputMode.JAPANESE_HANDWRITING)
                toggleScript()
            }
            UnifiedSelection(store).run {
                mode shouldBe UnifiedInputMode.JAPANESE_HANDWRITING
                script shouldBe ChineseScript.TRADITIONAL
            }
        }

        "English remains QWERTY-only and forces ASCII" {
            val english = UnifiedModeCatalog.decision(UnifiedInputMode.ENGLISH_QWERTY, ChineseScript.SIMPLIFIED)
            english.asciiMode shouldBe true
            english.modelLanguageTag.shouldBeNull()
        }
    })

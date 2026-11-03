/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedModeCatalogTest {
    @Test
    fun catalogContainsExactlyTheSixProductModes() {
        assertEquals(
            listOf(
                UnifiedInputMode.CHINESE_QWERTY,
                UnifiedInputMode.CHINESE_T9,
                UnifiedInputMode.CHINESE_HANDWRITING,
                UnifiedInputMode.JAPANESE_QWERTY,
                UnifiedInputMode.JAPANESE_HANDWRITING,
                UnifiedInputMode.ENGLISH_QWERTY,
            ),
            UnifiedModeCatalog.modes,
        )
    }

    @Test
    fun persistedValuesRoundTripAndInvalidValuesRecover() {
        UnifiedInputMode.entries.forEach { mode ->
            assertEquals(mode, UnifiedInputMode.fromPersisted(mode.name))
        }
        ChineseScript.entries.forEach { script ->
            assertEquals(script, ChineseScript.fromPersisted(script.name))
        }
        assertEquals(UnifiedInputMode.CHINESE_QWERTY, UnifiedInputMode.fromPersisted("GODAN"))
        assertEquals(ChineseScript.SIMPLIFIED, ChineseScript.fromPersisted(null))
    }

    @Test
    fun simplifiedAndTraditionalChooseMatchingRimeOptionsAndModels() {
        val simplified = UnifiedModeCatalog.decision(
            UnifiedInputMode.CHINESE_HANDWRITING,
            ChineseScript.SIMPLIFIED,
        )
        val traditional = UnifiedModeCatalog.decision(
            UnifiedInputMode.CHINESE_HANDWRITING,
            ChineseScript.TRADITIONAL,
        )
        assertEquals("zh-Hani-CN", simplified.modelLanguageTag)
        assertEquals("zh-Hani-TW", traditional.modelLanguageTag)
        assertEquals(true, simplified.runtimeOptions["zh_hans"])
        assertEquals(false, simplified.runtimeOptions["zh_hant"])
        assertEquals(false, traditional.runtimeOptions["zh_hans"])
        assertEquals(true, traditional.runtimeOptions["zh_hant"])

        val t9Traditional = UnifiedModeCatalog.decision(UnifiedInputMode.CHINESE_T9, ChineseScript.TRADITIONAL)
        assertEquals(mapOf("simplification" to false), t9Traditional.runtimeOptions)
    }

    @Test
    fun selectionPersistsAcrossControllerRecreation() {
        val store = object : UnifiedModeStore {
            override var mode = UnifiedInputMode.CHINESE_QWERTY
            override var script = ChineseScript.SIMPLIFIED
        }
        UnifiedSelection(store).apply {
            select(UnifiedInputMode.JAPANESE_HANDWRITING)
            toggleScript()
        }
        val recreated = UnifiedSelection(store)
        assertEquals(UnifiedInputMode.JAPANESE_HANDWRITING, recreated.mode)
        assertEquals(ChineseScript.TRADITIONAL, recreated.script)
    }

    @Test
    fun nonChineseModesDoNotMutateChineseOptions() {
        val japanese = UnifiedModeCatalog.decision(UnifiedInputMode.JAPANESE_QWERTY, ChineseScript.SIMPLIFIED)
        val english = UnifiedModeCatalog.decision(UnifiedInputMode.ENGLISH_QWERTY, ChineseScript.SIMPLIFIED)
        assertTrue(japanese.runtimeOptions.isEmpty())
        assertFalse(japanese.asciiMode)
        assertTrue(english.asciiMode)
        assertNull(english.modelLanguageTag)
    }
}

/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import androidx.annotation.StringRes
import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateEnum

enum class UnifiedInputMode(
    @StringRes override val stringRes: Int,
    val schemaId: String,
    val handwritingLanguageTag: String? = null,
    val forceAscii: Boolean = false,
) : PreferenceDelegateEnum {
    CHINESE_QWERTY(R.string.mode_chinese_qwerty, "luna_pinyin"),
    CHINESE_T9(R.string.mode_chinese_t9, "t9_pinyin"),
    CHINESE_HANDWRITING(R.string.mode_chinese_handwriting, "luna_pinyin", "zh-Hani-CN"),
    JAPANESE_QWERTY(R.string.mode_japanese_qwerty, "jaroomaji"),
    JAPANESE_HANDWRITING(R.string.mode_japanese_handwriting, "jaroomaji", "ja"),
    ENGLISH_QWERTY(R.string.mode_english_qwerty, "luna_pinyin", forceAscii = true),
    ;

    val isChinese: Boolean
        get() = this == CHINESE_QWERTY || this == CHINESE_T9 || this == CHINESE_HANDWRITING

    val isHandwriting: Boolean
        get() = handwritingLanguageTag != null

    fun modelTag(script: ChineseScript): String? = when (this) {
        CHINESE_HANDWRITING -> script.modelLanguageTag
        JAPANESE_HANDWRITING -> "ja"
        else -> handwritingLanguageTag
    }

    companion object {
        fun fromPersisted(value: String?): UnifiedInputMode = entries.firstOrNull { it.name == value } ?: CHINESE_QWERTY
    }
}

enum class ChineseScript(
    @StringRes override val stringRes: Int,
    val simplified: Boolean,
    val modelLanguageTag: String,
) : PreferenceDelegateEnum {
    SIMPLIFIED(R.string.chinese_script_simplified, true, "zh-Hani-CN"),
    TRADITIONAL(R.string.chinese_script_traditional, false, "zh-Hani-TW"),
    ;

    fun toggled(): ChineseScript = if (this == SIMPLIFIED) TRADITIONAL else SIMPLIFIED

    companion object {
        fun fromPersisted(value: String?): ChineseScript = entries.firstOrNull { it.name == value } ?: SIMPLIFIED
    }
}

data class ModeDecision(
    val schemaId: String,
    val asciiMode: Boolean,
    val runtimeOptions: Map<String, Boolean>,
    val modelLanguageTag: String?,
)

object UnifiedModeCatalog {
    val modes: List<UnifiedInputMode> = UnifiedInputMode.entries

    fun decision(mode: UnifiedInputMode, script: ChineseScript): ModeDecision = ModeDecision(
        schemaId = mode.schemaId,
        asciiMode = mode.forceAscii,
        runtimeOptions = when {
            mode == UnifiedInputMode.CHINESE_T9 -> mapOf("simplification" to script.simplified)

            mode.isChinese -> mapOf(
                "zh_hans" to script.simplified,
                "zh_hant" to !script.simplified,
                "zh_hant_hk" to false,
                "zh_hant_tw" to false,
            )

            else -> emptyMap()
        },
        modelLanguageTag = mode.modelTag(script),
    )
}

interface UnifiedModeStore {
    var mode: UnifiedInputMode
    var script: ChineseScript
}

class UnifiedSelection(private val store: UnifiedModeStore) {
    val mode: UnifiedInputMode get() = store.mode
    val script: ChineseScript get() = store.script

    fun select(mode: UnifiedInputMode) {
        store.mode = mode
    }

    fun toggleScript() {
        store.script = store.script.toggled()
    }
}

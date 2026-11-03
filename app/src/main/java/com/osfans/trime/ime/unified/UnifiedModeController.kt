/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import android.os.Build
import android.os.UserManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.T9CorrectionState

class PreferenceUnifiedModeStore(
    private val prefs: AppPrefs.Keyboard = AppPrefs.defaultInstance().keyboard,
) : UnifiedModeStore {
    override var mode: UnifiedInputMode
        get() = prefs.unifiedInputMode.getValue()
        set(value) = prefs.unifiedInputMode.setValue(value)

    override var script: ChineseScript
        get() = prefs.chineseScript.getValue()
        set(value) = prefs.chineseScript.setValue(value)
}

/** Applies persisted product mode and the current editor's safety policy to the main Rime session. */
class UnifiedModeController(
    private val service: TrimeInputMethodService,
    val store: UnifiedModeStore = PreferenceUnifiedModeStore(),
) {
    private val selection = UnifiedSelection(store)

    @Volatile
    var editorKeyboardTarget: String? = null
        private set

    val mode: UnifiedInputMode get() = selection.mode
    val script: ChineseScript get() = selection.script
    val effectiveAsciiMode: Boolean
        get() = editorKeyboardTarget != null || UnifiedModeCatalog.decision(mode, script).asciiMode
    val shouldShowHandwriting: Boolean
        get() = mode.isHandwriting && editorKeyboardTarget == null && isUserUnlocked()

    fun onStartInput(info: EditorInfo) {
        T9CorrectionState.reset()
        editorKeyboardTarget = editorTarget(info)
        applyCurrent(commitPending = false)
    }

    fun selectMode(mode: UnifiedInputMode, onApplied: () -> Unit = {}) {
        selection.select(mode)
        T9CorrectionState.cancelForModeSwitch(mode.schemaId)
        applyCurrent(commitPending = true, onApplied)
    }

    fun toggleScript(onApplied: () -> Unit = {}) {
        T9CorrectionState.cancelForModeSwitch(mode.schemaId)
        selection.toggleScript()
        applyCurrent(commitPending = true, onApplied)
    }

    /** Product-owned QWERTY/T9 layouts must not override the effective language's ASCII state. */
    fun asciiOverrideForKeyboard(keyboardId: String): Boolean? = when {
        keyboardId == "t9_pinyin" -> effectiveAsciiMode
        keyboardId == "default" -> effectiveAsciiMode
        keyboardId.startsWith("qwerty") -> effectiveAsciiMode
        else -> null
    }

    private fun applyCurrent(commitPending: Boolean, onApplied: () -> Unit = {}) {
        val decision = UnifiedModeCatalog.decision(selection.mode, selection.script)
        service.postRimeJob {
            if (commitPending && statusCached.isComposing) commitComposition()
            if (statusCached.schemaId != decision.schemaId) selectSchema(decision.schemaId)
            decision.runtimeOptions.forEach { (option, enabled) -> setRuntimeOption(option, enabled) }
            setRuntimeOption("ascii_mode", editorKeyboardTarget != null || decision.asciiMode)
        }.invokeOnCompletion {
            ContextCompat.getMainExecutor(service).execute(onApplied)
        }
    }

    private fun editorTarget(info: EditorInfo): String? {
        if (info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII != 0) return ".ascii"
        return when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
            -> "number"
            InputType.TYPE_CLASS_TEXT -> when (info.inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> ".ascii"
                else -> null
            }
            else -> null
        }
    }

    private fun isUserUnlocked(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            service.getSystemService(UserManager::class.java).isUserUnlocked
}

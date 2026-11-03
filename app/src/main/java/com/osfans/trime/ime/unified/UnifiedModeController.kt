/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import com.osfans.trime.data.prefs.AppPrefs
import androidx.core.content.ContextCompat
import com.osfans.trime.ime.core.TrimeInputMethodService

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

/** Applies every mode through the one Rime session owned by the main Trime service. */
class UnifiedModeController(
    private val service: TrimeInputMethodService,
    val store: UnifiedModeStore = PreferenceUnifiedModeStore(),
) {
    private val selection = UnifiedSelection(store)
    val mode: UnifiedInputMode get() = selection.mode
    val script: ChineseScript get() = selection.script

    fun selectMode(mode: UnifiedInputMode, onApplied: () -> Unit = {}) {
        selection.select(mode)
        applyCurrent(commitPending = true, onApplied)
    }

    fun toggleScript(onApplied: () -> Unit = {}) {
        selection.toggleScript()
        applyCurrent(commitPending = true, onApplied)
    }

    /**
     * A mode change commits the current composition before changing schema, so a user never
     * loses text or carries a composition into a schema whose alphabet cannot represent it.
     */
    private fun applyCurrent(commitPending: Boolean, onApplied: () -> Unit = {}) {
        val decision = UnifiedModeCatalog.decision(selection.mode, selection.script)
        service.postRimeJob {
            if (commitPending && statusCached.isComposing) commitComposition()
            if (statusCached.schemaId != decision.schemaId) selectSchema(decision.schemaId)
            decision.runtimeOptions.forEach { (option, enabled) -> setRuntimeOption(option, enabled) }
            setRuntimeOption("ascii_mode", decision.asciiMode)
        }.invokeOnCompletion {
            ContextCompat.getMainExecutor(service).execute(onApplied)
        }
    }

    /** Reassert persisted state after process/service reconstruction. */
    fun restore(onApplied: () -> Unit = {}) = applyCurrent(commitPending = false, onApplied)
}

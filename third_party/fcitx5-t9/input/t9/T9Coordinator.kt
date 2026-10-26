/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.t9

class T9Coordinator(
    private val resolver: T9PinyinResolver = T9PinyinResolver(),
) {

    var state: T9UiState = T9UiState.Empty
        private set

    fun sync(rawInput: String, caret: Int, preedit: String): T9UiState {
        state = T9SessionReducer.syncFromEngine(
            state = state,
            rawInput = rawInput,
            caret = caret,
            preedit = preedit,
            resolver = resolver,
        )
        return state
    }

    fun selectSideRail(entry: T9SideRailEntry): T9SessionCommand? {
        val mutation = T9SessionReducer.applySideRailEntry(
            state = state,
            entry = entry,
            resolver = resolver,
        )
        state = mutation.state
        return mutation.command
    }

    fun rememberRestorePoint() {
        state = T9SessionReducer.pushRestorePoint(state)
    }

    fun canRestore(): Boolean = state.interventionCheckpoint != null

    fun canRetype(): Boolean = state.rawInput.isNotBlank() || state.preedit.isNotBlank()

    fun onBackspace(): T9SessionCommand {
        val mutation = T9SessionReducer.onBackspace(
            state = state,
            resolver = resolver,
        )
        state = mutation.state
        return mutation.command ?: T9SessionCommand.SendBackspace
    }

    fun onRetype(): T9SessionCommand? {
        if (!canRetype()) return null
        state = T9UiState.Empty
        return T9SessionCommand.ResetSpellingArea
    }

    fun reset(): T9UiState {
        state = T9UiState.Empty
        return state
    }
}

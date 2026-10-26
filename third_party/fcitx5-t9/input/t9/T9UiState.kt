/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.t9

data class T9ReplacementWindow(
    val start: Int,
    val end: Int,
) {
    init {
        require(start >= 0) { "start must be non-negative" }
        require(end >= start) { "end must be >= start" }
    }

    val length: Int
        get() = end - start

    fun substringOf(text: String): String = text.substring(start, end)
}

data class T9SideRailEntry(
    val text: String,
    val replacementWindow: T9ReplacementWindow,
    val selected: Boolean,
)

data class T9InterventionCheckpoint(
    val rawInput: String,
    val caret: Int,
    val preedit: String,
    val engineInput: String,
    val engineCaret: Int,
    val engineInputOffset: Int,
    val fixedPrefixLength: Int,
    val replacementWindow: T9ReplacementWindow,
    val replacedText: String,
    val replacementText: String,
)

sealed interface T9SessionCommand {
    data class ReplaceInputRange(
        val replacementWindow: T9ReplacementWindow,
        val replacement: String,
        val caret: Int,
    ) : T9SessionCommand

    data class RestoreSnapshot(
        val rawInput: String,
        val caret: Int,
    ) : T9SessionCommand

    data object ResetSpellingArea : T9SessionCommand

    data object SendBackspace : T9SessionCommand

    data object DeleteHostTextBackward : T9SessionCommand
}

data class T9SessionMutation(
    val state: T9UiState,
    val command: T9SessionCommand? = null,
)

data class T9UiState(
    val rawInput: String = "",
    val caret: Int = 0,
    val preedit: String = "",
    val engineInput: String = "",
    val engineCaret: Int = 0,
    val engineInputOffset: Int = 0,
    val fixedPrefixLength: Int = 0,
    val replacementWindow: T9ReplacementWindow? = null,
    val manualActiveWindow: T9ReplacementWindow? = null,
    val sideRailEntries: List<T9SideRailEntry> = emptyList(),
    val undoStack: List<T9InterventionCheckpoint> = emptyList(),
) {
    val interventionCheckpoint: T9InterventionCheckpoint?
        get() = undoStack.lastOrNull()

    companion object {
        val Empty = T9UiState()
    }
}

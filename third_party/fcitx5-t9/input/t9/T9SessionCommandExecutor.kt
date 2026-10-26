/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.t9

import org.fcitx.fcitx5.android.core.FcitxAPI
import kotlin.math.max

internal suspend fun FcitxAPI.executeT9SessionCommand(
    state: T9UiState,
    command: T9SessionCommand
) {
    when (command) {
        is T9SessionCommand.ReplaceInputRange -> {
            if (state.engineInputOffset > 0 || state.engineInput != state.rawInput) {
                if (!tryApplySnapshotBackedReplace(state, command)) {
                    setInput(state.engineInput)
                    setCaret(state.engineCaret)
                }
            } else {
                setInput(state.engineInput)
                setCaret(state.engineCaret)
            }
        }

        is T9SessionCommand.RestoreSnapshot -> {
            if (state.engineInputOffset > 0 || state.engineInput != state.rawInput) {
                setInput(state.engineInput)
                setCaret(state.engineCaret)
            } else {
                setInput(command.rawInput)
                setCaret(command.caret)
            }
        }

        T9SessionCommand.ResetSpellingArea -> reset()
        T9SessionCommand.DeleteHostTextBackward -> Unit
        T9SessionCommand.SendBackspace -> {
            if (state.engineInput.isNotBlank() && state.engineCaret > 0) {
                val deleteAt = state.engineCaret.coerceIn(1, state.engineInput.length)
                val updated = buildString(state.engineInput.length - 1) {
                    append(state.engineInput, 0, deleteAt - 1)
                    append(state.engineInput, deleteAt, state.engineInput.length)
                }
                setInput(updated)
                setCaret(deleteAt - 1)
            } else {
                sendKey('\b')
            }
        }
    }
}

internal suspend fun FcitxAPI.selectT9Candidate(
    index: Int,
    state: T9UiState? = null
): Boolean {
    if (!select(index)) return false
    if (state != null) {
        normalizeLeakedTailDelimiterAfterSelection()
    }
    return true
}

private suspend fun FcitxAPI.tryApplySnapshotBackedReplace(
    state: T9UiState,
    command: T9SessionCommand.ReplaceInputRange
): Boolean {
    val checkpoint = state.interventionCheckpoint ?: return false
    val sessionInput = snapshotField(readRimeDebugSnapshot(), "input")
    if (sessionInput.isBlank() || checkpoint.engineInput.isBlank()) return false
    if (!sessionInput.endsWith(checkpoint.engineInput)) return false

    val hiddenPrefixLength = sessionInput.length - checkpoint.engineInput.length
    val hiddenPrefix = sessionInput.substring(0, hiddenPrefixLength)
    val updatedSessionInput = hiddenPrefix + state.engineInput
    val sessionCaret = hiddenPrefixLength + max(state.engineCaret, 0)

    setInput(updatedSessionInput)
    setCaret(sessionCaret)
    return true
}

private suspend fun FcitxAPI.normalizeLeakedTailDelimiterAfterSelection() {
    val snapshot = readRimeDebugSnapshot()
    val sessionInput = snapshotField(snapshot, "input")
    val commitPreview = snapshotField(snapshot, "commitTextPreview")
    val currentCaret = snapshotField(snapshot, "caret").toIntOrNull() ?: sessionInput.length
    val normalized = leakedTailDelimiterNormalization(
        sessionInput = sessionInput,
        commitPreview = commitPreview,
        currentCaret = currentCaret
    ) ?: return

    setInput(normalized.input)
    setCaret(normalized.caret)
}

private fun snapshotField(snapshot: String, key: String): String =
    snapshot.lineSequence()
        .filter { it.startsWith("$key=") }
        .lastOrNull()
        ?.substringAfter('=')
        .orEmpty()

internal data class TailDelimiterNormalization(
    val input: String,
    val caret: Int
)

private const val EscapedLeftSingleQuote = "\\xE2\\x80\\x98"
private const val EscapedByteMarker = "\\x"

internal fun leakedTailDelimiterNormalization(
    sessionInput: String,
    commitPreview: String,
    currentCaret: Int
): TailDelimiterNormalization? {
    if (sessionInput.isBlank() || commitPreview.isBlank()) return null

    val commitDelimiterIndex = commitPreview.lastIndexOf(EscapedLeftSingleQuote)
    if (commitDelimiterIndex < 0) return null
    val commitTail = commitPreview.substring(commitDelimiterIndex + EscapedLeftSingleQuote.length)
    if (commitTail.isBlank()) return null

    // Hamster does not normalize delimiter during ordinary ASCII tail continuation.
    // Keep normalization as a narrow final-closure fallback only.
    if (!commitTail.contains(EscapedByteMarker)) return null

    val delimiterIndex = sessionInput.lastIndexOf('\'')
    if (delimiterIndex < 0 || delimiterIndex >= sessionInput.lastIndex) return null

    val tail = sessionInput.substring(delimiterIndex + 1)
    if (tail.any { it != '\'' && it.lowercaseChar() !in 'a'..'z' }) return null

    return TailDelimiterNormalization(
        input = sessionInput.removeRange(delimiterIndex, delimiterIndex + 1),
        caret = (currentCaret - 1).coerceAtLeast(delimiterIndex)
    )
}

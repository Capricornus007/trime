/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.prediction

enum class PostCommitPredictionPhase {
    Idle,
    Pending,
    Active,
}

data class PostCommitPredictionState(
    val phase: PostCommitPredictionPhase = PostCommitPredictionPhase.Idle,
    val lastCommittedText: String = "",
) {
    val active: Boolean
        get() = phase == PostCommitPredictionPhase.Active

    val awaitingCandidates: Boolean
        get() = phase == PostCommitPredictionPhase.Pending
}

class PostCommitPredictionTracker(
    private val supportedSchemaIds: Set<String> = SupportedSchemaIds,
) {

    var state: PostCommitPredictionState = PostCommitPredictionState()
        private set

    fun onCommit(schemaId: String, committedText: String) {
        if (schemaId !in supportedSchemaIds || committedText.isBlank()) {
            reset()
            return
        }
        state = PostCommitPredictionState(
            phase = PostCommitPredictionPhase.Pending,
            lastCommittedText = committedText,
        )
    }

    fun onInputPanelPreeditChanged(preedit: String) {
        if (preedit.isNotEmpty()) {
            reset()
        }
    }

    fun onCandidateVisibilityChanged(hasCandidates: Boolean) {
        state = when {
            !hasCandidates -> PostCommitPredictionState()

            state.phase == PostCommitPredictionPhase.Pending ->
                state.copy(phase = PostCommitPredictionPhase.Active)

            else -> state
        }
    }

    fun consumeDeleteActionIfPredictionVisible(): Boolean {
        if (state.phase != PostCommitPredictionPhase.Active) return false
        reset()
        return true
    }

    fun reset() {
        state = PostCommitPredictionState()
    }

    companion object {
        val SupportedSchemaIds: Set<String> = setOf("rime_ice", "t9")
    }
}

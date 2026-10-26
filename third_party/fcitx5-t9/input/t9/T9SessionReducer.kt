/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.t9

object T9SessionReducer {

    private data class ResolvedInputs(
        val displayInput: String,
        val displayCaret: Int,
        val engineInput: String,
        val engineCaret: Int,
        val engineInputOffset: Int,
    )

    fun syncFromEngine(
        state: T9UiState,
        rawInput: String,
        caret: Int,
        preedit: String,
        resolver: T9PinyinResolver,
    ): T9UiState {
        val inputs = resolveInputs(
            rawInput = rawInput,
            caret = caret,
            preedit = preedit,
        )
        if (inputs.displayInput.isBlank() && preedit.isBlank()) {
            return T9UiState.Empty
        }
        val manualResolution = state.manualActiveWindow
            ?.takeIf { shouldPreserveManualActiveWindow(it, inputs.displayInput) }
            ?.let { resolver.resolveForWindow(inputs.displayInput, preedit, it) }
        val resolution = manualResolution ?: resolver.resolve(
            rawInput = inputs.displayInput,
            caret = inputs.displayCaret,
            preedit = preedit,
        )
        return T9UiState(
            rawInput = inputs.displayInput,
            caret = inputs.displayCaret,
            preedit = preedit,
            engineInput = inputs.engineInput,
            engineCaret = inputs.engineCaret,
            engineInputOffset = inputs.engineInputOffset,
            fixedPrefixLength = syncedFixedPrefixLength(
                previous = state.fixedPrefixLength,
                rawInput = inputs.displayInput,
                replacementWindow = resolution.replacementWindow,
                manualActiveWindow = manualResolution?.replacementWindow,
            ),
            replacementWindow = resolution.replacementWindow,
            manualActiveWindow = resolution.replacementWindow
                ?.takeIf { manualResolution != null },
            sideRailEntries = resolution.sideRailEntries,
            undoStack = state.undoStack,
        )
    }

    fun pushRestorePoint(state: T9UiState): T9UiState {
        if (state.rawInput.isBlank() && state.preedit.isBlank()) {
            return state
        }
        return state.copy(
            undoStack = state.undoStack + snapshotCheckpoint(state),
        )
    }

    fun applySideRailEntry(
        state: T9UiState,
        entry: T9SideRailEntry,
        resolver: T9PinyinResolver,
    ): T9SessionMutation {
        reopenedTailMutation(state, entry, resolver)?.let { return it }
        val collapseIndex = collapseCheckpointIndex(state, entry)
        val checkpointSource = collapseIndex
            ?.let { state.undoStack[it] }
            ?: T9InterventionCheckpoint(
                rawInput = state.rawInput,
                caret = state.caret,
                preedit = state.preedit,
                engineInput = state.engineInput,
                engineCaret = state.engineCaret,
                engineInputOffset = state.engineInputOffset,
                fixedPrefixLength = state.fixedPrefixLength,
                replacementWindow = entry.replacementWindow,
                replacedText = entry.replacementWindow.substringOf(state.rawInput),
                replacementText = entry.text,
            )
        val checkpoint = checkpointSource.copy(
            replacementWindow = entry.replacementWindow,
            replacedText = replacedTextFor(entry.replacementWindow, checkpointSource.rawInput),
            replacementText = entry.text,
        )
        val checkpointedState = state.copy(
            undoStack = if (collapseIndex != null) {
                state.undoStack.take(collapseIndex) + checkpoint
            } else {
                state.undoStack + checkpoint
            },
        )
        val nextDisplayInput = replaceRange(
            text = checkpointedState.rawInput,
            window = entry.replacementWindow,
            replacement = entry.text,
        )
        val nextDisplayCaret = nextDisplayInput.length
        val engineWindow = translateToEngineWindow(
            displayWindow = entry.replacementWindow,
            state = checkpointedState,
        )
        val nextEngineInput = replaceRange(
            text = checkpointedState.engineInput,
            window = engineWindow,
            replacement = entry.text,
        )
        val nextEngineCaret = nextEngineInput.length
        val nextPreedit = nextDisplayInput
        val resolution = resolver.resolve(
            rawInput = nextDisplayInput,
            caret = nextDisplayCaret,
            preedit = nextPreedit,
        )
        val preservedManualWindow = state.manualActiveWindow
            ?.takeIf { it == entry.replacementWindow && nextDisplayInput.none(Char::isDigit) }
        val nextResolution = preservedManualWindow
            ?.let { resolver.resolveForWindow(nextDisplayInput, nextPreedit, it) }
            ?: resolution
        val nextState = checkpointedState.copy(
            rawInput = nextDisplayInput,
            caret = nextDisplayCaret,
            preedit = nextPreedit,
            engineInput = nextEngineInput,
            engineCaret = nextEngineCaret,
            fixedPrefixLength = nextFixedPrefixLength(
                state = checkpointedState,
                entry = entry,
                nextDisplayInput = nextDisplayInput,
                nextReplacementWindow = nextResolution.replacementWindow,
                preservedManualWindow = preservedManualWindow,
            ),
            replacementWindow = nextResolution.replacementWindow,
            manualActiveWindow = preservedManualWindow,
            sideRailEntries = nextResolution.sideRailEntries,
        )
        return T9SessionMutation(
            state = nextState,
            command = T9SessionCommand.ReplaceInputRange(
                replacementWindow = entry.replacementWindow,
                replacement = entry.text,
                caret = nextDisplayCaret,
            ),
        )
    }

    private fun collapseCheckpointIndex(
        state: T9UiState,
        entry: T9SideRailEntry,
    ): Int? {
        if (state.undoStack.isEmpty()) return null
        var collapseIndex: Int? = null
        for (index in state.undoStack.indices.reversed()) {
            val checkpoint = state.undoStack[index]
            if (checkpoint.engineInputOffset != state.engineInputOffset) {
                break
            }
            if (!entry.replacementWindow.contains(checkpoint.replacementWindow)) {
                break
            }
            collapseIndex = index
        }
        return collapseIndex
    }

    private fun replacedTextFor(window: T9ReplacementWindow, rawInput: String): String = if (window.end <= rawInput.length) {
        window.substringOf(rawInput)
    } else {
        rawInput
    }

    fun onBackspace(
        state: T9UiState,
        resolver: T9PinyinResolver,
    ): T9SessionMutation {
        val checkpoint = state.interventionCheckpoint
        if (checkpoint == null) {
            if (state.engineInputOffset > 0 && state.engineInput.isNotBlank()) {
                val rolledBackState = syncFromEngine(
                    state = state.copy(
                        rawInput = state.engineInput,
                        caret = state.engineCaret,
                        engineInput = state.engineInput,
                        engineCaret = state.engineCaret,
                        engineInputOffset = 0,
                    ),
                    rawInput = state.engineInput,
                    caret = state.engineCaret,
                    preedit = state.engineInput,
                    resolver = resolver,
                )
                return T9SessionMutation(
                    state = rolledBackState,
                    command = T9SessionCommand.RestoreSnapshot(
                        rawInput = state.engineInput,
                        caret = state.engineCaret,
                    ),
                )
            }
            if (state.engineInput.isBlank()) {
                return T9SessionMutation(
                    state = state,
                    command = T9SessionCommand.DeleteHostTextBackward,
                )
            }
            if (state.engineInput.isNotBlank() && state.engineCaret > 0) {
                val deleteAt = state.engineCaret.coerceIn(1, state.engineInput.length)
                val updatedEngineInput = buildString(state.engineInput.length - 1) {
                    append(state.engineInput, 0, deleteAt - 1)
                    append(state.engineInput, deleteAt, state.engineInput.length)
                }
                val updatedEngineCaret = deleteAt - 1
                val restoredState = syncFromEngine(
                    state = state.copy(
                        rawInput = updatedEngineInput,
                        caret = updatedEngineCaret,
                        engineInput = updatedEngineInput,
                        engineCaret = updatedEngineCaret,
                    ),
                    rawInput = updatedEngineInput,
                    caret = updatedEngineCaret,
                    preedit = updatedEngineInput,
                    resolver = resolver,
                )
                return T9SessionMutation(
                    state = restoredState,
                    command = T9SessionCommand.RestoreSnapshot(
                        rawInput = updatedEngineInput,
                        caret = updatedEngineCaret,
                    ),
                )
            }
            return T9SessionMutation(
                state = state,
                command = T9SessionCommand.SendBackspace,
            )
        }
        val restoredState = syncFromEngine(
            state = state.copy(
                rawInput = checkpoint.rawInput,
                caret = checkpoint.caret,
                engineInput = checkpoint.engineInput,
                engineCaret = checkpoint.engineCaret,
                engineInputOffset = checkpoint.engineInputOffset,
                fixedPrefixLength = checkpoint.fixedPrefixLength,
                manualActiveWindow = null,
                undoStack = state.undoStack.dropLast(1),
            ),
            rawInput = checkpoint.rawInput,
            caret = checkpoint.caret,
            preedit = checkpoint.preedit,
            resolver = resolver,
        )
        return T9SessionMutation(
            state = restoredState,
            command = T9SessionCommand.RestoreSnapshot(
                rawInput = checkpoint.rawInput,
                caret = checkpoint.caret,
            ),
        )
    }

    private fun replaceRange(
        text: String,
        window: T9ReplacementWindow,
        replacement: String,
    ): String = buildString(text.length - window.length + replacement.length) {
        append(text, 0, window.start)
        append(replacement)
        append(text, window.end, text.length)
    }

    private fun snapshotCheckpoint(state: T9UiState): T9InterventionCheckpoint {
        val replacementWindow = state.replacementWindow ?: T9ReplacementWindow(
            start = state.caret,
            end = state.caret,
        )
        val replacedText = if (replacementWindow.length > 0 &&
            replacementWindow.end <= state.rawInput.length
        ) {
            replacementWindow.substringOf(state.rawInput)
        } else {
            state.rawInput
        }
        return T9InterventionCheckpoint(
            rawInput = state.rawInput,
            caret = state.caret,
            preedit = state.preedit,
            engineInput = state.engineInput,
            engineCaret = state.engineCaret,
            engineInputOffset = state.engineInputOffset,
            fixedPrefixLength = state.fixedPrefixLength,
            replacementWindow = replacementWindow,
            replacedText = replacedText,
            replacementText = replacedText,
        )
    }

    private fun T9ReplacementWindow.contains(other: T9ReplacementWindow): Boolean = start <= other.start && end >= other.end

    private fun syncedFixedPrefixLength(
        previous: Int,
        rawInput: String,
        replacementWindow: T9ReplacementWindow?,
        manualActiveWindow: T9ReplacementWindow?,
    ): Int {
        val clamped = previous.coerceIn(0, rawInput.length)
        if (manualActiveWindow != null) {
            return if (clamped >= rawInput.length && rawInput.none(Char::isDigit)) {
                rawInput.length
            } else {
                manualActiveWindow.start.coerceIn(0, rawInput.length)
            }
        }
        if (rawInput.isNotBlank() && rawInput.none(Char::isDigit)) {
            return clamped
        }
        val activeStart = replacementWindow?.start ?: rawInput.length
        return if (activeStart < clamped) activeStart else clamped
    }

    private fun nextFixedPrefixLength(
        state: T9UiState,
        entry: T9SideRailEntry,
        nextDisplayInput: String,
        nextReplacementWindow: T9ReplacementWindow?,
        preservedManualWindow: T9ReplacementWindow?,
    ): Int {
        if (preservedManualWindow != null) {
            return nextDisplayInput.length
        }
        var fixedPrefixLength = state.fixedPrefixLength.coerceIn(0, nextDisplayInput.length)
        val replacementStart = entry.replacementWindow.start.coerceIn(0, nextDisplayInput.length)
        val replacementEnd = (entry.replacementWindow.start + entry.text.length)
            .coerceIn(0, nextDisplayInput.length)
        if (replacementStart == fixedPrefixLength) {
            fixedPrefixLength = replacementEnd
            if (replacementEnd >= nextDisplayInput.length && nextDisplayInput.none(Char::isDigit)) {
                return nextDisplayInput.length
            }
        }
        val activeStart = nextReplacementWindow?.start ?: nextDisplayInput.length
        if (activeStart < fixedPrefixLength) {
            fixedPrefixLength = activeStart
        }
        return fixedPrefixLength
    }

    private fun reopenedTailMutation(
        state: T9UiState,
        entry: T9SideRailEntry,
        resolver: T9PinyinResolver,
    ): T9SessionMutation? {
        val activeWindow = state.replacementWindow ?: return null
        if (state.manualActiveWindow != null) return null
        if (activeWindow.end > state.rawInput.length) return null
        val activeToken = activeWindow.substringOf(state.rawInput)
        if (activeToken.isBlank() || activeToken.any(Char::isDigit)) return null
        val candidate = entry.text
        if (candidate.isBlank() || candidate.any(Char::isDigit)) return null
        if (!activeToken.startsWith(candidate) || candidate.length >= activeToken.length) return null

        val reopenedWindow = T9ReplacementWindow(
            start = activeWindow.start + candidate.length,
            end = activeWindow.end,
        )
        val reopenedResolution = resolver.resolveForWindow(
            rawInput = state.rawInput,
            preedit = state.preedit,
            window = reopenedWindow,
        )
        val checkpointedState = state.copy(
            undoStack = state.undoStack + snapshotCheckpoint(state),
        )
        return T9SessionMutation(
            state = checkpointedState.copy(
                fixedPrefixLength = reopenedWindow.start.coerceIn(0, state.rawInput.length),
                replacementWindow = reopenedResolution.replacementWindow,
                manualActiveWindow = reopenedResolution.replacementWindow,
                sideRailEntries = reopenedResolution.sideRailEntries,
            ),
            command = null,
        )
    }

    private fun shouldPreserveManualActiveWindow(
        window: T9ReplacementWindow,
        rawInput: String,
    ): Boolean = rawInput.none(Char::isDigit) && window.end <= rawInput.length

    private fun resolveInputs(
        rawInput: String,
        caret: Int,
        preedit: String,
    ): ResolvedInputs {
        val normalizedPreedit = preedit.replace(" ", "")
        val normalizedCaret = caret.coerceAtLeast(0)

        if (rawInput.isNotBlank()) {
            normalizeCollapsedBucketDisplayInput(rawInput, normalizedCaret)?.let { normalized ->
                return normalized
            }
            val engineCaret = normalizedCaret.coerceIn(0, rawInput.length)
            if (normalizedPreedit.isNotBlank() &&
                normalizedPreedit.endsWith(rawInput) &&
                normalizedPreedit.length > rawInput.length
            ) {
                val prefixLength = normalizedPreedit.length - rawInput.length
                val prefix = normalizedPreedit.substring(0, prefixLength)
                if (prefix.any { !isEngineChar(it) }) {
                    return ResolvedInputs(
                        displayInput = normalizedPreedit,
                        displayCaret = prefixLength + engineCaret,
                        engineInput = rawInput,
                        engineCaret = engineCaret,
                        engineInputOffset = prefixLength,
                    )
                }
            }
            return ResolvedInputs(
                displayInput = rawInput,
                displayCaret = engineCaret,
                engineInput = rawInput,
                engineCaret = engineCaret,
                engineInputOffset = 0,
            )
        }

        if (normalizedPreedit.isBlank()) {
            return ResolvedInputs(
                displayInput = "",
                displayCaret = 0,
                engineInput = "",
                engineCaret = 0,
                engineInputOffset = 0,
            )
        }

        val tailStart = normalizedPreedit.indexOfFirst(::isEngineChar)
        if (tailStart < 0) {
            return ResolvedInputs(
                displayInput = normalizedPreedit,
                displayCaret = normalizedPreedit.length,
                engineInput = "",
                engineCaret = 0,
                engineInputOffset = normalizedPreedit.length,
            )
        }
        val engineInput = normalizedPreedit.substring(tailStart)
        val engineCaret = normalizedCaret
            .takeIf { it > 0 }
            ?.coerceIn(0, engineInput.length)
            ?: engineInput.length
        return ResolvedInputs(
            displayInput = normalizedPreedit,
            displayCaret = tailStart + engineCaret,
            engineInput = engineInput,
            engineCaret = engineCaret,
            engineInputOffset = tailStart,
        )
    }

    private fun translateToEngineWindow(
        displayWindow: T9ReplacementWindow,
        state: T9UiState,
    ): T9ReplacementWindow {
        val start = if (state.engineInputOffset > 0) {
            (displayWindow.start - state.engineInputOffset).coerceIn(0, state.engineInput.length)
        } else if (state.engineInput == state.rawInput) {
            (displayWindow.start - state.engineInputOffset).coerceIn(0, state.engineInput.length)
        } else {
            displayIndexToEngineIndex(displayWindow.start, state.engineInput)
        }
        val end = if (state.engineInputOffset > 0) {
            (displayWindow.end - state.engineInputOffset).coerceIn(start, state.engineInput.length)
        } else if (state.engineInput == state.rawInput) {
            (displayWindow.end - state.engineInputOffset).coerceIn(start, state.engineInput.length)
        } else {
            displayIndexToEngineIndex(displayWindow.end, state.engineInput).coerceIn(start, state.engineInput.length)
        }
        return T9ReplacementWindow(start = start, end = end)
    }

    private fun normalizeCollapsedBucketDisplayInput(
        rawInput: String,
        caret: Int,
    ): ResolvedInputs? {
        if (!rawInput.contains('\'')) return null
        val segments = rawInput.split('\'')
        if (segments.size < 2) {
            return null
        }
        val prefixSegments = segments.dropLast(1)
        val tailSegment = segments.last()
        if (prefixSegments.isEmpty() ||
            prefixSegments.any { segment ->
                segment.length != 1 || segment.any { ch -> !isDisplayBucketChar(ch) }
            } ||
            tailSegment.isEmpty() ||
            tailSegment.any { ch -> !isDisplayBucketChar(ch) }
        ) {
            return null
        }
        val displayInput = rawInput.replace("'", "")
        val displayCaret = rawInput
            .take(caret.coerceIn(0, rawInput.length))
            .count { it != '\'' }
            .coerceIn(0, displayInput.length)
        return ResolvedInputs(
            displayInput = displayInput,
            displayCaret = displayCaret,
            engineInput = rawInput,
            engineCaret = caret.coerceIn(0, rawInput.length),
            engineInputOffset = 0,
        )
    }

    private fun displayIndexToEngineIndex(displayIndex: Int, engineInput: String): Int {
        val target = displayIndex.coerceAtLeast(0)
        var visibleCount = 0
        var index = 0
        while (index < engineInput.length) {
            if (visibleCount == target) {
                while (index < engineInput.length && engineInput[index] == '\'') {
                    index += 1
                }
                return index
            }
            if (engineInput[index] != '\'') {
                visibleCount += 1
            }
            index += 1
        }
        return engineInput.length
    }

    private fun isEngineChar(char: Char): Boolean = char == '\'' || char.lowercaseChar() in 'a'..'z'

    private fun isDisplayBucketChar(char: Char): Boolean = char.lowercaseChar() in 'a'..'z' || char in '2'..'9'
}

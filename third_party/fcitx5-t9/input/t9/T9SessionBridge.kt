/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.t9

class T9SessionBridge(
    private val coordinator: T9Coordinator = T9Coordinator(),
) {

    private var punctuationMapping: Map<String, String> = emptyMap()

    fun updatePunctuationMapping(mapping: Map<String, String>) {
        punctuationMapping = mapping
    }

    fun sync(rawInput: String, caret: Int, preedit: String): T9SideRailComponent.State {
        val state = coordinator.sync(
            rawInput = rawInput,
            caret = caret,
            preedit = preedit,
        )
        return railState(state)
    }

    fun onRailItemClick(item: T9SideRailComponent.Item): T9SessionCommand? = item.entry?.let(coordinator::selectSideRail)

    fun onRetype(): T9SessionCommand? = coordinator.onRetype()

    fun onBackspace(): T9SessionCommand = coordinator.onBackspace()

    fun railState(state: T9UiState): T9SideRailComponent.State = if (state.sideRailEntries.isEmpty()) {
        T9SideRailComponent.punctuationState(punctuationMapping)
    } else {
        T9SideRailComponent.State(
            mode = T9SideRailComponent.Mode.Candidate,
            items = state.sideRailEntries.map { entry ->
                if (entry.text.toIntOrNull() != null) {
                    T9SideRailComponent.Item(
                        text = entry.text,
                        kind = T9SideRailComponent.Kind.DirectCommit,
                        selected = false,
                        entry = null,
                    )
                } else {
                    T9SideRailComponent.Item(
                        text = entry.text,
                        kind = T9SideRailComponent.Kind.Candidate,
                        selected = entry.selected,
                        entry = entry,
                    )
                }
            },
        )
    }
}

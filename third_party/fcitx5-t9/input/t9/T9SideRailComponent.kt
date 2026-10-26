/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.t9

class T9SideRailComponent {

    enum class Mode {
        Punctuation,
        Candidate,
    }

    enum class Kind {
        Punctuation,
        Candidate,
        DirectCommit,
    }

    data class Item(
        val text: String,
        val hint: String? = null,
        val kind: Kind = Kind.Punctuation,
        val selected: Boolean = false,
        val entry: T9SideRailEntry? = null,
    )

    data class State(
        val mode: Mode,
        val items: List<Item>,
    )

    fun interface OnItemClickListener {
        fun onItemClick(item: Item)
    }

    private val renderObservers = linkedSetOf<(State) -> Unit>()
    private var onItemClickListener: OnItemClickListener? = null

    private var state = punctuationState()

    val mode: Mode
        get() = state.mode

    fun bind(render: (State) -> Unit) {
        renderObservers += render
        render(state)
    }

    fun unbind(render: (State) -> Unit) {
        renderObservers -= render
    }

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        onItemClickListener = listener
    }

    fun submitState(state: State) {
        update(state)
    }

    fun showPunctuation(mapping: Map<String, String> = emptyMap()) {
        update(punctuationState(mapping))
    }

    fun showCandidates(candidates: Collection<T9SideRailEntry>) {
        if (candidates.isEmpty()) {
            showPunctuation()
            return
        }
        update(
            State(
                mode = Mode.Candidate,
                items = candidates.map { candidate ->
                    Item(
                        text = candidate.text,
                        kind = Kind.Candidate,
                        selected = candidate.selected,
                        entry = candidate,
                    )
                },
            ),
        )
    }

    fun clear() {
        showPunctuation()
    }

    fun dispatchItemClick(item: Item) {
        onItemClickListener?.onItemClick(item)
    }

    private fun update(newState: State) {
        state = newState
        renderObservers.forEach { it(state) }
    }

    companion object {
        private val punctuationSeeds = listOf(",", ".", "?", "!", ";", ":", "、", "，")

        fun punctuationState(mapping: Map<String, String> = emptyMap()): State = State(
            mode = Mode.Punctuation,
            items = punctuationSeeds.map { seed ->
                Item(text = mapping[seed] ?: displayFor(seed), hint = seed, kind = Kind.Punctuation)
            },
        )

        fun candidateState(candidates: Collection<T9SideRailEntry>): State = State(
            mode = Mode.Candidate,
            items = candidates.map { candidate ->
                Item(
                    text = candidate.text,
                    kind = Kind.Candidate,
                    selected = candidate.selected,
                    entry = candidate,
                )
            },
        )

        private fun displayFor(seed: String): String = when (seed) {
            "," -> "，"
            "." -> "。"
            "?" -> "？"
            "!" -> "！"
            ";" -> "；"
            ":" -> "："
            else -> seed
        }
    }
}

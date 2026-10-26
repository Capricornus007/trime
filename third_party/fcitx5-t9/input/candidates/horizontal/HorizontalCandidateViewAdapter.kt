/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.IndexedCandidate
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

open class HorizontalCandidateViewAdapter(val theme: Theme) : RecyclerView.Adapter<CandidateViewHolder>() {

    init {
        setHasStableIds(true)
    }

    var indexedCandidates: Array<IndexedCandidate> = arrayOf()
        private set

    val candidates: Array<FcitxEvent.Candidate>
        get() = indexedCandidates.map { it.candidate }.toTypedArray()

    var total = -1
        private set

    var showCandidateComment = true
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    @SuppressLint("NotifyDataSetChanged")
    fun updateCandidates(data: Array<FcitxEvent.Candidate>, total: Int) {
        updateIndexedCandidates(
            data.mapIndexed { index, candidate ->
                IndexedCandidate(candidate = candidate, sourceIndex = index)
            }.toTypedArray(),
            total,
        )
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateIndexedCandidates(data: Array<IndexedCandidate>, total: Int) {
        this.indexedCandidates = data
        this.total = total
        notifyDataSetChanged()
    }

    override fun getItemCount() = indexedCandidates.size

    override fun getItemId(position: Int) = indexedCandidates.getOrNull(position).hashCode().toLong()

    @CallSuper
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val ui = CandidateItemUi(parent.context, theme)
        ui.root.apply {
            minimumWidth = dp(40)
            setPaddingDp(10, 0, 10, 0)
            layoutParams = FlexboxLayoutManager.LayoutParams(wrapContent, matchParent)
        }
        return CandidateViewHolder(ui)
    }

    @CallSuper
    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        val entry = indexedCandidates[position]
        holder.ui.update(entry.candidate, showComment = showCandidateComment)
        holder.text = entry.candidate.text
        holder.candidate = entry.candidate
        holder.idx = entry.sourceIndex
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.IndexedCandidate

open class PagingCandidateViewAdapter(val theme: Theme) : PagingDataAdapter<IndexedCandidate, CandidateViewHolder>(diffCallback) {

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<IndexedCandidate>() {
            override fun areItemsTheSame(
                oldItem: IndexedCandidate,
                newItem: IndexedCandidate,
            ): Boolean = oldItem == newItem

            override fun areContentsTheSame(
                oldItem: IndexedCandidate,
                newItem: IndexedCandidate,
            ): Boolean = oldItem == newItem
        }
    }

    var offset = 0
        private set

    var showCandidateComment = true
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun refreshWithOffset(offset: Int) {
        this.offset = offset
        refresh()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder = CandidateViewHolder(CandidateItemUi(parent.context, theme))

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        val entry = getItem(position)!!
        holder.ui.update(entry.candidate, showComment = showCandidateComment)
        holder.text = entry.candidate.text
        holder.candidate = entry.candidate
        holder.idx = entry.sourceIndex
    }
}

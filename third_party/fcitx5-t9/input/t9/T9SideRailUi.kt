/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.t9

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

class T9SideRailUi(
    context: Context,
    private val theme: Theme,
) : RecyclerView(context) {

    var onItemClick: ((T9SideRailComponent.Item) -> Unit)? = null

    private val railAdapter = RailAdapter(theme) { onItemClick?.invoke(it) }

    init {
        layoutManager = LinearLayoutManager(context)
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        itemAnimator = null
        setHasFixedSize(true)
        clipToPadding = false
        super.adapter = railAdapter
        val sidePadding = dp(6)
        setPadding(sidePadding, sidePadding, sidePadding, sidePadding)
    }

    fun submitState(state: T9SideRailComponent.State) {
        railAdapter.mode = state.mode
        railAdapter.submitList(state.items) {
            scrollToPosition(0)
        }
    }

    private class RailAdapter(
        private val theme: Theme,
        private val onClick: (T9SideRailComponent.Item) -> Unit,
    ) : ListAdapter<T9SideRailComponent.Item, RailAdapter.Holder>(DIFF) {

        var mode: T9SideRailComponent.Mode = T9SideRailComponent.Mode.Punctuation

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(RailItemView(parent.context, theme, onClick))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.ui.bind(getItem(position), mode)
        }

        class Holder(val ui: RailItemView) : RecyclerView.ViewHolder(ui)

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<T9SideRailComponent.Item>() {
                override fun areItemsTheSame(
                    oldItem: T9SideRailComponent.Item,
                    newItem: T9SideRailComponent.Item,
                ): Boolean = oldItem.kind == newItem.kind &&
                    oldItem.text == newItem.text &&
                    oldItem.hint == newItem.hint

                override fun areContentsTheSame(
                    oldItem: T9SideRailComponent.Item,
                    newItem: T9SideRailComponent.Item,
                ): Boolean = oldItem == newItem
            }
        }
    }

    private class RailItemView(
        context: Context,
        private val theme: Theme,
        private val onClick: (T9SideRailComponent.Item) -> Unit,
    ) : LinearLayout(context) {

        private val title = TextView(context)
        private val hint = TextView(context)
        private val backgroundDrawable = GradientDrawable()
        private var boundItem: T9SideRailComponent.Item? = null

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val h = dp(10)
            val v = dp(8)
            setPadding(h, v, h, v)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(6)
            }

            backgroundDrawable.cornerRadius = dp(12).toFloat()
            background = backgroundDrawable

            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            hint.setTextColor(theme.candidateCommentColor)

            addView(
                title,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            addView(
                hint,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                boundItem?.let(onClick)
            }
        }

        fun bind(item: T9SideRailComponent.Item, mode: T9SideRailComponent.Mode) {
            boundItem = item
            title.text = item.text
            hint.text = item.hint.orEmpty()
            hint.visibility = if (item.hint.isNullOrBlank()) GONE else VISIBLE
            title.setTextColor(
                when (item.kind) {
                    T9SideRailComponent.Kind.Candidate -> theme.candidateTextColor
                    T9SideRailComponent.Kind.DirectCommit -> theme.candidateTextColor
                    T9SideRailComponent.Kind.Punctuation -> theme.keyTextColor
                },
            )
            backgroundDrawable.setColor(
                when (mode) {
                    T9SideRailComponent.Mode.Candidate -> if (item.selected) {
                        theme.genericActiveBackgroundColor
                    } else {
                        theme.altKeyBackgroundColor
                    }

                    T9SideRailComponent.Mode.Punctuation -> theme.keyBackgroundColor
                },
            )
            val selectedAlpha = when {
                item.selected -> 0.28f

                item.kind == T9SideRailComponent.Kind.Candidate ||
                    item.kind == T9SideRailComponent.Kind.DirectCommit -> 0.16f

                else -> 0.10f
            }
            backgroundDrawable.setStroke(
                dp(1),
                Color.argb((255 * selectedAlpha).toInt(), 255, 255, 255),
            )
        }
    }
}

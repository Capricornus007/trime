/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.candidates.expanded.PagingCandidateViewAdapter
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import splitties.dimensions.dp

@RunWith(AndroidJUnit4::class)
class CandidateItemUiTest {

    @Test
    fun longCandidateTextMeasuresPastMinimumWidth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ui = CandidateItemUi(context, ThemeManager.activeTheme)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ui.root.minimumWidth = context.dp(40)
            ui.update(
                FcitxEvent.Candidate("", "zhonghuarenmingongheguo", ""),
                active = false,
            )
            ui.root.measure(
                View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            ui.root.layout(0, 0, ui.root.measuredWidth, ui.root.measuredHeight)
        }

        assertTrue(ui.text.measuredWidth > 0)
        assertTrue(ui.root.measuredWidth > ui.root.minimumWidth)
    }

    @Test
    fun horizontalCandidatesKeepSingleRowItemHeight() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = HorizontalCandidateViewAdapter(ThemeManager.activeTheme)
        lateinit var holder: CandidateViewHolder

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = FrameLayout(context)
            holder = adapter.onCreateViewHolder(parent, 0)
        }

        assertEquals(
            ViewGroup.LayoutParams.MATCH_PARENT,
            holder.itemView.layoutParams.height,
        )
    }

    @Test
    fun horizontalCandidatesCanHideCommentWithoutChangingCandidateOrIndex() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = HorizontalCandidateViewAdapter(ThemeManager.activeTheme)
        val candidate = FcitxEvent.Candidate("", "nihao", "ni hao")
        lateinit var holder: CandidateViewHolder

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = FrameLayout(context)
            holder = adapter.onCreateViewHolder(parent, 0)
            adapter.showCandidateComment = false
            adapter.updateCandidates(arrayOf(candidate), 1)
            adapter.onBindViewHolder(holder, 0)
        }

        assertEquals("nihao", holder.ui.text.text.toString())
        assertEquals(candidate, holder.candidate)
        assertEquals(0, holder.idx)
        assertFalse(holder.ui.text.text.toString().contains("ni hao"))
    }

    @Test
    fun expandedCandidatesCanHideCommentWithoutChangingCandidateOrIndex() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = PagingCandidateViewAdapter(ThemeManager.activeTheme)
        val candidate = FcitxEvent.Candidate("", "nihao", "ni hao")
        lateinit var holder: CandidateViewHolder

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = FrameLayout(context)
            holder = adapter.onCreateViewHolder(parent, 0)
            adapter.showCandidateComment = false
            holder.ui.update(candidate, showComment = adapter.showCandidateComment)
            holder.text = candidate.text
            holder.candidate = candidate
            holder.idx = 0
        }

        assertEquals("nihao", holder.ui.text.text.toString())
        assertEquals(candidate, holder.candidate)
        assertEquals(0, holder.idx)
        assertFalse(holder.ui.text.text.toString().contains("ni hao"))
    }
}

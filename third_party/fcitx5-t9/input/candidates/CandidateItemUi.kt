/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.text.style.RelativeSizeSpan
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.inSpans
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter

class CandidateItemUi(override val ctx: Context, private val theme: Theme) : Ui {

    val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        textSize = 20f // sp
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(theme.candidateTextColor)
    }

    override val root = view(::CustomGestureView) {
        background = pressHighlightDrawable(theme.keyPressHighlightColor)

        /**
         * candidate long press feedback is handled by [org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent.showCandidateActionMenu]
         */
        longPressFeedbackEnabled = false

        add(text, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    fun update(
        candidate: FcitxEvent.Candidate,
        active: Boolean = false,
        showComment: Boolean = true
    ) {
        val fg = if (active) theme.genericActiveForegroundColor else theme.candidateTextColor
        val altFg = if (active) theme.genericActiveForegroundColor else theme.candidateCommentColor
        text.text = buildSpannedString {
            color(fg) { append(candidate.text) }
            if (showComment && candidate.comment.isNotBlank()) {
                append(" ")
                inSpans(RelativeSizeSpan(0.7f)) {
                    color(altFg) { append(candidate.comment) }
                }
            }
        }
        text.setTextColor(fg)
    }
}

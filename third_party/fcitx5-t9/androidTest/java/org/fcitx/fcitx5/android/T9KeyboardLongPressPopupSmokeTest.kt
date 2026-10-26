/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T9KeyboardLongPressPopupSmokeTest {

    @Test
    fun longPressingDigitShowsPopupWheelWithDirectCommitChoices() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var keyboard: T9Keyboard
        var popupAction: PopupAction? = null

        instrumentation.runOnMainSync {
            keyboard = T9Keyboard(context, ThemeManager.activeTheme).apply {
                popupActionListener = org.fcitx.fcitx5.android.input.popup.PopupActionListener { action ->
                    popupAction = action
                }
                measure(
                    MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(720, MeasureSpec.EXACTLY),
                )
                layout(0, 0, 1080, 720)
            }
            findVisibleTextKey(keyboard, "ABC")!!.performLongClick()
        }

        val showKeyboard = popupAction as? PopupAction.ShowKeyboardAction
        val popupChoices = showKeyboard?.let { PopupPreset[it.keyboard.label]?.toList() }

        assertTrue(showKeyboard != null)
        assertEquals(listOf("a", "b", "c", "A", "B", "C", "2"), popupChoices)
    }

    private fun findVisibleTextKey(view: View, label: String): View? {
        val textKeyViewClass =
            Class.forName("org.fcitx.fcitx5.android.input.keyboard.TextKeyView")
        val mainTextField = textKeyViewClass.getDeclaredField("mainText").apply {
            isAccessible = true
        }
        if (textKeyViewClass.isInstance(view)) {
            val textView = mainTextField.get(view) as? android.widget.TextView
            if (textView?.text?.toString() == label) return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findVisibleTextKey(view.getChildAt(index), label)?.let { return it }
            }
        }
        return null
    }
}

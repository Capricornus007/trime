/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.ImageLayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.picker.PickerLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class KeyboardReturnNavigationSmokeTest {

    @Test
    fun numberKeyboardBottomLeftButtonShowsReturnAndDispatchesReturnAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var keyboard: NumberKeyboard
        val actions = mutableListOf<KeyAction>()

        instrumentation.runOnMainSync {
            keyboard = NumberKeyboard(context, ThemeManager.activeTheme).apply {
                keyActionListener = KeyActionListener { action, _ ->
                    actions += action
                }
            }
        }

        val returnKey = findVisibleTextKey(keyboard, "返回")
        assertNotNull(returnKey)

        performClick(returnKey!!)

        assertEquals(
            listOf(KeyAction.LayoutSwitchAction("__return_to_current_input_method_keyboard__")),
            actions,
        )
    }

    @Test
    fun symbolPickerBottomLeftButtonShowsReturnAndDispatchesReturnAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var pickerKeyboard: PickerLayout.Keyboard
        val actions = mutableListOf<KeyAction>()

        instrumentation.runOnMainSync {
            pickerKeyboard = PickerLayout.Keyboard(
                context,
                ThemeManager.activeTheme,
                ImageLayoutSwitchKey(R.drawable.ic_number_pad, NumberKeyboard.Name),
            ).apply {
                keyActionListener = KeyActionListener { action, _ ->
                    actions += action
                }
            }
        }

        val returnKey = findVisibleTextKey(pickerKeyboard, "返回")
        assertNotNull(returnKey)

        performClick(returnKey!!)

        assertEquals(
            listOf(KeyAction.LayoutSwitchAction("__return_to_current_input_method_keyboard__")),
            actions,
        )
    }

    private fun performClick(view: View) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.performClick()
        }
    }

    private fun findVisibleTextKey(view: View, label: String): View? {
        if (view is TextKeyView && view.mainText.text.toString() == label) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findVisibleTextKey(view.getChildAt(index), label)?.let { return it }
            }
        }
        return null
    }
}

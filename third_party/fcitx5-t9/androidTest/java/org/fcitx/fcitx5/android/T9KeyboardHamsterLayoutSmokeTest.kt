/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals as assertFloatEquals

class T9KeyboardHamsterLayoutSmokeTest {

    @Test
    fun t9KeyboardShowsHamsterStyleMainGridAndFourthRow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var keyboard: T9Keyboard

        instrumentation.runOnMainSync {
            keyboard = T9Keyboard(context, ThemeManager.activeTheme)
            keyboard.measure(
                MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(720, MeasureSpec.EXACTLY),
            )
            keyboard.layout(0, 0, 1080, 720)
        }

        assertEquals(1, keyboard.childCount)
        assertNotNull(keyboard.findViewById<View>(R.id.button_backspace))
        assertNotNull(keyboard.findViewById<View>(R.id.button_return))

        val rail = findRecyclerView(keyboard)
        val symbolKey = findVisibleTextKey(keyboard, "符")
        assertNotNull(rail)
        assertNotNull(symbolKey)
        assertTrue(bottomInRoot(rail!!) < keyboard.bottom)
        assertEquals(0, leftInRoot(symbolKey!!))
        assertEquals(bottomInRoot(rail), topInRoot(symbolKey))

        val mainLabels = linkedSetOf<String>()
        val secondaryLabels = linkedSetOf<String>()
        collectTextLabels(keyboard, mainLabels, secondaryLabels)
        assertTrue(mainLabels.containsAll(listOf("ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")))
        assertTrue(secondaryLabels.containsAll(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")))
        assertTrue(mainLabels.containsAll(listOf("重输", "符", "123", "中")))
    }

    @Test
    fun fourthRowKeysReuseExistingEntryActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var keyboard: T9Keyboard
        val actions = mutableListOf<KeyAction>()

        instrumentation.runOnMainSync {
            keyboard = T9Keyboard(context, ThemeManager.activeTheme).apply {
                keyActionListener = org.fcitx.fcitx5.android.input.keyboard.KeyActionListener { action, _ ->
                    actions += action
                }
            }
        }

        val symbolKey = findVisibleTextKey(keyboard, "符")
        val numberKey = findVisibleTextKey(keyboard, "123")
        val abcKey = findBottomMostTextKey(keyboard, "ABC")
        val spaceKey = keyboard.findViewById<View>(R.id.button_space)

        assertNotNull(symbolKey)
        assertNotNull(numberKey)
        assertNotNull(abcKey)
        assertNotNull(spaceKey)

        performClick(symbolKey!!)
        performClick(numberKey!!)
        performClick(spaceKey!!)
        performClick(abcKey!!)

        assertEquals(
            listOf(
                KeyAction.PickerSwitchAction(PickerWindow.Key.Symbol),
                KeyAction.LayoutSwitchAction("Number"),
                KeyAction.SymAction(
                    org.fcitx.fcitx5.android.core.KeySym(
                        org.fcitx.fcitx5.android.core.FcitxKeyMapping.FcitxKey_space,
                    ),
                ),
                KeyAction.LayoutSwitchAction("Text"),
            ),
            actions,
        )
    }

    @Test
    fun t9DigitKeysUseReferenceSizedMainLabels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var keyboard: T9Keyboard
        lateinit var key: TextKeyView

        instrumentation.runOnMainSync {
            keyboard = T9Keyboard(context, ThemeManager.activeTheme)
            keyboard.measure(
                MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(720, MeasureSpec.EXACTLY),
            )
            keyboard.layout(0, 0, 1080, 720)
            key = findVisibleTextKey(keyboard, "ABC") as TextKeyView
        }

        val density = context.resources.displayMetrics.density
        assertFloatEquals(24f, key.mainText.textSize / density, 0.5f)
    }

    private fun performClick(view: View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            view.performClick()
        }
    }

    private fun collectTextLabels(
        view: View,
        mainLabels: MutableSet<String>,
        secondaryLabels: MutableSet<String>,
    ) {
        if (view is TextKeyView) {
            mainLabels += view.mainText.text.toString()
            if (view is AltTextKeyView) {
                secondaryLabels += view.altText.text.toString()
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectTextLabels(view.getChildAt(index), mainLabels, secondaryLabels)
            }
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

    private fun findBottomMostTextKey(view: View, label: String): View? {
        val matches = mutableListOf<View>()
        collectMatchingTextKeys(view, label, matches)
        return matches.maxByOrNull(::topInRoot)
    }

    private fun collectMatchingTextKeys(view: View, label: String, matches: MutableList<View>) {
        if (view is TextKeyView && view.mainText.text.toString() == label) {
            matches += view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectMatchingTextKeys(view.getChildAt(index), label, matches)
            }
        }
    }

    private fun findRecyclerView(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findRecyclerView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun leftInRoot(view: View): Int = generateSequence(view) { it.parent as? View }.sumOf { it.left }

    private fun topInRoot(view: View): Int = generateSequence(view) { it.parent as? View }.sumOf { it.top }

    private fun bottomInRoot(view: View): Int = topInRoot(view) + view.height
}

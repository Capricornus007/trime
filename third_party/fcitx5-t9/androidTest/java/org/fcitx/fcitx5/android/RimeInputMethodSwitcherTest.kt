/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.RimeInputMethodEntries
import org.fcitx.fcitx5.android.core.RimeInputMethodSwitcher
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.dialog.InputMethodData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class RimeInputMethodSwitcherTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            FcitxTestEnvironment.setup()
        }
    }

    @Test
    fun enumerateIntoT9AndRestorePreviousSchema() = runBlocking {
        val prefs = AppPrefs.getInstance().internal
        prefs.rimeInputMethodEnabled.setValue(true)
        prefs.rimeT9InputMethodEnabled.setValue(true)
        prefs.rimePreviousNonT9SchemaId.setValue("")

        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.fcitx.setEnabledIme(arrayOf("rime"))
        FcitxTestEnvironment.fcitx.activateIme("rime")
        FcitxTestEnvironment.waitForCurrentIme("rime")
        FcitxTestEnvironment.fcitx.focusOutIn()
        FcitxTestEnvironment.fcitx.focus(true)
        FcitxTestEnvironment.clearEvents()
        FcitxTestEnvironment.setRimeSchema("rime_ice")

        val initialSchema = "rime_ice"
        assertNotEquals(RimeInputMethodEntries.T9Schema, initialSchema)

        val initialState = RimeInputMethodSwitcher.resolveEntries(FcitxTestEnvironment.fcitx)
        assertEquals(listOf("rime", "rime-t9"), initialState.enabled.map { it.uniqueName })
        assertEquals("rime", initialState.currentUniqueName)

        assertTrue(RimeInputMethodSwitcher.enumerate(FcitxTestEnvironment.fcitx))
        FcitxTestEnvironment.waitForCurrentIme("rime")
        FcitxTestEnvironment.waitForRimeSchema(RimeInputMethodEntries.T9Schema)

        val t9State = RimeInputMethodSwitcher.resolveEntries(FcitxTestEnvironment.fcitx)
        assertEquals("rime-t9", t9State.currentUniqueName)

        RimeInputMethodSwitcher.activateSelection(FcitxTestEnvironment.fcitx, RimeInputMethodEntries.Rime)
        FcitxTestEnvironment.waitForCurrentIme("rime")
        FcitxTestEnvironment.waitForRimeSchema(initialSchema)
    }

    @Test
    fun pickerDataIncludesSyntheticT9Entry() = runBlocking {
        val prefs = AppPrefs.getInstance().internal
        prefs.rimeInputMethodEnabled.setValue(true)
        prefs.rimeT9InputMethodEnabled.setValue(true)
        prefs.rimePreviousNonT9SchemaId.setValue("")

        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.fcitx.setEnabledIme(arrayOf("rime"))
        FcitxTestEnvironment.fcitx.activateIme("rime")
        FcitxTestEnvironment.waitForCurrentIme("rime")
        FcitxTestEnvironment.fcitx.focusOutIn()
        FcitxTestEnvironment.fcitx.focus(true)
        FcitxTestEnvironment.clearEvents()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolved = InputMethodData.resolve(FcitxTestEnvironment.fcitx, context)

        assertTrue(resolved.entries.any { !it.ime && it.uniqueName == RimeInputMethodEntries.RimeT9 })
        assertEquals(
            RimeInputMethodEntries.RimeDisplayName,
            resolved.entries.first { !it.ime && it.uniqueName == RimeInputMethodEntries.Rime }.name,
        )
        assertEquals(
            RimeInputMethodEntries.T9DisplayName,
            resolved.entries.first { !it.ime && it.uniqueName == RimeInputMethodEntries.RimeT9 }.name,
        )
        assertEquals("rime", resolved.currentUniqueName)
    }
}

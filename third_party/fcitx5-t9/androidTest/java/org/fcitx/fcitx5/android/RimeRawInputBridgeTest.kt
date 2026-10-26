/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class RimeRawInputBridgeTest {

    companion object {
        private const val REPRESENTATIVE_RAW_INPUT_CHARS = "'adgjmptw"

        @BeforeClass
        @JvmStatic
        fun setup() {
            FcitxTestEnvironment.setup()
        }
    }

    @Test
    fun replaceInputRangeUpdatesCompositionCandidatesAndMixedRawInput() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("94126", intervalMs = 100)
        val beforeInput = FcitxTestEnvironment.waitForRawInput { it.isNotBlank() }
        val beforeCaret = FcitxTestEnvironment.fcitx.readCaret()
        val beforePreedit = FcitxTestEnvironment.waitForInputPanelPreedit { it.isNotBlank() }
        val beforeCandidates = FcitxTestEnvironment.waitForPagedCandidateSnapshot {
            it.candidates.isNotEmpty()
        }

        assertTrue(beforeInput.isNotBlank())
        assertTrue(beforeCaret in 0..beforeInput.length)
        assertTrue(beforeCandidates.candidates.isNotEmpty())

        val replacement = "ni"
        val replaceStart = beforeInput.length / 2
        val replaceEnd = (replaceStart + 1).coerceAtMost(beforeInput.length)

        FcitxTestEnvironment.fcitx.replaceInputRange(replaceStart, replaceEnd, replacement)

        val expectedInput = buildString {
            append(beforeInput.substring(0, replaceStart))
            append(replacement)
            append(beforeInput.substring(replaceEnd))
        }
        val afterInput = FcitxTestEnvironment.waitForRawInput { it == expectedInput }
        val afterCaret = FcitxTestEnvironment.fcitx.readCaret()
        val afterPreedit = FcitxTestEnvironment.waitForInputPanelPreedit {
            it.isNotBlank() && it != beforePreedit
        }
        val afterCandidates = FcitxTestEnvironment.waitForPagedCandidateSnapshot {
            it.candidates.isNotEmpty() && it.candidates != beforeCandidates.candidates
        }

        assertEquals(expectedInput, afterInput)
        assertEquals(replaceStart + replacement.length, afterCaret)
        assertTrue(afterPreedit.isNotBlank())
        assertNotEquals(beforeCandidates.candidates, afterCandidates.candidates)
        assertTrue(afterInput.all { it in REPRESENTATIVE_RAW_INPUT_CHARS || it in 'a'..'z' })
        assertTrue(afterInput.any { it in REPRESENTATIVE_RAW_INPUT_CHARS })
        assertTrue(afterInput.any { it == 'n' || it == 'i' })
        assertTrue(afterInput.any { it !in REPRESENTATIVE_RAW_INPUT_CHARS && it in 'a'..'z' })

        FcitxTestEnvironment.fcitx.reset()
        assertFalse(FcitxTestEnvironment.fcitx.readInput().isNotBlank())
    }
}

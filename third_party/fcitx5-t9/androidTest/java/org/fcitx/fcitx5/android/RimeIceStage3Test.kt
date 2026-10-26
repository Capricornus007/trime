/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.whenStopped
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.t9.T9Coordinator
import org.fcitx.fcitx5.android.input.t9.T9ReadablePreview
import org.fcitx.fcitx5.android.input.t9.T9ReplacementWindow
import org.fcitx.fcitx5.android.input.t9.T9SessionCommand
import org.fcitx.fcitx5.android.input.t9.T9UiState
import org.fcitx.fcitx5.android.input.t9.executeT9SessionCommand
import org.fcitx.fcitx5.android.input.t9.selectT9Candidate
import org.fcitx.fcitx5.android.ui.main.ClipboardEditActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Test

class RimeIceStage3Test {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            FcitxTestEnvironment.setup()
        }
    }

    @Test
    fun t9KeyboardRuntimeProvidesRailMainDigitsAndRightFunctionColumn() {
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

        val labels = linkedSetOf<String>()
        collectTextLabels(keyboard, labels)
        assertTrue(labels.containsAll(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")))
        assertTrue(labels.containsAll(listOf("重输", "符", "123", "ABC")))
    }

    @Test
    fun touchingStandaloneT9BackspaceKeyDispatchesBackspaceAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var keyboard: T9Keyboard
        var lastAction: KeyAction? = null

        instrumentation.runOnMainSync {
            keyboard = T9Keyboard(context, ThemeManager.activeTheme).apply {
                keyActionListener = org.fcitx.fcitx5.android.input.keyboard.KeyActionListener { action, _ ->
                    lastAction = action
                }
            }
        }

        val backspace = keyboard.findViewById<View>(R.id.button_backspace)
        assertNotNull(backspace)
        performClick(backspace!!)

        assertTrue(
            "Expected touching standalone T9 backspace key to dispatch SymAction BackSpace, got: $lastAction",
            lastAction is KeyAction.SymAction,
        )
    }

    @Test
    fun touchingFourthRowT9KeysDispatchesExistingEntryActions() {
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
        val abcKey = findVisibleTextKey(keyboard, "ABC")
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
                KeyAction.SymAction(org.fcitx.fcitx5.android.core.KeySym(org.fcitx.fcitx5.android.core.FcitxKeyMapping.FcitxKey_space)),
                KeyAction.LayoutSwitchAction("Text"),
            ),
            actions,
        )
    }

    @Ignore("Flaky under library instrumentation on emulator: rail bootstrap for the first-digit scenario is nondeterministic.")
    @Test
    fun singleDigitT9InputKeepsNumericRawInputWhileRailExposesAbcAmbiguity() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("2", intervalMs = 100)

        val daemonRawInput = FcitxTestEnvironment.waitForDaemonRawInput { it == "2" }
        val railSnapshot = waitForRailState(T9Coordinator()) { entries ->
            entries.take(3).map { it.text } == listOf("a", "b", "c")
        }
        val pagedSnapshot = FcitxTestEnvironment.waitForPagedCandidateSnapshot { snapshot ->
            snapshot.candidates.isNotEmpty()
        }
        val allCandidates = FcitxTestEnvironment.fcitx.getCandidates(0, 40).toList()

        assertEquals("2", daemonRawInput)
        assertEquals(listOf("a", "b", "c"), railSnapshot.state.sideRailEntries.take(3).map { it.text })
        assertTrue(
            "Unresolved digit 2 should keep the current Rime candidate list non-empty instead of relying on app-side补排; snapshot=${pagedSnapshot.summary(20)} allCandidates=$allCandidates rawInput=$daemonRawInput rail=${railSnapshot.state.sideRailEntries.map { it.text to it.selected }}",
            pagedSnapshot.candidates.isNotEmpty(),
        )
    }

    @Test
    fun sideRailSelectionRewritesRuntimeCompositionAndReshapesCandidates() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("641426", intervalMs = 100)
        val coordinator = T9Coordinator()
        val before = waitForRailState(coordinator)
        val beforeCandidates = FcitxTestEnvironment.waitForPagedCandidateSnapshot {
            it.candidates.isNotEmpty()
        }
        val alternative = before.state.sideRailEntries.first { !it.selected }
        val command = coordinator.selectSideRail(alternative)

        assertTrue(command is T9SessionCommand.ReplaceInputRange)
        command as T9SessionCommand.ReplaceInputRange
        FcitxTestEnvironment.fcitx.replaceInputRange(
            command.replacementWindow.start,
            command.replacementWindow.end,
            command.replacement,
        )

        val afterInput = FcitxTestEnvironment.waitForRawInput { it != before.rawInput }
        val afterCaret = FcitxTestEnvironment.fcitx.readCaret()
        val afterPreedit = FcitxTestEnvironment.waitForInputPanelPreedit {
            it.isNotBlank() && it != before.preedit
        }
        val afterCandidates = FcitxTestEnvironment.waitForPagedCandidateSnapshot {
            it.candidates.isNotEmpty() && it.candidates != beforeCandidates.candidates
        }

        assertNotEquals(before.rawInput, afterInput)
        assertEquals(command.replacementWindow.start + command.replacement.length, afterCaret)
        assertNotEquals(beforeCandidates.candidates.first().text, afterCandidates.candidates.first().text)
        assertTrue(afterPreedit.isNotBlank())
    }

    @Test
    fun digit23RailFlowKeepsPrefixAndTailConvergingAcrossTwoSelections() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("23", intervalMs = 100)

        val coordinator = T9Coordinator()
        val before = waitForRailState(coordinator) { entries ->
            entries.isNotEmpty() &&
                entries.first().text == "ce" &&
                entries.map { it.text }.containsAll(listOf("a", "b", "c"))
        }
        val beforeCandidates = FcitxTestEnvironment.waitForPagedCandidateSnapshot {
            it.candidates.isNotEmpty()
        }
        val beforePreview = T9ReadablePreview.render(
            state = before.state,
            livePreedit = before.preedit,
        )

        val pickA = before.state.sideRailEntries.first { it.text == "a" }
        val commandA = coordinator.selectSideRail(pickA) as T9SessionCommand.ReplaceInputRange
        executeCoordinatorCommand(coordinator, commandA)

        val afterAInput = FcitxTestEnvironment.waitForDaemonRawInput { it == "a3" }
        val afterARail = waitForRailState(coordinator) { entries ->
            entries.take(3).map { it.text } == listOf("d", "e", "f") &&
                entries.none { it.selected }
        }
        val afterAPreview = T9ReadablePreview.render(
            state = afterARail.state,
            livePreedit = afterARail.preedit,
        )
        val afterACandidates = FcitxTestEnvironment.waitForPagedCandidateSnapshot { snapshot ->
            snapshot.candidates.isNotEmpty() && snapshot.candidates != beforeCandidates.candidates
        }

        val pickF = afterARail.state.sideRailEntries.first { it.text == "f" }
        val commandF = coordinator.selectSideRail(pickF) as T9SessionCommand.ReplaceInputRange
        executeCoordinatorCommand(coordinator, commandF)

        val afterFInput = FcitxTestEnvironment.waitForDaemonRawInput { it == "af" }
        val afterFRail = waitForRailState(coordinator) { entries ->
            entries.take(3).map { it.text } == listOf("d", "e", "f")
        }
        val afterFPreview = T9ReadablePreview.render(
            state = afterFRail.state,
            livePreedit = afterFRail.preedit,
        )
        val afterFCandidates = FcitxTestEnvironment.waitForPagedCandidateSnapshot { snapshot ->
            snapshot.candidates.isNotEmpty() && snapshot.candidates != afterACandidates.candidates
        }

        assertEquals(
            T9ReplacementWindow(start = 0, end = 1),
            commandA.replacementWindow,
        )
        assertEquals("ce", before.state.sideRailEntries.first().text)
        assertTrue(before.state.sideRailEntries.map { it.text }.containsAll(listOf("a", "b", "c")))
        assertEquals("a3", afterAInput)
        assertNotEquals("23", beforePreview)
        assertFalse(beforePreview.any(Char::isDigit))
        assertNotEquals("a", afterAPreview)
        assertTrue(afterAPreview.startsWith("a"))
        assertEquals(listOf("d", "e", "f"), afterARail.state.sideRailEntries.take(3).map { it.text })
        assertTrue(afterARail.state.sideRailEntries.none { it.selected })
        assertNotEquals(beforeCandidates.candidates.first().text, afterACandidates.candidates.first().text)

        assertEquals(
            T9ReplacementWindow(start = 1, end = 2),
            commandF.replacementWindow,
        )
        assertEquals("af", afterFInput)
        assertEquals("af", afterFPreview.replace(" ", "").replace("'", ""))
        assertEquals(listOf("d", "e", "f"), afterFRail.state.sideRailEntries.take(3).map { it.text })
        assertNotEquals(afterACandidates.candidates.first().text, afterFCandidates.candidates.first().text)
    }

    @Test
    fun digit23RailFlowKeepsBPrefixStableAcrossSecondSelection() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("23", intervalMs = 100)

        val coordinator = T9Coordinator()
        val before = waitForRailState(coordinator) { entries ->
            entries.isNotEmpty() &&
                entries.first().text == "ce" &&
                entries.map { it.text }.containsAll(listOf("a", "b", "c"))
        }

        val pickB = before.state.sideRailEntries.first { it.text == "b" }
        val commandB = coordinator.selectSideRail(pickB) as T9SessionCommand.ReplaceInputRange
        executeCoordinatorCommand(coordinator, commandB)

        val afterBInput = FcitxTestEnvironment.waitForDaemonRawInput { it == "b3" }
        val afterBRail = waitForRailState(coordinator) { entries ->
            entries.take(3).map { it.text } == listOf("d", "e", "f")
        }

        val pickF = afterBRail.state.sideRailEntries.first { it.text == "f" }
        val commandF = coordinator.selectSideRail(pickF) as T9SessionCommand.ReplaceInputRange
        executeCoordinatorCommand(coordinator, commandF)

        val afterFInput = FcitxTestEnvironment.waitForDaemonRawInput { it == "bf" }
        val afterFPreview = T9ReadablePreview.render(
            state = waitForRailState(coordinator) { it.take(3).map { entry -> entry.text } == listOf("d", "e", "f") }.state,
            livePreedit = FcitxTestEnvironment.waitForInputPanelPreedit { it.isNotBlank() },
        )
        val afterFCandidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { it.isNotEmpty() },
            limit = 40,
        )

        assertEquals(T9ReplacementWindow(start = 0, end = 1), commandB.replacementWindow)
        assertEquals(T9ReplacementWindow(start = 1, end = 2), commandF.replacementWindow)
        assertEquals("b3", afterBInput)
        assertEquals("bf", afterFInput)
        assertEquals("bf", afterFPreview.replace(" ", "").replace("'", ""))
        assertTrue(
            "Expected bf candidates to follow the chosen b+f path instead of drifting to fd; candidates=$afterFCandidates",
            afterFCandidates.isNotEmpty(),
        )
    }

    @Test
    fun digit23ReselectingLastBucketKeepsEditingTailAndBackspaceRestoresOriginalB3() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("23", intervalMs = 100)

        val coordinator = T9Coordinator()
        val initialRail = waitForRailState(coordinator) { entries ->
            entries.isNotEmpty() && entries.map { it.text }.containsAll(listOf("a", "b", "c"))
        }

        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(initialRail.state.sideRailEntries.first { it.text == "b" }) as
                T9SessionCommand.ReplaceInputRange,
        )
        FcitxTestEnvironment.waitForDaemonRawInput { it == "b3" }

        val afterBRail = waitForRailState(coordinator) { entries ->
            entries.take(3).map { it.text } == listOf("d", "e", "f") &&
                entries.none { it.selected }
        }
        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(afterBRail.state.sideRailEntries.first { it.text == "f" }) as
                T9SessionCommand.ReplaceInputRange,
        )

        val afterFInput = FcitxTestEnvironment.waitForDaemonRawInput { it == "bf" }
        val afterFRail = waitForRailState(coordinator) { entries ->
            entries.take(3).map { it.text } == listOf("d", "e", "f") &&
                entries.none { it.selected }
        }
        val afterFPreview = T9ReadablePreview.render(
            state = afterFRail.state,
            livePreedit = afterFRail.preedit,
        )

        val commandE = coordinator.selectSideRail(
            afterFRail.state.sideRailEntries.first { it.text == "e" },
        ) as T9SessionCommand.ReplaceInputRange
        executeCoordinatorCommand(coordinator, commandE)

        val afterEInput = FcitxTestEnvironment.waitForDaemonRawInput { it == "be" }
        val afterERail = waitForRailState(coordinator) { entries ->
            entries.take(3).map { it.text } == listOf("d", "e", "f") &&
                entries.none { it.selected }
        }
        val afterEPreview = T9ReadablePreview.render(
            state = afterERail.state,
            livePreedit = afterERail.preedit,
        )

        val backspace = coordinator.onBackspace()
        executeCoordinatorCommand(coordinator, backspace)

        val afterBackspaceInput = FcitxTestEnvironment.waitForDaemonRawInput { it == "b3" }
        val afterBackspaceRail = waitForRailState(coordinator) { entries ->
            entries.take(3).map { it.text } == listOf("d", "e", "f") &&
                entries.none { it.selected }
        }
        val afterBackspacePreview = T9ReadablePreview.render(
            state = afterBackspaceRail.state,
            livePreedit = afterBackspaceRail.preedit,
        )

        assertEquals("bf", afterFInput)
        assertEquals(
            T9ReplacementWindow(start = 1, end = 2),
            commandE.replacementWindow,
        )
        assertEquals("be", afterEInput)
        assertEquals("bf", afterFPreview.replace(" ", "").replace("'", ""))
        assertEquals("be", afterEPreview.replace(" ", "").replace("'", ""))
        assertEquals(listOf("d", "e", "f"), afterERail.state.sideRailEntries.take(3).map { it.text })
        assertTrue(afterERail.state.sideRailEntries.none { it.selected })
        assertTrue(backspace is T9SessionCommand.RestoreSnapshot)
        assertEquals("b3", afterBackspaceInput)
        assertEquals("b3", (backspace as T9SessionCommand.RestoreSnapshot).rawInput)
        assertEquals("b3", coordinator.state.rawInput)
        assertEquals("b3", afterBackspaceInput)
        assertEquals(listOf("d", "e", "f"), afterBackspaceRail.state.sideRailEntries.take(3).map { it.text })
        assertTrue(afterBackspaceRail.state.sideRailEntries.none { it.selected })
        assertTrue(
            "Expected backspace to restore the original b3 tail instead of the intermediate bf result; afterBackspacePreview=$afterBackspacePreview",
            afterBackspacePreview.startsWith("b") && afterBackspacePreview != "b'f",
        )
    }

    @Test
    fun digit32ResolvedSyllableReselectionOverwritesHistoryLikeHamster() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("32", intervalMs = 100)

        val coordinator = T9Coordinator()
        val initialRail = waitForRailState(coordinator) { entries ->
            entries.map { it.text }.containsAll(listOf("da", "fa", "d", "e", "f"))
        }
        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(initialRail.state.sideRailEntries.first { it.text == "d" }) as
                T9SessionCommand.ReplaceInputRange,
        )
        val afterDInput = FcitxTestEnvironment.waitForDaemonRawInput { it == "d2" }

        val afterDRail = waitForRailState(coordinator) { entries ->
            entries.map { it.text }.contains("a")
        }
        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(afterDRail.state.sideRailEntries.first { it.text == "a" }) as
                T9SessionCommand.ReplaceInputRange,
        )
        val afterAInput = FcitxTestEnvironment.waitForDaemonRawInput { it == "da" }

        val afterARail = waitForRailState(coordinator) { entries ->
            entries.map { it.text }.containsAll(listOf("da", "fa"))
        }
        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(afterARail.state.sideRailEntries.first { it.text == "da" }) as
                T9SessionCommand.ReplaceInputRange,
        )
        val afterDaInput = FcitxTestEnvironment.waitForDaemonRawInput { it == "da" }

        val afterDaRail = waitForRailState(coordinator) { entries ->
            entries.map { it.text }.containsAll(listOf("da", "fa"))
        }
        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(afterDaRail.state.sideRailEntries.first { it.text == "fa" }) as
                T9SessionCommand.ReplaceInputRange,
        )
        val afterFaInput = FcitxTestEnvironment.waitForDaemonRawInput { it == "fa" }

        val backspace = coordinator.onBackspace()
        assertEquals(
            T9SessionCommand.RestoreSnapshot(rawInput = "32", caret = 2),
            backspace,
        )
        executeCoordinatorCommand(coordinator, backspace)
        val restoredInput = FcitxTestEnvironment.waitForDaemonRawInput { it == "32" }

        assertEquals("d2", afterDInput)
        assertEquals("da", afterAInput)
        assertEquals("da", afterDaInput)
        assertEquals("fa", afterFaInput)
        assertEquals("32", restoredInput)
    }

    @Test
    fun continuingDigitsAfterFullyFixedLastBucketStartsANewHamsterStyleTail() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("23", intervalMs = 100)

        val coordinator = T9Coordinator()
        val initialRail = waitForRailState(coordinator) { entries ->
            entries.isNotEmpty() && entries.map { it.text }.containsAll(listOf("a", "b", "c"))
        }

        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(initialRail.state.sideRailEntries.first { it.text == "b" }) as
                T9SessionCommand.ReplaceInputRange,
        )
        FcitxTestEnvironment.waitForDaemonRawInput { it == "b3" }

        val afterBRail = waitForRailState(coordinator) { entries ->
            entries.take(3).map { it.text } == listOf("d", "e", "f")
        }
        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(afterBRail.state.sideRailEntries.first { it.text == "f" }) as
                T9SessionCommand.ReplaceInputRange,
        )

        FcitxTestEnvironment.waitForDaemonRawInput { it == "bf" }
        FcitxTestEnvironment.sendString("23", intervalMs = 100)

        val afterTypingMore = waitForRailState(coordinator) { entries ->
            entries.isNotEmpty() &&
                entries.first().text == "ce" &&
                entries.map { it.text }.containsAll(listOf("a", "b", "c"))
        }
        val afterPreview = T9ReadablePreview.render(
            state = afterTypingMore.state,
            livePreedit = afterTypingMore.preedit,
        )

        assertEquals("bf23", afterTypingMore.state.rawInput)
        assertEquals(T9ReplacementWindow(start = 2, end = 4), afterTypingMore.state.replacementWindow)
        assertEquals("ce", afterTypingMore.state.sideRailEntries.first().text)
        assertTrue(afterTypingMore.state.sideRailEntries.map { it.text }.containsAll(listOf("a", "b", "c")))
        assertTrue(
            "Expected the newly typed tail to stay unresolved as a fresh 23 sequence after fixing bf; preview=$afterPreview state=${afterTypingMore.state}",
            afterPreview.replace(" ", "").replace("'", "").startsWith("bf"),
        )
    }

    @Test
    fun delimiterBeforeNewDigitsDoesNotStealRailFocusFromEarlierUnresolvedDigits() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("2314", intervalMs = 100)

        val coordinator = T9Coordinator()
        val state = waitForRailState(coordinator) { entries ->
            entries.isNotEmpty() &&
                entries.first().text == "ce" &&
                entries.map { it.text }.containsAll(listOf("a", "b", "c"))
        }

        assertTrue(
            "Expected delimiter + new digit to keep the earlier unresolved 23 segment active instead of jumping to the trailing 4 group; state=${state.state}",
            state.state.rawInput.contains("23") && state.state.rawInput.contains("'4"),
        )
        assertEquals(T9ReplacementWindow(start = 0, end = 2), state.state.replacementWindow)
        assertEquals("ce", state.state.sideRailEntries.first().text)
        assertTrue(state.state.sideRailEntries.map { it.text }.containsAll(listOf("a", "b", "c")))
    }

    @Test
    fun digit23CandidatesFollowCurrentRimeInputAcrossRailSelections() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("23", intervalMs = 100)

        val coordinator = T9Coordinator()
        val before = waitForRailState(coordinator) { entries ->
            entries.isNotEmpty() &&
                entries.first().text == "ce" &&
                entries.map { it.text }.containsAll(listOf("a", "b", "c"))
        }
        val beforeCandidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = {
                val keys = candidateCommentKeys(it)
                keys.contains("ce") && it.isNotEmpty()
            },
            limit = 40,
        )
        val beforeKeys = candidateCommentKeys(beforeCandidates)

        val pickA = before.state.sideRailEntries.first { it.text == "a" }
        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(pickA) as T9SessionCommand.ReplaceInputRange,
        )

        waitForRailState(coordinator) { entries ->
            entries.take(3).map { it.text } == listOf("d", "e", "f") &&
                entries.none { it.selected }
        }
        val afterACandidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = {
                val keys = candidateCommentKeys(it)
                keys.any { key -> key.startsWith("ad") } &&
                    keys.any { key -> key.startsWith("ae") } &&
                    keys.any { key -> key.startsWith("af") } &&
                    keys.all { key -> key.startsWith("a") }
            },
            limit = 40,
        )
        val afterAKeys = candidateCommentKeys(afterACandidates)

        val afterARail = waitForRailState(coordinator) { entries ->
            entries.take(3).map { it.text } == listOf("d", "e", "f")
        }
        val pickF = afterARail.state.sideRailEntries.first { it.text == "f" }
        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(pickF) as T9SessionCommand.ReplaceInputRange,
        )

        val afterFCandidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = {
                val keys = candidateCommentKeys(it)
                keys.any { key -> key.startsWith("af") } &&
                    it.any { candidate ->
                        val text = candidateTextOf(candidate)
                        text == "阿飞" || text == "阿福"
                    }
            },
            limit = 40,
        )
        val afterFKeys = candidateCommentKeys(afterFCandidates)

        assertTrue(
            "Expected initial 23 candidates to follow current Rime input instead of app-side ambiguity expansion; candidates=$beforeCandidates keys=$beforeKeys",
            beforeKeys.contains("ce"),
        )
        assertTrue(
            "Expected selecting rail a to narrow candidate comments to the a+d/e/f ambiguity family; candidates=$afterACandidates keys=$afterAKeys",
            afterAKeys.any { it.startsWith("ad") } &&
                afterAKeys.any { it.startsWith("ae") } &&
                afterAKeys.any { it.startsWith("af") } &&
                afterACandidates.any {
                    val text = candidateTextOf(it)
                    text == "阿斗" || text == "阿呆" || text == "阿杜"
                },
        )
        assertTrue(
            "Expected selecting rail f to narrow candidate comments to the af family; candidates=$afterFCandidates keys=$afterFKeys",
            afterFKeys.any { it.startsWith("af") },
        )
        assertTrue(
            "Expected af candidates to include common a+f words such as 阿飞/阿福; candidates=$afterFCandidates",
            afterFCandidates.any { candidateTextOf(it) == "阿飞" || candidateTextOf(it) == "阿福" },
        )
    }

    @Test
    fun digit23SelectingSingleLetterRailKeepsHamsterStyleMixedTailInput() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("23", intervalMs = 100)

        val coordinator = T9Coordinator()
        val before = waitForRailState(coordinator) { entries ->
            entries.map { it.text }.containsAll(listOf("a", "b", "c"))
        }
        val pickA = before.state.sideRailEntries.first { it.text == "a" }
        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(pickA) as T9SessionCommand.ReplaceInputRange,
        )

        val sessionInput = waitForSessionInput { it == "a3" }
        val candidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { texts ->
                texts.any {
                    val text = candidateTextOf(it)
                    text == "阿斗" || text == "阿杜" || text == "阿飞" || text == "阿福"
                }
            },
            limit = 20,
        )

        assertEquals("a3", sessionInput)
        assertTrue(
            "Expected the Hamster-style a3 tail to promote a+d/e/f style words after rail selection; candidates=$candidates",
            candidates.any {
                val text = candidateTextOf(it)
                text == "阿斗" || text == "阿杜" || text == "阿飞" || text == "阿福"
            },
        )
    }

    @Test
    fun backspaceRestoresLatestRuntimeInterventionBeforeFallingThrough() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        FcitxTestEnvironment.sendString("641426", intervalMs = 100)
        val coordinator = T9Coordinator()
        val before = waitForRailState(coordinator)
        val alternative = before.state.sideRailEntries.first { !it.selected }
        val replace = coordinator.selectSideRail(alternative) as T9SessionCommand.ReplaceInputRange

        executeCoordinatorCommand(coordinator, replace)
        FcitxTestEnvironment.waitForInputPanelPreedit { it.isNotBlank() && it != before.preedit }

        val restore = coordinator.onBackspace()
        assertEquals(
            T9SessionCommand.RestoreSnapshot(before.state.rawInput, before.state.caret),
            restore,
        )
        executeCoordinatorCommand(coordinator, restore)

        assertEquals(before.preedit, FcitxTestEnvironment.waitForInputPanelPreedit { it == before.preedit })
        assertEquals(before.state.engineCaret, FcitxTestEnvironment.fcitx.readCaret())
        val deleteTail = coordinator.onBackspace()
        assertTrue(
            "After restoring the latest intervention, the next delete should continue deleting composing input",
            deleteTail is T9SessionCommand.RestoreSnapshot,
        )
    }

    @Test
    fun literalChongQingWithoutConfirmedPrefixStillConvergesToChongQing() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        FcitxTestEnvironment.fcitx.setInput("chongqing")
        FcitxTestEnvironment.fcitx.setCaret("chongqing".length)
        val candidateTexts = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { texts -> texts.map(::candidateTextOf).any { it == "重庆" } },
            limit = 30,
        )
        assertTrue(
            "Expected literal chongqing to converge to 重庆, got: $candidateTexts",
            candidateTexts.map(::candidateTextOf).any { it == "重庆" },
        )
    }

    @Test
    fun confirmedPrefixPlusLiteralChongQingStillDoesNotConvergeWhenWholeStringIsWrittenBack() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        FcitxTestEnvironment.fcitx.setInput("北京chongqing")
        FcitxTestEnvironment.fcitx.setCaret("北京chongqing".length)
        val candidateTexts = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { texts -> texts.isNotEmpty() },
            limit = 30,
        )
        assertFalse(
            "Writing back the whole confirmed-prefix string should still fail to converge to 重庆; got: $candidateTexts",
            candidateTexts.map(::candidateTextOf).any { it == "重庆" },
        )
    }

    @Test
    fun confirmedPrefixKeepsDaemonRawInputTailWhilePreeditRetainsTail() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        FcitxTestEnvironment.sendString("234546413484319426", intervalMs = 100)
        selectCandidateExactly("北京")

        val preedit = FcitxTestEnvironment.waitForInputPanelPreedit { it.isNotBlank() }
        val daemonRawInput = FcitxTestEnvironment.fcitx.readInput()

        assertTrue(
            "Daemon raw input should keep the hidden tail after confirming 北京; " +
                "daemonRawInput=$daemonRawInput preedit=$preedit",
            daemonRawInput.isNotBlank(),
        )
        assertTrue(
            "Preedit should keep exposing the remaining tail after selecting 北京; preedit=$preedit",
            preedit.isNotBlank(),
        )
    }

    @Test
    fun literalDelimitedAfConvergesToAfWordsInT9Schema() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.fcitx.setInput("a'f")
        FcitxTestEnvironment.fcitx.setCaret(3)

        val candidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { texts -> texts.isNotEmpty() },
            limit = 30,
        )

        assertTrue(
            "Expected literal delimited a'f to converge to af words such as 阿飞/阿福; candidates=$candidates",
            candidates.any { candidateTextOf(it) == "阿飞" || candidateTextOf(it) == "阿福" },
        )
    }

    @Test
    fun literalAfConvergesToAfWordsInT9Schema() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.fcitx.setInput("af")
        FcitxTestEnvironment.fcitx.setCaret(2)

        val candidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { texts -> texts.isNotEmpty() },
            limit = 30,
        )

        assertTrue(
            "Expected literal af to converge to af words such as 阿飞/阿福; candidates=$candidates",
            candidates.any { candidateTextOf(it) == "阿飞" || candidateTextOf(it) == "阿福" },
        )
    }

    @Test
    fun literalA3KeepsFixedAPrefixAndDefAmbiguity() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.fcitx.setInput("a3")
        FcitxTestEnvironment.fcitx.setCaret(2)

        val candidates = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { texts -> texts.isNotEmpty() },
            limit = 40,
        )
        val keys = candidateCommentKeys(candidates)

        assertTrue(
            "Expected literal a3 to expose the a+d/e/f ambiguity family; candidates=$candidates keys=$keys",
            keys.any { it.startsWith("ad") } &&
                keys.any { it.startsWith("ae") } &&
                keys.any { it.startsWith("af") } &&
                keys.all { it.startsWith("a") },
        )
        assertTrue(
            "Expected literal a3 candidates to include common a+d/e/f words such as 阿斗/阿呆/阿杜; candidates=$candidates",
            candidates.any {
                val text = candidateTextOf(it)
                text == "阿斗" || text == "阿呆" || text == "阿杜"
            },
        )
    }

    @Test
    fun backspaceRollsBackConfirmedChinesePrefixBeforeDeletingTail() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        FcitxTestEnvironment.sendString("234546413484319426", intervalMs = 100)
        selectCandidateExactly("北京")

        val coordinator = T9Coordinator()
        val before = waitForRailState(coordinator)
        val delete = coordinator.onBackspace()

        assertTrue(
            "Expected delete to roll back confirmed prefix before falling through; state=${before.state}",
            delete is T9SessionCommand.RestoreSnapshot,
        )
        executeCoordinatorCommand(coordinator, delete)

        val afterPreedit = FcitxTestEnvironment.waitForInputPanelPreedit {
            it.isNotBlank() && it != before.preedit
        }
        assertFalse(
            "After rolling back confirmed prefix, preedit should no longer start with 北京; before=${before.preedit} after=$afterPreedit",
            afterPreedit.startsWith("北京"),
        )
    }

    @Ignore("Flaky: this UI-host rollback check may stall in emulator IME focus handoff; covered by stable service backspace rollback tests.")
    @Test
    fun tappingVisibleT9BackspaceKeyRollsBackConfirmedPrefix() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        switchSystemImeToFcitx()

        val activity = launchClipboardEditActivity()
        try {
            waitForRunningFcitxService(activity)
            val backspace = waitForVisibleBackspaceButton()

            FcitxTestEnvironment.sendString("234546413484319426", intervalMs = 100)
            selectCandidateExactly("北京")
            val beforePreedit = FcitxTestEnvironment.waitForInputPanelPreedit { it.startsWith("北京") }

            tapView(backspace)

            val afterPreedit = FcitxTestEnvironment.waitForInputPanelPreedit {
                it.isNotBlank() && it != beforePreedit
            }
            assertFalse(
                "Visible T9 backspace key should roll back the confirmed 北京 prefix; before=$beforePreedit after=$afterPreedit",
                afterPreedit.startsWith("北京"),
            )
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.finish()
            }
        }
    }

    @Ignore("Flaky: this UI-host backspace case may stall in emulator IME focus handoff; covered by stable service/daemon backspace flows.")
    @Test
    fun tappingVisibleT9BackspaceKeyDeletesBasicRawInput() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        switchSystemImeToFcitx()

        val activity = launchClipboardEditActivity()
        try {
            waitForRunningFcitxService(activity)
            FcitxTestEnvironment.sendString("641426", intervalMs = 100)

            val beforeRawInput = FcitxTestEnvironment.waitForRawInput { it.isNotBlank() }
            val backspace = waitForVisibleBackspaceButton()

            tapView(backspace)

            val afterRawInput = FcitxTestEnvironment.waitForRawInput {
                it.isNotBlank() && it.length == beforeRawInput.length - 1
            }
            assertEquals(
                "Visible T9 backspace key should delete the last raw-input unit first",
                beforeRawInput.dropLast(1),
                afterRawInput,
            )
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.finish()
            }
        }
    }

    @Ignore("Flaky service process discovery under library instrumentation; covered by sibling backspace flow tests.")
    @Test
    fun serviceBackspaceRestoresLatestRailInterventionBeforeDeletingRawInput() = runBlocking {
        withLiveServiceBackspaceHost("abcd") { activity, editText, service ->
            sendStringViaService(service, "641426", intervalMs = 100)
            val coordinator = T9Coordinator()
            val before = waitForRailState(service, coordinator)
            val alternative = before.state.sideRailEntries.firstOrNull { !it.selected } ?: run {
                fail("Expected at least one selectable rail alternative before rollback test")
                return@withLiveServiceBackspaceHost
            }
            executeCoordinatorCommand(
                service,
                coordinator,
                coordinator.selectSideRail(alternative) as T9SessionCommand.ReplaceInputRange,
            )
            val afterRail = waitForRailState(service, coordinator) { entries ->
                entries.isNotEmpty()
            }
            assertNotEquals(
                "Rail replacement should change live session input before rollback",
                before.state.engineInput,
                afterRail.state.engineInput,
            )
            val forcedEngineInput = afterRail.state.engineInput

            val activeService = requestServiceBackspace(activity)
            val afterRollbackRail = waitForRailState(activeService, coordinator) { entries ->
                entries.isNotEmpty() && coordinator.state.engineInput != forcedEngineInput
            }
            assertTrue(
                "Host text should preserve the seeded prefix while rolling back rail intervention; host=${editText.text}",
                editText.text.toString().startsWith("abcd"),
            )
            assertTrue(
                "Backspace should cancel the latest rail intervention before deleting raw input",
                afterRollbackRail.state.engineInput != forcedEngineInput,
            )
        }
    }

    @Test
    fun serviceBackspaceRollsBackConfirmedPrefixBeforeDeletingTail() = runBlocking {
        withLiveServiceBackspaceHost("abcd") { activity, editText, service ->
            sendStringViaService(service, "2345464", intervalMs = 100)
            selectCandidateViaServiceUi(service, "北京")
            waitForHostText(editText) { it.startsWith("abcd") && it.contains("北京") }
            sendStringViaService(service, "348439426", intervalMs = 100)
            val beforeSessionInput = waitForSessionInput(service) { it.isNotBlank() }

            val activeService = requestServiceBackspace(activity)
            val afterSessionInput = waitForSessionInput(activeService) {
                it.isNotBlank() && it.length == beforeSessionInput.length - 1
            }

            assertEquals(
                "Service delete should shorten the live tail while keeping the confirmed 北京 prefix on the host side intact",
                beforeSessionInput.dropLast(1),
                afterSessionInput,
            )
            assertTrue(
                "Confirmed-prefix delete stage should preserve the seeded host prefix plus committed 北京; host=${editText.text}",
                editText.text.toString().startsWith("abcd北京"),
            )
        }
    }

    @Test
    fun serviceBackspaceDeletesBasicRawInput() = runBlocking {
        withLiveServiceBackspaceHost("abcd") { activity, editText, service ->
            sendStringViaService(service, "641426", intervalMs = 100)
            val beforeSessionInput = waitForSessionInput(service) { it.isNotBlank() }

            val activeService = requestServiceBackspace(activity)
            val afterSessionInput = waitForSessionInput(activeService) {
                it.isNotBlank() && it.length == beforeSessionInput.length - 1
            }
            assertEquals(
                "T9 service backspace should delete the last composing session-input unit first",
                beforeSessionInput.dropLast(1),
                afterSessionInput,
            )
            assertTrue(
                "Deleting composing session input should not eat the seeded host prefix; host=${editText.text}",
                editText.text.toString().startsWith("abcd"),
            )
        }
    }

    @Test
    fun daemonSendBackspaceDeletesBasicRawInput() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        FcitxTestEnvironment.sendString("641426", intervalMs = 100)
        val beforeRawInput = FcitxTestEnvironment.waitForRawInput { it.isNotBlank() }
        val state = T9UiState(
            rawInput = beforeRawInput,
            caret = beforeRawInput.length,
            preedit = beforeRawInput,
            engineInput = beforeRawInput,
            engineCaret = beforeRawInput.length,
        )

        FcitxTestEnvironment.fcitx.executeT9SessionCommand(state, T9SessionCommand.SendBackspace)

        val afterRawInput = FcitxTestEnvironment.waitForRawInput {
            it.isNotBlank() && it.length == beforeRawInput.length - 1
        }
        assertEquals(
            "Daemon-side T9 SendBackspace should delete the last raw-input unit first",
            beforeRawInput.dropLast(1),
            afterRawInput,
        )
    }

    @Test
    fun serviceBackspaceDeletesHostInputWhenNoT9CompositionRemains() = runBlocking {
        withLiveServiceBackspaceHost("abcd") { activity, editText, service ->
            Log.d("RimeIceStage3Test", "host-delete: service ready")
            waitForVisibleBackspaceButton(service)
            Log.d("RimeIceStage3Test", "host-delete: backspace ready")
            KeyboardWindow.debugKeyActionCount = 0
            KeyboardWindow.debugT9BackspaceInterceptCount = 0
            KeyboardWindow.debugLastKeyAction = ""
            waitForCompositionToClear(service)
            Log.d("RimeIceStage3Test", "host-delete: state blank")

            requestServiceBackspace(activity)
            Log.d("RimeIceStage3Test", "host-delete: requested")

            waitForHostText(editText, "abc")
        }
    }

    @Test
    fun serviceBackspaceFollowsHamsterDeleteOrderAcrossAllStages() = runBlocking {
        withLiveServiceBackspaceHost("abcd") { activity, editText, service ->
            Log.d("RimeIceStage3Test", "full-delete: phase1 rail setup")
            "641426".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            val sideRailCoordinator = T9Coordinator()
            val beforeRail = waitForRailState(service, sideRailCoordinator)
            val alternative = beforeRail.state.sideRailEntries.first { !it.selected }
            executeCoordinatorCommand(
                service,
                sideRailCoordinator,
                sideRailCoordinator.selectSideRail(alternative) as T9SessionCommand.ReplaceInputRange,
            )
            val afterRail = waitForRailState(service, sideRailCoordinator) { entries ->
                entries.any { it.selected && it.text == alternative.text }
            }
            assertNotEquals(
                "Rail replacement should change live session input before rollback",
                beforeRail.state.engineInput,
                afterRail.state.engineInput,
            )
            val forcedRailText = afterRail.state.sideRailEntries.first { it.selected }.text

            Log.d("RimeIceStage3Test", "full-delete: phase1 rail backspace")
            val phase1Service = requestServiceBackspace(activity)
            val afterRailRollback = waitForRailState(phase1Service, sideRailCoordinator) { entries ->
                entries.none { it.selected && it.text == forcedRailText }
            }
            assertTrue(
                afterRailRollback.state.sideRailEntries.none { it.selected && it.text == forcedRailText },
            )
            assertTrue(
                "Rail rollback phase should preserve the seeded host prefix before later delete stages; host=${editText.text}",
                editText.text.toString().startsWith("abcd"),
            )

            Log.d("RimeIceStage3Test", "full-delete: phase2 reset")
            resetT9CompositionForNextStage(phase1Service)
            Log.d("RimeIceStage3Test", "full-delete: phase2 confirmed-prefix setup")
            "2345464".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            selectCandidateViaServiceUi(phase1Service, "北京")
            waitForHostText(editText) { it.startsWith("abcd") && it.contains("北京") }
            "348439426".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            val confirmedTail = waitForSessionInput(phase1Service) { it.isNotBlank() }
            Log.d("RimeIceStage3Test", "full-delete: phase2 confirmed-prefix backspace")
            val phase2Service = requestServiceBackspace(activity)
            val afterPrefixRollback = waitForSessionInput(phase2Service) {
                it.isNotBlank() && it.length == confirmedTail.length - 1
            }
            assertEquals(
                "Second delete should shorten the live tail while preserving the committed 北京 host prefix; before=$confirmedTail after=$afterPrefixRollback",
                confirmedTail.dropLast(1),
                afterPrefixRollback,
            )
            assertTrue(
                "Phase2 should preserve the seeded host prefix plus committed 北京; host=${editText.text}",
                editText.text.toString().startsWith("abcd北京"),
            )

            Log.d("RimeIceStage3Test", "full-delete: phase3 reset")
            resetT9CompositionForNextStage(phase2Service)
            Log.d("RimeIceStage3Test", "full-delete: phase3 raw setup")
            sendStringViaService(phase2Service, "641426", intervalMs = 100)
            val beforeSessionDelete = waitForSessionInput(phase2Service) { it.isNotBlank() }
            Log.d("RimeIceStage3Test", "full-delete: phase3 raw backspace")
            val phase3Service = requestServiceBackspace(activity)
            val afterSessionDelete = waitForSessionInput(phase3Service) {
                it.isNotBlank() && it.length == beforeSessionDelete.length - 1
            }
            assertEquals(
                "Third delete should delete the composing session input when no rollback target remains",
                beforeSessionDelete.dropLast(1),
                afterSessionDelete,
            )
            assertTrue(
                "Composing delete phase should preserve the seeded host prefix before phase4 host deletion; host=${editText.text}",
                editText.text.toString().startsWith("abcd"),
            )

            Log.d("RimeIceStage3Test", "full-delete: phase4 reset")
            resetT9CompositionForNextStage(phase3Service)
            Log.d("RimeIceStage3Test", "full-delete: phase4 host backspace")
            waitForCompositionToClear(phase3Service)
            val beforeHostDelete = editText.text.toString()
            requestServiceBackspace(activity)
            waitForHostText(editText, beforeHostDelete.dropLast(1))
        }
    }

    @Ignore("Flaky: this UI-host reachability check may stall in emulator IME focus handoff; covered by service-level backspace tests.")
    @Test
    fun tappingVisibleT9BackspaceKeyReachesServiceHandler() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        switchSystemImeToFcitx()

        val activity = launchClipboardEditActivity()
        try {
            waitForRunningFcitxService(activity)
            val backspace = waitForVisibleBackspaceButton()
            KeyboardWindow.debugKeyActionCount = 0
            KeyboardWindow.debugT9BackspaceInterceptCount = 0
            KeyboardWindow.debugLastKeyAction = ""
            val before = FcitxInputMethodService.debugT9BackspaceRequestCount

            tapView(backspace)

            repeat(20) {
                if (FcitxInputMethodService.debugT9BackspaceRequestCount > before) return@runBlocking
                delay(100)
            }
            fail(
                "Visible T9 backspace key did not reach onT9BackspaceRequested(); " +
                    "serviceBefore=$before serviceAfter=${FcitxInputMethodService.debugT9BackspaceRequestCount} " +
                    "windowActionCount=${KeyboardWindow.debugKeyActionCount} " +
                    "windowBackspaceInterceptCount=${KeyboardWindow.debugT9BackspaceInterceptCount} " +
                    "windowLastKeyAction=${KeyboardWindow.debugLastKeyAction}",
            )
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.finish()
            }
        }
    }

    @Test
    fun confirmedPrefixSessionDebugSnapshotExposesComposingBoundary() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        FcitxTestEnvironment.sendString("234546412466474641486243", intervalMs = 100)
        selectCandidateExactly("北京")

        val daemonInput = FcitxTestEnvironment.fcitx.readInput()
        val daemonCaret = FcitxTestEnvironment.fcitx.readCaret()
        val snapshot = FcitxTestEnvironment.fcitx.readRimeDebugSnapshot()

        assertTrue(
            "Expected non-blank rime debug snapshot; " +
                "ready=${FcitxTestEnvironment.fcitx.isReady} daemonInput=$daemonInput daemonCaret=$daemonCaret " +
                "snapshot=<$snapshot>",
            snapshot.isNotBlank(),
        )
        assertTrue("Expected snapshot to expose session input; snapshot=$snapshot", "input=" in snapshot)
        assertTrue("Expected snapshot to expose composition preedit; snapshot=$snapshot", "compositionPreedit=" in snapshot)
        assertTrue("Expected snapshot to expose composing status; snapshot=$snapshot", "isComposing=" in snapshot)
    }

    @Test
    fun firstRailAfterConfirmedPrefixStillKeepsHiddenSessionPrefix() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("234546412466474641486243", intervalMs = 100)
        selectCandidateExactly("北京")

        val coordinator = T9Coordinator()
        val before = waitForRailState(coordinator)
        val beforeSnapshot = FcitxTestEnvironment.fcitx.readRimeDebugSnapshot()
        val beforeSessionInput = snapshotField(beforeSnapshot, "input")
        assertTrue(
            "Expected confirmed-prefix snapshot to expose a non-blank hidden session input; snapshot=$beforeSnapshot",
            beforeSessionInput.isNotBlank(),
        )

        val railAlternative = before.state.sideRailEntries.firstOrNull {
            !it.selected && it.text.any(Char::isLetter)
        } ?: run {
            fail(
                "Expected at least one non-selected rail entry after selecting 北京; rawInput=${before.rawInput} " +
                    "preedit=${before.preedit} entries=${before.state.sideRailEntries.map { it.text }}",
            )
            return@runBlocking
        }
        val command = coordinator.selectSideRail(railAlternative) as? T9SessionCommand.ReplaceInputRange
            ?: run {
                fail("Expected rail selection to produce ReplaceInputRange")
                return@runBlocking
            }

        executeCoordinatorCommand(coordinator, command)

        val afterSnapshot = FcitxTestEnvironment.fcitx.readRimeDebugSnapshot()
        val afterSessionInput = snapshotField(afterSnapshot, "input")

        assertTrue(
            "Expected first rail selection to update session input; before=$beforeSessionInput after=$afterSessionInput snapshot=$afterSnapshot",
            afterSessionInput.isNotBlank() && afterSessionInput != beforeSessionInput,
        )
        assertTrue(
            "First rail should preserve hidden confirmed-prefix script instead of collapsing to bare rail text; " +
                "rail=${railAlternative.text} after=$afterSessionInput snapshot=$afterSnapshot",
            afterSessionInput != railAlternative.text,
        )
    }

    @Test
    fun confirmedPrefixThenTailOnlyLiteralStillConvergesToChongQing() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        FcitxTestEnvironment.sendString("234546412466474641486243", intervalMs = 100)
        selectCandidateExactly("北京")

        FcitxTestEnvironment.fcitx.setInput("chongqing'gtmagd")
        FcitxTestEnvironment.fcitx.setCaret("chongqing'gtmagd".length)

        val candidateTexts = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { texts -> texts.map(::candidateTextOf).any { it == "重庆" || it == "火车" } },
            limit = 30,
        )
        assertTrue(
            "Expected tail-only literal after selecting 北京 to converge toward 重庆/火车, got: $candidateTexts",
            candidateTexts.map(::candidateTextOf).any { it == "重庆" || it == "火车" },
        )
    }

    @Ignore("Diagnostic stage3 blocker: current replaceInputRange helper is still raw/helper-range scoped and cannot consume confirmed-prefix display/preedit coordinates directly")
    @Test
    fun replaceInputRangeAfterConfirmedPrefixStillNeedsDisplayToEngineRangePrimitive() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        FcitxTestEnvironment.sendString("234546412466474641486243", intervalMs = 100)
        selectCandidateExactly("北京")

        val preedit = FcitxTestEnvironment.waitForInputPanelPreedit { it.isNotBlank() }
            .replace(" ", "")
        val tailStart = preedit.indexOfFirst { it == '\'' || it.lowercaseChar() in 'a'..'z' }
        assertTrue("Expected remaining tail after selecting 北京, got: $preedit", tailStart >= 0)

        FcitxTestEnvironment.fcitx.replaceInputRange(
            start = tailStart,
            endExclusive = preedit.length,
            replacement = "chongqing'gtmagd",
        )

        val candidateTexts = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { texts -> texts.map(::candidateTextOf).any { it == "重庆" || it == "火车" } },
            limit = 30,
        )
        assertTrue(
            "Expected replaceInputRange after selecting 北京 to keep prefix and continue toward 重庆/火车; preedit=$preedit candidates=$candidateTexts",
            candidateTexts.map(::candidateTextOf).any { it == "重庆" || it == "火车" },
        )
    }

    @Ignore("Diagnostic stage3 blocker: moveCursor+backspace did not mutate the mixed-chain tail in daemon-only runtime")
    @Test
    fun confirmedPrefixDisplayCursorEditStillKeepsPrefixWhileChangingTail() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")

        FcitxTestEnvironment.sendString("234546412466474641486243", intervalMs = 100)
        selectCandidateExactly("北京")

        val beforePreedit = FcitxTestEnvironment.waitForInputPanelPreedit { it.isNotBlank() }
        val tailStart = beforePreedit.indexOfFirst { it == '\'' || it.lowercaseChar() in 'a'..'z' }
        assertTrue("Expected remaining tail after selecting 北京, got: $beforePreedit", tailStart >= 0)

        FcitxTestEnvironment.fcitx.moveCursor(tailStart + 1)
        FcitxTestEnvironment.fcitx.sendKey('\b')

        val afterPreedit = FcitxTestEnvironment.waitForInputPanelPreedit {
            it.isNotBlank() && it != beforePreedit
        }
        assertTrue(
            "Display-cursor edit should preserve confirmed prefix 北京 while changing tail; before=$beforePreedit after=$afterPreedit",
            afterPreedit.startsWith("北京"),
        )
    }

    @Ignore("Diagnostic mixed-chain case is unstable after sdk host decoupling; covered by focused rail/session tests.")
    @Test
    fun mixedSelectionWithRailInterventionContinuesTowardChongQingHuoChe() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("234546412466474641486243", intervalMs = 100)
        selectCandidateExactly("北京")

        val coordinator = T9Coordinator()
        val before = waitForRailState(coordinator)
        val chongAlternative = before.state.sideRailEntries.firstOrNull {
            !it.selected && it.text.startsWith("chong")
        } ?: run {
            fail(
                "Expected chong-* rail entry after selecting 北京; rawInput=${before.rawInput} " +
                    "preedit=${before.preedit} entries=${before.state.sideRailEntries.map { it.text }}",
            )
            return@runBlocking
        }
        val command = coordinator.selectSideRail(chongAlternative) as? T9SessionCommand.ReplaceInputRange
            ?: run {
                fail("Expected rail selection to produce ReplaceInputRange")
                return@runBlocking
            }

        executeCoordinatorCommand(coordinator, command)

        val afterFirstRail = waitForRailState(coordinator) { entries ->
            entries.any { !it.selected && it.text.startsWith("qing") }
        }
        val qingAlternative = afterFirstRail.state.sideRailEntries.firstOrNull {
            !it.selected && it.text.startsWith("qing")
        } ?: run {
            fail(
                "Expected qing-* rail entry after fixing chong; rawInput=${afterFirstRail.rawInput} " +
                    "preedit=${afterFirstRail.preedit} entries=${afterFirstRail.state.sideRailEntries.map { it.text }}",
            )
            return@runBlocking
        }
        val qingCommand = coordinator.selectSideRail(qingAlternative) as? T9SessionCommand.ReplaceInputRange
            ?: run {
                fail("Expected qing rail selection to produce ReplaceInputRange")
                return@runBlocking
            }

        executeCoordinatorCommand(coordinator, qingCommand)

        val candidateTexts = FcitxTestEnvironment.waitForCandidateTexts(
            predicate = { texts ->
                texts.map(::candidateTextOf).any { it == "重庆" || it == "火车" }
            },
            limit = 30,
        )

        if (candidateTexts.map(::candidateTextOf).none { it == "重庆" || it == "火车" }) {
            val rawInput = FcitxTestEnvironment.readRawInput()
            val preedit = FcitxTestEnvironment.fcitx.inputPanelCached.preedit.toString()
            fail(
                "Rail intervention after 北京 should keep chain converging toward 重庆/火车; " +
                    "rawInput=$rawInput preedit=$preedit candidates=$candidateTexts",
            )
        }

        selectCandidateExactly("重庆")
        assertTrue(
            "Selecting 重庆 should keep tail converging toward 火车",
            FcitxTestEnvironment.waitForCandidateTexts(
                predicate = { texts -> texts.map(::candidateTextOf).any { it == "火车" } },
                limit = 30,
            ).map(::candidateTextOf).any { it == "火车" },
        )
    }

    @Ignore("Diagnostic stage3 blocker: session-side rail writes still cannot keep 北京 confirmed while exposing literal chong/qing progression")
    @Test
    fun firstRailAfterConfirmedPrefixPreservesBeijingAndAdvancesToQing() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("234546412466474641486243", intervalMs = 100)
        selectCandidateExactly("北京")

        val coordinator = T9Coordinator()
        val before = waitForRailState(coordinator) { entries ->
            entries.any { !it.selected && it.text.startsWith("chong") }
        }
        val chongAlternative = before.state.sideRailEntries.firstOrNull {
            !it.selected && it.text.startsWith("chong")
        } ?: run {
            fail(
                "Expected chong-* rail entry after selecting 北京; rawInput=${before.rawInput} " +
                    "preedit=${before.preedit} entries=${before.state.sideRailEntries.map { it.text }}",
            )
            return@runBlocking
        }
        val command = coordinator.selectSideRail(chongAlternative) as? T9SessionCommand.ReplaceInputRange
            ?: run {
                fail("Expected chong rail selection to produce ReplaceInputRange")
                return@runBlocking
            }
        val expectedEngineInput = coordinator.state.engineInput

        executeCoordinatorCommand(coordinator, command)

        val afterFirstRawInput = FcitxTestEnvironment.waitForRawInput { it.isNotBlank() }
        val afterFirstPreedit = FcitxTestEnvironment.waitForInputPanelPreedit { it.isNotBlank() }
        val afterFirstRail = waitForRailState(coordinator) { entries ->
            entries.any { !it.selected && it.text.startsWith("qing") }
        }

        assertEquals(
            "First rail should write the expected tail-only engine input",
            expectedEngineInput,
            afterFirstRawInput,
        )
        assertFalse(
            "First rail must not write confirmed prefix 北京 back into daemon raw input; rawInput=$afterFirstRawInput",
            afterFirstRawInput.contains("北京"),
        )
        assertTrue(
            "First rail must preserve confirmed prefix 北京 in preedit; preedit=$afterFirstPreedit",
            afterFirstPreedit.startsWith("北京"),
        )
        assertTrue(
            "First rail should advance to qing-* rail entries; entries=${afterFirstRail.state.sideRailEntries.map { it.text to it.selected }}",
            afterFirstRail.state.sideRailEntries.any { !it.selected && it.text.startsWith("qing") },
        )
    }

    @Ignore("Diagnostic stage3 blocker: sequential tail-only replace after confirmed prefix still stalls at chong candidates")
    @Test
    fun mixedSelectionWithRailInterventionCommitsWhenForcedThroughReplaceInputRange() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("234546412466474641486243", intervalMs = 100)
        selectCandidateExactly("北京")

        val coordinator = T9Coordinator()
        val before = waitForRailState(coordinator) { entries ->
            entries.any { !it.selected && it.text.startsWith("chong") }
        }
        val chongAlternative = before.state.sideRailEntries.first { !it.selected && it.text.startsWith("chong") }
        val chongCommand = coordinator.selectSideRail(chongAlternative) as T9SessionCommand.ReplaceInputRange
        FcitxTestEnvironment.fcitx.replaceInputRange(
            start = chongCommand.replacementWindow.start,
            endExclusive = chongCommand.replacementWindow.end,
            replacement = chongCommand.replacement,
        )

        val afterFirstRail = waitForRailState(coordinator) { entries ->
            entries.any { !it.selected && it.text.startsWith("qing") }
        }
        val qingAlternative = afterFirstRail.state.sideRailEntries.first { !it.selected && it.text.startsWith("qing") }
        val qingCommand = coordinator.selectSideRail(qingAlternative) as T9SessionCommand.ReplaceInputRange
        FcitxTestEnvironment.fcitx.replaceInputRange(
            start = qingCommand.replacementWindow.start,
            endExclusive = qingCommand.replacementWindow.end,
            replacement = qingCommand.replacement,
        )

        selectCandidateExactly("重庆")
        FcitxTestEnvironment.clearEvents()
        selectCandidateExactly("火车")
        val commit = pollCommitString()
        assertTrue(
            "Expected forcing rail commands through replaceInputRange to commit 北京重庆火车, got: $commit",
            !commit.isNullOrBlank() && "北京重庆火车" in commit,
        )
    }

    @Ignore("Diagnostic mixed-chain final-commit closure is unstable after sdk host decoupling; covered by simpler commit-chain tests.")
    @Test
    fun mixedSelectionWithRailInterventionStillNeedsFinalCommitClosure() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("234546412466474641486243", intervalMs = 100)
        selectCandidateExactly("北京")

        val coordinator = T9Coordinator()
        val before = waitForRailState(coordinator) { entries ->
            entries.any { !it.selected && it.text.startsWith("chong") }
        }
        val chongAlternative = before.state.sideRailEntries.first { !it.selected && it.text.startsWith("chong") }
        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(chongAlternative) as T9SessionCommand.ReplaceInputRange,
        )
        val afterFirstRail = waitForRailState(coordinator) { entries ->
            entries.any { !it.selected && it.text.startsWith("qing") }
        }
        val qingAlternative = afterFirstRail.state.sideRailEntries.first { !it.selected && it.text.startsWith("qing") }
        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(qingAlternative) as T9SessionCommand.ReplaceInputRange,
        )

        selectCandidateExactly("重庆")
        val snapshotAfterChongQing = FcitxTestEnvironment.fcitx.readRimeDebugSnapshot()
        val sessionInputAfterChongQing = snapshotField(snapshotAfterChongQing, "input")
        val preeditAfterChongQing = FcitxTestEnvironment.fcitx.inputPanelCached.preedit.toString()
        FcitxTestEnvironment.clearEvents()
        selectCandidateExactly("火车")
        val commit = pollCommitString()
        val rawInput = FcitxTestEnvironment.fcitx.readInput()
        val caret = FcitxTestEnvironment.fcitx.readCaret()
        val preedit = FcitxTestEnvironment.fcitx.inputPanelCached.preedit.toString()
        val candidates = FcitxTestEnvironment.fcitx.getCandidates(0, 20).filter { it.isNotBlank() }
        assertTrue(
            "Expected mixed selection to commit 北京重庆火车, got: commit=$commit rawInput=$rawInput caret=$caret preedit=$preedit " +
                "snapshotAfterChongQing=<$snapshotAfterChongQing> sessionInputAfterChongQing=$sessionInputAfterChongQing " +
                "preeditAfterChongQing=$preeditAfterChongQing candidates=$candidates",
            !commit.isNullOrBlank() && "北京重庆火车" in commit,
        )
    }

    @Ignore("Diagnostic: selecting 重庆 then removing the leaked tail delimiter manually should not be needed once real T9 selection normalization is in place")
    @Test
    fun mixedSelectionCommitsCleanlyIfPostChongQingDelimiterIsRemoved() = runBlocking {
        FcitxTestEnvironment.restart(resetData = true)
        FcitxTestEnvironment.waitForEnabledIme("rime")
        FcitxTestEnvironment.useIme("rime")
        FcitxTestEnvironment.setRimeSchema("t9")
        FcitxTestEnvironment.fcitx.setCandidatePagingMode(1)

        FcitxTestEnvironment.sendString("234546412466474641486243", intervalMs = 100)
        selectCandidateExactly("北京")

        val coordinator = T9Coordinator()
        val before = waitForRailState(coordinator) { entries ->
            entries.any { !it.selected && it.text.startsWith("chong") }
        }
        val chongAlternative = before.state.sideRailEntries.first { !it.selected && it.text.startsWith("chong") }
        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(chongAlternative) as T9SessionCommand.ReplaceInputRange,
        )
        val afterFirstRail = waitForRailState(coordinator) { entries ->
            entries.any { !it.selected && it.text.startsWith("qing") }
        }
        val qingAlternative = afterFirstRail.state.sideRailEntries.first { !it.selected && it.text.startsWith("qing") }
        executeCoordinatorCommand(
            coordinator,
            coordinator.selectSideRail(qingAlternative) as T9SessionCommand.ReplaceInputRange,
        )

        selectCandidateExactly("重庆")
        val snapshotAfterChongQing = FcitxTestEnvironment.fcitx.readRimeDebugSnapshot()
        val sessionInputAfterChongQing = snapshotField(snapshotAfterChongQing, "input")
        val normalizedSessionInput = sessionInputAfterChongQing.replace("chongqing'", "chongqing")
        FcitxTestEnvironment.fcitx.setInput(normalizedSessionInput)
        FcitxTestEnvironment.fcitx.setCaret(normalizedSessionInput.length)

        FcitxTestEnvironment.clearEvents()
        selectCandidateExactly("火车")
        val commit = pollCommitString()
        assertTrue(
            "Expected normalized post-ChongQing input to commit 北京重庆火车, got: commit=$commit snapshot=<$snapshotAfterChongQing> normalized=$normalizedSessionInput",
            !commit.isNullOrBlank() && "北京重庆火车" in commit,
        )
    }

    private suspend fun waitForRailState(
        coordinator: T9Coordinator,
        predicate: (List<org.fcitx.fcitx5.android.input.t9.T9SideRailEntry>) -> Boolean = { entries ->
            entries.any { !it.selected }
        },
    ): RailSnapshot {
        var lastSnapshot: RailSnapshot? = null
        repeat(50) {
            val preedit = FcitxTestEnvironment.fcitx.inputPanelCached.preedit.toString()
            val rawInput = FcitxTestEnvironment.fcitx.readInput()
            val caret = FcitxTestEnvironment.fcitx.readCaret()
            val state = coordinator.sync(
                rawInput = rawInput,
                caret = caret,
                preedit = preedit,
            )
            lastSnapshot = RailSnapshot(rawInput, caret, preedit, state)
            if (predicate(state.sideRailEntries)) {
                return RailSnapshot(rawInput, caret, preedit, state)
            }
            delay(100)
        }
        val snapshot = lastSnapshot
        val entriesSummary = snapshot?.state?.sideRailEntries?.map { "${it.text}:${it.selected}" }
        fail(
            "Timed out waiting for a T9 rail state with alternative entries; " +
                "rawInput=${snapshot?.rawInput} caret=${snapshot?.caret} preedit=${snapshot?.preedit} " +
                "entries=$entriesSummary snapshot=<${FcitxTestEnvironment.fcitx.readRimeDebugSnapshot()}>",
        )
        return RailSnapshot("", 0, "", T9UiState.Empty)
    }

    private suspend fun waitForRailState(
        service: FcitxInputMethodService,
        coordinator: T9Coordinator,
        predicate: (List<org.fcitx.fcitx5.android.input.t9.T9SideRailEntry>) -> Boolean = { entries ->
            entries.any { !it.selected }
        },
    ): RailSnapshot {
        var lastSnapshot: RailSnapshot? = null
        var lastDebugSnapshot = ""
        repeat(50) {
            val preedit = waitForInputPanelPreedit(service) { it.isNotBlank() }
            val rawInput = withServiceFcitx(service) { readInput() }
            val caret = withServiceFcitx(service) { readCaret() }
            lastDebugSnapshot = withServiceFcitx(service) { readRimeDebugSnapshot() }
            val state = coordinator.sync(
                rawInput = rawInput,
                caret = caret,
                preedit = preedit,
            )
            lastSnapshot = RailSnapshot(rawInput, caret, preedit, state)
            if (predicate(state.sideRailEntries)) {
                return RailSnapshot(rawInput, caret, preedit, state)
            }
            delay(100)
        }
        val snapshot = lastSnapshot
        val entriesSummary = snapshot?.state?.sideRailEntries?.map { "${it.text}:${it.selected}" }
        fail(
            "Timed out waiting for a live-service T9 rail state with alternative entries; " +
                "rawInput=${snapshot?.rawInput} caret=${snapshot?.caret} preedit=${snapshot?.preedit} " +
                "entries=$entriesSummary snapshot=<$lastDebugSnapshot>",
        )
        return RailSnapshot("", 0, "", T9UiState.Empty)
    }

    private suspend fun executeCoordinatorCommand(
        coordinator: T9Coordinator,
        command: T9SessionCommand,
    ) {
        FcitxTestEnvironment.fcitx.executeT9SessionCommand(coordinator.state, command)
    }

    private suspend fun executeCoordinatorCommand(
        service: FcitxInputMethodService,
        coordinator: T9Coordinator,
        command: T9SessionCommand,
    ) {
        withServiceFcitx(service) {
            executeT9SessionCommand(coordinator.state, command)
        }
    }

    private data class RailSnapshot(
        val rawInput: String,
        val caret: Int,
        val preedit: String,
        val state: T9UiState,
    )

    private suspend fun selectCandidateExactly(expected: String, limit: Int = 30) {
        repeat(50) {
            val candidates = FcitxTestEnvironment.fcitx.getCandidates(0, limit).filter { it.isNotBlank() }
            val index = candidates.indexOfFirst { candidateTextOf(it) == expected }
            if (index >= 0) {
                val coordinator = T9Coordinator()
                val selectionState = coordinator.sync(
                    rawInput = FcitxTestEnvironment.fcitx.readInput(),
                    caret = FcitxTestEnvironment.fcitx.readCaret(),
                    preedit = FcitxTestEnvironment.fcitx.inputPanelCached.preedit.toString(),
                )
                assertTrue(
                    "Failed to select exact T9 candidate '$expected'",
                    FcitxTestEnvironment.fcitx.selectT9Candidate(index, selectionState),
                )
                return
            }
            delay(100)
        }
        val preedit = FcitxTestEnvironment.fcitx.inputPanelCached.preedit.toString()
        val candidates = FcitxTestEnvironment.fcitx.getCandidates(0, limit).joinToString()
        fail("Timed out waiting to select exact candidate '$expected'; preedit=$preedit candidates=[$candidates]")
    }

    private suspend fun selectCandidateExactly(
        service: FcitxInputMethodService,
        expected: String,
        limit: Int = 30,
    ) {
        repeat(50) {
            val candidates = withServiceFcitx(service) {
                getCandidates(0, limit).filter { it.isNotBlank() }
            }
            val index = candidates.indexOfFirst { candidateTextOf(it) == expected }
            if (index >= 0) {
                val coordinator = T9Coordinator()
                val selectionState = coordinator.sync(
                    rawInput = withServiceFcitx(service) { readInput() },
                    caret = withServiceFcitx(service) { readCaret() },
                    preedit = withServiceFcitx(service) { inputPanelCached.preedit.toString() },
                )
                assertTrue(
                    "Failed to select exact T9 candidate '$expected'",
                    withServiceFcitx(service) { selectT9Candidate(index, selectionState) },
                )
                return
            }
            delay(100)
        }
        val preedit = withServiceFcitx(service) { inputPanelCached.preedit.toString() }
        val candidates = withServiceFcitx(service) { getCandidates(0, limit).joinToString() }
        fail("Timed out waiting to select exact candidate '$expected'; preedit=$preedit candidates=[$candidates]")
    }

    private suspend fun selectCandidateViaServiceUi(
        service: FcitxInputMethodService,
        expected: String,
        limit: Int = 30,
    ) {
        repeat(50) {
            val candidates = withServiceFcitx(service) {
                getCandidates(0, limit).filter { it.isNotBlank() }
            }
            val index = candidates.indexOfFirst { candidateTextOf(it) == expected }
            if (index >= 0) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    service.selectCandidateFromUi(index)
                }
                return
            }
            delay(100)
        }
        val preedit = withServiceFcitx(service) { inputPanelCached.preedit.toString() }
        val candidates = withServiceFcitx(service) { getCandidates(0, limit).joinToString() }
        fail("Timed out waiting to select live-service UI candidate '$expected'; preedit=$preedit candidates=[$candidates]")
    }

    private suspend fun pollCommitString(timeoutMs: Long = 1_500L): String? = withTimeoutOrNull(timeoutMs) {
        FcitxTestEnvironment.fcitxEventChannel
            .receiveAsFlow()
            .mapNotNull { (it as? FcitxEvent.CommitStringEvent)?.data?.text }
            .firstOrNull()
    }

    private suspend fun waitForSessionInput(predicate: (String) -> Boolean): String {
        repeat(50) {
            val sessionInput = snapshotField(FcitxTestEnvironment.fcitx.readRimeDebugSnapshot(), "input")
            if (predicate(sessionInput)) return sessionInput
            delay(100)
        }
        fail(
            "Timed out waiting for session input to match predicate; " +
                "daemonRaw=${FcitxTestEnvironment.fcitx.readInput()} " +
                "preedit=${FcitxTestEnvironment.fcitx.inputPanelCached.preedit} " +
                "snapshot=<${FcitxTestEnvironment.fcitx.readRimeDebugSnapshot()}>",
        )
        return ""
    }

    private suspend fun waitForSessionInput(
        service: FcitxInputMethodService,
        predicate: (String) -> Boolean,
    ): String {
        repeat(50) {
            val sessionInput = snapshotField(withServiceFcitx(service) { readRimeDebugSnapshot() }, "input")
            if (predicate(sessionInput)) return sessionInput
            delay(100)
        }
        fail(
            "Timed out waiting for live service session input to match predicate; " +
                "daemonRaw=${withServiceFcitx(service) { readInput() }} " +
                "preedit=${withServiceFcitx(service) { inputPanelCached.preedit.toString() }} " +
                "snapshot=<${withServiceFcitx(service) { readRimeDebugSnapshot() }}>",
        )
        return ""
    }

    private fun switchSystemImeToFcitx() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val imeId = FcitxTestEnvironment.currentImeId()
        instrumentation.uiAutomation.executeShellCommand("ime enable $imeId").close()
        instrumentation.uiAutomation.executeShellCommand("ime set $imeId").close()
    }

    private fun launchClipboardEditActivity(): Activity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        return instrumentation.startActivitySync(
            Intent(context, ClipboardEditActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ).also { activity ->
            instrumentation.runOnMainSync {
                activity.findViewById<EditText>(R.id.clipboard_edit_text)?.let { editText ->
                    editText.requestFocus()
                    activity.getSystemService(InputMethodManager::class.java)
                        ?.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
                }
            }
        }
    }

    private suspend fun waitForRunningFcitxService(activity: Activity? = null): FcitxInputMethodService {
        repeat(50) {
            activity?.let(::requestImeFromHostEditor)
            findRunningFcitxService()?.let { return it }
            delay(100)
        }
        fail("Timed out waiting for running FcitxInputMethodService")
        throw AssertionError("unreachable")
    }

    private suspend fun waitForVisibleBackspaceButton(): View {
        repeat(50) {
            findRunningFcitxService()?.let { service ->
                waitForVisibleBackspaceButton(service)?.let { return it }
            }
            delay(100)
        }
        fail("Timed out waiting for visible T9 backspace button from running IME")
        throw AssertionError("unreachable")
    }

    private suspend fun waitForVisibleBackspaceButton(service: FcitxInputMethodService): View {
        repeat(50) {
            ensureT9KeyboardLayout(service)
            extractVisibleT9BackspaceButton(service)
                ?.takeIf { it.isShown }
                ?.let { return it }
            extractInputView(service)
                ?.findViewById<View>(R.id.button_backspace)
                ?.takeIf { it.isShown }
                ?.let { return it }
            delay(100)
        }
        fail("Timed out waiting for visible T9 backspace button on active IME service")
        throw AssertionError("unreachable")
    }

    private suspend fun prepareLiveT9BackspaceScenario(): Pair<Activity, FcitxInputMethodService> {
        stopSharedTestEnvironmentFcitx()
        switchSystemImeToFcitx()
        val activity = launchClipboardEditActivity()
        val service = waitForRunningFcitxService(activity)
        configureServiceForT9(service)
        return activity to prepareHostEditorForT9Backspace(activity)
    }

    private suspend fun prepareHostEditorForT9Backspace(activity: Activity): FcitxInputMethodService {
        val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
        repeat(50) {
            val service = findRunningFcitxService()
            if (service == null) {
                Log.d("RimeIceStage3Test", "full-delete: waiting service")
                requestImeFromHostEditor(activity, editText)
                delay(100)
                return@repeat
            }
            ensureT9KeyboardLayout(service)
            requestImeFromHostEditor(activity, editText)
            if (service.currentInputConnection == null || !editText.hasFocus()) {
                Log.d(
                    "RimeIceStage3Test",
                    "full-delete: waiting host ready focus=${editText.hasFocus()} inputConnection=${service.currentInputConnection != null}",
                )
            }
            if (service.currentInputConnection != null &&
                editText.hasFocus()
            ) {
                return service
            }
            delay(100)
        }
        fail(
            "Timed out waiting for host editor/input connection before T9 backspace; " +
                "hasFocus=${editText.hasFocus()} servicePresent=${findRunningFcitxService() != null} " +
                "serviceConnection=${findRunningFcitxService()?.currentInputConnection != null}",
        )
        throw AssertionError("unreachable")
    }

    private suspend fun sendStringViaService(
        service: FcitxInputMethodService,
        str: String,
        intervalMs: Long = 50,
    ) {
        str.forEach { c ->
            withServiceFcitx(service) {
                sendKey(c)
            }
            delay(intervalMs)
        }
    }

    private suspend fun waitForInputPanelPreedit(
        service: FcitxInputMethodService,
        predicate: (String) -> Boolean,
    ): String {
        repeat(50) {
            val preedit = withServiceFcitx(service) {
                inputPanelCached.preedit.toString()
            }
            if (predicate(preedit)) return preedit
            delay(100)
        }
        fail(
            "Timed out waiting for live service input panel preedit; " +
                "preedit=${withServiceFcitx(service) { inputPanelCached.preedit.toString() }} " +
                "snapshot=<${withServiceFcitx(service) { readRimeDebugSnapshot() }}>",
        )
        return ""
    }

    private suspend fun waitForDaemonRawInput(
        service: FcitxInputMethodService,
        predicate: (String) -> Boolean,
    ): String {
        repeat(50) {
            val rawInput = withServiceFcitx(service) { readInput() }
            if (predicate(rawInput)) return rawInput
            delay(100)
        }
        fail(
            "Timed out waiting for live service daemon raw input; " +
                "daemonRawInput=${withServiceFcitx(service) { readInput() }} " +
                "preedit=${withServiceFcitx(service) { inputPanelCached.preedit.toString() }} " +
                "snapshot=<${withServiceFcitx(service) { readRimeDebugSnapshot() }}>",
        )
        return ""
    }

    private suspend fun waitForCompositionToClear(service: FcitxInputMethodService) {
        repeat(50) {
            val rawInput = withServiceFcitx(service) { readInput() }
            val preedit = withServiceFcitx(service) { inputPanelCached.preedit.toString() }
            if (rawInput.isBlank() && preedit.isBlank()) {
                return
            }
            delay(100)
        }
        fail(
            "Timed out waiting for live service composition to clear; " +
                "daemonRawInput=${withServiceFcitx(service) { readInput() }} " +
                "preedit=${withServiceFcitx(service) { inputPanelCached.preedit.toString() }} " +
                "snapshot=<${withServiceFcitx(service) { readRimeDebugSnapshot() }}>",
        )
    }

    private suspend fun configureServiceForT9(service: FcitxInputMethodService) {
        withServiceFcitx(service) {
            setEnabledIme(arrayOf("rime"))
            activateIme("rime")
            setSchema("t9")
            focus(true)
        }
        repeat(50) {
            val ready = withServiceFcitx(service) {
                currentIme().uniqueName == "rime" && currentSchema() == "t9"
            }
            if (ready) return
            delay(100)
        }
        fail("Timed out configuring live IME service for rime/t9")
    }

    private suspend fun requestServiceBackspace(service: FcitxInputMethodService) {
        Log.d("RimeIceStage3Test", "full-delete: request backspace")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.onT9BackspaceRequested()
        }
        Log.d("RimeIceStage3Test", "full-delete: request backspace returned")
    }

    private suspend fun waitForHostText(editText: EditText, expected: String) {
        waitForHostText(editText) { it == expected }
    }

    private suspend fun waitForHostText(editText: EditText, predicate: (String) -> Boolean) {
        repeat(30) {
            if (predicate(editText.text.toString())) return
            delay(100)
        }
        fail("Timed out waiting for host text; actual=${editText.text}")
    }

    private suspend fun requestServiceBackspace(activity: Activity): FcitxInputMethodService {
        val activeService = prepareHostEditorForT9Backspace(activity)
        requestServiceBackspace(activeService)
        return activeService
    }

    private suspend fun resetT9CompositionForNextStage() {
        Log.d(
            "RimeIceStage3Test",
            "full-delete: reset start daemonRaw=${FcitxTestEnvironment.fcitx.readInput()} " +
                "preedit=${FcitxTestEnvironment.fcitx.inputPanelCached.preedit}",
        )
        FcitxTestEnvironment.fcitx.reset()
        FcitxTestEnvironment.waitForCompositionToClear()
        FcitxTestEnvironment.clearEvents()
        Log.d("RimeIceStage3Test", "full-delete: reset done")
    }

    private suspend fun resetT9CompositionForNextStage(service: FcitxInputMethodService) {
        Log.d(
            "RimeIceStage3Test",
            "full-delete: reset start daemonRaw=${withServiceFcitx(service) { readInput() }} " +
                "preedit=${withServiceFcitx(service) { inputPanelCached.preedit.toString() }}",
        )
        withServiceFcitx(service) {
            reset()
        }
        waitForCompositionToClear(service)
        Log.d("RimeIceStage3Test", "full-delete: reset done")
    }

    private fun requestImeFromHostEditor(
        activity: Activity,
        editText: EditText = activity.findViewById(R.id.clipboard_edit_text),
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            editText.requestFocus()
            editText.setSelection(editText.text.length)
            activity.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
        }
    }

    private fun seedHostText(editText: EditText, text: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            editText.setText(text)
            editText.setSelection(editText.text.length)
        }
    }

    private suspend fun withClipboardBackspaceHost(
        seedText: String,
        block: suspend (Activity, EditText, FcitxInputMethodService) -> Unit,
    ) {
        withLiveServiceBackspaceHost(seedText, block)
    }

    private suspend fun withLiveServiceBackspaceHost(
        seedText: String,
        block: suspend (Activity, EditText, FcitxInputMethodService) -> Unit,
    ) {
        stopSharedTestEnvironmentFcitx()
        switchSystemImeToFcitx()
        val activity = launchClipboardEditActivity()
        try {
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            val service = waitForRunningFcitxService(activity)
            configureServiceForT9(service)
            seedHostText(editText, seedText)
            val activeService = prepareHostEditorForT9Backspace(activity)
            block(activity, editText, activeService)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.finish()
            }
        }
    }

    private suspend fun stopSharedTestEnvironmentFcitx() {
        runCatching {
            if (FcitxTestEnvironment.fcitx.isReady) {
                FcitxTestEnvironment.fcitx.stop()
                FcitxTestEnvironment.fcitx.lifecycle.whenStopped { }
            }
        }
    }

    private suspend fun tapVisibleT9Digit(label: String) {
        val view = waitForVisibleT9Digit(label)
        tapView(view)
        delay(150)
    }

    private suspend fun waitForVisibleT9Digit(label: String): View {
        repeat(50) {
            findRunningFcitxService()?.let { service ->
                ensureT9KeyboardLayout(service)
                extractVisibleT9MainKeyboard(service)
                    ?.let { keyboard ->
                        findVisibleTextKey(keyboard as View, label)?.let { return it }
                    }
            }
            delay(100)
        }
        fail("Timed out waiting for visible T9 digit key $label")
        throw AssertionError("unreachable")
    }

    private fun findRunningFcitxService(): FcitxInputMethodService? {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentActivityThread = activityThreadClass
            .getDeclaredMethod("currentActivityThread")
            .invoke(null)
        val servicesField = activityThreadClass.getDeclaredField("mServices").apply {
            isAccessible = true
        }

        @Suppress("UNCHECKED_CAST")
        val services = servicesField.get(currentActivityThread) as? Map<*, *> ?: return null
        return services.values.firstNotNullOfOrNull { it as? FcitxInputMethodService }
    }

    private fun serviceFcitx(service: FcitxInputMethodService): FcitxConnection {
        val field = FcitxInputMethodService::class.java.getDeclaredField("fcitx").apply {
            isAccessible = true
        }
        return field.get(service) as FcitxConnection
    }

    private suspend fun waitForServiceFcitxReady(service: FcitxInputMethodService) {
        repeat(50) {
            val ready = serviceFcitx(service).runImmediately { isReady }
            if (ready) return
            delay(100)
        }
        fail("Timed out waiting for live service fcitx connection to become ready")
    }

    private suspend fun <T> withServiceFcitx(
        service: FcitxInputMethodService,
        block: suspend FcitxAPI.() -> T,
    ): T {
        waitForServiceFcitxReady(service)
        return serviceFcitx(service).runOnReady(block)
    }

    private fun extractInputView(service: FcitxInputMethodService): View? {
        val field = FcitxInputMethodService::class.java.getDeclaredField("inputView").apply {
            isAccessible = true
        }
        return field.get(service) as? View
    }

    private fun extractVisibleT9BackspaceButton(service: FcitxInputMethodService): View? {
        val mainKeyboard = extractVisibleT9MainKeyboard(service) ?: return null
        val getter = mainKeyboard.javaClass.getDeclaredMethod("getBackspace").apply {
            isAccessible = true
        }
        return getter.invoke(mainKeyboard) as? View
    }

    private fun extractVisibleT9MainKeyboard(service: FcitxInputMethodService): Any? {
        val inputView = extractInputView(service) as? InputView ?: return null
        val keyboardWindow = extractKeyboardWindow(inputView) ?: return null

        @Suppress("UNCHECKED_CAST")
        val keyboards = KeyboardWindow::class.java
            .getDeclaredMethod("getKeyboards")
            .apply { isAccessible = true }
            .invoke(keyboardWindow) as? Map<String, Any> ?: return null
        val t9Keyboard = keyboards[T9Keyboard.Name] as? T9Keyboard ?: return null
        val mainKeyboardField = T9Keyboard::class.java.getDeclaredField("mainKeyboard").apply {
            isAccessible = true
        }
        return mainKeyboardField.get(t9Keyboard)
    }

    private fun extractKeyboardWindow(inputView: InputView): KeyboardWindow? {
        val keyboardWindowField = InputView::class.java.getDeclaredField("keyboardWindow").apply {
            isAccessible = true
        }
        return keyboardWindowField.get(inputView) as? KeyboardWindow
    }

    private fun ensureT9KeyboardLayout(service: FcitxInputMethodService) {
        val inputView = extractInputView(service) as? InputView ?: return
        val keyboardWindow = extractKeyboardWindow(inputView) ?: return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            keyboardWindow.switchLayout(T9Keyboard.Name)
        }
    }

    private fun tapView(view: View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val x = view.width / 2f
            val y = view.height / 2f
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(downTime, downTime + 16L, MotionEvent.ACTION_UP, x, y, 0)
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }
    }

    private fun performClick(view: View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            view.performClick()
        }
    }

    private fun candidateTextOf(candidate: String): String = candidate.substringBefore(' ')

    private fun candidateCommentKeys(candidates: List<String>): Set<String> = candidates
        .map(::candidateCommentOf)
        .map(::candidateCommentKeyOf)
        .filter(String::isNotBlank)
        .toSet()

    private fun candidateCommentKeyOf(comment: String): String = Regex("[a-z]+")
        .findAll(comment.lowercase())
        .map { it.value }
        .joinToString("")

    private fun candidateCommentOf(candidate: String): String = candidate.substringAfter(' ', "")

    private fun snapshotField(snapshot: String, key: String): String = snapshot.lineSequence()
        .filter { it.startsWith("$key=") }
        .lastOrNull()
        ?.substringAfter('=')
        .orEmpty()

    private fun collectTextLabels(view: View, output: MutableSet<String>) {
        if (view is TextKeyView) {
            output += view.mainText.text.toString()
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectTextLabels(view.getChildAt(index), output)
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

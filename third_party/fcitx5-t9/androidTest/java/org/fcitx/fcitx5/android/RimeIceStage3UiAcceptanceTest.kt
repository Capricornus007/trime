/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.whenStopped
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.input.t9.T9ReplacementWindow
import org.fcitx.fcitx5.android.input.t9.T9SideRailComponent
import org.fcitx.fcitx5.android.input.t9.T9SideRailEntry
import org.fcitx.fcitx5.android.input.t9.T9SideRailUi
import org.fcitx.fcitx5.android.input.t9.T9UiState
import org.fcitx.fcitx5.android.ui.main.ClipboardEditActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Test

class RimeIceStage3UiAcceptanceTest {

    companion object {
        private const val UI_WAIT_TIMEOUT_MS = 15_000L
        private const val SERVICE_WAIT_TIMEOUT_MS = 30_000L
        private const val TOP_PREEDIT_TAG = "fcitx-preedit-top"
    }

    @Test
    fun switchingToT9SchemaStartsVisibleImeWithoutCrash() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            val currentIme = withServiceFcitx(service) { currentIme().uniqueName }
            val currentSchema = withServiceFcitx(service) { currentSchema() }

            assertEquals("rime", currentIme)
            assertEquals("t9", currentSchema)
            assertTrue("Visible IME should stay alive after switching into T9 schema", service.isT9ModeActive)
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun topT9PreeditUsesLiveUiTextAndShowsReadablePinyin() = runBlocking {
        Log.d("RimeIceStage3Ui", "top-preedit: start")
        val activity = prepareVisibleT9Ime()
        try {
            Log.d("RimeIceStage3Ui", "top-preedit: ime prepared")
            waitForRunningFcitxService()
            Log.d("RimeIceStage3Ui", "top-preedit: service confirmed")
            "94126".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            Log.d("RimeIceStage3Ui", "top-preedit: digits tapped")

            val preedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank()
            }
            Log.d("RimeIceStage3Ui", "top-preedit: snapshot=$preedit")

            assertNotEquals("94126", preedit.topText)
            assertFalse("Top preedit should not expose raw digits: $preedit", preedit.topText.any(Char::isDigit))
            assertTrue(
                "Top preedit should show a readable pinyin preview: $preedit",
                preedit.topText.any { it == '\'' || it == ' ' || it in 'a'..'z' || it in 'A'..'Z' },
            )
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun singleDigitVisibleCandidatesFollowCurrentRimeInputWhileRailShowsAbc() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            tapVisibleT9Digit("2")

            val visibleCandidates = waitForVisibleHorizontalCandidates { candidates ->
                candidates.isNotEmpty()
            }
            val visibleRail = waitForVisibleSideRailItems { items ->
                items.take(3).map { it.text } == listOf("a", "b", "c")
            }

            assertTrue(
                "Visible T9 candidates for digit 2 should follow the current Rime input state and stay non-empty; visibleCandidates=$visibleCandidates rail=$visibleRail",
                visibleCandidates.isNotEmpty(),
            )
            assertEquals(listOf("a", "b", "c"), visibleRail.take(3).map { it.text })
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun repeatedBaRailSelectionsUnderlineEntireTopPreedit() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            repeat(10) {
                tapVisibleT9Digit("2")
            }

            repeat(5) {
                tapVisibleSideRailItem("ba")
            }

            val snapshot = waitForTopPreeditSnapshot {
                normalizeVisibleT9Preedit(it.topText) == "bababababa" &&
                    it.underlineCoverageLength >= it.topText.length
            }

            assertEquals("bababababa", normalizeVisibleT9Preedit(snapshot.topText))
            assertTrue(
                "Expected full top preedit underline coverage after repeatedly fixing every syllable to ba; snapshot=$snapshot",
                snapshot.underlineCoverageLength >= snapshot.topText.length,
            )
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun reopeningLastBaTailKeepsVisibleTextButDropsTailOutOfAccent() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            repeat(10) {
                tapVisibleT9Digit("2")
            }
            repeat(5) {
                tapVisibleSideRailItem("ba")
            }
            tapVisibleSideRailItem("b")

            val snapshot = waitForTopPreeditSnapshot {
                normalizeVisibleT9Preedit(it.topText) == "bababababa" &&
                    it.topText.endsWith("ba") &&
                    it.underlineCoverageLength == it.topText.length - 1
            }

            assertEquals("bababababa", normalizeVisibleT9Preedit(snapshot.topText))
            assertTrue(snapshot.topText.endsWith("ba"))
            assertEquals(snapshot.topText.length - 1, snapshot.underlineCoverageLength)
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun selectingBForTheReopenedTailMaterializesVisibleBBAtTheEnd() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            repeat(10) {
                tapVisibleT9Digit("2")
            }
            repeat(5) {
                tapVisibleSideRailItem("ba")
            }
            tapVisibleSideRailItem("b")
            tapVisibleSideRailItem("b")

            val snapshot = waitForTopPreeditSnapshot {
                normalizeVisibleT9Preedit(it.topText) == "bababababb" &&
                    it.topText.endsWith("b'b") &&
                    it.underlineCoverageLength >= it.topText.length
            }

            assertEquals("bababababb", normalizeVisibleT9Preedit(snapshot.topText))
            assertTrue(snapshot.topText.endsWith("b'b"))
            assertTrue(snapshot.underlineCoverageLength >= snapshot.topText.length)
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun clickingVisibleSingleDigitRailOptionCommitsDigitAndClearsComposition() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            tapVisibleT9Digit("2")

            waitForVisibleSideRailItems { items ->
                items.map { it.text }.contains("2")
            }

            tapVisibleSideRailItem("2")

            waitForHostText(editText, "2")
            waitForClearedT9Composition(service)
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun requestingSingleDigitRailDirectCommitFromServiceCommitsDigitAndClearsComposition() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            tapVisibleT9Digit("2")

            waitForVisibleSideRailItems { items ->
                items.map { it.text }.contains("2")
            }

            readOnMain {
                service.onT9DirectCommitRequested("2")
            }

            waitForHostText(editText, "2")
            waitForClearedT9Composition(service)
        } finally {
            finishActivity(activity)
        }
    }

    @Ignore("IME switching to Number keyboard is flaky on emulator shells; covered by stable direct-input cases")
    @Test
    fun switchingToNumberKeyboardCommitsDigitsDirectly() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)

            tapVisibleT9TextKey("123")
            waitForKeyboardLayout(service, "Number")
            tapVisibleKeyboardTextKey("1")

            waitForHostText(editText, "1")
            waitForClearedT9Composition(service)
        } finally {
            finishActivity(activity)
        }
    }

    @Ignore("IME switching to keyboard-us is flaky on emulator shells; covered by stable language-switch smoke tests")
    @Test
    fun switchingToEnglishAndChoosingPopupDigitCommitsDigitDirectly() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            KeyboardWindow.debugLastKeyAction = ""
            KeyboardWindow.debugLastKeyActionSource = ""
            KeyboardWindow.debugLastDirectCommitText = ""
            KeyboardWindow.debugLastDirectCommitContext = ""

            tapCurrentKeyboardViewById(service, R.id.button_lang)
            waitForCurrentIme(service, "keyboard-us")
            waitForVisibleKeyboardTextKey("Q", "q", "1")
            selectVisibleKeyboardPopupText("1", "Q", "q")
            waitForKeyboardDispatch()

            waitForHostText(editText, "1")
            waitForClearedT9Composition(service)
        } finally {
            finishActivity(activity)
        }
    }

    @Ignore("Composite Number/English/T9 direct-commit flow is flaky under emulator timing; sub-flows are validated separately")
    @Test
    fun t9DirectCommitRegressionFlowCoversNumberEnglishAndPopupLetter() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService(activity)
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)

            clearHostText(editText)
            tapVisibleT9TextKey("123")
            waitForVisibleKeyboardTextKey("1")
            tapVisibleKeyboardTextKey("1")
            waitForHostText(editText, "1")
            waitForClearedT9Composition(service)

            clearHostText(editText)
            tapVisibleKeyboardTextKey("返回")
            waitForVisibleT9Digit("2")
            tapCurrentKeyboardViewById(service, R.id.button_lang)
            waitForCurrentIme(service, "keyboard-us")
            waitForVisibleKeyboardTextKey("Q", "q", "1")
            selectVisibleKeyboardPopupText("1", "Q", "q")
            waitForKeyboardDispatch()
            waitForHostText(editText, "1")
            waitForClearedT9Composition(service)

            clearHostText(editText)
            tapCurrentKeyboardViewById(service, R.id.button_lang)
            waitForCurrentIme(service, "rime")
            waitForCurrentSchema(service, "t9")
            waitForVisibleT9Digit("2")
            selectVisibleT9PopupText("a", "2")
            waitForKeyboardDispatch()
            waitForHostText(editText, "a")
            waitForClearedT9Composition(service)
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun languageKeySwitchesBetweenT9RimeAndKeyboardUs() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()

            tapCurrentKeyboardViewById(service, R.id.button_lang)
            waitForKeyboardLayout(service, "Text")
            waitForCurrentIme(service, "keyboard-us")

            tapCurrentKeyboardViewById(service, R.id.button_lang)
            waitForCurrentIme(service, "rime")
            waitForCurrentSchema(service, "t9")
            waitForKeyboardLayout(service, "T9")
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun longPressingT9DigitAndChoosingLetterCommitsLetterDirectly() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService(activity)
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            KeyboardWindow.debugLastKeyAction = ""
            KeyboardWindow.debugLastKeyActionSource = ""
            KeyboardWindow.debugLastDirectCommitText = ""
            KeyboardWindow.debugLastDirectCommitContext = ""

            selectVisibleT9PopupText("a", "2")
            waitForKeyboardDispatch()

            waitForHostText(editText, "a")
            waitForClearedT9Composition(service)
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun tappingRetypeClearsTopPreeditAndResetsSideRailToPunctuation() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()

            tapVisibleT9Digit("2")
            tapVisibleT9Digit("3")

            waitForTopPreeditSnapshot {
                normalizeVisibleT9Preedit(it.topText).isNotBlank()
            }
            waitForVisibleSideRailItems { items ->
                items.any { item -> item.text == "a" || item.text == "ce" }
            }

            tapVisibleT9Retype()

            waitForClearedT9Composition(service)
            val visibleRail = waitForVisibleSideRailItems { items ->
                items.isNotEmpty() && items.none { it.text == "a" || it.text == "ce" }
            }
            assertTrue(
                "Expected retype to clear candidate rail and fall back to non-candidate items; rail=$visibleRail",
                visibleRail.none { it.text == "a" || it.text == "ce" },
            )
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun digit23VisibleRailFlowKeepsTopPreviewAndCandidatesInSync() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            tapVisibleT9Digit("2")
            tapVisibleT9Digit("3")

            val beforeState = waitForLiveT9State {
                it.rawInput == "23" &&
                    it.sideRailEntries.firstOrNull()?.text == "ce" &&
                    it.sideRailEntries.map { entry -> entry.text }.containsAll(listOf("a", "b", "c"))
            }
            val beforeCandidates = waitForVisibleHorizontalCandidates { it.isNotEmpty() }
            val beforePreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() && !it.topText.any(Char::isDigit)
            }
            val beforeRail = waitForVisibleSideRailItems { it.isNotEmpty() }

            tapVisibleSideRailItem("a")

            val afterAState = waitForLiveT9State {
                it.rawInput == "a3" &&
                    it.sideRailEntries.take(3).map { entry -> entry.text } == listOf("d", "e", "f") &&
                    it.sideRailEntries.none { entry -> entry.selected }
            }
            val afterACandidates = waitForVisibleHorizontalCandidates { candidates ->
                candidates.any { candidate ->
                    candidate.text == "阿斗" || candidate.text == "阿呆" || candidate.text == "阿杜"
                }
            }
            val afterAPreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() &&
                    !it.topText.any(Char::isDigit) &&
                    it.topText.startsWith("a") &&
                    it.topText != "a"
            }
            val afterARail = waitForVisibleSideRailItems {
                it.take(3).map { item -> item.text } == listOf("d", "e", "f")
            }

            tapVisibleSideRailItem("f")

            val afterFState = waitForLiveT9State {
                it.rawInput == "af" &&
                    it.sideRailEntries.take(3).map { entry -> entry.text } == listOf("d", "e", "f")
            }
            val afterFCandidates = waitForVisibleHorizontalCandidates { candidates ->
                candidates.any { candidate ->
                    candidate.text == "阿飞" || candidate.text == "阿福"
                }
            }
            val afterFPreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() &&
                    !it.topText.any(Char::isDigit) &&
                    it.topText == "a'f"
            }

            assertEquals("23", beforeState.rawInput)
            assertEquals("ce", beforeState.sideRailEntries.first().text)
            assertTrue(beforeRail.isNotEmpty())
            assertNotEquals(beforeCandidates, afterACandidates)
            assertNotEquals(afterACandidates, afterFCandidates)
            assertTrue(afterACandidates.any { it.text == "阿斗" || it.text == "阿呆" || it.text == "阿杜" })
            assertTrue(afterFCandidates.any { it.text == "阿飞" || it.text == "阿福" })
            assertNotEquals(beforePreedit.topText, afterAPreedit.topText)
            assertTrue(afterAPreedit.topText.startsWith("a"))
            assertNotEquals("a", afterAPreedit.topText)
            assertEquals(listOf("d", "e", "f"), afterARail.take(3).map { it.text })
            assertEquals("a'f", afterFPreedit.topText)
            assertEquals("a3", afterAState.rawInput)
            assertEquals("af", afterFState.rawInput)
            assertEquals(listOf("d", "e", "f"), afterFState.sideRailEntries.take(3).map { it.text })
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun digit23VisibleRailFlowKeepsBPrefixStableAcrossSecondSelection() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            tapVisibleT9Digit("2")
            tapVisibleT9Digit("3")

            waitForLiveT9State {
                it.rawInput == "23" &&
                    it.sideRailEntries.map { entry -> entry.text }.containsAll(listOf("a", "b", "c"))
            }

            tapVisibleSideRailItem("b")

            val afterBState = waitForLiveT9State {
                it.rawInput == "b3" &&
                    it.sideRailEntries.take(3).map { entry -> entry.text } == listOf("d", "e", "f")
            }
            val afterBPreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() &&
                    !it.topText.any(Char::isDigit) &&
                    it.topText.startsWith("b")
            }
            val afterBCandidates = waitForVisibleHorizontalCandidates { it.isNotEmpty() }

            tapVisibleSideRailItem("f")

            val afterFState = waitForLiveT9State {
                it.rawInput == "bf" &&
                    it.sideRailEntries.take(3).map { entry -> entry.text } == listOf("d", "e", "f")
            }
            val afterFPreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() &&
                    !it.topText.any(Char::isDigit) &&
                    it.topText == "b'f"
            }
            val afterFCandidates = waitForVisibleHorizontalCandidates {
                it.isNotEmpty() && it != afterBCandidates
            }

            assertEquals("b3", afterBState.rawInput)
            assertTrue(afterBPreedit.topText.startsWith("b"))
            assertEquals("bf", afterFState.rawInput)
            assertEquals("b'f", afterFPreedit.topText)
            assertEquals(listOf("d", "e", "f"), afterFState.sideRailEntries.take(3).map { it.text })
            assertNotEquals(afterBCandidates, afterFCandidates)
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun digit23VisibleLastBucketReselectionOverwritesTailAndBackspaceRestoresB3() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            tapVisibleT9Digit("2")
            tapVisibleT9Digit("3")

            waitForLiveT9State {
                it.rawInput == "23" &&
                    it.sideRailEntries.map { entry -> entry.text }.containsAll(listOf("a", "b", "c"))
            }

            tapVisibleSideRailItem("b")

            waitForLiveT9State {
                it.rawInput == "b3" &&
                    it.sideRailEntries.take(3).map { entry -> entry.text } == listOf("d", "e", "f")
            }

            tapVisibleSideRailItem("f")

            val afterFState = waitForLiveT9State {
                it.rawInput == "bf" &&
                    it.sideRailEntries.take(3).map { entry -> entry.text } == listOf("d", "e", "f") &&
                    it.sideRailEntries.none { entry -> entry.selected }
            }
            val afterFPreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() &&
                    !it.topText.any(Char::isDigit) &&
                    it.topText == "b'f"
            }
            val afterFCandidates = waitForVisibleHorizontalCandidates { it.isNotEmpty() }

            tapVisibleSideRailItem("e")

            val afterEState = waitForLiveT9State {
                it.rawInput == "be" &&
                    it.sideRailEntries.take(3).map { entry -> entry.text } == listOf("d", "e", "f") &&
                    it.sideRailEntries.none { entry -> entry.selected }
            }
            val afterEPreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() &&
                    !it.topText.any(Char::isDigit) &&
                    it.topText == "b'e"
            }
            val afterECandidates = waitForVisibleHorizontalCandidates {
                it.isNotEmpty() && it != afterFCandidates
            }

            tapVisibleBackspaceButton()

            val afterBackspaceState = waitForLiveT9State {
                it.rawInput == "b3" &&
                    it.sideRailEntries.take(3).map { entry -> entry.text } == listOf("d", "e", "f") &&
                    it.sideRailEntries.none { entry -> entry.selected }
            }
            val afterBackspacePreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() &&
                    !it.topText.any(Char::isDigit) &&
                    it.topText.startsWith("b") &&
                    it.topText != afterEPreedit.topText
            }
            val afterBackspaceCandidates = waitForVisibleHorizontalCandidates {
                it.isNotEmpty() && it != afterECandidates
            }

            assertEquals("bf", afterFState.rawInput)
            assertEquals("b'f", afterFPreedit.topText)
            assertEquals("be", afterEState.rawInput)
            assertEquals("b'e", afterEPreedit.topText)
            assertEquals(listOf("d", "e", "f"), afterEState.sideRailEntries.take(3).map { it.text })
            assertNotEquals(afterFCandidates, afterECandidates)
            assertEquals("b3", afterBackspaceState.rawInput)
            assertEquals(listOf("d", "e", "f"), afterBackspaceState.sideRailEntries.take(3).map { it.text })
            assertNotEquals(afterEPreedit.topText, afterBackspacePreedit.topText)
            assertNotEquals(afterECandidates, afterBackspaceCandidates)
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun continuingDigitsAfterFullyFixedLastBucketShowsAFreshVisible23Tail() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            tapVisibleT9Digit("2")
            tapVisibleT9Digit("3")

            waitForLiveT9State {
                it.rawInput == "23" &&
                    it.sideRailEntries.map { entry -> entry.text }.containsAll(listOf("a", "b", "c"))
            }

            tapVisibleSideRailItem("b")

            waitForLiveT9State {
                it.rawInput == "b3" &&
                    it.sideRailEntries.take(3).map { entry -> entry.text } == listOf("d", "e", "f")
            }

            tapVisibleSideRailItem("f")

            waitForLiveT9State {
                it.rawInput == "bf" &&
                    it.sideRailEntries.take(3).map { entry -> entry.text } == listOf("d", "e", "f")
            }

            tapVisibleT9Digit("2")
            tapVisibleT9Digit("3")

            val afterTypingMoreState = waitForLiveT9State {
                it.rawInput == "bf23" &&
                    it.sideRailEntries.firstOrNull()?.text == "ce" &&
                    it.sideRailEntries.map { entry -> entry.text }.containsAll(listOf("a", "b", "c"))
            }
            val afterTypingMorePreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() &&
                    !it.topText.any(Char::isDigit) &&
                    it.topText.replace(" ", "").replace("'", "").startsWith("bf")
            }
            val afterTypingMoreRail = waitForVisibleSideRailItems { items ->
                items.isNotEmpty() &&
                    items.first().text == "ce" &&
                    items.map { it.text }.containsAll(listOf("a", "b", "c"))
            }

            assertEquals("bf23", afterTypingMoreState.rawInput)
            assertEquals(T9ReplacementWindow(start = 2, end = 4), afterTypingMoreState.replacementWindow)
            assertEquals("ce", afterTypingMoreState.sideRailEntries.first().text)
            assertTrue(afterTypingMoreState.sideRailEntries.map { it.text }.containsAll(listOf("a", "b", "c")))
            assertTrue(afterTypingMorePreedit.topText.replace(" ", "").replace("'", "").startsWith("bf"))
            assertEquals("ce", afterTypingMoreRail.first().text)
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun expandedCandidateWindowHidesRenderedCommentForVisibleT9Rows() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }
            "234546412466474641486243".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            val structuredCandidates = waitForHorizontalStructuredCandidates(service) {
                it.isNotEmpty() && it.any { candidate -> candidate.comment.isNotBlank() }
            }

            tapExpandCandidateButton()

            val visibleRows = waitForExpandedCandidateRows {
                it.any { row -> row.index in structuredCandidates.indices }
            }
            val structuredRow = visibleRows.firstOrNull { row ->
                row.index in structuredCandidates.indices &&
                    structuredCandidates[row.index].comment.isNotBlank()
            }

            assertNotNull(
                "Expected at least one expanded row backed by a structured horizontal candidate; candidates=${structuredCandidates.toList()} rows=$visibleRows",
                structuredRow,
            )

            val row = structuredRow!!
            val expected = structuredCandidates[row.index]
            assertEquals(expected.text, row.text)
            assertEquals(expected.comment, row.comment)
            assertTrue(
                "Expanded row should still render the structured candidate text: row=$row expected=$expected",
                row.renderedText.contains(expected.text),
            )
            assertFalse(
                "Expanded row should hide T9 comment text while keeping the underlying structured candidate intact: row=$row expected=$expected",
                row.renderedText.contains(expected.comment),
            )
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun singleDigitT9InputShowsExpandButtonWhenEngineHasMoreThanFirstPage() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            tapVisibleT9Digit("2")

            val engineCandidates = withServiceFcitx(service) {
                getCandidates(0, 20).toList()
            }
            assertTrue(
                "Expected engine-side candidate pool for single digit 2 to exceed the first page; candidates=$engineCandidates",
                engineCandidates.size > 5,
            )

            tapExpandCandidateButton()

            val expandedRows = waitForExpandedCandidateRows { rows ->
                rows.size >= 6
            }
            assertTrue(
                "Expanded candidate window should expose more than the first five candidates for single digit 2; rows=$expandedRows engineCandidates=$engineCandidates",
                expandedRows.size >= 6,
            )
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun expandedSingleDigitTwoCandidateWindowHidesRenderedLatinPinyin() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            tapVisibleT9Digit("2")
            tapExpandCandidateButton()

            val expandedRows = waitForExpandedCandidateRows { rows ->
                rows.size >= 6
            }
            val rowWithLatin = expandedRows.firstOrNull { row ->
                row.renderedText.any { it in 'a'..'z' || it in 'A'..'Z' }
            }

            assertNull(
                "Single-digit 2 expanded candidates should not render Latin pinyin text; rows=$expandedRows",
                rowWithLatin,
            )
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun tappingExpandedSingleDigitCandidateBeyondFirstPageCommitsWithoutCrash() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            tapVisibleT9Digit("2")
            tapExpandCandidateButton()

            val expandedRows = waitForExpandedCandidateRows { rows ->
                rows.size >= 6
            }
            val targetRow = expandedRows[5]

            tapExpandedCandidateRowAt(5)
            waitForHostText(editText, targetRow.text)

            assertTrue(
                "Expanded candidate tap should commit the tapped row without crashing; row=$targetRow rows=$expandedRows",
                targetRow.text.isNotBlank(),
            )
        } finally {
            finishActivity(activity)
        }
    }

    @Ignore("Expanded->prediction return assertion is timing-sensitive on emulator in SDK shell mode")
    @Test
    fun expandedSelectionThatTriggersPredictionReturnsToMainCandidateBar() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            clearHostText(editText)
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            tapVisibleT9Digit("2")
            tapVisibleT9Digit("3")
            tapVisibleT9Digit("3")
            tapExpandCandidateButton()
            val expandedRows = waitForExpandedCandidateRows { rows -> rows.isNotEmpty() }
            val selectedCandidate = expandedRows.firstOrNull { it.text == "测" } ?: expandedRows.first()

            tapExpandedCandidateRow(selectedCandidate.text)
            waitForVisibleHorizontalCandidates { candidates ->
                candidates.any { it.text == "大" }
            }
            tapVisibleHorizontalCandidate("大")
            waitForClearedT9Composition(service)
            waitForHostText(editText) { host ->
                host.isNotBlank() && host.endsWith("大")
            }
            waitForExpandedCandidateWindowToClose()

            val predictionCandidates = waitForVisibleHorizontalCandidates { candidates ->
                candidates.isNotEmpty()
            }
            assertTrue(
                "Prediction should continue on the main candidate bar after expanded selection; candidates=$predictionCandidates",
                predictionCandidates.isNotEmpty(),
            )
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun expandingAndCollapsingCandidatesKeepsLiveSideRailState() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            tapVisibleT9Digit("2")
            tapVisibleT9Digit("3")
            tapVisibleSideRailItem("a")

            val beforeState = waitForLiveT9State {
                it.rawInput == "a3" &&
                    it.sideRailEntries.take(3).map { entry -> entry.text } == listOf("d", "e", "f") &&
                    it.sideRailEntries.none { entry -> entry.selected }
            }
            val beforeRail = waitForVisibleSideRailItems {
                it.take(3).map { item -> item.text } == listOf("d", "e", "f")
            }

            tapExpandCandidateButton()
            val expandedRows = waitForExpandedCandidateRows { it.isNotEmpty() }

            tapExpandCandidateButton()

            val afterState = waitForLiveT9State {
                it.rawInput == "a3" &&
                    it.sideRailEntries.take(3).map { entry -> entry.text } == listOf("d", "e", "f") &&
                    it.sideRailEntries.none { entry -> entry.selected }
            }
            val afterRail = waitForVisibleSideRailItems {
                it.take(3).map { item -> item.text } == listOf("d", "e", "f")
            }

            assertTrue(expandedRows.isNotEmpty())
            assertEquals("a3", beforeState.rawInput)
            assertEquals(listOf("d", "e", "f"), beforeRail.take(3).map { it.text })
            assertEquals("a3", afterState.rawInput)
            assertEquals(listOf("d", "e", "f"), afterState.sideRailEntries.take(3).map { it.text })
            assertEquals(listOf("d", "e", "f"), afterRail.take(3).map { it.text })
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun fixedDelimiterCommitsXiAnViaVisibleClicks() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            clearHostText(editText)
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            "94126".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            tapVisibleHorizontalCandidate("西安")

            waitForHostText(editText, "西安")
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun sideRailInterventionReshapesVisibleCandidatesViaVisibleClicks() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }
            "8426".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            val beforeState = waitForLiveT9State {
                it.sideRailEntries.size >= 2 && it.sideRailEntries.any { entry -> !entry.selected }
            }
            val beforeCandidates = waitForVisibleHorizontalCandidates { it.isNotEmpty() }
            val beforePreedit = waitForTopPreeditSnapshot { it.topText.isNotBlank() }
            val beforeRail = waitForVisibleSideRailItems { it.size >= 2 }
            val alternative = beforeState.sideRailEntries.firstOrNull { it.text == "ti" && !it.selected }?.text
                ?: beforeState.sideRailEntries.last { !it.selected }.text

            tapVisibleSideRailItem(alternative)

            val afterState = waitForLiveT9State {
                it.preedit != beforeState.preedit &&
                    it.rawInput != beforeState.rawInput &&
                    it.sideRailEntries.none { entry -> entry.selected }
            }
            val afterCandidates = waitForVisibleHorizontalCandidates { it.isNotEmpty() }
            val afterPreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() && it.topText != beforePreedit.topText
            }
            assertTrue(
                "Expected visible rail click to keep candidate surface alive after rewriting the live composition; rail=$alternative beforeRail=$beforeRail beforeCandidates=$beforeCandidates afterCandidates=$afterCandidates beforeState=$beforeState afterState=$afterState",
                afterCandidates.isNotEmpty(),
            )
            assertNotEquals("Expected rail click to update live preedit", beforePreedit.topText, afterPreedit.topText)
            assertFalse("Preedit should remain readable after rail click: $afterPreedit", afterPreedit.topText.any(Char::isDigit))
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun sideRailUiRefreshReturnsViewportToTop() = runBlocking {
        val activity = launchClipboardEditActivity()
        try {
            val railUi = readOnMain {
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val density = activity.resources.displayMetrics.density
                val railWidth = (84 * density).toInt()
                val railHeight = (196 * density).toInt()
                T9SideRailUi(activity, ThemePreset.MaterialLight).also { rail ->
                    root.addView(
                        rail,
                        FrameLayout.LayoutParams(railWidth, railHeight),
                    )
                    rail.submitState(
                        T9SideRailComponent.candidateState(
                            listOf("tian", "tiao", "ti", "t", "u", "v", "8").map { text ->
                                T9SideRailEntry(
                                    text = text,
                                    replacementWindow = T9ReplacementWindow(0, 1),
                                    selected = false,
                                )
                            },
                        ),
                    )
                }
            }
            delay(200)
            readOnMain { railUi.scrollToPosition(5) }
            delay(100)
            val beforeViewport = readOnMain { readSideRailViewport(railUi) }
            readOnMain {
                railUi.submitState(
                    T9SideRailComponent.candidateState(
                        listOf("tian", "tiao", "ti", "t", "u", "v", "8").map { text ->
                            T9SideRailEntry(
                                text = text,
                                replacementWindow = T9ReplacementWindow(0, 1),
                                selected = text == "v",
                            )
                        },
                    ),
                )
            }
            delay(100)
            val afterViewport = readOnMain { readSideRailViewport(railUi) }

            assertTrue(
                "Expected the pre-refresh viewport to already sit near the last group; before=$beforeViewport",
                beforeViewport.firstVisiblePosition > 0,
            )
            assertEquals(
                "Expected side rail refresh to jump back to the top instead of staying near the bottom; before=$beforeViewport after=$afterViewport",
                0,
                afterViewport.firstVisiblePosition,
            )
            assertTrue(
                "Expected refreshed side rail viewport to show the top group again; after=$afterViewport",
                afterViewport.visibleTexts.firstOrNull() == "tian",
            )
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun continuousSelectionCommitsBeijingDiTieZhanViaVisibleClicks() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            clearHostText(editText)
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            "2345464".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            tapVisibleHorizontalCandidate("北京")
            "34843".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            tapVisibleHorizontalCandidate("地铁")
            "9426".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            tapVisibleHorizontalCandidate("站")

            waitForHostText(editText, "北京地铁站")
        } finally {
            finishActivity(activity)
        }
    }

    @Ignore("Flaky under emulator timing after SDK shell migration; covered by other Stage3 acceptance flows")
    @Test
    fun mixedChainCommitsBeijingChongQingHuoCheViaVisibleClicks() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            clearHostText(editText)
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            "234546412466474641486243".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            tapHorizontalCandidateFromAdapter("北京")
            val beforeChong = waitForLiveT9State {
                it.sideRailEntries.any { entry -> !entry.selected && entry.text.startsWith("chong") }
            }
            tapVisibleSideRailItem("chong")
            waitForLiveT9State {
                it.preedit != beforeChong.preedit &&
                    it.sideRailEntries.any { entry -> !entry.selected && entry.text.startsWith("qing") }
            }
            tapVisibleSideRailItem("qing")
            tapHorizontalCandidateFromAdapter("重庆")
            tapHorizontalCandidateFromAdapter("火车")

            waitForHostText(editText, "北京重庆火车")
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun visibleBackspaceRollsBackConfirmedPrefixViaVisibleClicks() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            clearHostText(editText)
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            "2345464".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            tapVisibleHorizontalCandidate("北京")
            "348439426".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            waitForHostText(editText, "北京")
            val beforeState = waitForLiveT9State { it.rawInput.isNotBlank() && it.preedit.isNotBlank() }
            val beforePreedit = waitForTopPreeditSnapshot { it.topText.isNotBlank() }

            tapVisibleBackspaceButton()

            val afterState = waitForLiveT9State {
                it.preedit.isNotBlank() && it.preedit != beforeState.preedit
            }
            val afterPreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() && it.topText != beforePreedit.topText
            }
            assertNotEquals(
                "Visible backspace should rewind the live tail preview before touching the confirmed 北京 prefix; before=$beforePreedit after=$afterPreedit",
                beforePreedit.topText,
                afterPreedit.topText,
            )
            assertTrue(
                "Visible backspace should shorten the live tail while leaving the confirmed 北京 prefix on the host side untouched; before=$beforeState after=$afterState",
                afterState.rawInput.length < beforeState.rawInput.length,
            )
            assertEquals("北京", editText.text.toString())
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun selectingDiZhenThenZaiKeepsConfirmedPrefixAndShowsZaiCandidates() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            "349436924".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }

            tapHorizontalCandidateFromAdapter("地震")

            val beforeRail = waitForVisibleSideRailItems { items ->
                items.any { it.text == "zai" }
            }
            val beforeState = waitForLiveT9State { state ->
                state.preedit.isNotBlank()
            }

            tapVisibleSideRailItem("zai")

            val topPreedit = waitForTopPreeditSnapshot {
                val normalized = normalizeVisibleT9Preedit(it.topText)
                normalized == "地震zai" &&
                    !it.topText.contains("gen", ignoreCase = true) &&
                    !it.topText.contains('`') &&
                    !it.topText.contains('‘')
            }
            val afterState = waitForLiveT9State { state ->
                normalizeVisibleT9Preedit(state.preedit) == "地震zai"
            }
            val visibleCandidates = waitForVisibleHorizontalCandidates { candidates ->
                candidates.any { candidate ->
                    candidate.text in setOf("在", "再", "载", "灾", "宰", "仔")
                }
            }

            assertEquals("地震zai", normalizeVisibleT9Preedit(topPreedit.topText))
            assertFalse(
                "Top preedit should not regress to zai`gen`zai-style composition; top=$topPreedit beforeRail=$beforeRail beforeState=$beforeState afterState=$afterState snapshot=<${withServiceFcitx(service) { readRimeDebugSnapshot() }}>",
                topPreedit.topText.contains("gen", ignoreCase = true) ||
                    topPreedit.topText.contains('`') ||
                    topPreedit.topText.contains('‘'),
            )
            assertTrue(
                "Expected visible candidates to follow zai semantics after selecting side-rail zai; candidates=$visibleCandidates top=$topPreedit beforeRail=$beforeRail beforeState=$beforeState afterState=$afterState snapshot=<${withServiceFcitx(service) { readRimeDebugSnapshot() }}>",
                visibleCandidates.any { it.text in setOf("在", "再", "载", "灾", "宰", "仔") },
            )
        } finally {
            finishActivity(activity)
        }
    }

    @Ignore("Flaky side-rail undo timing on CI emulator; core backspace semantics are covered in stable cases")
    @Test
    fun visibleBackspaceRestoresLatestRailInterventionViaVisibleClicks() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            clearHostText(editText)
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            "8426".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            val beforeState = waitForLiveT9State {
                it.sideRailEntries.size >= 2 && it.sideRailEntries.any { entry -> !entry.selected }
            }
            val beforeCandidates = waitForVisibleHorizontalCandidates { it.isNotEmpty() }
            val alternative = beforeState.sideRailEntries.firstOrNull { it.text == "ti" && !it.selected }?.text
                ?: beforeState.sideRailEntries.last { !it.selected }.text

            tapVisibleSideRailItem(alternative)

            val afterRail = waitForLiveT9State {
                it.preedit != beforeState.preedit &&
                    it.sideRailEntries.any { entry -> entry.selected && entry.text == alternative }
            }
            val afterRailCandidates = waitForVisibleHorizontalCandidates { it != beforeCandidates }

            tapVisibleBackspaceButton()

            val afterBackspace = waitForLiveT9State {
                it.preedit != afterRail.preedit &&
                    it.sideRailEntries.none { entry -> entry.selected && entry.text == alternative }
            }
            val afterBackspaceCandidates = waitForVisibleHorizontalCandidates { it != afterRailCandidates }

            assertTrue(
                "Visible backspace should restore the latest rail intervention before touching host text; before=$beforeState afterRail=$afterRail afterBackspace=$afterBackspace",
                afterBackspace.preedit == beforeState.preedit ||
                    afterBackspace.sideRailEntries.map { it.text to it.selected } ==
                    beforeState.sideRailEntries.map { it.text to it.selected },
            )
            assertEquals("", editText.text.toString())
            assertEquals(
                "Visible backspace should restore the candidate surface after undoing the rail choice; before=$beforeCandidates afterRail=$afterRailCandidates afterBackspace=$afterBackspaceCandidates",
                beforeCandidates,
                afterBackspaceCandidates,
            )
        } finally {
            finishActivity(activity)
        }
    }

    @Ignore("Backspace tail-shrink timing is non-deterministic on emulator; stable backspace cases cover semantics")
    @Test
    fun visibleBackspaceDeletesBasicRawInputViaVisibleClicks() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            clearHostText(editText)

            "94126".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            val beforeState = waitForLiveT9State { it.rawInput.isNotBlank() }

            tapVisibleBackspaceButton()

            val afterState = waitForLiveT9State { it.rawInput.length < beforeState.rawInput.length }
            assertEquals(
                "Visible backspace should delete one raw-input unit when no confirmed-prefix rollback is pending; before=$beforeState after=$afterState",
                beforeState.rawInput.length - 1,
                afterState.rawInput.length,
            )
            assertEquals("", editText.text.toString())
        } finally {
            finishActivity(activity)
        }
    }

    @Test
    fun visibleBackspaceDeletesHostInputWhenCompositionIsClearViaVisibleClicks() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            clearHostText(editText)
            waitForClearedT9Composition(service)
            seedHostText(editText, "abcd")
            waitForHostText(editText, "abcd")

            tapVisibleBackspaceButton()

            waitForHostText(editText, "abc")
            val state = readLiveT9State(service)
            val preedit = readTopPreeditSnapshot(service)
            assertTrue(
                "Visible backspace should fall through to host deletion only after T9 composition is clear; state=$state preedit=$preedit",
                state.rawInput.isBlank() && state.preedit.isBlank() && preedit.topText.isBlank(),
            )
        } finally {
            finishActivity(activity)
        }
    }

    @Ignore("Large end-to-end ad-hoc scenario is timing sensitive on emulator; stable sub-scenarios are validated elsewhere")
    @Test
    fun freeTypingRegressionViaVisibleClicksCoversCoreAdHocScenarios() = runBlocking {
        val activity = prepareVisibleT9Ime()
        try {
            val service = waitForRunningFcitxService()
            val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
            clearHostText(editText)
            withServiceFcitx(service) {
                setCandidatePagingMode(1)
            }

            "94126".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            val xiAnPreedit = waitForTopPreeditSnapshot { it.topText.isNotBlank() && !it.topText.any(Char::isDigit) }
            tapVisibleHorizontalCandidate("西安")
            waitForHostText(editText, "西安")
            waitForClearedT9Composition(service)

            "2345464".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            val beijingPreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() && !it.topText.any(Char::isDigit) && !it.topText.contains("西安")
            }
            tapVisibleHorizontalCandidate("北京")
            waitForHostText(editText, "西安北京")

            "34843".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            tapVisibleHorizontalCandidate("地铁")
            "9426".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            tapVisibleHorizontalCandidate("站")
            waitForHostText(editText, "西安北京地铁站")
            waitForClearedT9Composition(service)

            "8426".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            val beforeRail = waitForLiveT9State {
                it.sideRailEntries.size >= 2 && it.sideRailEntries.any { entry -> !entry.selected }
            }
            val alternative = beforeRail.sideRailEntries.firstOrNull { it.text == "ti" && !it.selected }?.text
                ?: beforeRail.sideRailEntries.last { !it.selected }.text
            tapVisibleSideRailItem(alternative)
            val afterRail = waitForLiveT9State {
                it.preedit != beforeRail.preedit &&
                    it.sideRailEntries.any { entry -> entry.selected && entry.text == alternative }
            }
            val railRoundPreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() && !it.topText.any(Char::isDigit) && !it.topText.contains("北京地铁站")
            }
            val committedFromRail = tapFirstVisibleHorizontalCandidate()
            val hostAfterRailCommit = waitForHostText(editText) { host ->
                host.length > "西安北京地铁站".length
            }
            waitForClearedT9Composition(service)

            val beforeHostDelete = editText.text.toString()
            tapVisibleBackspaceButton()
            waitForHostText(editText, beforeHostDelete.dropLast(1))

            "641426".forEach { digit ->
                tapVisibleT9Digit(digit.toString())
            }
            val nextRoundPreedit = waitForTopPreeditSnapshot {
                it.topText.isNotBlank() && !it.topText.any(Char::isDigit)
            }
            tapExpandCandidateButton()
            val expandedRows = waitForExpandedCandidateRows { it.isNotEmpty() }

            assertTrue(
                "Fixed-delimiter round should expose a readable top preedit before commit; snapshot=$xiAnPreedit",
                xiAnPreedit.topText.isNotBlank(),
            )
            assertFalse(
                "Next round should not inherit the first committed word into live preedit; first=$xiAnPreedit second=$beijingPreedit",
                beijingPreedit.topText.contains("西安"),
            )
            assertFalse(
                "Rail intervention round should keep top preedit readable instead of falling back to digits; before=$beforeRail after=$afterRail preedit=$railRoundPreedit",
                railRoundPreedit.topText.any(Char::isDigit),
            )
            assertFalse(
                "Fresh round after host deletion should not leak the previous rail-commit word into new live preedit; committed=$committedFromRail hostAfterRailCommit=$hostAfterRailCommit next=$nextRoundPreedit",
                nextRoundPreedit.topText.contains(committedFromRail.text),
            )
            assertTrue(
                "Expanded candidate page should still be reachable during free typing regression; rows=$expandedRows",
                expandedRows.isNotEmpty(),
            )
        } finally {
            finishActivity(activity)
        }
    }

    private suspend fun prepareVisibleT9Ime(): Activity {
        Log.d("RimeIceStage3Ui", "prepare-ime: reset service env")
        stopSharedTestEnvironmentFcitx()
        switchSystemImeToFcitx()
        val activity = launchClipboardEditActivity()
        val service = waitForRunningFcitxService(activity)
        configureServiceForT9(service)
        Log.d(
            "RimeIceStage3Ui",
            "prepare-ime: service ready inputConnection=${service.currentInputConnection != null}",
        )
        prepareHostEditorForIme(service, activity)
        withServiceFcitx(service) {
            reset()
        }
        waitForClearedT9Composition(service)
        prepareHostEditorForIme(service, activity)
        waitForT9KeyboardLayout(service)
        Log.d("RimeIceStage3Ui", "prepare-ime: host ready")
        return activity
    }

    private suspend fun stopSharedTestEnvironmentFcitx() {
        runCatching {
            if (FcitxTestEnvironment.fcitx.isReady) {
                FcitxTestEnvironment.fcitx.stop()
                FcitxTestEnvironment.fcitx.lifecycle.whenStopped { }
            }
        }
    }

    private suspend fun waitForHorizontalStructuredCandidates(
        service: FcitxInputMethodService,
        predicate: (Array<FcitxEvent.Candidate>) -> Boolean,
    ): Array<FcitxEvent.Candidate> {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var lastCandidates = emptyArray<FcitxEvent.Candidate>()
        while (SystemClock.uptimeMillis() < deadline) {
            val candidates = readOnMain {
                extractHorizontalCandidateComponent(service)
                    ?.adapter
                    ?.candidates
                    ?: emptyArray()
            }
            if (candidates.isNotEmpty()) {
                lastCandidates = candidates
                if (predicate(candidates)) return candidates
            }
            delay(100)
        }
        fail("Timed out waiting for structured horizontal candidates; lastCandidates=${lastCandidates.toList()}")
        return emptyArray()
    }

    private suspend fun waitForTopPreeditSnapshot(
        predicate: (PreeditUiSnapshot) -> Boolean,
    ): PreeditUiSnapshot {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var lastSnapshot = PreeditUiSnapshot("", "", false)
        while (SystemClock.uptimeMillis() < deadline) {
            val service = findRunningFcitxService()
            val snapshot = service?.let(::readTopPreeditSnapshot)
            if (snapshot != null) {
                lastSnapshot = snapshot
                if (predicate(snapshot)) return snapshot
            }
            delay(100)
        }
        fail("Timed out waiting for top preedit UI snapshot; lastSnapshot=$lastSnapshot")
        throw AssertionError("unreachable")
    }

    private suspend fun waitForExpandedCandidateRows(
        predicate: (List<ExpandedCandidateRowSnapshot>) -> Boolean,
    ): List<ExpandedCandidateRowSnapshot> {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var lastRows = emptyList<ExpandedCandidateRowSnapshot>()
        while (SystemClock.uptimeMillis() < deadline) {
            val service = findRunningFcitxService()
            val rows = service?.let(::readExpandedCandidateRows).orEmpty()
            if (rows.isNotEmpty()) {
                lastRows = rows
                if (predicate(rows)) return rows
            }
            delay(100)
        }
        fail("Timed out waiting for expanded candidate rows; lastRows=$lastRows")
        throw AssertionError("unreachable")
    }

    private suspend fun waitForExpandedCandidateWindowToClose() {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var lastRows = emptyList<ExpandedCandidateRowSnapshot>()
        var lastCandidates = emptyList<HorizontalCandidateSnapshot>()
        while (SystemClock.uptimeMillis() < deadline) {
            val service = findRunningFcitxService()
            if (service != null) {
                val expandedRecycler = extractExpandedCandidateRecyclerView(service)
                if (expandedRecycler == null || !readOnMain { expandedRecycler.isShown }) {
                    return
                }
                lastRows = readExpandedCandidateRows(service)
                lastCandidates = readVisibleHorizontalCandidates(service)
            }
            delay(100)
        }
        fail(
            "Timed out waiting for expanded candidate window to close; " +
                "lastRows=$lastRows lastCandidates=$lastCandidates",
        )
        throw AssertionError("unreachable")
    }

    private suspend fun waitForVisibleHorizontalCandidates(
        predicate: (List<HorizontalCandidateSnapshot>) -> Boolean,
    ): List<HorizontalCandidateSnapshot> {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var lastCandidates = emptyList<HorizontalCandidateSnapshot>()
        var lastPreedit = PreeditUiSnapshot("", "", false)
        var lastState = T9UiState.Empty
        while (SystemClock.uptimeMillis() < deadline) {
            val service = findRunningFcitxService()
            val candidates = service?.let(::readVisibleHorizontalCandidates).orEmpty()
            if (service != null) {
                lastPreedit = readTopPreeditSnapshot(service)
                lastState = readLiveT9State(service)
            }
            if (candidates.isNotEmpty()) {
                lastCandidates = candidates
                if (predicate(candidates)) return candidates
            }
            delay(100)
        }
        fail("Timed out waiting for visible horizontal candidates; lastCandidates=$lastCandidates lastPreedit=$lastPreedit lastState=$lastState")
        throw AssertionError("unreachable")
    }

    private suspend fun waitForVisibleSideRailItems(
        predicate: (List<SideRailItemSnapshot>) -> Boolean,
    ): List<SideRailItemSnapshot> {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var lastItems = emptyList<SideRailItemSnapshot>()
        var lastCandidates = emptyList<HorizontalCandidateSnapshot>()
        while (SystemClock.uptimeMillis() < deadline) {
            val service = findRunningFcitxService()
            val items = service?.let(::readVisibleSideRailItems).orEmpty()
            if (service != null) {
                waitForT9KeyboardLayout(service)
                extractSideRailUi(service)?.let { railUi ->
                    readOnMain {
                        railUi.scrollToPosition(0)
                    }
                }
                lastCandidates = readVisibleHorizontalCandidates(service)
            }
            if (items.isNotEmpty()) {
                lastItems = items
                if (predicate(items)) return items
            }
            delay(100)
        }
        fail("Timed out waiting for visible side rail items; lastItems=$lastItems lastCandidates=$lastCandidates")
        throw AssertionError("unreachable")
    }

    private suspend fun waitForLiveT9State(
        predicate: (T9UiState) -> Boolean,
    ): T9UiState {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var lastState = T9UiState.Empty
        while (SystemClock.uptimeMillis() < deadline) {
            val service = findRunningFcitxService()
            val state = service?.let(::readLiveT9State)
            if (state != null) {
                lastState = state
                if (predicate(state)) return state
            }
            delay(100)
        }
        fail("Timed out waiting for live T9 state; lastState=$lastState")
        throw AssertionError("unreachable")
    }

    private suspend fun waitForClearedT9Composition(service: FcitxInputMethodService) {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var lastState = T9UiState.Empty
        var lastPreedit = PreeditUiSnapshot("", "", false)
        while (SystemClock.uptimeMillis() < deadline) {
            lastState = readLiveT9State(service)
            lastPreedit = readTopPreeditSnapshot(service)
            if (lastState.rawInput.isBlank() &&
                lastState.preedit.isBlank() &&
                lastPreedit.topText.isBlank() &&
                lastPreedit.bottomText.isBlank()
            ) {
                return
            }
            delay(100)
        }
        fail("Timed out waiting for cleared T9 composition; lastState=$lastState lastPreedit=$lastPreedit")
        throw AssertionError("unreachable")
    }

    private suspend fun waitForRunningFcitxService(activity: Activity? = null): FcitxInputMethodService {
        val deadline = SystemClock.uptimeMillis() + SERVICE_WAIT_TIMEOUT_MS
        var lastCurrentIme = ""
        var lastEnabledIme = ""
        while (SystemClock.uptimeMillis() < deadline) {
            activity?.let {
                requestImeFromHostEditor(it)
                lastCurrentIme = runShellCommand("settings get secure default_input_method").trim()
                if (lastCurrentIme != currentFcitxImeId()) {
                    switchSystemImeToFcitx()
                    lastCurrentIme = runShellCommand("settings get secure default_input_method").trim()
                }
                lastEnabledIme = runShellCommand("settings get secure enabled_input_methods").trim()
            }
            findRunningFcitxService()?.let {
                val ready = runCatching { serviceFcitx(it).runImmediately { isReady } }.getOrDefault(false)
                if (!ready) {
                    delay(100)
                    return@let
                }
                Log.d(
                    "RimeIceStage3Ui",
                    "wait-service: found inputConnection=${it.currentInputConnection != null} activityProvided=${activity != null}",
                )
                return it
            }
            delay(100)
        }
        fail(
            "Timed out waiting for running FcitxInputMethodService; " +
                "currentIme=$lastCurrentIme enabledIme=$lastEnabledIme activityProvided=${activity != null}",
        )
        throw AssertionError("unreachable")
    }

    private suspend fun waitForCurrentIme(
        service: FcitxInputMethodService,
        expectedUniqueName: String,
    ) {
        repeat(50) {
            val currentIme = withServiceFcitx(service) { currentIme().uniqueName }
            if (currentIme == expectedUniqueName) {
                return
            }
            delay(100)
        }
        fail("Timed out waiting for current IME '$expectedUniqueName'")
    }

    private suspend fun waitForCurrentSchema(
        service: FcitxInputMethodService,
        expectedSchemaId: String,
    ) {
        repeat(50) {
            val schemaId = withServiceFcitx(service) { currentSchema() }
            if (schemaId == expectedSchemaId) {
                return
            }
            delay(100)
        }
        fail("Timed out waiting for current schema '$expectedSchemaId'")
    }

    private suspend fun configureServiceForT9(service: FcitxInputMethodService) {
        withServiceFcitx(service) {
            setEnabledIme(arrayOf("rime"))
            activateIme("rime")
            setSchema("t9")
            focus(true)
        }
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val ready = withServiceFcitx(service) {
                currentIme().uniqueName == "rime" && currentSchema() == "t9"
            }
            if (ready) return
            delay(100)
        }
        fail("Timed out configuring live IME service for rime/t9")
    }

    private suspend fun tapVisibleHorizontalCandidate(expectedText: String) {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var lastCandidates = emptyList<HorizontalCandidateSnapshot>()
        var lastPreedit = PreeditUiSnapshot("", "", false)
        var lastChildDebug = emptyList<String>()
        while (SystemClock.uptimeMillis() < deadline) {
            val service = findRunningFcitxService()
            val recyclerView = service?.let(::extractHorizontalCandidateRecyclerView)
            if (recyclerView != null) {
                lastCandidates = readVisibleHorizontalCandidates(service)
                lastPreedit = readTopPreeditSnapshot(service)
                if (lastCandidates.any { candidate ->
                        candidateLabelMatches(
                            candidateText = candidate.text,
                            renderedText = candidate.renderedText,
                            expectedText = expectedText,
                        )
                    } && tapUiObjectByText(expectedText)
                ) {
                    return
                }
                val (targetView, childDebug) = readOnMain {
                    val childLines = mutableListOf<String>()
                    var resolved: View? = null
                    for (index in 0 until recyclerView.childCount) {
                        val child = recyclerView.getChildAt(index) ?: continue
                        val holder = recyclerView.findContainingViewHolder(child) as? CandidateViewHolder
                        val candidateText = holder?.candidate?.text.orEmpty()
                        val renderedText = holder?.ui?.text?.text?.toString().orEmpty()
                        childLines += "child=$index idx=${holder?.idx ?: -1} shown=${child.isShown} size=${child.width}x${child.height} candidate=$candidateText rendered=$renderedText itemTap=${hasTapTarget(child)} textTap=${holder?.ui?.text?.let(::hasTapTarget) == true} itemBounds=${hasUsableBounds(child)} textBounds=${holder?.ui?.text?.let(::hasUsableBounds) == true}"
                        if (resolved == null && candidateLabelMatches(
                                candidateText = candidateText,
                                renderedText = renderedText,
                                expectedText = expectedText,
                            )
                        ) {
                            resolved = holder?.ui?.text?.takeIf(::hasTapTarget)
                                ?: child.takeIf(::hasTapTarget)
                        }
                    }
                    resolved to childLines
                }
                lastChildDebug = childDebug
                if (targetView != null) {
                    tapViewOnScreen(targetView)
                    return
                }
            }
            if (tapUiObjectByText(expectedText)) return
            delay(100)
        }
        fail(
            "Timed out waiting for visible horizontal candidate '$expectedText'; " +
                "lastCandidates=$lastCandidates lastPreedit=$lastPreedit lastChildDebug=$lastChildDebug",
        )
        throw AssertionError("unreachable")
    }

    private suspend fun tapHorizontalCandidateFromAdapter(expectedText: String) {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var lastAdapterCandidates = emptyArray<FcitxEvent.Candidate>()
        var lastVisibleCandidates = emptyList<HorizontalCandidateSnapshot>()
        var lastPreedit = PreeditUiSnapshot("", "", false)
        while (SystemClock.uptimeMillis() < deadline) {
            val service = findRunningFcitxService()
            val recyclerView = service?.let(::extractHorizontalCandidateRecyclerView)
            val adapterCandidates = service?.let {
                readOnMain {
                    extractHorizontalCandidateComponent(it)?.adapter?.candidates ?: emptyArray()
                }
            } ?: emptyArray()
            if (service != null) {
                lastAdapterCandidates = adapterCandidates
                lastVisibleCandidates = readVisibleHorizontalCandidates(service)
                lastPreedit = readTopPreeditSnapshot(service)
                if (lastVisibleCandidates.any { candidate ->
                        candidateLabelMatches(
                            candidateText = candidate.text,
                            renderedText = candidate.renderedText,
                            expectedText = expectedText,
                        )
                    }
                ) {
                    tapVisibleHorizontalCandidate(expectedText)
                    return
                }
            }
            if (recyclerView != null) {
                val targetIndex = adapterCandidates.indexOfFirst { candidate ->
                    candidateLabelMatches(
                        candidateText = candidate.text,
                        renderedText = buildString {
                            append(candidate.text)
                            if (candidate.comment.isNotBlank()) {
                                append(' ')
                                append(candidate.comment)
                            }
                        },
                        expectedText = expectedText,
                    )
                }
                if (targetIndex >= 0) {
                    readOnMain {
                        recyclerView.scrollToPosition(targetIndex)
                    }
                    delay(75)
                    val targetView = readOnMain {
                        (recyclerView.findViewHolderForAdapterPosition(targetIndex) as? CandidateViewHolder)?.let { holder ->
                            holder.ui.text.takeIf(::hasTapTarget)
                                ?: holder.itemView.takeIf(::hasTapTarget)
                        } ?: run {
                            for (index in 0 until recyclerView.childCount) {
                                val child = recyclerView.getChildAt(index) ?: continue
                                val holder = recyclerView.findContainingViewHolder(child) as? CandidateViewHolder ?: continue
                                if ((
                                        holder.idx == targetIndex || candidateLabelMatches(
                                            candidateText = holder.candidate.text,
                                            renderedText = holder.ui.text.text.toString(),
                                            expectedText = expectedText,
                                        )
                                        ) &&
                                    (hasTapTarget(holder.ui.text) || hasTapTarget(child))
                                ) {
                                    return@readOnMain holder.ui.text.takeIf(::hasTapTarget) ?: child
                                }
                            }
                            null
                        }
                    }
                    if (targetView != null) {
                        tapViewOnScreen(targetView)
                        return
                    }
                }
            }
            if (tapUiObjectByText(expectedText)) return
            delay(100)
        }
        fail(
            "Timed out waiting for horizontal adapter candidate '$expectedText'; " +
                "lastAdapterCandidates=${lastAdapterCandidates.toList()} " +
                "lastVisibleCandidates=$lastVisibleCandidates lastPreedit=$lastPreedit",
        )
        throw AssertionError("unreachable")
    }

    private suspend fun tapFirstVisibleHorizontalCandidate(): HorizontalCandidateSnapshot {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val service = findRunningFcitxService()
            val candidates = service?.let(::readVisibleHorizontalCandidates).orEmpty()
            if (candidates.isNotEmpty()) {
                val first = candidates.first()
                tapVisibleHorizontalCandidate(first.text)
                return first
            }
            delay(100)
        }
        fail("Timed out waiting for the first visible horizontal candidate")
        throw AssertionError("unreachable")
    }

    private suspend fun tapVisibleSideRailItem(expectedText: String) {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var lastItems = emptyList<SideRailItemSnapshot>()
        var lastCandidates = emptyList<HorizontalCandidateSnapshot>()
        while (SystemClock.uptimeMillis() < deadline) {
            val service = findRunningFcitxService()
            val railUi = service?.let(::extractSideRailUi)
            if (railUi != null) {
                waitForT9KeyboardLayout(service)
                readOnMain {
                    railUi.scrollToPosition(0)
                }
                delay(75)
                lastItems = readVisibleSideRailItems(service)
                lastCandidates = readVisibleHorizontalCandidates(service)
                val itemCount = readOnMain {
                    railUi.adapter?.itemCount ?: 0
                }
                for (position in 0 until itemCount) {
                    readOnMain {
                        railUi.scrollToPosition(position)
                    }
                    delay(75)
                    val itemView = readOnMain {
                        railUi.findViewHolderForAdapterPosition(position)?.itemView
                    } ?: continue
                    val text = findFirstTextViewText(itemView)
                    if (text == expectedText && hasUsableBounds(itemView)) {
                        tapViewOnScreen(itemView)
                        return
                    }
                }
            }
            delay(100)
        }
        fail(
            "Timed out waiting for visible side rail item '$expectedText'; " +
                "lastItems=$lastItems lastCandidates=$lastCandidates",
        )
        throw AssertionError("unreachable")
    }

    private suspend fun tapExpandCandidateButton() {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val button = findRunningFcitxService()
                ?.let(::extractInputView)
                ?.findViewById<View>(R.id.expand_candidate_btn)
                ?.takeIf { it.isShown && it.visibility == View.VISIBLE }
            if (button != null) {
                tapViewOnScreen(button)
                return
            }
            delay(100)
        }
        fail("Timed out waiting for visible expand candidate button")
        throw AssertionError("unreachable")
    }

    private suspend fun tapExpandedCandidateRow(expectedText: String) {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var lastRows = emptyList<ExpandedCandidateRowSnapshot>()
        while (SystemClock.uptimeMillis() < deadline) {
            val service = findRunningFcitxService()
            val recyclerView = service?.let(::extractExpandedCandidateRecyclerView)
            if (service != null) {
                lastRows = readExpandedCandidateRows(service)
            }
            if (recyclerView != null) {
                val itemCount = readOnMain {
                    recyclerView.adapter?.itemCount ?: 0
                }
                for (position in 0 until itemCount) {
                    readOnMain {
                        recyclerView.scrollToPosition(position)
                    }
                    delay(75)
                    val holder = readOnMain {
                        recyclerView.findViewHolderForAdapterPosition(position) as? CandidateViewHolder
                    }
                    if (holder != null && candidateLabelMatches(
                            candidateText = holder.candidate.text,
                            renderedText = holder.ui.text.text.toString(),
                            expectedText = expectedText,
                        ) && hasUsableBounds(holder.itemView)
                    ) {
                        tapViewOnScreen(holder.itemView)
                        return
                    }
                }
            }
            delay(100)
        }
        fail("Timed out waiting for expanded candidate row '$expectedText'; lastRows=$lastRows")
        throw AssertionError("unreachable")
    }

    private suspend fun tapExpandedCandidateRowAt(position: Int) {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var lastRows = emptyList<ExpandedCandidateRowSnapshot>()
        while (SystemClock.uptimeMillis() < deadline) {
            val service = findRunningFcitxService()
            val recyclerView = service?.let(::extractExpandedCandidateRecyclerView)
            if (service != null) {
                lastRows = readExpandedCandidateRows(service)
            }
            if (recyclerView != null) {
                readOnMain {
                    recyclerView.scrollToPosition(position)
                }
                delay(75)
                val holder = readOnMain {
                    recyclerView.findViewHolderForAdapterPosition(position) as? CandidateViewHolder
                }
                if (holder != null && hasUsableBounds(holder.itemView)) {
                    tapViewOnScreen(holder.itemView)
                    return
                }
            }
            delay(100)
        }
        fail("Timed out waiting for expanded candidate row at position=$position; lastRows=$lastRows")
        throw AssertionError("unreachable")
    }

    private fun findRunningFcitxService(): FcitxInputMethodService? {
        FcitxInputMethodService.debugLatestInstance?.let { return it }
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

    private suspend fun tapVisibleT9Digit(label: String) {
        val view = waitForVisibleT9Digit(label)
        tapView(view)
        delay(150)
    }

    private suspend fun tapVisibleT9TextKey(label: String) {
        val view = waitForVisibleT9TextKey(label)
        tapView(view)
        delay(150)
    }

    private suspend fun tapVisibleT9Retype() {
        val view = waitForVisibleT9TextKey("重输")
        tapView(view)
        delay(150)
    }

    private suspend fun tapVisibleKeyboardTextKey(label: String) {
        val view = waitForVisibleKeyboardTextKey(label)
        tapView(view)
        delay(150)
    }

    private suspend fun longPressVisibleKeyboardTextKey(vararg labels: String) {
        val view = waitForVisibleKeyboardTextKey(*labels)
        longPressView(view)
        delay(150)
    }

    private suspend fun selectVisibleKeyboardPopupText(
        popupLabel: String,
        vararg triggerLabels: String,
    ) {
        val triggerView = waitForVisibleKeyboardTextKey(*triggerLabels)
        longPressAndReleaseViewForPopupLabel(triggerView, popupLabel)
    }

    private suspend fun selectVisibleT9PopupText(
        popupLabel: String,
        triggerLabel: String,
    ) {
        val triggerView =
            if (triggerLabel.length == 1 && triggerLabel[0].isDigit()) {
                waitForVisibleT9Digit(triggerLabel)
            } else {
                waitForVisibleT9TextKey(triggerLabel)
            }
        longPressAndReleaseViewForPopupLabel(triggerView, popupLabel)
    }

    private suspend fun tapVisiblePopupText(label: String) {
        val view = waitForVisiblePopupText(label)
        tapViewOnScreen(view)
        delay(150)
    }

    private suspend fun tapVisibleBackspaceButton() {
        tapViewOnScreen(waitForVisibleBackspaceButton())
        delay(150)
    }

    private suspend fun waitForVisibleT9Digit(label: String): View = waitForVisibleT9TextKey(*visibleT9DigitLabels(label))

    private fun visibleT9DigitLabels(label: String): Array<String> = when (label) {
        "1" -> arrayOf("1", "'")
        "2" -> arrayOf("2", "ABC")
        "3" -> arrayOf("3", "DEF")
        "4" -> arrayOf("4", "GHI")
        "5" -> arrayOf("5", "JKL")
        "6" -> arrayOf("6", "MNO")
        "7" -> arrayOf("7", "PQRS")
        "8" -> arrayOf("8", "TUV")
        "9" -> arrayOf("9", "WXYZ")
        else -> arrayOf(label)
    }

    private suspend fun waitForVisibleT9TextKey(label: String): View = waitForVisibleT9TextKey(*arrayOf(label))

    private suspend fun waitForVisibleT9TextKey(vararg labels: String): View {
        var lastDebug = ""
        repeat(50) {
            findRunningFcitxService()?.let { service ->
                waitForT9KeyboardLayout(service)
                lastDebug = debugT9KeyboardState(service)
                extractVisibleT9MainKeyboard(service)
                    ?.let { keyboard ->
                        labels.firstNotNullOfOrNull { label ->
                            findVisibleTextKey(keyboard as View, label)
                        }?.let { return it }
                    }
            }
            delay(100)
        }
        fail("Timed out waiting for visible T9 digit key ${labels.toList()}; $lastDebug")
        throw AssertionError("unreachable")
    }

    private suspend fun waitForVisibleKeyboardTextKey(label: String): View = waitForVisibleKeyboardTextKey(*arrayOf(label))

    private suspend fun waitForVisibleKeyboardTextKey(vararg labels: String): View {
        var lastDebug = ""
        repeat(50) {
            findRunningFcitxService()?.let { service ->
                lastDebug = debugKeyboardState(service)
                extractCurrentKeyboardView(service)
                    ?.let { keyboard ->
                        labels.firstNotNullOfOrNull { label -> findVisibleTextKey(keyboard, label) }?.let { return it }
                    }
            }
            delay(100)
        }
        fail("Timed out waiting for visible keyboard key ${labels.toList()}; $lastDebug")
        throw AssertionError("unreachable")
    }

    private suspend fun waitForVisiblePopupText(label: String): View {
        repeat(50) {
            findRunningFcitxService()?.let { service ->
                extractPopupRoot(service)
                    ?.let { popupRoot ->
                        findVisiblePopupText(popupRoot, label)?.let { return it }
                    }
            }
            delay(100)
        }
        fail("Timed out waiting for visible popup text $label")
        throw AssertionError("unreachable")
    }

    private suspend fun waitForKeyboardDispatch() {
        repeat(20) {
            if (KeyboardWindow.debugLastKeyAction.isNotBlank()) {
                return
            }
            delay(50)
        }
        fail("Timed out waiting for keyboard dispatch")
    }

    private suspend fun waitForVisibleBackspaceButton(): View {
        repeat(50) {
            findRunningFcitxService()?.let { service ->
                waitForT9KeyboardLayout(service)
                val backspace = (extractVisibleT9MainKeyboard(service) as? View)
                    ?.findViewById<View>(R.id.button_backspace)
                    ?.takeIf(::hasUsableBounds)
                if (backspace != null) return backspace
            }
            delay(100)
        }
        fail("Timed out waiting for visible T9 backspace button")
        throw AssertionError("unreachable")
    }

    private fun readTopPreeditSnapshot(service: FcitxInputMethodService): PreeditUiSnapshot {
        return readOnMain {
            val inputView = extractInputView(service) ?: return@readOnMain PreeditUiSnapshot("", "", false)
            val top = inputView.findViewWithTag<View>(TOP_PREEDIT_TAG) as? TextView
            val bottom = inputView.findViewWithTag<View>("fcitx-preedit-bottom") as? TextView
            val topSpanned = top?.text as? Spanned
            val underlineCoverageLength = topSpanned
                ?.getSpans(0, topSpanned.length, UnderlineSpan::class.java)
                ?.map { span ->
                    topSpanned.getSpanEnd(span) - topSpanned.getSpanStart(span)
                }
                ?.maxOrNull()
                ?: 0
            PreeditUiSnapshot(
                topText = top?.text?.toString().orEmpty(),
                bottomText = bottom?.text?.toString().orEmpty(),
                visible = top?.isShown == true || bottom?.isShown == true,
                underlineCoverageLength = underlineCoverageLength,
            )
        }
    }

    private fun readExpandedCandidateRows(service: FcitxInputMethodService): List<ExpandedCandidateRowSnapshot> {
        return readOnMain {
            val inputView = extractInputView(service) ?: return@readOnMain emptyList()
            val expandedRoot = inputView.findViewById<View>(R.id.expanded_candidate_view) ?: return@readOnMain emptyList()
            val recyclerView = findFirstRecyclerView(expandedRoot) ?: return@readOnMain emptyList()
            buildList {
                for (index in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(index) ?: continue
                    val holder = recyclerView.findContainingViewHolder(child) as? CandidateViewHolder ?: continue
                    add(
                        ExpandedCandidateRowSnapshot(
                            index = holder.idx,
                            text = holder.candidate.text,
                            comment = holder.candidate.comment,
                            renderedText = holder.ui.text.text.toString(),
                        ),
                    )
                }
            }
        }
    }

    private fun normalizeVisibleT9Preedit(text: String): String = text.filterNot { it == ' ' || it == '\'' }

    private fun readVisibleHorizontalCandidates(service: FcitxInputMethodService): List<HorizontalCandidateSnapshot> {
        return readOnMain {
            val recyclerView = extractHorizontalCandidateRecyclerView(service) ?: return@readOnMain emptyList()
            buildList {
                for (index in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(index) ?: continue
                    val holder = recyclerView.findContainingViewHolder(child) as? CandidateViewHolder ?: continue
                    add(
                        HorizontalCandidateSnapshot(
                            index = holder.idx,
                            text = holder.candidate.text,
                            comment = holder.candidate.comment,
                            renderedText = holder.ui.text.text.toString(),
                        ),
                    )
                }
            }
        }
    }

    private fun readVisibleSideRailItems(service: FcitxInputMethodService): List<SideRailItemSnapshot> {
        return readOnMain {
            val railUi = extractSideRailUi(service) ?: return@readOnMain emptyList()
            buildList {
                for (index in 0 until railUi.childCount) {
                    val child = railUi.getChildAt(index) ?: continue
                    val texts = collectTextViews(child)
                    if (texts.isEmpty()) continue
                    add(
                        SideRailItemSnapshot(
                            text = texts.first(),
                            hint = texts.getOrNull(1).orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    private fun readSideRailViewport(service: FcitxInputMethodService): SideRailViewportSnapshot {
        return readOnMain {
            val railUi = extractSideRailUi(service) ?: return@readOnMain SideRailViewportSnapshot(
                firstVisiblePosition = -1,
                lastVisiblePosition = -1,
                itemCount = 0,
                visibleTexts = emptyList(),
            )
            val layoutManager = railUi.layoutManager as? LinearLayoutManager
            SideRailViewportSnapshot(
                firstVisiblePosition = layoutManager?.findFirstVisibleItemPosition() ?: -1,
                lastVisiblePosition = layoutManager?.findLastVisibleItemPosition() ?: -1,
                itemCount = railUi.adapter?.itemCount ?: 0,
                visibleTexts = buildList {
                    for (index in 0 until railUi.childCount) {
                        val child = railUi.getChildAt(index) ?: continue
                        collectTextViews(child).firstOrNull()?.let(::add)
                    }
                },
            )
        }
    }

    private fun readSideRailViewport(railUi: RecyclerView): SideRailViewportSnapshot {
        val layoutManager = railUi.layoutManager as? LinearLayoutManager
        return SideRailViewportSnapshot(
            firstVisiblePosition = layoutManager?.findFirstVisibleItemPosition() ?: -1,
            lastVisiblePosition = layoutManager?.findLastVisibleItemPosition() ?: -1,
            itemCount = railUi.adapter?.itemCount ?: 0,
            visibleTexts = buildList {
                for (index in 0 until railUi.childCount) {
                    val child = railUi.getChildAt(index) ?: continue
                    collectTextViews(child).firstOrNull()?.let(::add)
                }
            },
        )
    }

    private fun readLiveT9State(service: FcitxInputMethodService): T9UiState = readOnMain {
        service.t9UiState.value
    }

    private fun findFirstRecyclerView(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findFirstRecyclerView(group.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun extractHorizontalCandidateComponent(service: FcitxInputMethodService): HorizontalCandidateComponent? {
        val inputView = extractInputView(service) as? InputView ?: return null
        val field = InputView::class.java.getDeclaredField("horizontalCandidate").apply {
            isAccessible = true
        }
        return field.get(inputView) as? HorizontalCandidateComponent
    }

    private fun extractHorizontalCandidateRecyclerView(service: FcitxInputMethodService): RecyclerView? = extractHorizontalCandidateComponent(service)?.view

    private fun extractExpandedCandidateRecyclerView(service: FcitxInputMethodService): RecyclerView? {
        val inputView = extractInputView(service) ?: return null
        val expandedRoot = inputView.findViewById<View>(R.id.expanded_candidate_view) ?: return null
        return findFirstRecyclerView(expandedRoot)
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
        val field = InputView::class.java.getDeclaredField("keyboardWindow").apply {
            isAccessible = true
        }
        return field.get(inputView) as? KeyboardWindow
    }

    private fun extractT9Keyboard(service: FcitxInputMethodService): T9Keyboard? {
        val inputView = extractInputView(service) as? InputView ?: return null
        val keyboardWindow = extractKeyboardWindow(inputView) ?: return null

        @Suppress("UNCHECKED_CAST")
        val keyboards = KeyboardWindow::class.java
            .getDeclaredMethod("getKeyboards")
            .apply { isAccessible = true }
            .invoke(keyboardWindow) as? Map<String, Any> ?: return null
        return keyboards[T9Keyboard.Name] as? T9Keyboard
    }

    private fun extractCurrentKeyboardView(service: FcitxInputMethodService): View? {
        val inputView = extractInputView(service) as? InputView ?: return null
        val keyboardWindow = extractKeyboardWindow(inputView) ?: return null
        val currentKeyboardName = KeyboardWindow::class.java
            .getDeclaredField("currentKeyboardName")
            .apply { isAccessible = true }
            .get(keyboardWindow) as? String ?: return null

        @Suppress("UNCHECKED_CAST")
        val keyboards = KeyboardWindow::class.java
            .getDeclaredMethod("getKeyboards")
            .apply { isAccessible = true }
            .invoke(keyboardWindow) as? Map<String, View> ?: return null
        return keyboards[currentKeyboardName]
    }

    private fun extractPopupRoot(service: FcitxInputMethodService): View? {
        val inputView = extractInputView(service) as? InputView ?: return null
        val field = InputView::class.java.getDeclaredField("popup").apply {
            isAccessible = true
        }
        val popupComponent = field.get(inputView)
        val rootGetter = popupComponent.javaClass.methods.firstOrNull {
            it.name == "getRoot" && it.parameterCount == 0
        } ?: popupComponent.javaClass.getDeclaredMethod("getRoot").apply {
            isAccessible = true
        }
        return rootGetter.invoke(popupComponent) as? View
    }

    private fun extractSideRailUi(service: FcitxInputMethodService): RecyclerView? {
        val keyboard = extractT9Keyboard(service) ?: return null
        val field = T9Keyboard::class.java.getDeclaredField("railUi").apply {
            isAccessible = true
        }
        return field.get(keyboard) as? RecyclerView
    }

    private fun debugT9KeyboardState(service: FcitxInputMethodService): String {
        val inputView = extractInputView(service) as? InputView ?: return "inputView=null"
        val keyboardWindow = extractKeyboardWindow(inputView) ?: return "keyboardWindow=null"
        val currentKeyboardName = KeyboardWindow::class.java
            .getDeclaredField("currentKeyboardName")
            .apply { isAccessible = true }
            .get(keyboardWindow) as? String
        val mainKeyboard = extractVisibleT9MainKeyboard(service) as? View
        val labels = linkedSetOf<String>()
        mainKeyboard?.let { collectTextLabels(it, labels) }
        return "currentKeyboardName=$currentKeyboardName mainKeyboardPresent=${mainKeyboard != null} mainKeyboardShown=${mainKeyboard?.isShown} labels=$labels"
    }

    private fun debugKeyboardState(service: FcitxInputMethodService): String {
        val inputView = extractInputView(service) as? InputView ?: return "inputView=null"
        val keyboardWindow = extractKeyboardWindow(inputView) ?: return "keyboardWindow=null"
        val currentKeyboardName = KeyboardWindow::class.java
            .getDeclaredField("currentKeyboardName")
            .apply { isAccessible = true }
            .get(keyboardWindow) as? String
        val currentKeyboard = extractCurrentKeyboardView(service)
        val labels = linkedSetOf<String>()
        currentKeyboard?.let { collectTextLabels(it, labels) }
        return "currentKeyboardName=$currentKeyboardName currentKeyboardPresent=${currentKeyboard != null} labels=$labels"
    }

    private suspend fun waitForT9KeyboardLayout(service: FcitxInputMethodService) {
        repeat(50) {
            ensureT9KeyboardLayout(service)
            val inputView = extractInputView(service) as? InputView
            val keyboardWindow = inputView?.let(::extractKeyboardWindow)
            val currentKeyboardName = keyboardWindow?.let {
                KeyboardWindow::class.java
                    .getDeclaredField("currentKeyboardName")
                    .apply { isAccessible = true }
                    .get(it) as? String
            }
            if (currentKeyboardName == T9Keyboard.Name) return
            delay(100)
        }
        fail("Timed out waiting for T9 keyboard layout")
    }

    private suspend fun waitForKeyboardLayout(service: FcitxInputMethodService, expectedName: String) {
        repeat(50) {
            val inputView = extractInputView(service) as? InputView
            val keyboardWindow = inputView?.let(::extractKeyboardWindow)
            val currentKeyboardName = keyboardWindow?.let {
                KeyboardWindow::class.java
                    .getDeclaredField("currentKeyboardName")
                    .apply { isAccessible = true }
                    .get(it) as? String
            }
            if (currentKeyboardName == expectedName) return
            delay(100)
        }
        fail("Timed out waiting for keyboard layout $expectedName")
    }

    private suspend fun switchSystemImeToFcitx() {
        val imeId = currentFcitxImeId()
        var lastEnableOutput = ""
        var lastSetOutput = ""
        repeat(50) {
            lastEnableOutput = runShellCommand("ime enable $imeId").trim()
            lastSetOutput = runShellCommand("ime set $imeId").trim()
            val currentIme = runShellCommand("settings get secure default_input_method").trim()
            if (currentIme == imeId) {
                return
            }
            delay(100)
        }
        val currentIme = runShellCommand("settings get secure default_input_method").trim()
        val enabledIme = runShellCommand("settings get secure enabled_input_methods").trim()
        val listedIme = runShellCommand("ime list -s").trim()
        fail(
            "Timed out switching system IME to Fcitx; " +
                "currentIme=$currentIme enabledIme=$enabledIme listedIme=$listedIme " +
                "enableOutput=$lastEnableOutput setOutput=$lastSetOutput",
        )
    }

    private fun currentFcitxImeId(): String = FcitxTestEnvironment.currentImeId()

    private fun runShellCommand(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { reader ->
            reader.readText()
        }
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

    private fun extractInputView(service: FcitxInputMethodService): View? {
        val field = FcitxInputMethodService::class.java.getDeclaredField("inputView").apply {
            isAccessible = true
        }
        return field.get(service) as? View
    }

    private fun serviceFcitx(service: FcitxInputMethodService): FcitxConnection {
        val field = FcitxInputMethodService::class.java.getDeclaredField("fcitx").apply {
            isAccessible = true
        }
        return field.get(service) as FcitxConnection
    }

    private suspend fun waitForServiceFcitxReady(service: FcitxInputMethodService) {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val ready = runCatching { serviceFcitx(service).runImmediately { isReady } }.getOrDefault(false)
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

    private fun longPressView(view: View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            view.performLongClick()
        }
    }

    private suspend fun longPressAndReleaseViewForPopupLabel(triggerView: View, popupLabel: String) {
        val gesture = pressAndHoldView(triggerView)
        waitForVisiblePopupText(popupLabel)
        releaseHeldView(gesture)
        delay(150)
    }

    private data class HeldViewGesture(
        val view: View,
        val downTime: Long,
    )

    private fun pressAndHoldView(view: View): HeldViewGesture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        val downEvent = readOnMain {
            MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                view.width / 2f,
                view.height / 2f,
                0,
            )
        }
        instrumentation.runOnMainSync {
            view.dispatchTouchEvent(downEvent)
        }
        downEvent.recycle()
        Thread.sleep(650)
        return HeldViewGesture(view, downTime)
    }

    private fun releaseHeldView(gesture: HeldViewGesture) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val upEvent = readOnMain {
            MotionEvent.obtain(
                gesture.downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                gesture.view.width / 2f,
                gesture.view.height / 2f,
                0,
            )
        }
        instrumentation.runOnMainSync {
            gesture.view.dispatchTouchEvent(upEvent)
        }
        upEvent.recycle()
    }

    private fun popupSelectionTargetView(view: View): View {
        val parent = view.parent as? View ?: return view
        return if (parent.width >= view.width && parent.height >= view.height) parent else view
    }

    private fun clickView(view: View) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.performClick()
        }
    }

    private fun tapViewOnScreen(view: View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            val device = UiDevice.getInstance(instrumentation)
            val center = readOnMain {
                IntArray(2).also { location ->
                    if (!view.isShown || view.width <= 0 || view.height <= 0) {
                        throw AssertionError("View is not visible for system tap: $view")
                    }
                    view.getLocationOnScreen(location)
                    location[0] += view.width / 2
                    location[1] += view.height / 2
                }
            }
            if (!device.click(center[0], center[1])) {
                tapView(view)
            }
        } catch (_: IllegalStateException) {
            tapView(view)
        }
    }

    private fun tapCurrentKeyboardViewById(service: FcitxInputMethodService, viewId: Int) {
        val target = readOnMain {
            extractCurrentKeyboardView(service)
                ?.findViewById<View>(viewId)
                ?.takeIf(::hasUsableBounds)
        } ?: throw AssertionError("Current keyboard view id=$viewId is not visible")
        tapViewOnScreen(target)
    }

    private fun tapUiObjectByText(text: String): Boolean {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val target = device.wait(Until.findObject(By.text(text)), 250L)
            ?: device.wait(Until.findObject(By.textContains(text)), 250L)
            ?: return false
        target.click()
        return true
    }

    private fun hasUsableBounds(view: View): Boolean {
        val visibleRect = Rect()
        return view.isShown &&
            view.width > 0 &&
            view.height > 0 &&
            view.getGlobalVisibleRect(visibleRect) &&
            !visibleRect.isEmpty
    }

    private fun hasTapTarget(view: View): Boolean = view.isShown && view.width > 0 && view.height > 0

    private fun <T> readOnMain(block: () -> T): T {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: Result<T>? = null
        instrumentation.runOnMainSync {
            result = runCatching(block)
        }
        return result!!.getOrThrow()
    }

    private fun candidateLabelMatches(candidateText: String, renderedText: String, expectedText: String): Boolean {
        if (candidateText == expectedText || renderedText == expectedText) return true
        if (candidateText.startsWith("$expectedText ") || renderedText.startsWith("$expectedText ")) return true
        return false
    }

    private fun findVisibleTextKey(view: View, label: String): View? {
        if (view is org.fcitx.fcitx5.android.input.keyboard.TextKeyView &&
            view.isShown &&
            view.mainText.text.toString() == label
        ) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findVisibleTextKey(view.getChildAt(index), label)?.let { return it }
            }
        }
        return null
    }

    private fun findVisiblePopupText(view: View, label: String): View? {
        if (view is TextView &&
            view.isShown &&
            hasUsableBounds(view) &&
            view.text.toString() == label
        ) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findVisiblePopupText(view.getChildAt(index), label)?.let { return it }
            }
        }
        return null
    }

    private fun findFirstTextViewText(view: View): String {
        if (view is TextView && view.text.isNotBlank()) return view.text.toString()
        val group = view as? ViewGroup ?: return ""
        for (index in 0 until group.childCount) {
            val text = findFirstTextViewText(group.getChildAt(index))
            if (text.isNotBlank()) return text
        }
        return ""
    }

    private fun collectTextViews(view: View): List<String> {
        if (view is TextView) {
            val text = view.text.toString()
            return if (text.isBlank()) emptyList() else listOf(text)
        }
        val group = view as? ViewGroup ?: return emptyList()
        return buildList {
            for (index in 0 until group.childCount) {
                addAll(collectTextViews(group.getChildAt(index)))
            }
        }
    }

    private fun collectTextLabels(view: View, output: MutableSet<String>) {
        if (view is org.fcitx.fcitx5.android.input.keyboard.TextKeyView) {
            output += view.mainText.text.toString()
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectTextLabels(view.getChildAt(index), output)
            }
        }
    }

    private suspend fun prepareHostEditorForIme(
        service: FcitxInputMethodService,
        activity: Activity,
    ) {
        val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
        repeat(50) {
            ensureT9KeyboardLayout(service)
            requestImeFromHostEditor(activity, editText)
            if (service.currentInputConnection != null && editText.hasFocus()) {
                return
            }
            delay(100)
        }
        fail(
            "Timed out waiting for host editor/input connection before UI acceptance; " +
                "hasFocus=${editText.hasFocus()} serviceConnection=${service.currentInputConnection != null}",
        )
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

    private fun clearHostText(editText: EditText) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            editText.setText("")
            editText.setSelection(0)
        }
    }

    private fun seedHostText(editText: EditText, text: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            editText.setText(text)
            editText.setSelection(text.length)
        }
    }

    private suspend fun waitForHostText(editText: EditText, expected: String) {
        waitForHostText(editText) { it == expected }
    }

    private suspend fun waitForHostText(editText: EditText, predicate: (String) -> Boolean): String {
        val deadline = SystemClock.uptimeMillis() + UI_WAIT_TIMEOUT_MS
        var last = readOnMain { editText.text.toString() }
        var lastPreedit = PreeditUiSnapshot("", "", false)
        var lastCandidates = emptyList<HorizontalCandidateSnapshot>()
        var lastRailItems = emptyList<SideRailItemSnapshot>()
        while (SystemClock.uptimeMillis() < deadline) {
            val current = readOnMain { editText.text.toString() }
            last = current
            findRunningFcitxService()?.let { service ->
                lastPreedit = readTopPreeditSnapshot(service)
                lastCandidates = readVisibleHorizontalCandidates(service)
                lastRailItems = readVisibleSideRailItems(service)
            }
            if (predicate(current)) return current
            delay(100)
        }
        fail(
            "Timed out waiting for host text; " +
                "last=$last lastPreedit=$lastPreedit lastCandidates=$lastCandidates lastRailItems=$lastRailItems " +
                "lastKeyAction=${KeyboardWindow.debugLastKeyAction} lastKeySource=${KeyboardWindow.debugLastKeyActionSource} " +
                "lastDirectCommitText=${KeyboardWindow.debugLastDirectCommitText} " +
                "lastDirectCommitContext=${KeyboardWindow.debugLastDirectCommitContext}",
        )
        throw AssertionError("unreachable")
    }

    private fun ensureT9KeyboardLayout(service: FcitxInputMethodService) {
        val inputView = extractInputView(service) as? InputView ?: return
        val keyboardWindow = extractKeyboardWindow(inputView) ?: return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            keyboardWindow.switchLayout(T9Keyboard.Name)
        }
    }

    private fun finishActivity(activity: Activity) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity.finish()
        }
    }

    data class PreeditUiSnapshot(
        val topText: String,
        val bottomText: String,
        val visible: Boolean,
        val underlineCoverageLength: Int = 0,
    )

    data class ExpandedCandidateRowSnapshot(
        val index: Int,
        val text: String,
        val comment: String,
        val renderedText: String,
    )

    data class HorizontalCandidateSnapshot(
        val index: Int,
        val text: String,
        val comment: String,
        val renderedText: String,
    )

    data class SideRailItemSnapshot(
        val text: String,
        val hint: String,
    )

    data class SideRailViewportSnapshot(
        val firstVisiblePosition: Int,
        val lastVisiblePosition: Int,
        val itemCount: Int,
        val visibleTexts: List<String>,
    )
}

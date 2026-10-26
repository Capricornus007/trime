/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.transition.Slide
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.RimeInputMethodEntries
import org.fcitx.fcitx5.android.core.RimeInputMethodSwitcher
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.t9.T9SessionBridge
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

private const val KEYBOARD_US_IME = "keyboard-us"

internal fun t9DirectCommitText(
    schemaId: String,
    keyboardName: String,
    action: KeyAction,
    source: KeyActionListener.Source
): String? {
    return when {
        keyboardName == NumberKeyboard.Name -> numberKeyboardDirectCommitText(action)
        keyboardName == TextKeyboard.Name &&
            source == KeyActionListener.Source.Popup -> popupDigitDirectCommitText(action)
        schemaId == T9Keyboard.SchemaId &&
            keyboardName == T9Keyboard.Name &&
            source == KeyActionListener.Source.Popup -> popupSingleCharDirectCommitText(action)
        else -> null
    }
}

private fun numberKeyboardDirectCommitText(action: KeyAction): String? = when (action) {
    is KeyAction.CommitAction -> action.text
    is KeyAction.SymAction -> when (action.sym.sym) {
        0xffaa -> "*"
        0xffab -> "+"
        0xffac -> ","
        0xffad -> "-"
        0xffae -> "."
        0xffaf -> "/"
        0xffb0 -> "0"
        0xffb1 -> "1"
        0xffb2 -> "2"
        0xffb3 -> "3"
        0xffb4 -> "4"
        0xffb5 -> "5"
        0xffb6 -> "6"
        0xffb7 -> "7"
        0xffb8 -> "8"
        0xffb9 -> "9"
        0xffbd -> "="
        else -> null
    }
    else -> null
}

private fun popupDigitDirectCommitText(action: KeyAction): String? = when (action) {
    is KeyAction.CommitAction -> action.text.takeIf { it.length == 1 && it[0].isDigit() }
    is KeyAction.FcitxKeyAction -> action.act.takeIf { it.length == 1 && it[0].isDigit() }
    else -> null
}

private fun popupSingleCharDirectCommitText(action: KeyAction): String? = when (action) {
    is KeyAction.CommitAction -> action.text.takeIf { it.length == 1 }
    is KeyAction.FcitxKeyAction -> action.act.takeIf { it.length == 1 }
    else -> null
}

class KeyboardWindow : InputWindow.SimpleInputWindow<KeyboardWindow>(), EssentialWindow,
    InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    companion object : EssentialWindow.Key {
        const val ReturnToCurrentInputMethodKeyboardAction =
            "__return_to_current_input_method_keyboard__"
        @Volatile
        var debugKeyActionCount: Int = 0
        @Volatile
        var debugT9BackspaceInterceptCount: Int = 0
        @Volatile
        var debugLastKeyAction: String = ""
        @Volatile
        var debugLastKeyActionSource: String = ""
        @Volatile
        var debugLastDirectCommitText: String = ""
        @Volatile
        var debugLastDirectCommitContext: String = ""
    }

    override val key: EssentialWindow.Key
        get() = KeyboardWindow

    override fun enterAnimation(lastWindow: InputWindow) = Slide().apply {
        slideEdge = Gravity.BOTTOM
    }.takeIf {
        // disable animation switching between picker
        lastWindow !is PickerWindow
    }

    override fun exitAnimation(nextWindow: InputWindow) =
        super.exitAnimation(nextWindow).takeIf {
            // disable animation switching between picker
            nextWindow !is PickerWindow
        }

    private lateinit var keyboardView: FrameLayout
    private lateinit var keyboardHost: FrameLayout
    private lateinit var t9KeyboardHost: FrameLayout

    private val keyboards: HashMap<String, View> by lazy {
        hashMapOf(
            TextKeyboard.Name to TextKeyboard(context, theme),
            NumberKeyboard.Name to NumberKeyboard(context, theme),
            T9Keyboard.Name to T9Keyboard(context, theme)
        )
    }
    private var currentKeyboardName = ""
    private var currentEditorClass = InputType.TYPE_CLASS_TEXT
    private var currentSchemaId = ""
    private var layoutRefreshGeneration = 0L
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout
    private var punctuationMapping: Map<String, String> = emptyMap()
    private var t9StateJob: Job? = null
    private val t9SessionBridge = T9SessionBridge()
    private var currentImeUniqueName = ""
    private var rimeAsciiMode = RimeAsciiModeStatus(enabled = false, toggleActionId = null)
    private var rimeLanguageState = RimeLanguageStateMachine.State(
        keyboard = RimeLanguageStateMachine.Keyboard.Text,
        returnTarget = RimeLanguageStateMachine.ReturnTarget.TextChinese
    )

    private val currentKeyboard: View? get() = keyboards[currentKeyboardName]
    private val currentT9Keyboard: T9Keyboard? get() = currentKeyboard as? T9Keyboard
    private val t9Keyboard: T9Keyboard? get() = keyboards[T9Keyboard.Name] as? T9Keyboard
    private val isT9KeyboardContext: Boolean
        get() = currentSchemaId == T9Keyboard.SchemaId || currentKeyboardName == T9Keyboard.Name

    private fun applyT9RailState(keyboard: T9Keyboard) {
        val state = service.t9UiState.value
        if (!service.isT9ModeActive || state.sideRailEntries.isEmpty()) {
            keyboard.submitRailPunctuation(punctuationMapping)
        } else {
            keyboard.submitRailState(t9SessionBridge.railState(state))
        }
    }

    private fun isAuthoritativeT9Context(): Boolean {
        if (isT9KeyboardContext) return true
        return runCatching {
            fcitx.runImmediately {
                currentIme().uniqueName == "rime" && currentSchema() == T9Keyboard.SchemaId
            }
        }.getOrDefault(false)
    }

    private val keyActionListener = KeyActionListener { it, source ->
        debugKeyActionCount += 1
        debugLastKeyAction = it.toString()
        debugLastKeyActionSource = source.name
        if (it is KeyAction.LayoutSwitchAction) {
            when (it.act) {
                T9Keyboard.RetypeAction -> t9Keyboard?.requestRetype()
                T9Keyboard.BackspaceAction -> {
                    debugT9BackspaceInterceptCount += 1
                    service.onT9BackspaceRequested()
                }
                else -> switchLayout(it.act)
            }
        } else if (it is KeyAction.RimeAsciiToggleAction) {
            toggleLanguageMode()
        } else {
            val directCommitText = t9DirectCommitText(
                schemaId = currentSchemaId,
                keyboardName = currentKeyboardName,
                action = it,
                source = source
            )
            debugLastDirectCommitText = directCommitText.orEmpty()
            debugLastDirectCommitContext =
                "schema=$currentSchemaId keyboard=$currentKeyboardName source=$source action=$it"
            if (directCommitText != null) {
                service.onT9DirectCommitRequested(directCommitText)
                return@KeyActionListener
            }
            if (
                isAuthoritativeT9Context() &&
                it is KeyAction.SymAction &&
                it.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace
            ) {
                debugT9BackspaceInterceptCount += 1
                service.onT9BackspaceRequested()
            } else {
                commonKeyActionListener.listener.onKeyAction(it, source)
            }
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        popup.listener
    }

    // This will be called EXACTLY ONCE
    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        keyboardHost = context.frameLayout()
        t9KeyboardHost = context.frameLayout().apply {
            visibility = View.GONE
        }
        keyboardView.apply {
            add(keyboardHost, lParams(matchParent, matchParent))
            add(t9KeyboardHost, lParams(matchParent, matchParent))
        }
        attachLayout(TextKeyboard.Name)
        return keyboardView
    }

    private fun detachCurrentLayout() {
        currentKeyboard?.also {
            when (it) {
                is BaseKeyboard -> {
                    it.onDetach()
                    it.keyActionListener = null
                    it.popupActionListener = null
                }
                is T9Keyboard -> {
                    it.onDetach()
                    it.keyActionListener = null
                    it.popupActionListener = null
                }
            }
            keyboardHost.removeView(it)
            t9KeyboardHost.removeView(it)
        }
    }

    private fun hostFor(target: String): FrameLayout {
        return if (target == T9Keyboard.Name) t9KeyboardHost else keyboardHost
    }

    private fun updateInputMethodOnCurrentKeyboard(ime: InputMethodEntry) {
        currentKeyboard?.let {
            when (it) {
                is BaseKeyboard -> it.onInputMethodUpdate(ime)
                is T9Keyboard -> it.onInputMethodUpdate(ime)
            }
        }
    }

    private fun updateHostVisibility(target: String) {
        keyboardHost.visibility = if (target == T9Keyboard.Name) View.GONE else View.VISIBLE
        t9KeyboardHost.visibility = if (target == T9Keyboard.Name) View.VISIBLE else View.GONE
    }

    private fun bindCurrentKeyboard(ime: InputMethodEntry? = null) {
        currentKeyboard?.also {
            when (it) {
                is BaseKeyboard -> {
                    it.keyActionListener = keyActionListener
                    it.popupActionListener = popupActionListener
                    it.onAttach()
                    it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
                    it.onInputMethodUpdate(ime ?: fcitx.runImmediately { inputMethodEntryCached })
                }
                is T9Keyboard -> {
                    it.keyActionListener = KeyActionListener { action, source ->
                        when {
                            action is KeyAction.LayoutSwitchAction &&
                                action.act == T9Keyboard.RetypeAction -> {
                                it.requestRetype()
                            }
                            action is KeyAction.LayoutSwitchAction &&
                                action.act == T9Keyboard.BackspaceAction -> {
                                debugT9BackspaceInterceptCount += 1
                                service.onT9BackspaceRequested()
                            }
                            action is KeyAction.SymAction &&
                                action.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace -> {
                                debugT9BackspaceInterceptCount += 1
                                service.onT9BackspaceRequested()
                            }
                            else -> keyActionListener.onKeyAction(action, source)
                        }
                    }
                    it.popupActionListener = popupActionListener
                    it.onRailItemClickListener = { item ->
                        when (item.kind) {
                            org.fcitx.fcitx5.android.input.t9.T9SideRailComponent.Kind.Candidate -> {
                                item.entry?.let(service::onT9SideRailSelected)
                            }
                            org.fcitx.fcitx5.android.input.t9.T9SideRailComponent.Kind.Punctuation -> {
                                commonKeyActionListener.listener.onKeyAction(
                                    KeyAction.CommitAction(item.text),
                                    KeyActionListener.Source.Keyboard
                                )
                            }
                            org.fcitx.fcitx5.android.input.t9.T9SideRailComponent.Kind.DirectCommit -> {
                                service.onT9DirectCommitRequested(item.text)
                            }
                        }
                    }
                    it.onRetypeRequested = service::onT9RetypeRequested
                    it.onAttach()
                    it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
                    it.onInputMethodUpdate(ime ?: fcitx.runImmediately { inputMethodEntryCached })
                    it.updatePunctuationMapping(punctuationMapping)
                    applyT9RailState(it)
                }
            }
        }
    }

    private fun attachLayout(target: String, ime: InputMethodEntry? = null) {
        currentKeyboardName = target
        currentKeyboard?.let {
            hostFor(target).addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        updateHostVisibility(target)
        bindCurrentKeyboard(ime)
    }

    private fun resolveLayout(editorClass: Int, schemaId: String): String {
        return when (editorClass) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> resolveInputMethodKeyboard(schemaId, rimeAsciiMode.enabled)
        }
    }

    private fun resolveCurrentInputMethodKeyboard(): String =
        resolveInputMethodKeyboard(currentSchemaId, rimeAsciiMode.enabled)

    private fun refreshRimeLanguageKeyLabels() {
        val englishMode = currentImeUniqueName == KEYBOARD_US_IME || rimeAsciiMode.enabled
        (keyboards[TextKeyboard.Name] as? TextKeyboard)?.updateRimeLanguageKey(englishMode)
        (keyboards[T9Keyboard.Name] as? T9Keyboard)?.updateRimeLanguageKey(englishMode)
    }

    private fun syncRimeLanguageState(ime: InputMethodEntry, actions: Array<Action>) {
        currentImeUniqueName = ime.uniqueName
        if (ime.uniqueName == KEYBOARD_US_IME) {
            rimeAsciiMode = RimeAsciiModeStatus(enabled = false, toggleActionId = null)
            refreshRimeLanguageKeyLabels()
            return
        }
        if (ime.uniqueName != "rime") {
            rimeAsciiMode = RimeAsciiModeStatus(enabled = false, toggleActionId = null)
            rimeLanguageState = RimeLanguageStateMachine.State(
                keyboard = RimeLanguageStateMachine.Keyboard.Text,
                returnTarget = RimeLanguageStateMachine.ReturnTarget.TextChinese
            )
            refreshRimeLanguageKeyLabels()
            return
        }

        rimeAsciiMode = RimeAsciiMode.fromStatusArea(actions)
        rimeLanguageState = RimeLanguageStateMachine.onSchemaChanged(rimeLanguageState, currentSchemaId)
        if (currentSchemaId == T9Keyboard.SchemaId && rimeAsciiMode.enabled) {
            rimeLanguageState = rimeLanguageState.copy(
                keyboard = RimeLanguageStateMachine.Keyboard.Text,
                returnTarget = RimeLanguageStateMachine.ReturnTarget.T9Chinese
            )
        }
        refreshRimeLanguageKeyLabels()
    }

    private fun toggleLanguageMode() {
        val ime = fcitx.runImmediately { inputMethodEntryCached }
        when (ime.uniqueName) {
            "rime" -> {
                val keyboard = if (currentSchemaId == T9Keyboard.SchemaId) {
                    RimeLanguageStateMachine.Keyboard.T9
                } else {
                    RimeLanguageStateMachine.Keyboard.Text
                }
                rimeLanguageState =
                    RimeLanguageStateMachine.onToggleFromChinese(rimeLanguageState.copy(keyboard = keyboard))
                service.lifecycleScope.launch {
                    fcitx.runOnReady {
                        ensureImeEnabled(KEYBOARD_US_IME)
                        activateIme(KEYBOARD_US_IME)
                    }
                }
            }
            KEYBOARD_US_IME -> {
                rimeLanguageState = RimeLanguageStateMachine.onToggleFromEnglish(rimeLanguageState)
                val targetUniqueName = when (rimeLanguageState.returnTarget) {
                    RimeLanguageStateMachine.ReturnTarget.TextChinese -> RimeInputMethodEntries.Rime
                    RimeLanguageStateMachine.ReturnTarget.T9Chinese -> RimeInputMethodEntries.RimeT9
                }
                service.lifecycleScope.launch {
                    fcitx.runOnReady {
                        ensureImeEnabled(RimeInputMethodEntries.Rime)
                        RimeInputMethodSwitcher.activateSelection(this, targetUniqueName)
                        ensureRimeChineseMode()
                    }
                }
            }
        }
    }

    private suspend fun FcitxAPI.ensureImeEnabled(uniqueName: String) {
        val enabledIme = enabledIme().map(InputMethodEntry::uniqueName)
        if (enabledIme.contains(uniqueName)) {
            return
        }
        setEnabledIme((enabledIme + uniqueName).toTypedArray())
    }

    private suspend fun FcitxAPI.ensureRimeChineseMode() {
        repeat(50) {
            if (currentIme().uniqueName != RimeInputMethodEntries.Rime) {
                delay(100)
                return@repeat
            }
            val status = RimeAsciiMode.fromStatusArea(statusArea())
            if (!status.enabled) {
                return
            }
            val toggleActionId = status.toggleActionId ?: return
            activateAction(toggleActionId)
            delay(100)
        }
    }

    private fun scheduleRimeSelection(uniqueName: String) {
        val generation = ++layoutRefreshGeneration
        service.lifecycleScope.launch {
            fcitx.runOnReady { RimeInputMethodSwitcher.activateSelection(this, uniqueName) }
            val confirmedSchemaId = fcitx.runOnReady { currentSchema() }
            val ime = fcitx.runImmediately { inputMethodEntryCached }
            ContextCompat.getMainExecutor(service).execute {
                if (generation != layoutRefreshGeneration) return@execute
                currentSchemaId = confirmedSchemaId
                refreshLayout(ime)
            }
        }
    }

    private fun applyLayout(target: String, remember: Boolean = true, ime: InputMethodEntry? = null) {
        if (!keyboards.containsKey(target)) {
            if (remember) {
                lastSymbolType = PickerWindow.Key.Symbol.name
            }
            windowManager.attachWindow(PickerWindow.Key.Symbol)
            return
        }
        if (remember && target != TextKeyboard.Name && target != T9Keyboard.Name) {
            lastSymbolType = target
        }
        if (target == currentKeyboardName) {
            ime?.let { updateInputMethodOnCurrentKeyboard(it) }
            refreshRimeLanguageKeyLabels()
            return
        }
        detachCurrentLayout()
        attachLayout(target, ime)
        refreshRimeLanguageKeyLabels()
        if (windowManager.isAttached(this)) {
            notifyBarLayoutChanged()
        }
    }

    private fun refreshLayout(ime: InputMethodEntry? = null) {
        applyLayout(resolveLayout(currentEditorClass, currentSchemaId), remember = false, ime = ime)
    }

    fun switchLayout(to: String, remember: Boolean = true) {
        if (to == ReturnToCurrentInputMethodKeyboardAction) {
            ContextCompat.getMainExecutor(service).execute {
                applyLayout(resolveCurrentInputMethodKeyboard(), remember = false)
            }
            return
        }
        val target = to.ifEmpty { lastSymbolType }
        ContextCompat.getMainExecutor(service).execute {
            when (target) {
                T9Keyboard.Name -> {
                    if (currentSchemaId != T9Keyboard.SchemaId) {
                        scheduleRimeSelection(RimeInputMethodEntries.RimeT9)
                    } else {
                        applyLayout(target, remember)
                    }
                }
                TextKeyboard.Name -> {
                    if (currentKeyboardName == T9Keyboard.Name) {
                        scheduleRimeSelection(RimeInputMethodEntries.Rime)
                    } else {
                        applyLayout(target, remember)
                    }
                }
                else -> applyLayout(target, remember)
            }
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        currentEditorClass = info.inputType and InputType.TYPE_MASK_CLASS
        val generation = ++layoutRefreshGeneration
        service.lifecycleScope.launch {
            val schemaId = fcitx.runOnReady { currentSchema() }
            val ime = fcitx.runImmediately { inputMethodEntryCached }
            ContextCompat.getMainExecutor(service).execute {
                if (generation != layoutRefreshGeneration) return@execute
                currentSchemaId = schemaId
                refreshLayout(ime)
            }
        }
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        if (ime.uniqueName != "rime") {
            layoutRefreshGeneration++
            currentSchemaId = ""
            syncRimeLanguageState(ime, emptyArray())
            refreshLayout(ime)
            return
        }
        val generation = ++layoutRefreshGeneration
        service.lifecycleScope.launch {
            val schemaId = fcitx.runOnReady { currentSchema() }
            val actions = fcitx.runOnReady { statusArea() }
            ContextCompat.getMainExecutor(service).execute {
                if (generation != layoutRefreshGeneration) return@execute
                currentSchemaId = schemaId
                syncRimeLanguageState(ime, actions)
                refreshLayout(ime)
            }
        }
    }

    override fun onStatusAreaUpdate(actions: Array<Action>) {
        val ime = fcitx.runImmediately { inputMethodEntryCached }
        if (ime.uniqueName != "rime") {
            syncRimeLanguageState(ime, actions)
            return
        }
        val generation = ++layoutRefreshGeneration
        service.lifecycleScope.launch {
            val schemaId = fcitx.runOnReady { currentSchema() }
            ContextCompat.getMainExecutor(service).execute {
                if (generation != layoutRefreshGeneration) return@execute
                currentSchemaId = schemaId
                syncRimeLanguageState(ime, actions)
                refreshLayout(ime)
            }
        }
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        currentKeyboard?.let {
            when (it) {
                is BaseKeyboard -> it.onPunctuationUpdate(mapping)
                is T9Keyboard -> it.onPunctuationUpdate(mapping)
            }
        }
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        currentKeyboard?.let {
            when (it) {
                is BaseKeyboard -> it.onReturnDrawableUpdate(resourceId)
                is T9Keyboard -> it.onReturnDrawableUpdate(resourceId)
            }
        }
    }

    override fun onAttached() {
        if (t9StateJob == null) {
            t9StateJob = service.lifecycleScope.launch {
                service.t9UiState.collectLatest { state ->
                    val keyboard = keyboards[T9Keyboard.Name] as? T9Keyboard ?: return@collectLatest
                    applyT9RailState(keyboard)
                }
            }
        }
        bindCurrentKeyboard()
        refreshRimeLanguageKeyLabels()
        notifyBarLayoutChanged()
    }

    override fun onDetached() {
        t9StateJob?.cancel()
        t9StateJob = null
        currentKeyboard?.let {
            when (it) {
                is BaseKeyboard -> {
                    it.onDetach()
                    it.keyActionListener = null
                    it.popupActionListener = null
                }
                is T9Keyboard -> {
                    it.onDetach()
                    it.keyActionListener = null
                    it.popupActionListener = null
                    it.onRailItemClickListener = null
                    it.onRetypeRequested = null
                }
            }
        }
        popup.dismissAll()
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        bar.onKeyboardLayoutSwitched(currentKeyboardName == NumberKeyboard.Name)
    }
}

internal fun resolveInputMethodKeyboard(
    schemaId: String,
    rimeAsciiModeEnabled: Boolean
): String = when {
    schemaId == T9Keyboard.SchemaId && !rimeAsciiModeEnabled -> T9Keyboard.Name
    else -> TextKeyboard.Name
}

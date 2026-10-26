/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.t9.T9SideRailComponent
import org.fcitx.fcitx5.android.input.t9.T9SideRailEntry
import org.fcitx.fcitx5.android.input.t9.T9SideRailUi
import splitties.views.imageResource

private const val T9_POPUP_PRESET_PREFIX = "__t9_digit__:"

internal fun t9PopupPresetLabel(digit: String): String = "$T9_POPUP_PRESET_PREFIX$digit"

private const val T9_CONTENT_WIDTH_WEIGHT = 0.82f
private const val T9_FUNCTION_COLUMN_WIDTH_WEIGHT = 0.18f
private const val T9_RAIL_WIDTH_WEIGHT = 0.18f
private const val T9_TOP_GRID_WIDTH_WEIGHT = 0.64f
private const val T9_BOTTOM_SMALL_KEY_PERCENT = T9_RAIL_WIDTH_WEIGHT / T9_CONTENT_WIDTH_WEIGHT
private const val T9_DIGIT_KEY_MAIN_TEXT_SIZE = 24f

private class T9DigitKey(
    digit: String,
    inputAct: String,
    letters: String,
    percentWidth: Float = 0f,
) : KeyDef(
    appearance = KeyDef.Appearance.AltText(
        displayText = letters,
        altText = digit,
        textSize = T9_DIGIT_KEY_MAIN_TEXT_SIZE,
        percentWidth = percentWidth
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyAction.FcitxKeyAction(inputAct))),
    popup = arrayOf(
        KeyDef.Popup.AltPreview(digit, letters),
        KeyDef.Popup.Preview(digit),
        // Keep Keyboard last so Gesture.Up triggers the focused popup key
        // before preview handlers dismiss the popup container.
        KeyDef.Popup.Keyboard(t9PopupPresetLabel(digit))
    )
)

private class T9RetypeKey : KeyDef(
    appearance = KeyDef.Appearance.ImageText(
        displayText = "重输",
        textSize = 14f,
        src = R.drawable.ic_baseline_redo_24,
        percentWidth = 0f,
        variant = KeyDef.Appearance.Variant.Alternative
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyAction.LayoutSwitchAction(T9Keyboard.RetypeAction))
    ),
    popup = arrayOf(
        KeyDef.Popup.Menu(
            arrayOf(
                KeyDef.Popup.Menu.Item(
                    "重输",
                    R.drawable.ic_baseline_redo_24,
                    KeyAction.LayoutSwitchAction(T9Keyboard.RetypeAction)
                )
            )
        )
    )
)

private class T9BackspaceKey(
    percentWidth: Float = 0f,
) : KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_baseline_backspace_24,
        percentWidth = percentWidth,
        variant = KeyDef.Appearance.Variant.Alternative,
        viewId = R.id.button_backspace,
        soundEffect = InputFeedbacks.SoundEffect.Delete
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyAction.LayoutSwitchAction(T9Keyboard.BackspaceAction)),
        KeyDef.Behavior.Repeat(KeyAction.LayoutSwitchAction(T9Keyboard.BackspaceAction))
    )
)

private fun t9SymbolPickerKey(
    percentWidth: Float = T9_BOTTOM_SMALL_KEY_PERCENT,
): KeyDef =
    TextPickerSwitchKey(
        text = "符",
        to = PickerWindow.Key.Symbol,
        percentWidth = percentWidth,
        variant = KeyDef.Appearance.Variant.Alternative
    )

private fun t9BottomSwitchKey(
    text: String,
    to: String,
    percentWidth: Float = T9_BOTTOM_SMALL_KEY_PERCENT,
): KeyDef =
    LayoutSwitchKey(
        displayText = text,
        to = to,
        percentWidth = percentWidth,
        variant = KeyDef.Appearance.Variant.Alternative
    )

@SuppressLint("ViewConstructor")
private class T9TopGridKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout) {

    companion object {
        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                T9DigitKey("1", "'", "'"),
                T9DigitKey("2", "2", "ABC"),
                T9DigitKey("3", "3", "DEF")
            ),
            listOf(
                T9DigitKey("4", "4", "GHI"),
                T9DigitKey("5", "5", "JKL"),
                T9DigitKey("6", "6", "MNO")
            ),
            listOf(
                T9DigitKey("7", "7", "PQRS"),
                T9DigitKey("8", "8", "TUV"),
                T9DigitKey("9", "9", "WXYZ")
            )
        )
    }
}

@SuppressLint("ViewConstructor")
private class T9BottomRowKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout) {

    companion object {
        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                t9SymbolPickerKey(),
                t9BottomSwitchKey("123", NumberKeyboard.Name),
                SpaceKey(),
                RimeLanguageKey(percentWidth = T9_BOTTOM_SMALL_KEY_PERCENT)
            )
        )
    }
}

@SuppressLint("ViewConstructor")
private class T9FunctionColumn(
    context: Context,
    private val theme: Theme,
) : LinearLayout(context) {
    private val prefs = AppPrefs.getInstance()
    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress
    private val hapticOnRepeat by prefs.keyboard.hapticOnRepeat

    private val backspaceDef = T9BackspaceKey()
    private val retypeDef = T9RetypeKey()
    private val returnDef = ReturnKey(percentWidth = 0f)

    var keyActionListener: KeyActionListener? = null
    var popupActionListener: PopupActionListener? = null

    val backspace: ImageKeyView = createKeyView(backspaceDef) as ImageKeyView
    val retype: ImageTextKeyView = createKeyView(retypeDef) as ImageTextKeyView
    val `return`: ImageKeyView = createKeyView(returnDef) as ImageKeyView

    init {
        orientation = VERTICAL

        addView(backspace, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(retype, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(`return`, LayoutParams(LayoutParams.MATCH_PARENT, 0, 2f))
    }

    private fun createKeyView(def: KeyDef): KeyView {
        val keyView = when (val appearance = def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, theme, appearance)
            is KeyDef.Appearance.DualText -> DualTextKeyView(context, theme, appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, theme, appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, theme, appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, theme, appearance)
        }

        keyView.soundEffect = def.appearance.soundEffect
        def.behaviors.forEach { behavior ->
            when (behavior) {
                is KeyDef.Behavior.Press -> keyView.setOnClickListener {
                    onAction(behavior.action)
                }
                is KeyDef.Behavior.LongPress -> keyView.setOnLongClickListener {
                    onAction(behavior.action)
                    true
                }
                is KeyDef.Behavior.Repeat -> {
                    keyView.repeatEnabled = true
                    keyView.onRepeatListener = { view ->
                        onAction(behavior.action)
                        if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                    }
                }
                is KeyDef.Behavior.DoubleTap -> {
                    keyView.doubleTapEnabled = true
                    keyView.onDoubleTapListener = {
                        onAction(behavior.action)
                    }
                }
                is KeyDef.Behavior.Swipe -> Unit
            }
        }
        def.popup?.forEach { popup ->
            when (popup) {
                is KeyDef.Popup.Menu -> {
                    keyView.setOnLongClickListener { view ->
                        view as KeyView
                        onPopupAction(PopupAction.ShowMenuAction(view.id, popup, view.bounds))
                        false
                    }
                    val oldOnGestureListener =
                        keyView.onGestureListener ?: CustomGestureView.OnGestureListener.Empty
                    keyView.swipeEnabled = true
                    keyView.onGestureListener = CustomGestureView.OnGestureListener { view, event ->
                        view as KeyView
                        when (event.type) {
                            CustomGestureView.GestureType.Move -> {
                                onPopupChangeFocus(view.id, event.x, event.y)
                            }
                            CustomGestureView.GestureType.Up -> {
                                onPopupTrigger(view.id)
                            }
                            else -> false
                        } || oldOnGestureListener.onGesture(view, event)
                    }
                }
                is KeyDef.Popup.Keyboard -> {
                    keyView.setOnLongClickListener { view ->
                        view as KeyView
                        onPopupAction(PopupAction.ShowKeyboardAction(view.id, popup, view.bounds))
                        false
                    }
                    val oldOnGestureListener =
                        keyView.onGestureListener ?: CustomGestureView.OnGestureListener.Empty
                    keyView.swipeEnabled = true
                    keyView.onGestureListener = CustomGestureView.OnGestureListener { view, event ->
                        view as KeyView
                        when (event.type) {
                            CustomGestureView.GestureType.Move -> {
                                onPopupChangeFocus(view.id, event.x, event.y)
                            }
                            CustomGestureView.GestureType.Up -> {
                                onPopupTrigger(view.id)
                            }
                            else -> false
                        } || oldOnGestureListener.onGesture(view, event)
                    }
                }
                is KeyDef.Popup.AltPreview -> {
                    val oldOnGestureListener =
                        keyView.onGestureListener ?: CustomGestureView.OnGestureListener.Empty
                    keyView.onGestureListener = CustomGestureView.OnGestureListener { view, event ->
                        view as KeyView
                        if (popupOnKeyPress) {
                            when (event.type) {
                                CustomGestureView.GestureType.Down -> onPopupAction(
                                    PopupAction.PreviewAction(view.id, popup.content, view.bounds)
                                )
                                CustomGestureView.GestureType.Move -> {
                                    val text =
                                        if (event.totalY < 0) popup.alternative else popup.content
                                    onPopupAction(
                                        PopupAction.PreviewUpdateAction(view.id, text)
                                    )
                                }
                                CustomGestureView.GestureType.Up -> {
                                    onPopupAction(PopupAction.DismissAction(view.id))
                                }
                            }
                        }
                        oldOnGestureListener.onGesture(view, event)
                    }
                }
                is KeyDef.Popup.Preview -> {
                    val oldOnGestureListener =
                        keyView.onGestureListener ?: CustomGestureView.OnGestureListener.Empty
                    keyView.onGestureListener = CustomGestureView.OnGestureListener { view, event ->
                        view as KeyView
                        if (popupOnKeyPress) {
                            when (event.type) {
                                CustomGestureView.GestureType.Down -> onPopupAction(
                                    PopupAction.PreviewAction(view.id, popup.content, view.bounds)
                                )
                                CustomGestureView.GestureType.Up -> {
                                    onPopupAction(PopupAction.DismissAction(view.id))
                                }
                                else -> Unit
                            }
                        }
                        oldOnGestureListener.onGesture(view, event)
                    }
                }
            }
        }

        return keyView
    }

    private fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source = KeyActionListener.Source.Keyboard
    ) {
        keyActionListener?.onKeyAction(action, source)
    }

    private fun onPopupAction(action: PopupAction) {
        popupActionListener?.onPopupAction(action)
    }

    private fun onPopupChangeFocus(viewId: Int, x: Float, y: Float): Boolean {
        val changeFocusAction = PopupAction.ChangeFocusAction(viewId, x, y)
        popupActionListener?.onPopupAction(changeFocusAction)
        return changeFocusAction.outResult
    }

    private fun onPopupTrigger(viewId: Int): Boolean {
        val triggerAction = PopupAction.TriggerAction(viewId)
        onPopupAction(triggerAction)
        val action = triggerAction.outAction ?: return false
        onAction(action, KeyActionListener.Source.Popup)
        onPopupAction(PopupAction.DismissAction(viewId))
        return true
    }

    fun onDetach() {
        keyActionListener = null
        popupActionListener = null
    }

    fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }
}

@SuppressLint("ViewConstructor")
private class T9MainKeyboard(
    context: Context,
    theme: Theme,
    railUi: T9SideRailUi,
) : LinearLayout(context) {

    private val topGridKeyboard = T9TopGridKeyboard(context, theme)
    private val bottomRowKeyboard = T9BottomRowKeyboard(context, theme)
    private val functionColumn = T9FunctionColumn(context, theme)
    private val leftContent = LinearLayout(context)
    private val upperContent = LinearLayout(context)

    var keyActionListener: KeyActionListener?
        get() = topGridKeyboard.keyActionListener
        set(value) {
            topGridKeyboard.keyActionListener = value
            bottomRowKeyboard.keyActionListener = value
            functionColumn.keyActionListener = value
        }

    var popupActionListener: PopupActionListener?
        get() = topGridKeyboard.popupActionListener
        set(value) {
            topGridKeyboard.popupActionListener = value
            bottomRowKeyboard.popupActionListener = value
            functionColumn.popupActionListener = value
        }

    val backspace: ImageKeyView
        get() = functionColumn.backspace

    val languageKey: DualTextKeyView
        get() = bottomRowKeyboard.findViewById(R.id.button_lang)

    val `return`: ImageKeyView
        get() = functionColumn.`return`

    init {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        leftContent.orientation = VERTICAL
        leftContent.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, T9_CONTENT_WIDTH_WEIGHT)

        upperContent.orientation = HORIZONTAL
        upperContent.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 3f)

        railUi.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, T9_RAIL_WIDTH_WEIGHT)
        topGridKeyboard.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, T9_TOP_GRID_WIDTH_WEIGHT)
        bottomRowKeyboard.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        functionColumn.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, T9_FUNCTION_COLUMN_WIDTH_WEIGHT)

        upperContent.addView(railUi)
        upperContent.addView(topGridKeyboard)
        leftContent.addView(upperContent)
        leftContent.addView(bottomRowKeyboard)

        addView(leftContent)
        addView(functionColumn)
    }

    fun onAttach() {
        topGridKeyboard.onAttach()
        bottomRowKeyboard.onAttach()
    }

    fun onDetach() {
        topGridKeyboard.onDetach()
        topGridKeyboard.keyActionListener = null
        topGridKeyboard.popupActionListener = null
        bottomRowKeyboard.onDetach()
        bottomRowKeyboard.keyActionListener = null
        bottomRowKeyboard.popupActionListener = null
        functionColumn.onDetach()
    }

    fun onReturnDrawableUpdate(returnDrawable: Int) {
        functionColumn.onReturnDrawableUpdate(returnDrawable)
    }

    fun onPunctuationUpdate(mapping: Map<String, String>) {
        topGridKeyboard.onPunctuationUpdate(mapping)
        bottomRowKeyboard.onPunctuationUpdate(mapping)
    }

    fun onInputMethodUpdate(ime: org.fcitx.fcitx5.android.core.InputMethodEntry) {
        topGridKeyboard.onInputMethodUpdate(ime)
        bottomRowKeyboard.onInputMethodUpdate(ime)
    }

    fun updateRimeLanguageKey(asciiMode: Boolean) {
        if (asciiMode) {
            languageKey.setLabels("英", "中")
        } else {
            languageKey.setLabels("中", "英")
        }
    }
}

@SuppressLint("ViewConstructor")
class T9Keyboard(
    context: Context,
    theme: Theme,
) : LinearLayout(context) {

    companion object {
        const val Name = "T9"
        const val SchemaId = "t9"
        const val RetypeAction = "__t9_retype__"
        const val BackspaceAction = "__t9_backspace__"
    }

    private val railComponent = T9SideRailComponent()
    private val railUi = T9SideRailUi(context, theme)
    private val mainKeyboard = T9MainKeyboard(context, theme, railUi)

    private var punctuationMapping: Map<String, String> = emptyMap()

    var keyActionListener: KeyActionListener?
        get() = mainKeyboard.keyActionListener
        set(value) {
            mainKeyboard.keyActionListener = value
        }

    var popupActionListener: PopupActionListener?
        get() = mainKeyboard.popupActionListener
        set(value) {
            mainKeyboard.popupActionListener = value
        }

    var onRailItemClickListener: ((T9SideRailComponent.Item) -> Unit)? = null
    var onRetypeRequested: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        mainKeyboard.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(mainKeyboard)

        railComponent.bind(railUi::submitState)
        railUi.onItemClick = { railComponent.dispatchItemClick(it) }
        railComponent.setOnItemClickListener { onRailItemClickListener?.invoke(it) }
        railComponent.showPunctuation(punctuationMapping)
    }

    fun submitRailCandidates(candidates: Collection<T9SideRailEntry>) {
        railComponent.showCandidates(candidates)
    }

    fun submitRailState(state: T9SideRailComponent.State) {
        railComponent.submitState(state)
    }

    fun submitRailPunctuation(mapping: Map<String, String>) {
        punctuationMapping = mapping
        railComponent.showPunctuation(mapping)
    }

    fun updatePunctuationMapping(mapping: Map<String, String>) {
        punctuationMapping = mapping
        if (railComponent.mode == T9SideRailComponent.Mode.Punctuation) {
            railComponent.showPunctuation(mapping)
        }
    }

    fun requestRetype() {
        onRetypeRequested?.invoke()
    }

    fun onAttach() {
        mainKeyboard.onAttach()
        railComponent.showPunctuation(punctuationMapping)
    }

    fun onDetach() {
        mainKeyboard.onDetach()
        mainKeyboard.keyActionListener = null
        mainKeyboard.popupActionListener = null
        onRailItemClickListener = null
        onRetypeRequested = null
    }

    fun onReturnDrawableUpdate(@DrawableRes returnDrawable: Int) {
        mainKeyboard.onReturnDrawableUpdate(returnDrawable)
    }

    fun onPunctuationUpdate(mapping: Map<String, String>) {
        updatePunctuationMapping(mapping)
    }

    fun onInputMethodUpdate(ime: org.fcitx.fcitx5.android.core.InputMethodEntry) {
        mainKeyboard.onInputMethodUpdate(ime)
    }

    fun updateRimeLanguageKey(asciiMode: Boolean) {
        mainKeyboard.updateRimeLanguageKey(asciiMode)
    }
}

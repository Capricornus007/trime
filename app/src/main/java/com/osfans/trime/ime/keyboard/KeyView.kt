/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupAction
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.util.sp
import splitties.dimensions.dp
import timber.log.Timber

@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class KeyView(
    context: Context,
    private val theme: Theme,
    private val key: Key,
    private val keyboard: Keyboard,
    private val keyboardView: KeyboardView,
    private val keyboardActionListener: KeyboardActionListener,
) : GestureFrame(context) {

    private val service: TrimeInputMethodService
        get() = keyboardView.service

    private val popup: PopupDelegate
        get() = keyboardView.popup

    private val deletedTextBuffer = ArrayDeque<String>()

    private var keyPressed = false
    override fun isPressed(): Boolean = keyPressed

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private var cachedIcon: IconicsDrawable? = null
    private var cachedIconName: String? = null

    private val cachedLocation = intArrayOf(0, 0)
    private val cachedBounds = Rect()
    private var boundsValid = false

    val bounds: Rect
        get() = cachedBounds.also {
            if (!boundsValid) updateBounds()
        }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { getLocationInWindow(it) }
        cachedBounds.set(x + key.extraWidthLeft, y, x + width - key.extraWidthRight, y + height)
        boundsValid = true
    }

    init {
        setWillNotDraw(false)
        isRepeatable = key.click?.isRepeatable ?: false
        isSlideCursor = key.click?.isSlideCursor ?: false
        isSlideDelete = key.click?.isSlideDelete ?: false
        hasLongPress = key.hasAction(KeyBehavior.LONG_CLICK)
        // Shift键始终参与双击检测，以便支持双击锁定大写（可通过设置项 hookShiftLock 关闭）
        hasDouble = key.hasAction(KeyBehavior.DOUBLE_CLICK) || key.isShift
        hasLazyDouble = key.hasAction(KeyBehavior.LAZY_DOUBLE_CLICK)
        hasPopup = key.popup.isNotEmpty()

        onPress = {
            if (keyboard.firstPressedKeyIndex == -1) keyboard.firstPressedKeyIndex = id
            setPressedState(true)
            key.getCode(KeyBehavior.CLICK).let { keyboardActionListener.onPress(it) }
            showPopupPreview()
        }

        onRelease = { behavior, isFromLongPress ->
            Timber.d("KeyView release: label=${key.getLabel()}, behavior=$behavior, fromLongPress=$isFromLongPress")
            if (isFromLongPress) {
                if (hasPopup) {
                    val triggerAction = PopupAction.TriggerAction(id)
                    popup.listener.onPopupAction(triggerAction)
                    triggerAction.outAction?.let { action ->
                        keyboardActionListener.onAction(theme.resolveAction(action))
                        dismissPopupPreview()
                    }
                    setPressedState(false)
                } else if (isRepeatable) {
                    key.getAction(KeyBehavior.CLICK)?.let { processKeyAction(it, KeyBehavior.CLICK) }
                } else {
                    // trime-9key: plain long_click commits here on release
                    // (GestureFrame no longer fires it at the timeout), so a
                    // hold that develops into a drag can still end as a swipe.
                    key.getAction(KeyBehavior.LONG_CLICK)?.let { processKeyAction(it, KeyBehavior.LONG_CLICK) }
                    setPressedState(false)
                    dismissPopupPreview()
                }
            } else {
                when (behavior) {
                    KeyBehavior.CLICK -> {
                        val pressedIdx = keyboard.firstPressedKeyIndex
                        val actionBehavior = if (pressedIdx != -1 && pressedIdx != id) KeyBehavior.COMBO else behavior
                        key.getAction(actionBehavior)?.let { processKeyAction(it, actionBehavior) }
                    }
                    KeyBehavior.SWIPE_UP, KeyBehavior.SWIPE_DOWN, KeyBehavior.SWIPE_LEFT, KeyBehavior.SWIPE_RIGHT,
                    -> {
                        // trime-9key: a fast tap that drifts past the swipe
                        // threshold in a direction this key doesn't bind used
                        // to dispatch nothing at all -- the keystroke silently
                        // vanished. Fall back to the key's normal click, which
                        // is what such a sloppy tap meant.
                        val action = key.getAction(behavior) ?: key.getAction(KeyBehavior.CLICK)
                        val effective = if (key.getAction(behavior) != null) behavior else KeyBehavior.CLICK
                        action?.let { processKeyAction(it, effective) }
                    }
                    KeyBehavior.DOUBLE_CLICK, KeyBehavior.LAZY_DOUBLE_CLICK,
                    ->
                        key.getAction(behavior)?.let { processKeyAction(it, behavior) }

                    else -> {}
                }

                setPressedState(false)
                dismissPopupPreview()
            }
            if (keyboard.firstPressedKeyIndex == id) keyboard.firstPressedKeyIndex = -1
        }

        onSwipe = { direction ->
            setPressedState(true)
            if (direction == KeyBehavior.CLICK) {
                // finger came back under the swipe threshold: releasing now
                // is a click/long-click again, so drop the gesture preview.
                dismissPopupPreview()
            } else {
                showPopupPreview(direction)
            }
        }

        onLongPressEngaged = {
            showPopupPreview(KeyBehavior.LONG_CLICK)
        }

        onSlide = { delta, _, _ ->
            if (isSlideCursor) {
                when {
                    delta > 0 -> keyboardActionListener.onAction(theme.resolveAction("Right"))
                    delta < 0 -> keyboardActionListener.onAction(theme.resolveAction("Left"))
                }
            } else if (isSlideDelete) {
                val ic = service.currentInputConnection
                when {
                    delta < 0 -> {
                        val beforeText = ic.getTextBeforeCursor(1, 0) ?: ""
                        if (beforeText.isNotEmpty()) {
                            deletedTextBuffer.addFirst(beforeText.toString())
                            ic.deleteSurroundingText(1, 0)
                        }
                    }

                    delta > 0 -> {
                        if (deletedTextBuffer.isNotEmpty()) {
                            ic.commitText(deletedTextBuffer.removeFirst(), 1)
                        }
                    }
                }
            }
        }

        onLongClick = {
            if (key.popup.isNotEmpty()) {
                dismissPopupPreview()
                showPopupKeyboard()
            } else if (hasLongPress) {
                key.getAction(KeyBehavior.LONG_CLICK)?.let {
                    processKeyAction(it, KeyBehavior.LONG_CLICK)
                    setPressedState(false)
                    dismissPopupPreview()
                }
            }
        }

        onMove = { x, y, isLongPress ->
            if (isLongPress && hasPopup) {
                popup.listener.onPopupAction(PopupAction.ChangeFocusAction(id, x, y))
            }
        }

        onCancel = {
            deletedTextBuffer.clear()
            setPressedState(false)
            dismissPopupPreview()
        }
    }

    fun setPressedState(pressed: Boolean) {
        if (keyPressed != pressed) {
            keyPressed = pressed
            if (pressed) {
                key.onPressed()
            } else {
                key.onReleased()
            }
            invalidate()
        }
    }

    private fun processKeyAction(action: KeyAction, behavior: KeyBehavior) {
        Timber.d("processKeyAction: label=${key.getLabel()}, code=${action.code}, type=$behavior")

        if (action.isModifierKey) {
            keyboard.clickModifierKey(
                modifierKeyLockState(action, behavior),
                action.modifierKeyOnMask,
            )
            keyboardView.invalidateAllKeys()
            return
        }

        keyboardActionListener.onAction(action)

        val hookArrow = if (keyboardView.hookShiftArrow) {
            when (action.code) {
                in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> true
                else -> false
            }
        } else {
            false
        }

        if (!hookArrow) {
            if (keyboard.refreshModifier()) {
                keyboardView.invalidateAllKeys()
            }
        }
    }

    /**
     * 计算修饰键本次触发后的锁定状态
     *
     * - 长按：取[KeyAction.isShiftLock]的反值（沿用原有的 isShiftLock xor longClick 规则）
     * - 双击Shift：设置项 hookShiftLock 开启时，锁定大写（CapsLock）；再次单击Shift解除锁定
     * - 其它（单击、滑动等）：与[KeyAction.isShiftLock]一致
     */
    private fun modifierKeyLockState(
        action: KeyAction,
        behavior: KeyBehavior,
    ): Boolean = when (behavior) {
        KeyBehavior.LONG_CLICK -> !action.isShiftLock

        KeyBehavior.DOUBLE_CLICK ->
            // 双击Shift恒等于锁定大写。不能按当前锁定状态取反：默认主题的
            // shift_lock: ascii_long 在中文态下单击即锁定，取反会让双击变成解锁。
            (keyboardView.hookShiftLock && key.isShift) || action.isShiftLock

        else -> action.isShiftLock
    }

    private fun showPopupKeyboard() {
        val popupKeys = key.popup
        if (popupKeys.isEmpty()) return

        popup.listener.onPopupAction(
            PopupAction.ShowKeyboardAction(id, popupKeys, bounds),
        )
    }

    private fun showPopupPreview(behavior: KeyBehavior = KeyBehavior.CLICK) {
        // trime-9key: the press-preview bubble stays opt-in (popupOnKeyPress),
        // but gesture feedback -- what a swipe or engaged long press WILL
        // produce on release -- must always show, or the bindings are
        // undiscoverable and mid-gesture there's no way to know what letter
        // or symbol you're about to commit.
        if (behavior == KeyBehavior.CLICK && !keyboardView.popupOnKeyPress) return
        if (behavior != KeyBehavior.CLICK && key.getAction(behavior) == null) {
            // nothing bound in this direction (getPreviewText would NPE)
            dismissPopupPreview()
            return
        }
        key.getPreviewText(behavior).takeIf { it.isNotEmpty() }?.let { previewText ->
            val context = if (previewText.isIconFont) {
                previewText
            } else {
                String(Character.toChars(previewText.codePointAt(0)))
            }
            popup.listener.onPopupAction(PopupAction.PreviewAction(id, context, bounds))
        }
    }

    private fun dismissPopupPreview() {
        popup.listener.onPopupAction(
            PopupAction.DismissAction(id),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        val desiredWidth = totalWidth + paddingLeft + paddingRight
        val desiredHeight = key.height + paddingTop + paddingBottom

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        boundsValid = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawBackground(canvas, key)

        val label = key.getLabel().let {
            if (it == "enter_labels") keyboardView.labelEnter else it
        }

        if (label.isNotEmpty()) {
            drawLabel(canvas, label)
        }

        val symbol = key.symbolLabel
        if (symbol.isNotEmpty()) {
            drawSymbol(canvas, symbol)
        }

        val hint = key.hint
        if (hint.isNotEmpty()) {
            drawSymbol(canvas, hint, isTop = false)
        }

        drawSideGestureHints(canvas)
    }

    /**
     * trime-9key: the left/right swipe letters were completely invisible --
     * only the long-press letter got a hint, so nothing on the key said that
     * GHI can also give you g or i. Draw them in the bottom corners they
     * point at, read straight from the key's own bindings so the hint can
     * never disagree with what the gesture does.
     *
     * Only for letters the key itself advertises (GHI -> g, i). A qwerty key
     * binds its side swipes to brackets and cursor jumps, and hinting all of
     * those would bury the keyboard in corner text.
     */
    private fun drawSideGestureHints(canvas: Canvas) {
        if (rime.run { getRuntimeOption("_hide_key_symbol") }) return
        val face = key.getLabel()
        if (face.length < 2) return
        fun letterHint(behavior: KeyBehavior): String = key
            .getAction(behavior)
            ?.getLabel(keyboard)
            .orEmpty()
            .takeIf { it.length == 1 && face.contains(it, ignoreCase = true) }
            .orEmpty()
        val left = letterHint(KeyBehavior.SWIPE_LEFT)
        val right = letterHint(KeyBehavior.SWIPE_RIGHT)
        if (left.isEmpty() && right.isEmpty()) return

        symbolPaint.apply {
            color = key.getSymbolColor()
            textSize = sp(key.symbolTextSize.takeIf { it > 0f } ?: keyboardView.symbolTextSize)
            typeface = FontManager.getTypeface("symbol_font")
            alpha = SIDE_HINT_ALPHA
        }
        // On the label's own line, not the key's bottom edge: down there the
        // hints of adjacent keys ("i" of GHI beside "j" of JKL) sit closer to
        // each other than to the key they describe, and read as a pair.
        val fm = symbolPaint.fontMetrics
        val centerY = (height - paddingTop - paddingBottom) / 2f + paddingTop
        val baseline = centerY - (fm.ascent + fm.descent) / 2f
        val inset = sp(SIDE_HINT_INSET_SP)
        if (left.isNotEmpty()) {
            symbolPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(left, paddingLeft + inset, baseline, symbolPaint)
        }
        if (right.isNotEmpty()) {
            symbolPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(right, width - paddingRight - inset, baseline, symbolPaint)
        }
        // shared paint: restore what the other draw helpers expect
        symbolPaint.textAlign = Paint.Align.CENTER
        symbolPaint.alpha = 255
    }

    private fun drawBackground(canvas: Canvas, k: Key) {
        val bg = k.getBackgroundDrawable() ?: return

        if (bg is GradientDrawable) {
            k.roundCorner.takeIf { it > 0f }?.let { bg.cornerRadius = dp(it) }
            k.keyBorder.takeIf { it > 0 }?.let { bg.setStroke(dp(it), k.getBorderColor()) }
        }

        bg.setBounds(
            paddingLeft,
            paddingTop,
            width - paddingRight,
            height - paddingBottom,
        )
        bg.draw(canvas)
    }

    private fun drawLabel(canvas: Canvas, label: String) {
        val textColor = key.getTextColor()
        val textSize = sp(key.keyTextSize.takeIf { it > 0 } ?: if (label.length > 1 && !label.isIconFont) keyboardView.keyLongTextSize else keyboardView.keyTextSize)

        if (label.isIconFont) {
            drawIcon(canvas, label, textSize.toInt(), textColor, key.keyTextOffsetX, key.keyTextOffsetY)
        } else {
            textPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = theme.fonts.key
                clearShadowLayer()
            }

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft
            val centerY = (height - paddingTop - paddingBottom) / 2f + paddingTop
            val fontMetrics = textPaint.fontMetrics
            val adjustmentY = -(fontMetrics.ascent + fontMetrics.descent) / 2f

            canvas.drawText(label, centerX + sp(key.keyTextOffsetX), centerY + adjustmentY + sp(key.keyTextOffsetY), textPaint)
        }
    }

    private fun drawIcon(
        canvas: Canvas,
        iconName: String,
        size: Int,
        color: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        isTop: Boolean? = null,
    ) {
        val halfSize = size / 2

        val cmdName = iconName.toIconName()
        val icon = if (cachedIconName == cmdName) {
            cachedIcon!!
        } else {
            IconicsDrawable(context, cmdName).apply {
                sizeDp = size
            }.also {
                cachedIcon = it
                cachedIconName = cmdName
            }
        }

        icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX)

        val centerY = when (isTop) {
            true -> paddingTop + halfSize + sp(offsetY)
            false -> height - paddingBottom - size + sp(offsetY)
            null -> (height - paddingTop - paddingBottom) / 2f + paddingTop + sp(offsetY)
        }

        icon.setBounds(
            (centerX - halfSize).toInt(),
            (centerY - halfSize).toInt(),
            (centerX + halfSize).toInt(),
            (centerY + halfSize).toInt(),
        )
        icon.draw(canvas)
    }

    private fun drawSymbol(canvas: Canvas, text: String, isTop: Boolean = true) {
        if (isTop && keyboardView.hideKeySymbol) return
        if (!isTop && keyboardView.hideKeyHint) return

        val textColor = key.getSymbolColor()
        val textSize = sp(key.symbolTextSize.takeIf { it > 0f } ?: keyboardView.symbolTextSize)
        val offsetX = if (isTop) key.keySymbolOffsetX else key.keyHintOffsetX
        val offsetY = if (isTop) key.keySymbolOffsetY else key.keyHintOffsetY

        if (text.isIconFont) {
            drawIcon(canvas, text, textSize.toInt(), textColor, offsetX, offsetY, isTop)
        } else {
            symbolPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = theme.fonts.symbol
            }

            val lines = text.split("\n")
            val fontMetrics = symbolPaint.fontMetrics
            val lineHeight = fontMetrics.descent - fontMetrics.ascent
            val totalHeight = lineHeight * lines.size

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX)
            val startY = if (isTop) {
                paddingTop - fontMetrics.top + sp(offsetY) - (totalHeight - lineHeight) / 2
            } else {
                height - paddingBottom - fontMetrics.bottom + sp(offsetY) - (totalHeight - lineHeight) / 2
            }

            for (i in lines.indices) {
                val lineY = startY + lineHeight * i
                canvas.drawText(lines[i], centerX, lineY, symbolPaint)
            }
        }
    }

    companion object {
        /** Corner hints stay quieter than the key's own label. */
        private const val SIDE_HINT_ALPHA = 150
        private const val SIDE_HINT_INSET_SP = 3f
    }
}

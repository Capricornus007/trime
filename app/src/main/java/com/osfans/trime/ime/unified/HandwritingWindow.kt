/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ime.window.ResidentWindow
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp

class HandwritingWindow(di: DI) :
    BoardWindow.NoBarBoardWindow(di),
    ResidentWindow {
    private val service: TrimeInputMethodService by instance()
    private val controller: UnifiedModeController by instance()
    private val keyboardWindow: KeyboardWindow by instance()
    private val windowManager: BoardWindowManager by instance()
    private val modelManager = DigitalInkModelManager()
    private val handwritingStrokes = AppPrefs.defaultInstance().keyboard.handwritingStrokes

    private lateinit var root: LinearLayout
    private lateinit var canvas: HandwritingCanvasView
    private lateinit var status: TextView
    private lateinit var retry: Button
    private lateinit var script: Button
    private lateinit var candidates: LinearLayout
    private var uiState = HandwritingUiState()
    private var overrideTag: String? = null
    private var currentTag: String? = null

    override val key: ResidentWindow.Key get() = HandwritingWindow

    companion object : ResidentWindow.Key

    override fun onCreateView(): View {
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF202124.toInt())
        }
        root.addView(createToolbar(), rowParams())
        candidates = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                contentDescription = context.getString(R.string.handwriting_candidates_description)
                addView(candidates)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(48)),
        )
        canvas = HandwritingCanvasView(context).apply {
            state = StrokeState.decode(handwritingStrokes.getValue())
            onStrokeFinished = {
                persistStrokes()
                recognize()
            }
        }
        root.addView(
            canvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(createActions(), rowParams())
        renderState()
        return root
    }

    private fun createToolbar(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(button(R.string.input_mode) { keyboardWindow.showUnifiedModePicker() }, weighted())
        script = button(R.string.chinese_script_button) {
            controller.toggleScript {
                updateLanguage(null)
                keyboardWindow.refreshUnifiedToolbar()
            }
        }
        addView(script, weighted())
        status = TextView(context).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.model_status_description)
        }
        addView(status, weighted(2f))
        retry = button(R.string.retry) {
            uiState = HandwritingStateReducer.downloading(uiState)
            renderState()
            modelManager.retry(::acceptState)
        }
        addView(retry, weighted())
        addView(button(R.string.keyboard) {
            overrideTag = null
            windowManager.attachWindow(KeyboardWindow)
        }, weighted())
    }

    private fun createActions(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(button(R.string.handwriting_undo) {
            if (canvas.state.undo()) {
                persistStrokes()
                canvas.invalidate()
                recognize()
            }
        }, weighted())
        addView(button(R.string.handwriting_clear) { clearInk() }, weighted())
        addView(button(R.string.handwriting_recognize) { recognize() }, weighted())
        addView(button(R.string.space) { service.commitText(" ") }, weighted())
        addView(button(R.string.delete) { service.sendDownUpKeyEvent(android.view.KeyEvent.KEYCODE_DEL) }, weighted())
        addView(button(R.string.enter) { service.sendDownUpKeyEvent(android.view.KeyEvent.KEYCODE_ENTER) }, weighted())
    }

    private fun button(label: Int, action: () -> Unit) = Button(context).apply {
        setText(label)
        contentDescription = context.getString(label)
        setOnClickListener { action() }
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
    }

    private fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
    private fun rowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(48))

    fun openEnglishHandwriting() {
        overrideTag = "en-US"
        if (::root.isInitialized) updateLanguage("en-US")
        windowManager.attachWindow(HandwritingWindow)
    }

    fun updateLanguage(tag: String? = overrideTag) {
        overrideTag = tag
        val desired = tag ?: controller.mode.modelTag(controller.script) ?: "en-US"
        script.visibility = if (tag == null && controller.mode == UnifiedInputMode.CHINESE_HANDWRITING) View.VISIBLE else View.GONE
        script.text = "${context.getString(R.string.chinese_script_button)}: ${context.getString(controller.script.stringRes)}"
        if (desired == currentTag && uiState.model == ModelDownloadState.READY) return
        currentTag = desired
        clearCandidates()
        uiState = HandwritingStateReducer.downloading(HandwritingUiState())
        renderState()
        modelManager.prepare(desired, ::acceptState)
    }

    private fun acceptState(state: HandwritingUiState) {
        uiState = state
        renderState()
    }

    private fun renderState() {
        if (!::status.isInitialized) return
        status.text = when (uiState.model) {
            ModelDownloadState.NOT_DOWNLOADED -> context.getString(R.string.model_not_downloaded)
            ModelDownloadState.DOWNLOADING -> context.getString(R.string.model_downloading)
            ModelDownloadState.READY -> context.getString(R.string.model_ready)
            ModelDownloadState.FAILED -> context.getString(R.string.model_failed)
        }
        status.contentDescription = listOfNotNull(status.text, uiState.error).joinToString(". ")
        retry.visibility = if (uiState.model == ModelDownloadState.FAILED) View.VISIBLE else View.GONE
        renderCandidates(uiState.candidates)
    }

    private fun recognize() {
        if (!::canvas.isInitialized || canvas.state.strokes.isEmpty()) {
            clearCandidates()
            return
        }
        if (uiState.model != ModelDownloadState.READY) return
        modelManager.recognize(
            canvas.state.strokes,
            onSuccess = { found ->
                uiState = HandwritingStateReducer.recognized(uiState, found)
                renderState()
            },
            onFailure = { message ->
                uiState = HandwritingStateReducer.failed(uiState, message)
                renderState()
            },
        )
    }

    private fun renderCandidates(values: List<String>) {
        if (!::candidates.isInitialized) return
        candidates.removeAllViews()
        values.forEach { candidate ->
            candidates.addView(Button(context).apply {
                text = candidate
                contentDescription = context.getString(R.string.commit_handwriting_candidate, candidate)
                setOnClickListener {
                    val committed = HandwritingCandidateCommitPolicy.select(candidate, canvas.state)
                        ?: return@setOnClickListener
                    service.commitText(committed)
                    clearInk()
                }
            })
        }
    }

    private fun clearCandidates() {
        uiState = uiState.copy(candidates = emptyList())
        if (::candidates.isInitialized) candidates.removeAllViews()
    }

    private fun clearInk() {
        if (::canvas.isInitialized) {
            canvas.state.clear()
            canvas.invalidate()
        }
        handwritingStrokes.setValue("")
        clearCandidates()
    }

    private fun persistStrokes() {
        handwritingStrokes.setValue(canvas.state.encode())
    }

    override fun onAttached() {
        updateLanguage()
    }

    override fun onDetached() = Unit
}

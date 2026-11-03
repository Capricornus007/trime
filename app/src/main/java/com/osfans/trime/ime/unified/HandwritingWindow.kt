/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.unified

import android.graphics.Color
import android.os.Build
import android.os.UserManager
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.R
import com.osfans.trime.TrimeApplication
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
    private val modelManager = DigitalInkModelManager(::isUserUnlocked)
    private val storage = HandwritingSessionStorage(TrimeApplication.getInstance().directBootAwareContext.noBackupFilesDir)

    private lateinit var root: LinearLayout
    private lateinit var canvas: HandwritingCanvasView
    private lateinit var status: TextView
    private lateinit var retry: Button
    private lateinit var script: Button
    private lateinit var candidates: LinearLayout
    private var strokeState = StrokeState()
    private var uiState = HandwritingUiState()
    private var currentTag: String? = null
    private var currentEditorToken: String? = null
    private var languageRefreshPending = true

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
            state = strokeState
            onInkChanged = {
                modelManager.invalidateInk()
                clearRecognitionResult()
            }
            onStrokeFinished = {
                persistStrokes()
                recognize()
            }
        }
        root.addView(canvas, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
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
                requestLanguageRefresh()
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
            if (uiState.model == ModelDownloadState.FAILED) {
                uiState = HandwritingStateReducer.downloading(uiState)
                renderState()
                modelManager.retryModel(::acceptState)
            } else {
                recognize()
            }
        }
        addView(retry, weighted())
        addView(button(R.string.keyboard) { windowManager.attachWindow(KeyboardWindow) }, weighted())
    }

    private fun createActions(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(
            button(R.string.handwriting_undo) {
                modelManager.invalidateInk()
                if (canvas.state.undo()) {
                    persistStrokes()
                    canvas.invalidate()
                    clearRecognitionResult()
                    recognize()
                }
            },
            weighted(),
        )
        addView(button(R.string.handwriting_clear) { clearInk() }, weighted())
        addView(button(R.string.handwriting_recognize) { recognize() }, weighted())
        addView(button(R.string.space) { service.commitText(" ") }, weighted())
        addView(button(R.string.delete) { service.sendDownUpKeyEvent(android.view.KeyEvent.KEYCODE_DEL) }, weighted())
        addView(button(R.string.enter) { service.handleReturnKey() }, weighted())
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

    fun beginEditorSession(info: EditorInfo, restarting: Boolean) {
        val token = HandwritingSessionStorage.editorToken(info)
        val editorChanged = currentEditorToken != null && currentEditorToken != token
        if (currentEditorToken == null || editorChanged) {
            strokeState = storage.begin(info)
            currentEditorToken = token
            modelManager.invalidateInk()
            uiState = uiState.copy(candidates = emptyList(), recognitionError = null)
            if (::canvas.isInitialized) canvas.state = strokeState
            if (::candidates.isInitialized) renderState()
            if (::canvas.isInitialized && uiState.model == ModelDownloadState.READY && strokeState.strokes.isNotEmpty()) {
                canvas.post { recognize() }
            }
        } else if (!restarting) {
            // A new input session can reuse package/field metadata; never carry ink into it.
            modelManager.invalidateInk()
            strokeState.clear()
            storage.clearInk()
            if (::canvas.isInitialized) canvas.state = strokeState
            uiState = uiState.copy(candidates = emptyList(), recognitionError = null)
            if (::candidates.isInitialized) renderState()
        }
    }

    fun requestLanguageRefresh() {
        languageRefreshPending = true
        if (::root.isInitialized) updateLanguageIfReady()
    }

    private fun updateLanguageIfReady() {
        if (!::root.isInitialized || !controller.shouldShowHandwriting) return
        val desired = controller.mode.modelTag(controller.script) ?: return
        script.visibility = if (controller.mode == UnifiedInputMode.CHINESE_HANDWRITING) View.VISIBLE else View.GONE
        script.text = "${context.getString(R.string.chinese_script_button)}: ${context.getString(controller.script.stringRes)}"
        if (!languageRefreshPending && desired == currentTag && uiState.model == ModelDownloadState.READY) return
        languageRefreshPending = false
        currentTag = desired
        modelManager.invalidateInk()
        clearRecognitionResult()
        uiState = HandwritingStateReducer.downloading(HandwritingUiState())
        renderState()
        modelManager.prepare(desired, ::acceptState)
    }

    private fun acceptState(state: HandwritingUiState) {
        uiState = state
        renderState()
        if (state.model == ModelDownloadState.READY && strokeState.strokes.isNotEmpty()) recognize()
    }

    private fun renderState() {
        if (!::status.isInitialized) return
        status.text = when {
            uiState.model == ModelDownloadState.NOT_DOWNLOADED -> context.getString(R.string.model_not_downloaded)
            uiState.model == ModelDownloadState.DOWNLOADING -> context.getString(R.string.model_downloading)
            uiState.model == ModelDownloadState.FAILED -> context.getString(R.string.model_failed)
            uiState.recognitionError != null -> context.getString(R.string.recognition_failed)
            else -> context.getString(R.string.model_ready)
        }
        status.contentDescription = listOfNotNull(status.text, uiState.modelError, uiState.recognitionError).joinToString(". ")
        retry.visibility = if (uiState.model == ModelDownloadState.FAILED || uiState.recognitionError != null) View.VISIBLE else View.GONE
        renderCandidates(uiState.candidates)
    }

    private fun recognize() {
        if (!::canvas.isInitialized || strokeState.strokes.isEmpty() || canvas.width <= 0 || canvas.height <= 0) {
            clearRecognitionResult()
            return
        }
        if (uiState.model != ModelDownloadState.READY) return
        modelManager.recognize(
            strokeState.scaled(canvas.width, canvas.height),
            onSuccess = { found ->
                uiState = HandwritingStateReducer.recognized(uiState, found)
                renderState()
            },
            onFailure = { message ->
                uiState = HandwritingStateReducer.recognitionFailed(uiState, message)
                renderState()
            },
        )
    }

    private fun renderCandidates(values: List<String>) {
        if (!::candidates.isInitialized) return
        candidates.removeAllViews()
        values.forEach { candidate ->
            candidates.addView(
                Button(context).apply {
                    text = candidate
                    contentDescription = context.getString(R.string.commit_handwriting_candidate, candidate)
                    setOnClickListener {
                        val committed = HandwritingCandidateCommitPolicy.select(candidate, strokeState)
                            ?: return@setOnClickListener
                        modelManager.invalidateInk()
                        service.commitText(committed)
                        clearInk()
                    }
                },
            )
        }
    }

    private fun clearRecognitionResult() {
        uiState = uiState.copy(candidates = emptyList(), recognitionError = null)
        if (::candidates.isInitialized) candidates.removeAllViews()
    }

    private fun clearInk() {
        modelManager.invalidateInk()
        strokeState.clear()
        if (::canvas.isInitialized) canvas.invalidate()
        storage.clearInk()
        clearRecognitionResult()
        renderState()
    }

    private fun persistStrokes() {
        storage.save(strokeState)
    }

    override fun onAttached() {
        updateLanguageIfReady()
    }

    override fun onDetached() = Unit

    fun dispose() {
        modelManager.close()
    }

    private fun isUserUnlocked(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
        service.getSystemService(UserManager::class.java).isUserUnlocked
}

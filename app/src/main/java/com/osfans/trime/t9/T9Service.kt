package com.osfans.trime.t9

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.osfans.trime.t9.ui.NineKeyKeyboard
import com.osfans.trime.t9.ui.T9Theme

/**
 * 九鍵輸入法服務（真 librime 引擎＋Compose／Material 3 Expressive 鍵盤層）。
 *
 * 按鍵只送數字 keysym（2=abc … 9=wxyz，跟 gboard／搜狗一致，不做多擊循環），
 * 消歧由方案 `t9_pinyin` 的 speller/algebra 與詞庫詞頻決定。
 *
 * Compose 需要 ViewTree 上有 lifecycle／savedState 兩個 owner，而 InputMethodService
 * 不是 Activity，所以這裡自己實作兩個介面再掛到 ComposeView 上（照 fcitx5-android 的做法）。
 */
class T9Service :
    InputMethodService(),
    LifecycleOwner,
    SavedStateRegistryOwner {

    private val registry by lazy { LifecycleRegistry(this) }
    override val lifecycle: Lifecycle get() = registry

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())

    private var uiState by mutableStateOf(KeyboardState())

    // 挑字母工作階段：暫切到 luna_pinyin 把「精確拼音」組起來，上屏或退光後切回原方案。
    private var picking = false
    private var originSchema = T9_SCHEMA

    private data class KeyboardState(
        val code: String = "",
        val candidates: List<String> = emptyList(),
        val asciiMode: Boolean = false,
    )

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        RimeEngine.onDeployed = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@T9Service)
        setViewTreeSavedStateRegistryOwner(this@T9Service)
        setContent {
            T9Theme {
                NineKeyKeyboard(
                    code = uiState.code,
                    candidates = uiState.candidates,
                    asciiMode = uiState.asciiMode,
                    onKey = ::onKey,
                    onCandidate = ::onCandidate,
                    onToggleMode = ::toggleMode,
                    onPickLetter = ::pickLetter,
                    onPunct = ::onPunct,
                )
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // 新欄位不能繼承上一個欄位挑到一半的狀態（process 可能被殺過）。
        picking = false
        originSchema = T9_SCHEMA
        RimeEngine.stageAssets(this)
        RimeEngine.startup(this)
        RimeEngine.selectSchema("t9_pinyin")
        RimeEngine.onDeployed = {
            // 詞庫編完才選得到方案，選成功要回頭把候選列刷一次。
            // InputMethodService 沒有 runOnUiThread（那是 Activity 的東西），要自己丟主執行緒。
            mainHandler.post {
                RimeEngine.selectSchema("t9_pinyin")
                refresh()
            }
        }
        refresh()
    }

    private fun toggleMode() {
        if (!RimeEngine.ready) return
        RimeEngine.setOption("ascii_mode", !RimeEngine.getOption("ascii_mode"))
        refresh()
    }

    private fun onKey(digit: String) {
        if (!RimeEngine.ready) return
        // 挑字母時方案是 luna_pinyin，數字會被 rime 當成選候選；直接吞掉，
        // 要繼續組字就用同一顆鍵繼續滑動挑字母。
        if (picking && digit.length == 1 && digit[0] in '1'..'9') return
        val consumed = when (digit) {
            "⌫" -> RimeEngine.sendKey(RimeEngine.KEY_BACKSPACE)

            "␣" -> RimeEngine.sendKey(0x20)

            "⏎" -> !RimeEngine.getOption("ascii_mode") && RimeEngine.sendKey(RimeEngine.KEY_RETURN)

            else -> if (digit.isNotEmpty() && digit[0].isDigit()) {
                // 數字字元本身的碼位就是它的 X11 keysym（'2' = 0x32）。
                RimeEngine.sendKey(digit[0].code)
            } else {
                false
            }
        }
        drainCommit()
        refresh()
        if (!consumed && digit == "␣") {
            // 沒有候選時 rime 可能不吃空格，這時直接把空格打進去，別讓按鍵消失。
            currentInputConnection?.commitText(" ", 1)
        }
        if (!consumed && digit == "⏎") {
            // rime 沒吃掉 Enter（英文模式、或欄位是「搜尋／完成」這種動作鍵）時，
            // 才自己送實體換行；InputConnection 沒有 sendEditorAction，只能送按鍵事件。
            val now = SystemClock.uptimeMillis()
            val connection = currentInputConnection ?: return
            connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
            connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
        }
    }

    private fun onCandidate(index: Int) {
        if (RimeEngine.selectCandidate(index)) {
            drainCommit()
            refresh()
        }
    }

    /**
     * 在數字鍵上挑了一個確切字母（按住＝中間、左滑＝第一個、右滑＝最後、四字母鍵上滑＝第二個）。
     *
     * 作法跟 lida0407 的 T9CorrectionState 一樣：t9_pinyin 的 speller 只吃數字，
     * 所以暫時切到 luna_pinyin，把「修正後的拼音」整串重打一次；上屏（或退到空）
     * 再切回原本的方案。已存在的同組字母會被替換（hao→gao），不同組則接在後面
     * （h→ha→hao），所以可以連續挑字母組出整個詞。
     */
    private fun pickLetter(letter: Char) {
        if (!RimeEngine.ready) return
        val group = LETTER_GROUPS.firstOrNull { letter in it } ?: return
        // 英文模式沒有拼音要消歧：滑到哪個字母就直接打那個字母。
        if (RimeEngine.getOption("ascii_mode")) {
            currentInputConnection?.commitText(letter.toString(), 1)
            refresh()
            return
        }
        val raw = RimeEngine.preedit().replace(CURSOR_MARK, "").trim()
        val corrected = if (raw.isEmpty()) {
            letter.toString()
        } else {
            val idx = raw.indexOfFirst { it.lowercaseChar() in group }
            when {
                idx < 0 -> raw + letter
                raw[idx].lowercaseChar() == letter -> return
                else -> raw.substring(0, idx) + letter + raw.substring(idx + 1)
            }
        }
        if (!picking) {
            // 切方案前先記住使用者的原方案（通常就是 t9_pinyin）。
            originSchema = RimeEngine.currentSchema()
                .takeIf { it.isNotBlank() && it != LETTER_SCHEMA }
                ?: T9_SCHEMA
            picking = true
        }
        RimeEngine.clearComposition()
        if (RimeEngine.currentSchema() != LETTER_SCHEMA) {
            RimeEngine.selectSchema(LETTER_SCHEMA)
        }
        // 多音節的編碼區用空格分隔，重打時改成可輸入的隔音符號。
        RimeEngine.simulateKeySequence(corrected.replace(' ', '\''))
        drainCommit()
        refresh()
    }

    private fun onPunct(text: String) {
        if (!RimeEngine.ready) return
        val c = text.firstOrNull() ?: return
        if (c.code < 0x80) {
            // ASCII 標點交給 rime 的 punctuator：中文模式會變成全角，英文模式維持半角。
            val consumed = RimeEngine.sendKey(c.code)
            if (!consumed) currentInputConnection?.commitText(text, 1)
        } else {
            // 「、」這類字元沒有對應 keysym：先把組字收掉再直接上屏，免得編碼區留在畫面上。
            if (RimeEngine.commitComposition()) drainCommit()
            currentInputConnection?.commitText(text, 1)
        }
        drainCommit()
        refresh()
    }

    /** 挑字母工作階段結束（上屏、退到空、或換欄位）：切回原本的方案。 */
    private fun endPicking() {
        if (!picking) return
        picking = false
        RimeEngine.clearComposition()
        RimeEngine.selectSchema(originSchema)
    }

    /** rime 決定要上屏的東西（選字、成詞、標點上屏）都走這裡，不這樣讀會漏字。 */
    private fun drainCommit() {
        val text = RimeEngine.commitText() ?: return
        currentInputConnection?.commitText(text, 1)
        // 挑字母後選字／按空格＝這個詞結束，切回九鍵方案。
        if (picking) endPicking()
    }

    private fun refresh() {
        if (!RimeEngine.ready) return
        // 退到空組字（例如一直按退格）也代表挑字母結束，別把使用者留在 luna_pinyin。
        if (picking && RimeEngine.preedit().isEmpty()) endPicking()
        uiState = KeyboardState(
            code = RimeEngine.preedit(),
            candidates = RimeEngine.candidates().map { it.text },
            asciiMode = RimeEngine.getOption("ascii_mode"),
        )
    }

    private companion object {
        const val T9_SCHEMA = "t9_pinyin"
        const val LETTER_SCHEMA = "luna_pinyin"
        const val CURSOR_MARK = "\u2038"
        val LETTER_GROUPS = listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")
    }
}

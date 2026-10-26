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
                )
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
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

    /** rime 決定要上屏的東西（選字、成詞、標點上屏）都走這裡，不這樣讀會漏字。 */
    private fun drainCommit() {
        val text = RimeEngine.commitText() ?: return
        currentInputConnection?.commitText(text, 1)
    }

    private fun refresh() {
        if (!RimeEngine.ready) return
        uiState = KeyboardState(
            code = RimeEngine.preedit(),
            candidates = RimeEngine.candidates().map { it.text },
            asciiMode = RimeEngine.getOption("ascii_mode"),
        )
    }
}

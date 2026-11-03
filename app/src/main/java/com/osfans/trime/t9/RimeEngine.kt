package com.osfans.trime.t9

import android.content.Context
import android.util.Log
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.util.RimeStoragePermission

/**
 * librime 的啟動與按鍵封裝。
 *
 * 全部方法都假設在 IME 的主執行緒呼叫：`librime_jni` 內部只有一個 session，
 * 沒有鎖，跨執行緒呼叫會直接踩到 rime 的內部狀態。
 */
object RimeEngine {

    // X11 keysym：rime 認的是這套碼，不是 Android 的 KeyEvent.keyCode。
    const val KEY_BACKSPACE = 0xff08
    const val KEY_RETURN = 0xff0d
    const val KEY_ESCAPE = 0xff1b
    const val KEY_LEFT = 0xff51
    const val KEY_RIGHT = 0xff53
    const val KEY_UP = 0xff52
    const val KEY_DOWN = 0xff54
    const val KEY_PAGE_UP = 0xff55
    const val KEY_PAGE_DOWN = 0xff56
    const val KEY_SPACE = 0x20

    private const val TAG = "T9Rime"

    var ready = false
        private set

    /** 部署（編詞庫）是 rime 背景執行緒做的事，成功前按鍵不會出候選，所以要有這個狀態。 */
    @Volatile
    var deployed = false
        private set

    var onDeployed: (() -> Unit)? = null

    /** 權限核准後才同步並啟動；兩套鍵盤共用公共 `/Rime` 這份唯一資料源。 */
    fun startup(context: Context): Boolean {
        if (ready && RimeStoragePermission.isGranted(context)) return true
        return RimeStoragePermission.runIfGranted(context) {
            DataManager.sync()
            Rime.registerRimeMessageHandler { message ->
                when (message) {
                    is RimeMessage.DeployMessage -> when (message.data) {
                        RimeMessage.DeployMessage.State.Success -> {
                            Log.i(TAG, "rime 部署成功")
                            deployed = true
                            onDeployed?.invoke()
                        }

                        RimeMessage.DeployMessage.State.Failure -> Log.w(TAG, "rime 部署失敗")

                        else -> Unit
                    }

                    else -> Unit
                }
            }
            Rime.startupRime(
                DataManager.sharedDataDir.absolutePath,
                DataManager.userDataDir.absolutePath,
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0",
                true,
            )
            ready = true
        }
    }

    fun selectSchema(schemaId: String): Boolean {
        val ok = Rime.selectRimeSchema(schemaId)
        Log.i(TAG, "選方案 $schemaId → $ok（可用：${Rime.getAvailableRimeSchemaList().joinToString { it.id }}）")
        return ok
    }

    fun sendKey(keycode: Int, mask: Int = 0): Boolean = Rime.processRimeKey(keycode, mask)

    /** 目前使用中的方案 id（挑字母時要知道自己在 t9_pinyin 還是 luna_pinyin）。 */
    fun currentSchema(): String = Rime.getCurrentRimeSchema()

    /**
     * 把一整串字母當成按鍵餵給 rime（挑字母時用來重打「已修正的拼音」）。
     * 這條是 librime 的 simulate_key_sequence，不是 Android 的 KeyEvent。
     */
    fun simulateKeySequence(sequence: String): Boolean = Rime.simulateRimeKeySequence(sequence)

    /** 把目前組字中的內容直接上屏（標點鍵要送非 ASCII 字元前先收乾淨）。 */
    fun commitComposition(): Boolean = Rime.commitRimeComposition()

    /** 這一輪 rime 要上屏的文字（選字、成詞、標點），沒有就回 null；每次按鍵後都該讀一次。 */
    fun commitText(): String? = Rime.getRimeCommit().text

    fun preedit(): String = Rime.getRimeContext().composition.preedit ?: ""

    fun candidates(limit: Int = 9): Array<CandidateProto> = Rime.getRimeCandidates(0, limit)

    fun selectCandidate(index: Int): Boolean = Rime.selectRimeCandidate(index, false)

    fun changePage(backward: Boolean): Boolean = Rime.changeRimeCandidatePage(backward)

    /** 候選欄清空但不上屏（Backspace 到空、或按 Esc）時要叫這個。 */
    fun clearComposition() = Rime.clearRimeComposition()

    fun setOption(name: String, value: Boolean) = Rime.setRimeOption(name, value)

    fun getOption(name: String): Boolean = Rime.getRimeOption(name)

    fun syncUserData(): Boolean = Rime.syncRimeUserData()
}

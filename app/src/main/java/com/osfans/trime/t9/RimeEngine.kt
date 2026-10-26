package com.osfans.trime.t9

import android.content.Context
import android.util.Log
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeMessage
import java.io.File

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
    private const val MARKER = ".t9-shared-version"

    var ready = false
        private set

    /** 部署（編詞庫）是 rime 背景執行緒做的事，成功前按鍵不會出候選，所以要有這個狀態。 */
    @Volatile
    var deployed = false
        private set

    var onDeployed: (() -> Unit)? = null

    fun sharedDir(context: Context) = File(context.filesDir, "rime/shared")

    fun userDir(context: Context) = File(context.filesDir, "rime/user")

    /**
     * 把 APK 裡的 `assets/shared` 解到內部儲存。用版本號當marker：
     * 同一版只解一次，升級（versionName 變）才重解，使用者自己放進 user 目錄的東西永遠不動。
     */
    fun stageAssets(context: Context) {
        val shared = sharedDir(context)
        userDir(context).mkdirs()
        val marker = File(shared, MARKER)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        if (marker.exists() && marker.readText() == version) return
        shared.mkdirs()
        copyAssets(context, "shared", shared)
        marker.writeText(version ?: "unknown")
        // 換了版本的 shared，舊的編譯產物（prism/table/userdb）要丟給 rime 重建，
        // 否則 start_maintenance(fullCheck) 會拿舊 bin 配新 yaml。
        shared.listFiles()?.filter { it.extension in setOf("prism", "table", "bin") }?.forEach { it.delete() }
    }

    private fun copyAssets(context: Context, path: String, dest: File) {
        val children = context.assets.list(path) ?: return
        if (children.isEmpty()) {
            context.assets.open(path).use { input ->
                File(dest.parentFile, dest.name).outputStream().use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            if (child == MARKER) continue
            copyAssets(context, "$path/$child", File(dest, child))
        }
    }

    fun startup(context: Context) {
        if (ready) return
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
            sharedDir(context).absolutePath,
            userDir(context).absolutePath,
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0",
            true,
        )
        ready = true
    }

    fun selectSchema(schemaId: String): Boolean {
        val ok = Rime.selectRimeSchema(schemaId)
        Log.i(TAG, "選方案 $schemaId → $ok（可用：${Rime.getAvailableRimeSchemaList().joinToString { it.id }}）")
        return ok
    }

    fun sendKey(keycode: Int, mask: Int = 0): Boolean = Rime.processRimeKey(keycode, mask)

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

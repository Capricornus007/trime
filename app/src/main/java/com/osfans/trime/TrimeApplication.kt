/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Environment
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.receiver.RimeIntentReceiver
import com.osfans.trime.ui.main.LogActivity
import com.osfans.trime.util.isNightMode
import com.osfans.trime.worker.BackgroundSyncWork
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import splitties.systemservices.userManager
import timber.log.Timber
import java.io.File
import kotlin.system.exitProcess

/**
 * Custom Application class.
 * Application class will only be created once when the app run,
 * so you can init a "global" class here, whose methods serve other
 * classes everywhere.
 */
class TrimeApplication : Application() {
    val coroutineScope = MainScope() + CoroutineName("TrimeApplication")

    private val rimeIntentReceiver = RimeIntentReceiver()

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_UNLOCKED) return
            if (!isDirectBootMode) return
            Timber.d("Device unlocked, app will exit now and restart to normal mode")
            RimeDaemon.getFirstSessionOrNull()?.also {
                // try to shutdown rime gracefully
                RimeDaemon.stopRime()
            }
            exitProcess(0)
        }
    }

    private fun registerBroadcastReceiver() {
        val intentFilter =
            IntentFilter().apply {
                addAction(RimeIntentReceiver.ACTION_DEPLOY)
                addAction(RimeIntentReceiver.ACTION_SYNC_USER_DATA)
            }
        ContextCompat.registerReceiver(
            this,
            rimeIntentReceiver,
            intentFilter,
            PERMISSION_TEST_INPUT_METHOD,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    var isDirectBootMode = false
        private set

    val directBootAwareContext: Context
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isDirectBootMode) {
            createDeviceProtectedStorageContext()
        } else {
            applicationContext
        }

    /**
     * Rime 的 `shared/` 與 `rime/` 都掛在這個目錄底下。
     *
     * **公共目錄優先、裝好就用，不再讓向導問「存哪裡」**：預設直接落到
     * `<外部儲存根目錄>/Rime`（實測路徑 `/sdcard/Rime` = `/storage/emulated/0/Rime`），
     * 這樣使用者那份 PC↔手機共用的 rime 配置用一般檔案管理員就碰得到。
     * 順帶避開裝置側的坑：實測這臺 realme 的 `StorageManager.mkdirs` 建不出
     * `Android/data/<包>/files`（`DataManager.copyFile → getExternalFilesDir` 拋
     * RemoteException，最後 `System.exit(10)`），首啟就當機。
     * 寫公共目錄需要 All files access；拿不到時退回應用專用目錄而不是崩掉。
     */
    val externalFilesDir: File
        get() = with(directBootAwareContext) {
            val public = File(Environment.getExternalStorageDirectory(), PUBLIC_RIME_DIR_NAME)
            if (runCatching { public.mkdirs() && public.canWrite() }.getOrDefault(false)) {
                return@with public
            }
            Timber.w("公共目錄 ${public.path} 不可寫（沒有 All files access？），退回應用專用目錄")
            getExternalFilesDir(null) ?: filesDir
        }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !userManager.isUserUnlocked) {
            isDirectBootMode = true
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        val ctx = directBootAwareContext

        if (!BuildConfig.DEBUG) {
            Thread.setDefaultUncaughtExceptionHandler { _, e ->
                val crashTime = System.currentTimeMillis()
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                val lastCrashTimePrefKey = "last_crash_time"
                val lastCrashTime = sharedPrefs.getLong(lastCrashTimePrefKey, -1L)
                sharedPrefs.edit(commit = true) {
                    putLong(lastCrashTimePrefKey, crashTime)
                }
                if (crashTime - lastCrashTime <= 10_000L) {
                    // continuous crashes within 10 seconds, maybe in a crash loop. just bail
                    exitProcess(10)
                }
                startActivity(
                    Intent(ctx, LogActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        putExtra(LogActivity.FROM_CRASH, true)
                        // avoid transaction overflow
                        val truncated =
                            e.stackTraceToString().let {
                                if (it.length > MAX_STACKTRACE_SIZE) {
                                    it.take(MAX_STACKTRACE_SIZE) + "<truncated>"
                                } else {
                                    it
                                }
                            }
                        putExtra(LogActivity.CRASH_STACK_TRACE, truncated)
                    },
                )
                exitProcess(10)
            }
        }
        instance = this
        try {
            if (BuildConfig.DEBUG) {
                Timber.plant(
                    object : Timber.DebugTree() {
                        override fun createStackElementTag(element: StackTraceElement): String = "${super.createStackElementTag(element)}|${element.fileName}:${element.lineNumber}"

                        override fun log(
                            priority: Int,
                            tag: String?,
                            message: String,
                            t: Throwable?,
                        ) {
                            super.log(
                                priority,
                                "[${Thread.currentThread().name}] ${tag?.substringBefore('|')}",
                                "${tag?.substringAfter('|')}] $message",
                                t,
                            )
                        }
                    },
                )
            } else {
                Timber.plant(
                    object : Timber.Tree() {
                        override fun log(
                            priority: Int,
                            tag: String?,
                            message: String,
                            t: Throwable?,
                        ) {
                            if (priority < Log.INFO) return
                            Log.println(priority, "[${Thread.currentThread().name}]", message)
                        }
                    },
                )
            }
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

            Timber.d("isDirectBootMode=$isDirectBootMode")

            val appPrefs = AppPrefs.initDefault(sharedPreferences)
            // record last pid for crash logs
            appPrefs.internal.pid.apply {
                val currentPid = Process.myPid()
                lastPid = getValue()
                Timber.d("Last pid is $lastPid. Set it to current pid: $currentPid")
                setValue(currentPid)
            }
            ClipboardHelper.init(ctx)
            CollectionHelper.init(ctx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isDirectBootMode) {
                AppPrefs.defaultInstance().syncToDeviceEncryptedStorage()
                ThemeManager.syncToDeviceEncryptedStorage()
            }
            registerBroadcastReceiver()
            startWorkManager()
        } catch (e: Exception) {
            e.fillInStackTrace()
            return
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            ColorManager.onSystemNightModeChange(newConfig.isNightMode())
        } catch (e: Exception) {
            Timber.w(e, "Something wrong on configuration changed")
        }
    }

    private fun startWorkManager() {
        coroutineScope.launch {
            BackgroundSyncWork.start(directBootAwareContext)
        }
    }

    companion object {
        /** 公共目錄裡那個資料夾的名字（大小寫隨意，這裡跟 librime 慣例一致用 `Rime`）。 */
        const val PUBLIC_RIME_DIR_NAME = "Rime"

        /** 讓使用者把 Rime 資料改放公共目錄（例 `/sdcard/Rime`）的偏好鍵，見 [externalFilesDir]。 */
        const val RIME_DIR_PREF = "general_rime_dir"

        private var instance: TrimeApplication? = null
        private var lastPid: Int? = null

        fun getInstance() = instance ?: throw IllegalStateException("Trime application is not created!")

        fun getLastPid() = lastPid

        private const val MAX_STACKTRACE_SIZE = 128000

        /**
         * This permission is requested by com.android.shell, makes it possible to start
         * deploy from `adb shell am` command:
         * ```sh
         * adb shell am broadcast -a com.osfans.trime.action.DEPLOY
         * ```
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.0.0_r1/packages/Shell/AndroidManifest.xml#67
         *
         * other candidate: android.permission.TEST_INPUT_METHOD requires Android 14
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/packages/Shell/AndroidManifest.xml#628
         */
        const val PERMISSION_TEST_INPUT_METHOD = "android.permission.READ_INPUT_STATE"
    }
}

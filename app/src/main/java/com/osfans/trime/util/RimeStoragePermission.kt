/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import timber.log.Timber

/** Pure decisions used by [RimeStoragePermission] and its behavior tests. */
object RimeStoragePermissionPolicy {
    const val ANDROID_R = 30

    enum class SettingsDestination {
        AppAllFilesAccess,
        AllFilesAccess,
        AppDetails,
    }

    fun isGranted(
        sdkInt: Int,
        hasAllFilesAccess: Boolean,
        hasLegacyWriteAccess: Boolean,
    ): Boolean = if (sdkInt >= ANDROID_R) hasAllFilesAccess else hasLegacyWriteAccess

    fun settingsDestinations(sdkInt: Int): List<SettingsDestination> =
        if (sdkInt >= ANDROID_R) {
            listOf(
                SettingsDestination.AppAllFilesAccess,
                SettingsDestination.AllFilesAccess,
                SettingsDestination.AppDetails,
            )
        } else {
            listOf(SettingsDestination.AppDetails)
        }

    /** Execute [action] only after the permission decision succeeds. */
    inline fun runIfGranted(
        granted: Boolean,
        action: () -> Unit,
    ): Boolean {
        if (!granted) return false
        action()
        return true
    }
}

/**
 * Single permission gate for the public `/Rime` data root.
 *
 * Android 11+ uses All files access. Android 10 and earlier use the legacy write permission;
 * the manifest keeps legacy external-storage behavior enabled for those releases.
 */
object RimeStoragePermission {
    fun isGranted(context: Context = appContext): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            RimeStoragePermissionPolicy.isGranted(
                sdkInt = Build.VERSION.SDK_INT,
                hasAllFilesAccess = Environment.isExternalStorageManager(),
                hasLegacyWriteAccess = false,
            )
        } else {
            RimeStoragePermissionPolicy.isGranted(
                sdkInt = Build.VERSION.SDK_INT,
                hasAllFilesAccess = false,
                hasLegacyWriteAccess =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == PackageManager.PERMISSION_GRANTED,
            )
        }

    inline fun runIfGranted(
        context: Context = appContext,
        action: () -> Unit,
    ): Boolean = RimeStoragePermissionPolicy.runIfGranted(isGranted(context), action)

    /**
     * Open the most specific available permission page. OEMs may omit the per-app All files
     * access activity, so Android 11+ falls back to the general list and finally app details.
     */
    fun openSettings(context: Context): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        for (destination in RimeStoragePermissionPolicy.settingsDestinations(Build.VERSION.SDK_INT)) {
            val intent =
                when (destination) {
                    RimeStoragePermissionPolicy.SettingsDestination.AppAllFilesAccess ->
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)

                    RimeStoragePermissionPolicy.SettingsDestination.AllFilesAccess ->
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

                    RimeStoragePermissionPolicy.SettingsDestination.AppDetails ->
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                }.apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            val opened =
                runCatching {
                    context.startActivity(intent)
                    true
                }.getOrElse {
                    Timber.w(it, "Unable to open storage permission settings: ${intent.action}")
                    false
                }
            if (opened) return true
        }
        return false
    }
}

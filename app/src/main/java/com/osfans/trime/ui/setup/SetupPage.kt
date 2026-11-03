/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.setup

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.osfans.trime.R
import com.osfans.trime.util.InputMethodUtils
import com.osfans.trime.util.RimeStoragePermission

enum class SetupPage {
    Storage,
    Enable,
    Select,
    ;

    fun getStepText(context: Context) = context.getText(
        when (this) {
            Storage -> R.string.setup__step_one
            Enable -> R.string.setup__step_two
            Select -> R.string.setup__step_three
        },
    )

    fun getHintText(context: Context) = context.getText(
        when (this) {
            Storage ->
                if (isDone()) {
                    R.string.setup__storage_permission_granted_hint
                } else {
                    R.string.setup__storage_permission_denied
                }
            Enable -> R.string.setup__enable_ime_hint
            Select -> R.string.setup__select_ime_hint
        },
    )

    fun getButtonText(context: Context) = context.getText(
        when (this) {
            Storage -> R.string.setup__open_storage_settings
            Enable -> R.string.setup__enable_ime
            Select -> R.string.setup__select_ime
        },
    )

    fun getButtonAction(activity: FragmentActivity) {
        when (this) {
            Storage -> RimeStoragePermission.openSettings(activity)
            Enable -> InputMethodUtils.showImeEnablerActivity(activity)
            Select -> InputMethodUtils.showImePicker()
        }
    }

    fun showActionButton(): Boolean = true

    fun isDone() = when (this) {
        Storage -> RimeStoragePermission.isGranted()
        Enable -> InputMethodUtils.checkIsTrimeEnabled()
        Select -> InputMethodUtils.checkIsTrimeSelected()
    }

    fun getDoneText(context: Context) = context.getText(
        if (this == Storage) R.string.storage_permission_granted else R.string.done,
    )

    companion object {
        fun SetupPage.isLastPage() = this == entries.last()

        fun Int.isLastPage() = this == entries.size - 1

        fun hasUndonePage() = entries.any { !it.isDone() }

        fun firstUndonePage() = entries.firstOrNull { !it.isDone() }
    }
}

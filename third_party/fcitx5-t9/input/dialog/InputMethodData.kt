/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dialog

import android.content.Context
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RimeInputMethodEntries
import org.fcitx.fcitx5.android.core.RimeInputMethodSwitcher
import org.fcitx.fcitx5.android.utils.inputMethodManager

data class InputMethodData(
    val uniqueName: String,
    val name: String,
    val ime: Boolean,
) {
    data class Resolved(
        val entries: List<InputMethodData>,
        val currentUniqueName: String,
    )

    companion object {
        fun normalizePickerName(name: String): String = when (name) {
            "Latin Mode", "英文模式" -> "英文"
            "雾凇拼音" -> RimeInputMethodEntries.RimeDisplayName
            "中文九键" -> RimeInputMethodEntries.T9DisplayName
            else -> name
        }

        suspend fun resolve(fcitx: FcitxAPI, context: Context): Resolved {
            val switcherState = RimeInputMethodSwitcher.resolveEntries(fcitx)
            val enabled = switcherState.enabled
                .map { InputMethodData(it.uniqueName, normalizePickerName(it.displayName), false) }
                .toMutableList()
            enabled += context.inputMethodManager.enabledInputMethodList
                .filter { it.packageName != context.packageName }
                .map {
                    val label = it.loadLabel(context.packageManager).toString()
                    InputMethodData(it.id, normalizePickerName(label), true)
                }
            return Resolved(
                entries = enabled.toList(),
                currentUniqueName = switcherState.currentUniqueName,
            )
        }
    }
}

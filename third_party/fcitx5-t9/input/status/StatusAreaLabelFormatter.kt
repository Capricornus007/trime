/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import org.fcitx.fcitx5.android.core.RimeInputMethodEntries

object StatusAreaLabelFormatter {
    fun normalize(label: String): String = when (label) {
        "Latin Mode", "英文模式" -> "英文"
        "雾凇拼音" -> RimeInputMethodEntries.RimeDisplayName
        "中文九键" -> RimeInputMethodEntries.T9DisplayName
        else -> label
    }
}

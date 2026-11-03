/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.keyboard

import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard

/** Product-owned layout: T9 remains usable even when the selected theme has no T9 preset. */
object UnifiedKeyboardLayouts {
    private fun key(
        click: String,
        label: String = "",
        width: Float = 0f,
    ) = TextKeyboard.TextKey(
        click = KeyActionToken.Plain(click),
        label = label,
        width = width,
    )

    val chineseT9 = TextKeyboard(
        name = "拼音九宮格",
        author = "Trime community",
        width = 25f,
        height = 55f,
        columns = 4,
        asciiMode = 0,
        resetAsciiMode = true,
        lock = true,
        keys = listOf(
            key(",", "，。？！"),
            key("2", "ABC"),
            key("3", "DEF"),
            key("BackSpace", "⌫"),
            key("4", "GHI"),
            key("5", "JKL"),
            key("6", "MNO"),
            key("/", "、"),
            key("7", "PQRS"),
            key("8", "TUV"),
            key("9", "WXYZ"),
            key("space", "空格"),
            key("Keyboard_number", "數字"),
            key("Keyboard_symbols", "符號"),
            key("0", "0"),
            key("Return", "⏎"),
        ),
    )
}

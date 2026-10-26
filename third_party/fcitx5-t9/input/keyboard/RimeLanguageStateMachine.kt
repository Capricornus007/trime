/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

object RimeLanguageStateMachine {

    enum class Keyboard {
        Text,
        T9,
    }

    enum class ReturnTarget {
        TextChinese,
        T9Chinese,
    }

    data class State(
        val keyboard: Keyboard,
        val returnTarget: ReturnTarget,
    )

    fun onToggleFromChinese(current: State): State = when (current.keyboard) {
        Keyboard.Text -> current.copy(
            keyboard = Keyboard.Text,
            returnTarget = ReturnTarget.TextChinese,
        )

        Keyboard.T9 -> current.copy(
            keyboard = Keyboard.Text,
            returnTarget = ReturnTarget.T9Chinese,
        )
    }

    fun onToggleFromEnglish(current: State): State = when (current.returnTarget) {
        ReturnTarget.TextChinese -> current.copy(keyboard = Keyboard.Text)
        ReturnTarget.T9Chinese -> current.copy(keyboard = Keyboard.T9)
    }

    fun onSchemaChanged(current: State, schemaId: String): State = if (schemaId == T9Keyboard.SchemaId) {
        current.copy(keyboard = Keyboard.T9)
    } else {
        current.copy(
            keyboard = Keyboard.Text,
            returnTarget = ReturnTarget.TextChinese,
        )
    }
}

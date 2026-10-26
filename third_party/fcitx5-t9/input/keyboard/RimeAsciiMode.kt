/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.Action

data class RimeAsciiModeStatus(
    val enabled: Boolean,
    val toggleActionId: Int?,
)

object RimeAsciiMode {

    private val latinModeHints = listOf(
        "ascii_mode",
        "latin mode",
        "latin-mode",
        "英文模式",
    )

    fun fromStatusArea(actions: Array<Action>): RimeAsciiModeStatus {
        val rimeAction = actions.firstOrNull { action ->
            action.name.contains("rime", ignoreCase = true) ||
                action.icon.contains("rime", ignoreCase = true)
        } ?: return RimeAsciiModeStatus(enabled = false, toggleActionId = null)

        val toggleActionId = rimeAction.menu
            ?.firstOrNull(::looksLikeLatinModeAction)
            ?.id

        return RimeAsciiModeStatus(
            enabled = rimeAction.shortText == "A",
            toggleActionId = toggleActionId,
        )
    }

    private fun looksLikeLatinModeAction(action: Action): Boolean = latinModeHints.any { hint ->
        action.name.contains(hint, ignoreCase = true) ||
            action.shortText.contains(hint, ignoreCase = true) ||
            action.longText.contains(hint, ignoreCase = true)
    }
}

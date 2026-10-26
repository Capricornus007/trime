/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText

internal fun sanitizeT9InputPanel(
    rawInput: String,
    inputPanel: FcitxEvent.InputPanelEvent.Data,
): FcitxEvent.InputPanelEvent.Data {
    if (rawInput.isNotBlank()) return inputPanel
    return inputPanel.copy(preedit = FormattedText.Empty)
}

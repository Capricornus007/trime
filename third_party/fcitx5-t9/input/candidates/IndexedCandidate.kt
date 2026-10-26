/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import org.fcitx.fcitx5.android.core.FcitxEvent

data class IndexedCandidate(
    val candidate: FcitxEvent.Candidate,
    val sourceIndex: Int,
)

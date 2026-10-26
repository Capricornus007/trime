/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.core.FcitxEvent.CandidateListEvent
import org.fcitx.fcitx5.android.core.FcitxEvent.PagedCandidateEvent

object CandidateBarState {

    fun isEmpty(data: CandidateListEvent.Data): Boolean = data.candidates.isEmpty()

    fun isEmpty(data: PagedCandidateEvent.Data): Boolean = data.candidates.isEmpty()
}

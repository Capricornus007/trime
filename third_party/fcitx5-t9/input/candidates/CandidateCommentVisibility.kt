/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import org.fcitx.fcitx5.android.input.FcitxInputMethodService

internal fun FcitxInputMethodService.shouldShowCandidateComment(): Boolean = !isT9ModeActive

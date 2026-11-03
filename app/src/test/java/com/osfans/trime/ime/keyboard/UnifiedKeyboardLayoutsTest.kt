/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.keyboard

import com.osfans.trime.data.theme.model.KeyActionToken
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class UnifiedKeyboardLayoutsTest :
    StringSpec({
        "owned T9 layout supplies every ambiguity digit without a theme" {
            val layout = UnifiedKeyboardLayouts.chineseT9
            layout.columns shouldBe 4
            layout.asciiMode shouldBe 0
            val actions = layout.keys.mapNotNull { (it.click as? KeyActionToken.Plain)?.token }.toSet()
            (('2'..'9').map { it.toString() }.all(actions::contains)) shouldBe true
            actions.contains("BackSpace") shouldBe true
            actions.contains("Return") shouldBe true
        }
    })

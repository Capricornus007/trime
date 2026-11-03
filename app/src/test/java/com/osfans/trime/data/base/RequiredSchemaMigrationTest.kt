/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.data.base

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class RequiredSchemaMigrationTest :
    StringSpec({
        "upgrade preserves user schemas and appends missing product schemas" {
            val input = """
                patch:
                  schema_list:
                    - schema: luna_pinyin
                    - schema: custom_user_schema
                    - schema: t9_pinyin
                  menu/page_size: 9
            """.trimIndent()
            val migrated = RequiredSchemaMigration.migrate(input, "unused")
            migrated shouldContain "- schema: custom_user_schema"
            migrated shouldContain "- schema: jaroomaji"
            migrated shouldContain "menu/page_size: 9"
            migrated.lines().size shouldBe input.lines().size + 1
        }

        "legacy generated list removes the duplicate simplified Chinese schema" {
            val input = """
                patch:
                  schema_list:
                    - schema: luna_pinyin
                    - schema: luna_pinyin_simp
                    - schema: t9_pinyin
            """.trimIndent()
            val migrated = RequiredSchemaMigration.migrate(input, "unused")
            migrated.contains("luna_pinyin_simp") shouldBe false
            migrated shouldContain "- schema: jaroomaji"
        }

        "migration is idempotent" {
            val input = """
                patch:
                  schema_list:
                    - schema: luna_pinyin
                    - schema: t9_pinyin
                    - schema: jaroomaji
            """.trimIndent()
            RequiredSchemaMigration.migrate(input, "unused") shouldBe input
        }
    })

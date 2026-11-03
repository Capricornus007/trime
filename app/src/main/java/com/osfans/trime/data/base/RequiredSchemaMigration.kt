/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.data.base

object RequiredSchemaMigration {
    val required = listOf("luna_pinyin", "t9_pinyin", "jaroomaji")
    private val legacyGenerated = setOf("luna_pinyin", "luna_pinyin_simp", "t9_pinyin")

    fun migrate(raw: String, emptyPatch: String): String {
        if (raw.isBlank()) return emptyPatch.trimIndent()
        val lines = raw.lines().toMutableList()
        val header = lines.indexOfFirst { it.trim() == "schema_list:" }
        if (header < 0) return raw
        val headerIndent = lines[header].indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        var end = header + 1
        while (end < lines.size) {
            val line = lines[end]
            val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) Int.MAX_VALUE else it }
            if (line.isNotBlank() && !line.trimStart().startsWith("#") && indent <= headerIndent) break
            end++
        }
        var entries = lines.subList(header + 1, end).toList()
        var existing = schemaIds(entries)
        if (existing == legacyGenerated) {
            val sizeBefore = lines.size
            lines.removeAll { it.trim() == "- schema: luna_pinyin_simp" }
            end -= sizeBefore - lines.size
            entries = lines.subList(header + 1, end).toList()
            existing = schemaIds(entries)
        }
        val itemIndent = entries.firstOrNull { it.trimStart().startsWith("- schema:") }
            ?.takeWhile(Char::isWhitespace)
            ?: " ".repeat(headerIndent + 2)
        val missing = required.filterNot(existing::contains)
        if (missing.isEmpty() && lines.joinToString("\n") == raw) return raw
        lines.addAll(end, missing.map { "$itemIndent- schema: $it" })
        return lines.joinToString("\n")
    }

    private fun schemaIds(lines: List<String>): Set<String> = lines.mapNotNull { line ->
        Regex("^-\\s*schema:\\s*([^#\\s]+)").find(line.trim())?.groupValues?.get(1)
    }.toSet()
}

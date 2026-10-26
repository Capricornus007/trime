/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.t9

import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.TextFormatFlag

object T9ReadablePreview {

    fun render(
        state: T9UiState,
        livePreedit: String,
        highlightedComment: String = "",
    ): String = render(
        livePreedit = livePreedit.ifBlank { state.preedit },
        highlightedComment = highlightedComment,
    )

    fun render(
        livePreedit: String,
        highlightedComment: String = "",
    ): String {
        val preview = if (highlightedComment.isBlank()) {
            livePreedit
        } else {
            livePreedit.t9PinyinToPinyin(highlightedComment)
        }
        return preview.replaceT9Pinyin()
    }

    fun renderFormatted(
        state: T9UiState,
        livePreedit: String,
        highlightedComment: String = "",
    ): FormattedText {
        val effectivePreedit = livePreedit.ifBlank { state.preedit }
        val preview = render(
            livePreedit = effectivePreedit,
            highlightedComment = highlightedComment,
        )
        if (preview.isBlank()) return FormattedText.Empty

        val fixedPrefixLength = fixedPreviewPrefixLength(
            state = state,
            effectivePreedit = effectivePreedit,
            preview = preview,
        )

        return when {
            fixedPrefixLength <= 0 -> FormattedText(
                strings = arrayOf(preview),
                flags = intArrayOf(TextFormatFlag.NoFlag.flag),
                cursor = preview.length,
            )

            fixedPrefixLength >= preview.length -> FormattedText(
                strings = arrayOf(preview),
                flags = intArrayOf(TextFormatFlag.Underline.flag),
                cursor = preview.length,
            )

            else -> FormattedText(
                strings = arrayOf(
                    preview.take(fixedPrefixLength),
                    preview.drop(fixedPrefixLength),
                ),
                flags = intArrayOf(
                    TextFormatFlag.Underline.flag,
                    TextFormatFlag.NoFlag.flag,
                ),
                cursor = preview.length,
            )
        }
    }

    private fun String.replaceT9Pinyin(): String = map(::digitPreviewChar).joinToString("")

    private fun fixedPreviewPrefixLength(
        state: T9UiState,
        effectivePreedit: String,
        preview: String,
    ): Int {
        val prefixEnd = state.fixedPrefixLength.coerceIn(0, state.rawInput.length)
        if (prefixEnd == 0) return 0
        val preeditPrefix = effectivePreedit.prefixForRawText(state.rawInput.take(prefixEnd))
        return render(
            livePreedit = preeditPrefix,
            highlightedComment = "",
        ).length.coerceAtMost(preview.length)
    }

    private fun String.prefixForRawText(rawPrefix: String): String {
        if (rawPrefix.isEmpty()) return ""
        var rawIndex = 0
        forEachIndexed { index, char ->
            val target = rawPrefix.getOrNull(rawIndex)
            if (target != null && char.equals(target, ignoreCase = true)) {
                rawIndex += 1
            }
            if (rawIndex >= rawPrefix.length) {
                return substring(0, index + 1)
            }
        }
        return this
    }

    private fun String.t9PinyinToPinyin(comment: String, separator: String = "'"): String {
        if (comment.isBlank()) return replaceT9Pinyin()

        val commentList = comment
            .split(' ')
            .filter(String::isNotBlank)
        val t9CommentList = commentList.map { syllable ->
            T9PinyinResolver.toDigits(syllable).ifBlank { syllable }
        }

        val previewList = split(' ').map { it }.toMutableList()
        t9CommentList.forEachIndexed { index, t9Pinyin ->
            if (index >= previewList.size) return@forEachIndexed
            previewList[index] = if (previewList[index].length >= t9Pinyin.length) {
                previewList[index].replace(t9Pinyin, commentList[index])
            } else {
                commentList[index].take(previewList[index].length)
            }
        }
        return previewList.joinToString(separator)
    }

    private fun digitPreviewChar(char: Char): Char = when (char) {
        '2' -> 'a'
        '3' -> 'd'
        '4' -> 'g'
        '5' -> 'j'
        '6' -> 'm'
        '7' -> 'p'
        '8' -> 't'
        '9' -> 'w'
        else -> char
    }
}

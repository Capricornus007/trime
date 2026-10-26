/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.t9

class T9PinyinResolver {

    data class Resolution(
        val replacementWindow: T9ReplacementWindow?,
        val sideRailEntries: List<T9SideRailEntry>,
    )

    fun resolve(rawInput: String, caret: Int, preedit: String): Resolution {
        if (rawInput.isEmpty()) {
            return Resolution(
                replacementWindow = null,
                sideRailEntries = emptyList(),
            )
        }

        val segments = splitSegments(rawInput)
        val preeditTokens = normalizeDelimiters(preedit).split(DELIMITER)
        val activeSegment = activeSegment(segments, caret, preeditTokens) ?: return Resolution(
            replacementWindow = null,
            sideRailEntries = emptyList(),
        )
        val rankedCandidates = rankedCandidates(
            rawToken = activeSegment.rawToken,
            preeditToken = activeSegment.preeditToken,
        )

        return Resolution(
            replacementWindow = activeSegment.window,
            sideRailEntries = rankedCandidates.map { candidate ->
                T9SideRailEntry(
                    text = candidate,
                    replacementWindow = candidateReplacementWindow(
                        activeWindow = activeSegment.window,
                        rawToken = activeSegment.rawToken,
                        candidate = candidate,
                    ),
                    selected = false,
                )
            },
        )
    }

    fun resolveForWindow(
        rawInput: String,
        preedit: String,
        window: T9ReplacementWindow,
    ): Resolution {
        if (rawInput.isEmpty() || window.end > rawInput.length) {
            return Resolution(
                replacementWindow = null,
                sideRailEntries = emptyList(),
            )
        }
        val rawToken = window.substringOf(rawInput)
        if (rawToken.isBlank()) {
            return Resolution(
                replacementWindow = null,
                sideRailEntries = emptyList(),
            )
        }
        val rankedCandidates = rankedCandidates(
            rawToken = rawToken,
            preeditToken = rawToken,
        )
        return Resolution(
            replacementWindow = window,
            sideRailEntries = rankedCandidates.map { candidate ->
                T9SideRailEntry(
                    text = candidate,
                    replacementWindow = candidateReplacementWindow(
                        activeWindow = window,
                        rawToken = rawToken,
                        candidate = candidate,
                    ),
                    selected = false,
                )
            },
        )
    }

    private fun rankedCandidates(
        rawToken: String,
        preeditToken: String,
    ): List<String> {
        val hamsterCandidates = hamsterCandidates(preeditToken)
            .ifEmpty { hamsterCandidates(rawToken) }
        if (hamsterCandidates.isNotEmpty()) {
            return hamsterCandidates
        }

        val fallbackToken = normalizeDelimiters(preeditToken).ifBlank {
            normalizeDelimiters(rawToken)
        }
        return if (fallbackToken.isNotBlank() && fallbackToken.all(::isT9EditableChar)) {
            legacyRankedCandidates(rawToken, preeditToken)
        } else {
            emptyList()
        }
    }

    private fun legacyRankedCandidates(
        rawToken: String,
        preeditToken: String,
    ): List<String> {
        val digits = toDigits(rawToken)
        if (digits.isBlank()) {
            return listOfNotNull(
                preeditToken.takeIf { it.isNotBlank() },
                rawToken.takeIf { it.isNotBlank() && rawToken != preeditToken },
            )
        }

        val ranked = linkedSetOf<String>()
        for (count in 1..digits.length) {
            shortCandidates(digits.take(count))
                .forEach(ranked::add)
            syllablesByDigits[digits.take(count)]
                ?.forEach(ranked::add)
        }
        if (ranked.isEmpty() && rawToken.isNotBlank()) {
            ranked += rawToken
        }
        val preferSingleLetterBucket = preferSingleLetterBucket(rawToken, preeditToken, digits)
        return ranked.sortedWith { left, right ->
            if (preferSingleLetterBucket) {
                compareValues(left.length > 1, right.length > 1)
            } else {
                compareValues(toDigits(right).length, toDigits(left).length)
            }.takeIf { it != 0 }
                ?: left.compareTo(right)
        }
    }

    private fun shortCandidates(digits: String): List<String> {
        if (digits.length != 1) return emptyList()
        return digitToLetters[digits.single()]
            ?.map(Char::toString)
            .orEmpty()
    }

    private fun activeSegment(
        segments: List<Segment>,
        caret: Int,
        preeditTokens: List<String>,
    ): ActiveSegment? {
        if (segments.isEmpty()) return null
        val normalizedCaret = caret.coerceIn(0, segments.last().window.end)
        segments.forEach { segment ->
            val prefixedSegment = focusedPrefixedSegment(
                segment = segment,
                preeditToken = preeditTokens.getOrElse(segment.index) { "" },
            )
            if (prefixedSegment != null) {
                return prefixedSegment
            }
        }
        val caretSegment = segmentForCaret(segments, normalizedCaret)
        val unresolvedDigitSegment = segments.firstOrNull { segment ->
            segment.index < caretSegment.index && segment.text.any(Char::isDigit)
        }
        if (unresolvedDigitSegment != null) {
            return focusedSegmentByCaret(
                segment = unresolvedDigitSegment,
                preeditToken = unresolvedDigitSegment.text,
                caret = unresolvedDigitSegment.window.end,
            )
        }
        val segment = caretSegment
        return focusedSegmentByCaret(
            segment = segment,
            preeditToken = preeditTokens.getOrElse(segment.index) { "" },
            caret = normalizedCaret,
        )
    }

    private fun segmentForCaret(segments: List<Segment>, caret: Int): Segment = segments.firstOrNull { caret in it.window.start until it.window.end }
        ?: segments.firstOrNull { caret == it.window.start }
        ?: segments.lastOrNull { it.window.end <= caret }
        ?: segments.first()

    private fun splitSegments(rawInput: String): List<Segment> {
        if (rawInput.isEmpty()) return emptyList()
        val segments = mutableListOf<Segment>()
        var start = 0
        var index = 0
        while (start <= rawInput.length) {
            val next = rawInput.indexOf(DELIMITER, start).let { if (it == -1) rawInput.length else it }
            if (next > start) {
                segments += Segment(
                    index = index,
                    window = T9ReplacementWindow(start = start, end = next),
                    text = rawInput.substring(start, next),
                )
                index += 1
            }
            if (next == rawInput.length) {
                break
            }
            start = next + 1
        }
        return segments
    }

    private data class Segment(
        val index: Int,
        val window: T9ReplacementWindow,
        val text: String,
    )

    private data class ActiveSegment(
        val index: Int,
        val window: T9ReplacementWindow,
        val rawToken: String,
        val preeditToken: String,
    )

    private data class EditableFocus(
        val offset: Int,
        val rawToken: String,
        val preeditToken: String,
    )

    private fun focusedPrefixedSegment(segment: Segment, preeditToken: String): ActiveSegment? {
        val editableOffset = segment.text.indexOfFirst(::isEditableChar)
        if (editableOffset <= 0) return null
        val editableRaw = segment.text.substring(editableOffset)
        if (editableRaw.isBlank()) return null

        val editablePreedit = preeditToken
            .dropWhile { !isEditableChar(it) }
            .trimStart()
        val focus = focusEditableToken(
            editableRaw = editableRaw,
            editablePreedit = editablePreedit,
        )

        return ActiveSegment(
            index = segment.index,
            window = T9ReplacementWindow(
                start = segment.window.start + editableOffset + focus.offset,
                end = segment.window.start + editableOffset + focus.offset + focus.rawToken.length,
            ),
            rawToken = focus.rawToken,
            preeditToken = focus.preeditToken,
        )
    }

    private fun focusedSegmentByCaret(
        segment: Segment,
        preeditToken: String,
        caret: Int,
    ): ActiveSegment {
        val caretOffset = (caret - segment.window.start).coerceIn(0, segment.text.length)
        if (caretOffset in 1 until segment.text.length) {
            val literalPrefix = segment.text.substring(0, caretOffset)
            val suffix = segment.text.substring(caretOffset)
            if (shouldAdvancePastLiteralPrefix(literalPrefix, suffix) &&
                longestLiteralSyllablePrefix(segment.text) == caretOffset
            ) {
                val hintedPreedit = preeditToken.drop(caretOffset).trimStart()
                val focus = focusEditableToken(
                    editableRaw = suffix,
                    editablePreedit = hintedPreedit,
                )
                return ActiveSegment(
                    index = segment.index,
                    window = T9ReplacementWindow(
                        start = segment.window.start + caretOffset + focus.offset,
                        end = segment.window.start + caretOffset + focus.offset + focus.rawToken.length,
                    ),
                    rawToken = focus.rawToken,
                    preeditToken = focus.preeditToken,
                )
            }
        }
        val focus = focusEditableToken(
            editableRaw = segment.text,
            editablePreedit = preeditToken,
        )
        if (focus.offset > 0) {
            return ActiveSegment(
                index = segment.index,
                window = T9ReplacementWindow(
                    start = segment.window.start + focus.offset,
                    end = segment.window.start + focus.offset + focus.rawToken.length,
                ),
                rawToken = focus.rawToken,
                preeditToken = focus.preeditToken,
            )
        }
        return ActiveSegment(
            index = segment.index,
            window = segment.window,
            rawToken = segment.text,
            preeditToken = preeditToken,
        )
    }

    private fun focusEditableToken(
        editableRaw: String,
        editablePreedit: String,
    ): EditableFocus {
        var focusOffset = 0
        var rawRemainder = editableRaw
        var preeditRemainder = editablePreedit

        leadingLiteralPrefixBeforeDigitLength(rawRemainder)
            ?.takeIf { it in 1 until rawRemainder.length }
            ?.let { literalPrefixLength ->
                focusOffset += literalPrefixLength
                rawRemainder = rawRemainder.drop(literalPrefixLength)
                preeditRemainder = preeditRemainder.drop(literalPrefixLength).trimStart()
            }

        if (rawRemainder.length == 2 &&
            rawRemainder.all { it.lowercaseChar() in 'a'..'z' } &&
            rawRemainder !in Syllables
        ) {
            focusOffset += 1
            rawRemainder = rawRemainder.drop(1)
            preeditRemainder = preeditRemainder.drop(1).trimStart()
        }

        while (true) {
            val literalPrefixLength = longestLiteralSyllablePrefix(rawRemainder)
            if (literalPrefixLength <= 0 || literalPrefixLength >= rawRemainder.length) {
                break
            }
            val literalPrefix = rawRemainder.substring(0, literalPrefixLength)
            val suffix = rawRemainder.substring(literalPrefixLength)
            if (!shouldAdvancePastLiteralPrefix(literalPrefix, suffix)) {
                break
            }
            focusOffset += literalPrefixLength
            rawRemainder = suffix
            preeditRemainder = preeditRemainder.drop(literalPrefixLength).trimStart()
        }

        preeditRemainder = alignPreeditRemainder(
            rawRemainder = rawRemainder,
            preeditRemainder = preeditRemainder,
        )
        val hamsterTailHint = hamsterDigitTailHint(editablePreedit)
        if (rawRemainder.any(Char::isDigit) &&
            hamsterTailHint.length in 1 until rawRemainder.length
        ) {
            val hintedStart = rawRemainder.lastIndexOf(hamsterTailHint)
            if (hintedStart > 0) {
                val hintedPrefix = rawRemainder.substring(0, hintedStart)
                if (hintedPrefix.all { it.isDigit() || it.lowercaseChar() in 'a'..'z' }) {
                    focusOffset += hintedStart
                    rawRemainder = rawRemainder.drop(hintedStart)
                    preeditRemainder = hamsterTailHint
                }
            }
        }

        val hintedLength = preeditRemainder
            .substringBefore(' ')
            .takeWhile(::isEditableChar)
            .length
        val inferredLength = longestSyllablePrefixLength(rawRemainder)
        val targetLength = when {
            inferredLength > 0 -> inferredLength
            hintedLength in 1..rawRemainder.length -> hintedLength
            else -> rawRemainder.length
        }
        val rawToken = rawRemainder.take(targetLength)
        val preeditToken = preeditRemainder
            .substringBefore(' ')
            .takeWhile(::isEditableChar)
            .ifBlank { rawToken }
            .take(targetLength)
            .ifBlank { rawToken }
        return EditableFocus(
            offset = focusOffset,
            rawToken = rawToken,
            preeditToken = preeditToken,
        )
    }

    companion object {
        private const val DELIMITER = '\''
        private val digitToLetters = mapOf(
            '1' to "'",
            '2' to "abc",
            '3' to "def",
            '4' to "ghi",
            '5' to "jkl",
            '6' to "mno",
            '7' to "pqrs",
            '8' to "tuv",
            '9' to "wxyz",
        )

        private val representativeToGroup = mapOf(
            '\'' to "'",
            'a' to "abc",
            'd' to "def",
            'g' to "ghi",
            'j' to "jkl",
            'm' to "mno",
            'p' to "pqrs",
            't' to "tuv",
            'w' to "wxyz",
        )

        private val letterToRepresentative = buildMap {
            representativeToGroup.forEach { (representative, letters) ->
                letters.forEach { letter ->
                    put(letter, representative)
                }
            }
        }

        private val representativeToDigit = buildMap {
            digitToLetters.forEach { (digit, letters) ->
                letters.forEach { letter ->
                    put(letter, digit)
                }
            }
            put('\'', '1')
            representativeToGroup.forEach { (representative, letters) ->
                val digit = digitToLetters.entries
                    .firstOrNull { (_, group) -> group == letters }
                    ?.key
                    ?: return@forEach
                put(representative, digit)
            }
        }

        private val Syllables = (
            "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu " +
                "ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo " +
                "da dai dan dang dao de dei deng di dian diao die ding diu dong dou du duan dui dun duo " +
                "e ei en eng er " +
                "fa fan fang fei fen feng fo fou fu " +
                "ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo " +
                "ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo " +
                "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun " +
                "ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo " +
                "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu long lou lu luan lue lun luo lv " +
                "ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu " +
                "na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nue nuo nv " +
                "o ou " +
                "pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu " +
                "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun " +
                "ran rang rao re ren reng ri rong rou ru ruan rui run ruo " +
                "sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo " +
                "ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo " +
                "wa wai wan wang wei wen weng wo wu " +
                "xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun " +
                "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun " +
                "za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo"
            ).split(' ')

        private val syllablesBySignature = buildMap<String, MutableSet<String>> {
            Syllables.forEach { syllable ->
                getOrPut(toSignature(syllable)) { linkedSetOf() }.add(syllable)
            }
        }

        private val syllablesByDigits = buildMap<String, MutableSet<String>> {
            Syllables.forEach { syllable ->
                getOrPut(toDigits(syllable)) { linkedSetOf() }.add(syllable)
            }
        }

        private val syllableToDigits = buildMap<String, String> {
            Syllables.forEach { syllable ->
                put(syllable, toDigits(syllable))
            }
        }

        private fun isEditableChar(char: Char): Boolean = char.lowercaseChar() in 'a'..'z'

        private fun isRepresentativeChar(char: Char): Boolean = representativeToGroup.containsKey(char.lowercaseChar())

        private fun isT9EditableChar(char: Char): Boolean = char in '1'..'9' || isRepresentativeChar(char)

        private fun shouldAdvancePastLiteralPrefix(
            literalPrefix: String,
            suffix: String,
        ): Boolean {
            if (suffix.isEmpty()) return false
            if (suffix.any { it in '1'..'9' }) return true
            if (literalPrefix.length == 1 &&
                suffix.length == 1 &&
                suffix.first().lowercaseChar() in 'a'..'z'
            ) {
                return true
            }
            return suffix.any(::isRepresentativeChar) && toSignature(literalPrefix) != literalPrefix
        }

        private fun preferSingleLetterBucket(
            rawToken: String,
            preeditToken: String,
            digits: String,
        ): Boolean {
            if (digits.length != 2) return false
            if (preeditToken.any(::isEditableChar)) return false
            return rawToken.all(::isT9EditableChar)
        }

        private fun longestLiteralSyllablePrefix(text: String): Int = Syllables
            .asSequence()
            .filter { syllable -> text.startsWith(syllable) }
            .maxOfOrNull { it.length }
            ?: 0

        private fun leadingLiteralPrefixBeforeDigitLength(text: String): Int? {
            val digitIndex = text.indexOfFirst(Char::isDigit)
            if (digitIndex <= 0) return null
            return digitIndex.takeIf { prefixEnd ->
                text.substring(0, prefixEnd).all { it.lowercaseChar() in 'a'..'z' }
            }
        }

        private fun alignPreeditRemainder(rawRemainder: String, preeditRemainder: String): String {
            val trimmed = preeditRemainder.trimStart()
            if (trimmed.isEmpty()) return trimmed
            if (rawRemainder.any(Char::isDigit)) {
                val firstDigitIndex = trimmed.indexOfFirst(Char::isDigit)
                if (firstDigitIndex >= 0) {
                    return trimmed.substring(firstDigitIndex)
                }
            }
            return trimmed.dropWhile { !isEditableChar(it) && !it.isDigit() }
        }

        private fun hamsterDigitTailHint(preeditText: String): String {
            val compact = normalizeDelimiters(preeditText).replace(" ", "")
            if (compact.isEmpty()) return ""
            val firstDigitIndex = compact.indexOfFirst(Char::isDigit)
            if (firstDigitIndex < 0) return ""
            return compact.substring(firstDigitIndex)
        }

        private fun longestSyllablePrefixLength(text: String): Int = (1..text.length)
            .filter { length ->
                val token = text.substring(0, length)
                token in Syllables || syllablesBySignature.containsKey(toSignature(token))
            }
            .maxOrNull()
            ?: 0

        internal fun toSignature(text: String): String = buildString(text.length) {
            text.lowercase().forEach { char ->
                append(letterToRepresentative[char] ?: char)
            }
        }

        internal fun toDigits(text: String): String = buildString(text.length) {
            text.lowercase().forEach { char ->
                when {
                    char in '1'..'9' -> append(char)
                    else -> representativeToDigit[char]?.let(::append)
                }
            }
        }

        private fun normalizeDelimiters(text: String): String = text.replace('‘', DELIMITER)
            .replace('’', DELIMITER)

        private fun hamsterCandidates(token: String): List<String> {
            val normalizedToken = normalizeDelimiters(token)
            if (normalizedToken.isBlank()) return emptyList()

            val pinyinList = mutableListOf<String>()
            var compactPreedit = normalizedToken.replace(" ", "")
            if (compactPreedit.isEmpty()) return emptyList()

            while (compactPreedit.isNotEmpty() && !compactPreedit.first().isDigit()) {
                compactPreedit = compactPreedit.drop(1)
            }

            if (compactPreedit.isEmpty()) {
                var lastPinyin = normalizedToken
                    .split(' ')
                    .lastOrNull()
                    .orEmpty()
                while (lastPinyin.isNotEmpty() && !lastPinyin.first().isLowerCase()) {
                    lastPinyin = lastPinyin.drop(1)
                }
                var firstT9Pinyin = syllableToDigits[lastPinyin].orEmpty()
                if (firstT9Pinyin.isBlank() &&
                    lastPinyin.length <= 2 &&
                    lastPinyin.all { it.lowercaseChar() in 'a'..'z' }
                ) {
                    val tailToken = lastPinyin.takeLast(1)
                    firstT9Pinyin = syllableToDigits[tailToken].orEmpty()
                    if (firstT9Pinyin.isBlank()) {
                        firstT9Pinyin = toDigits(tailToken)
                    }
                }
                while (firstT9Pinyin.isNotEmpty()) {
                    pinyinList += hamsterCandidatesForDigits(firstT9Pinyin)
                    firstT9Pinyin = firstT9Pinyin.dropLast(1)
                }
            } else {
                for (count in 1..compactPreedit.length) {
                    val sub = compactPreedit.take(count)
                    if (!hamsterTrieContains(sub)) break
                    pinyinList += hamsterCandidatesForDigits(sub)
                }
            }

            return pinyinList
                .distinct()
                .sortedWith { left, right ->
                    compareValues(right.length, left.length)
                        .takeIf { it != 0 }
                        ?: run {
                            val leftNumeric = left.toIntOrNull() != null
                            val rightNumeric = right.toIntOrNull() != null
                            when {
                                leftNumeric != rightNumeric -> if (leftNumeric) 1 else -1
                                else -> left.compareTo(right)
                            }
                        }
                }
        }

        private fun hamsterCandidatesForDigits(digits: String): List<String> = when {
            digits.length == 1 -> digitToLetters[digits.single()]
                ?.map(Char::toString)
                .orEmpty() + digits

            else -> syllablesByDigits[digits]?.toList().orEmpty()
        }

        private fun hamsterTrieContains(prefix: String): Boolean = prefix.length == 1 || syllablesByDigits.keys.any { it.startsWith(prefix) }
    }

    private fun candidateReplacementWindow(
        activeWindow: T9ReplacementWindow,
        rawToken: String,
        candidate: String,
    ): T9ReplacementWindow {
        val candidateDigitsLength = toDigits(candidate).length
        val rawDigitsLength = toDigits(rawToken).length
        val replacementLength = when {
            candidateDigitsLength in 1 until rawDigitsLength -> candidateDigitsLength
            else -> activeWindow.length
        }
        return T9ReplacementWindow(
            start = activeWindow.start,
            end = activeWindow.start + replacementLength.coerceIn(1, activeWindow.length),
        )
    }
}

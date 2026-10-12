// OcrTextNormalizer.kt (полный файл)
package com.example.screentranslator

import android.graphics.Rect

/**
 * Работа с текстом OCR.
 *
 * ИЗМЕНЕНО (textsAreSimilar):
 *   - Добавлена проверка на расстояние Левенштейна для коротких
 *     текстов (≤ 30 символов). Если расстояние ≤ 2 — тексты похожи.
 *     Ловит "start chapter" vs "start chaper"  (dist=1)
 *     и     "start chapter" vs "start chapler" (dist=1).
 */
object OcrTextNormalizer {

    private const val VOWELS = "aeiouyAEIOUYаеёиоуыэюяАЕЁИОУЫЭЮЯ"

    private val NORMALIZE_SKIP_WORDS = setOf(
        "A", "An", "The", "I", "OK", "It"
    )

    private val NORMALIZE_LEAD_REGEX =
        Regex("^([A-Z0-9]{1,3}|[A-Z]\\.)\\s+([A-Z].*)$")

    private val NORMALIZE_TRAIL_REGEX = Regex("^(.*[a-zа-яё.,!?])\\s+([A-Za-z0-9]{1,2})$")

    private val URL_REGEX = Regex(
        """(https?://|www\.|[a-z0-9\-]+\.(com|ru|org|net|io|dev|edu|gov)\b)""",
        RegexOption.IGNORE_CASE
    )
    private val LOG_REGEX = Regex("""^\[\d{2}:\d{2}:\d{2}]\s*(OCR|TR)\b""")

    private val RARE_CHARS: Set<Char> = hashSetOf(
        'ş', 'Ş', 'ţ', 'Ţ', 'ģ', 'Ģ', 'ż', 'Ż', 'ý', 'Ý',
        'þ', 'Þ', 'ð', 'Ð', 'ł', 'Ł', 'ń', 'Ń', 'ć', 'Ć',
        'ę', 'Ę', 'ą', 'Ą', 'ś', 'Ś', 'ź', 'Ź', 'ğ', 'Ğ',
        'ı', 'İ', 'ř', 'Ř', 'ě', 'Ě', 'ů', 'Ů', 'ž', 'Ž',
        'á', 'Á', 'é', 'É', 'í', 'Í', 'ó', 'Ó', 'ú', 'Ú',
        'ä', 'Ä', 'ö', 'Ö', 'ü', 'Ü', 'ß', 'ç', 'Ç', 'ñ',
        'Ñ', 'õ', 'Õ', 'ã', 'Ã', 'å', 'Å', 'æ', 'Æ', 'ø',
        'Ø', 'đ', 'Đ', 'ħ', 'Ħ', 'ĸ', 'ŀ', 'ŉ', 'ŧ', 'Ŧ',
        'ƀ', 'ƃ', 'ƈ', 'ƌ', 'ƒ', 'ƙ', 'ƚ', 'ƞ', 'ơ', 'Ơ',
        'ư', 'Ư', 'ƴ', 'Ƶ', 'ǆ', 'ǉ', 'ǌ', 'ǎ', 'Ǐ', 'ǒ',
        'ǔ', 'ǖ', 'ǘ', 'ǚ', 'ǜ', 'ș', 'Ș', 'ț', 'Ț', 'ȝ'
    )

    private val ENG_BIGRAMS: Set<String> = hashSetOf(
        "th", "he", "in", "er", "an", "re", "on", "at", "en", "nd",
        "ti", "es", "or", "te", "of", "ed", "is", "it", "al", "ar",
        "st", "to", "nt", "ng", "se", "ha", "as", "ou", "io", "le",
        "ve", "co", "me", "de", "hi", "ri", "ro", "ic", "ne", "ea",
        "ra", "ce", "li", "ch", "ll", "be", "ma", "si", "om", "ur",
        "ay", "da", "od", "ad", "ai", "il", "ir", "el", "ly", "et",
        "ee", "oo", "ie", "ue", "ui", "oa", "ta", "ac", "ca", "nc",
        "no", "ot", "so", "us", "un", "up", "ut", "id", "im", "pe",
        "po", "pr", "pl", "tr", "tw", "wh", "sh", "ck", "gh", "ph",
        "qu", "ge", "gi", "gu", "ga", "go", "lo", "la", "lu", "mi",
        "mu", "na", "ni", "nu", "ob", "oc", "op", "pa", "pi", "pu",
        "sa", "sc", "sp", "su", "sy", "va", "vi", "vo", "wa", "we",
        "wi", "wo", "ye", "yo", "za", "ze", "zo",
        "sl", "ep", "py", "ap", "pp", "ys", "em", "tt", "ty", "ow",
        "bl", "cl", "fl", "gl", "br", "cr", "dr", "fr", "gr", "sw",
        "sk", "sm", "sn", "sq", "str", "scr", "spr", "spl", "thr",
        "shr", "squ", "ght", "igh", "ein", "eir", "our", "oul", "ous",
        "ing", "ion", "ent", "ant", "and", "old", "ild", "ind", "ill",
        "all", "ell", "oll", "ull", "ack", "eck", "ick", "ock", "uck",
        "ace", "ice", "oce", "uce", "age", "ige", "oge", "uge",
        "are", "ere", "ire", "ore", "ure", "ave", "eve", "ive", "ove",
        "ate", "ite", "ote", "ute", "ame", "eme", "ime", "ome", "ume",
        "ane", "ene", "ine", "one", "une", "ale", "ele", "ile", "ole",
        "ape", "ipe", "ope", "ale", "ile", "ole", "ule"
    )

    private val ENG_SUFFIXES: List<String> = listOf(
        "ing", "ed", "ly", "er", "est", "tion", "sion", "ous", "ful",
        "less", "able", "ible", "ment", "ness", "ity", "ty", "al",
        "ic", "ive", "ish", "en", "an", "ary", "ory", "ize", "ise",
        "ate", "y", "ies", "ied", "ier", "iest", "able", "ible"
    )

    private const val MAX_RARE_CHARS = 2
    private const val MAX_CONSECUTIVE_CONSONANTS = 5

    private const val LEVENSHTEIN_MAX_DIST = 2
    private const val LEVENSHTEIN_MAX_LEN = 30

    // ============================================================
    // НОРМАЛИЗАЦИЯ
    // ============================================================

    fun normalizeText(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return t

        val words = t.split(Regex("\\s+"))
        val cleaned = ArrayList<String>(words.size)

        for (word in words) {
            if (word.isEmpty()) continue

            val isAllDigitsAndPercent = word.all { it.isDigit() || it == '%' || it == ':' }
            if (isAllDigitsAndPercent) continue

            var cutIdx = word.length
            while (cutIdx > 0) {
                val c = word[cutIdx - 1]
                if (c.isDigit() || c == '%') {
                    cutIdx--
                } else {
                    break
                }
            }
            val trimmed = word.substring(0, cutIdx)
            if (trimmed.isEmpty()) continue

            cleaned.add(trimmed)
        }

        if (cleaned.isEmpty()) return ""

        t = cleaned.joinToString(" ")

        var prev: String
        var iter = 0
        do {
            prev = t
            val leadMatch = NORMALIZE_LEAD_REGEX.find(t)
            if (leadMatch != null) {
                val prefix = leadMatch.groupValues[1]
                val rest = leadMatch.groupValues[2]
                if (prefix !in NORMALIZE_SKIP_WORDS) {
                    t = rest
                }
            }
            iter++
        } while (t != prev && iter < 5)

        val trailMatch = NORMALIZE_TRAIL_REGEX.find(t)
        if (trailMatch != null) {
            t = trailMatch.groupValues[1]
        }

        return t.trim()
    }

    // ============================================================
    // СРАВНЕНИЕ ТЕКСТОВ
    // ============================================================

    fun textsAreSimilar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val la = a.trim().lowercase()
        val lb = b.trim().lowercase()
        if (la == lb) return true

        val hasCyrA = la.any { it in '\u0400'..'\u04FF' }
        val hasLatA = la.any { it in 'a'..'z' }
        val hasCyrB = lb.any { it in '\u0400'..'\u04FF' }
        val hasLatB = lb.any { it in 'a'..'z' }

        if (hasCyrA && hasLatB) return false
        if (hasLatA && hasCyrB) return false

        if (la.length <= LEVENSHTEIN_MAX_LEN && lb.length <= LEVENSHTEIN_MAX_LEN) {
            val dist = levenshteinDistance(la, lb)
            if (dist <= LEVENSHTEIN_MAX_DIST) {
                return true
            }
        }

        val minLen = minOf(la.length, lb.length)
        val maxLen = maxOf(la.length, lb.length)
        if (minLen == 0) return false

        if (maxLen > minLen * 3 / 2) {
            if (la.startsWith(lb) || lb.startsWith(la)) {
                return maxLen <= minLen + 5
            }
            return false
        }

        if (la.startsWith(lb) || lb.startsWith(la)) {
            if (maxLen > 20 && (maxLen - minLen) > 10) {
                return false
            }
            return true
        }

        if (maxLen <= 20) {
            val shorter = if (la.length <= lb.length) la else lb
            val longer = if (la.length <= lb.length) lb else la
            var common = 0
            for (c in shorter) {
                if (c.isLetterOrDigit() && longer.contains(c)) common++
            }
            val lettersInShort = shorter.count { it.isLetterOrDigit() }
            if (lettersInShort == 0) return false
            return common.toDouble() / lettersInShort.toDouble() >= 0.6
        }

        return false
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val s1 = if (a.length <= b.length) a else b
        val s2 = if (a.length <= b.length) b else a

        val n = s1.length
        val m = s2.length

        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (j in 1..m) {
            curr[0] = j
            val c2 = s2[j - 1]
            for (i in 1..n) {
                val cost = if (s1[i - 1] == c2) 0 else 1
                val del = prev[i] + 1
                val ins = curr[i - 1] + 1
                val sub = prev[i - 1] + cost
                curr[i] = minOf(del, ins, sub)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }

        return prev[n]
    }

    // ============================================================
    // ДЕДУПЛИКАЦИЯ
    // ============================================================

    fun deduplicateOverlapping(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size <= 1) return blocks

        val sorted = blocks.sortedByDescending { b ->
            val r = b.boundingBox
            b.confidence.toDouble() * (r.width().toDouble() * r.height().toDouble())
        }

        val accepted = ArrayList<OcrBlock>(sorted.size)
        for (b in sorted) {
            var dup = false
            for (other in accepted) {
                val r = b.boundingBox
                val o = other.boundingBox
                val ix1 = maxOf(r.left, o.left)
                val iy1 = maxOf(r.top, o.top)
                val ix2 = minOf(r.right, o.right)
                val iy2 = minOf(r.bottom, o.bottom)
                val iw = ix2 - ix1
                val ih = iy2 - iy1
                if (iw <= 0 || ih <= 0) continue
                val inter = iw.toLong() * ih.toLong()
                val minArea = minOf(
                    r.width().toLong() * r.height().toLong(),
                    o.width().toLong() * o.height().toLong()
                )
                if (minArea <= 0) continue
                val ratio = inter.toDouble() / minArea.toDouble()
                if (ratio >= 0.5 && textsAreSimilar(b.text, other.text)) {
                    dup = true
                    break
                }
            }
            if (!dup) accepted.add(b)
        }
        return accepted
    }

    // ============================================================
    // ДЕЛЕНИЕ ПО ТЕКСТУ И ЦИФРАМ
    // ============================================================

    fun splitByTextAndNumbers(block: OcrBlock): List<OcrBlock> {
        val elements = block.elements
        if (elements.isEmpty()) return listOf(block)

        var hasDigitElements = false
        for (el in elements) {
            if (el.text.any { it.isDigit() || it == '%' }) {
                hasDigitElements = true
                break
            }
        }
        if (!hasDigitElements) return listOf(block)

        var significantWords = 0
        for (el in elements) {
            val t = el.text.trim()
            if (t.length >= 2 && t.any { it.isLetter() }) {
                significantWords++
            }
        }
        if (significantWords > 4) {
            return listOf(block)
        }

        val result = ArrayList<OcrBlock>(elements.size)

        for (el in elements) {
            val t = el.text.trim()
            if (t.isEmpty()) continue

            val hasLetter = t.any { it.isLetter() }
            if (!hasLetter) continue

            val r = el.boundingBox
            if (r.width() <= 0 || r.height() <= 0) continue

            val elBox = Rect(r)

            result.add(
                OcrBlock(
                    text = t,
                    boundingBox = elBox,
                    confidence = block.confidence,
                    initialBoundingBox = Rect(elBox),
                    elements = listOf(el)
                )
            )
        }
        return if (result.isEmpty()) listOf(block) else result
    }

    // ============================================================
    // ДЕЛЕНИЕ ПО ОДИНОЧНЫМ БУКВАМ
    // ============================================================

    fun splitBySingleLetters(block: OcrBlock): List<OcrBlock> {
        val text = block.text.trim()
        if (text.isEmpty()) return listOf(block)

        val elements = block.elements
        if (elements.isNotEmpty()) {
            return splitBySingleLettersViaElements(block, elements, text)
        }

        return splitBySingleLettersViaWords(block, text)
    }

    private fun splitBySingleLettersViaElements(
        block: OcrBlock,
        elements: List<OcrElement>,
        originalText: String
    ): List<OcrBlock> {
        if (elements.size < 3) return listOf(block)

        val separatorIndices = ArrayList<Int>()

        for (i in elements.indices) {
            val el = elements[i]
            val t = el.text.trim()
            if (t.length != 1) continue
            if (t[0] !in 'A'..'Z') continue

            val r = el.boundingBox
            if (r.width() <= 0 || r.height() <= 0) continue

            val nextIdx = i + 1
            if (nextIdx >= elements.size) {
                val prevIdx = i - 1
                if (prevIdx >= 0) {
                    val gap = gapX(elements[prevIdx].boundingBox, r)
                    if (gap > GAP_THRESHOLD_PX) {
                        separatorIndices.add(i)
                    }
                }
                continue
            }

            val next = elements[nextIdx]
            val gapToNext = gapX(r, next.boundingBox)

            val nextText = next.text.trim()
            if (nextText.isNotEmpty() && nextText[0].isLowerCase() &&
                gapToNext <= GAP_THRESHOLD_PX
            ) {
                continue
            }

            val prevIdx = i - 1
            if (prevIdx >= 0) {
                val gapFromPrev = gapX(elements[prevIdx].boundingBox, r)
                if (gapFromPrev <= GAP_THRESHOLD_PX) {
                    continue
                }
            }

            if (gapToNext <= GAP_THRESHOLD_PX) {
                continue
            }

            separatorIndices.add(i)
        }

        if (separatorIndices.isEmpty()) return listOf(block)

        val groups = ArrayList<MutableList<OcrElement>>()
        var current = mutableListOf<OcrElement>()
        val separatorSet = separatorIndices.toHashSet()

        for (i in elements.indices) {
            if (i in separatorSet) {
                if (current.isNotEmpty()) {
                    groups.add(current)
                    current = mutableListOf()
                }
            } else {
                current.add(elements[i])
            }
        }
        if (current.isNotEmpty()) {
            groups.add(current)
        }

        if (groups.size <= 1) return listOf(block)

        val result = ArrayList<OcrBlock>(groups.size)
        for (group in groups) {
            if (group.isEmpty()) continue

            val groupText = group.joinToString(" ") { it.text.trim() }
                .trim()
            if (groupText.isEmpty()) continue
            if (!groupText.any { it.isLetter() }) continue

            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            var bottom = Int.MIN_VALUE
            for (el in group) {
                val r = el.boundingBox
                if (r.left < left) left = r.left
                if (r.top < top) top = r.top
                if (r.right > right) right = r.right
                if (r.bottom > bottom) bottom = r.bottom
            }
            if (left >= right || top >= bottom) continue

            val subBox = Rect(left, top, right, bottom)
            result.add(
                OcrBlock(
                    text = groupText,
                    boundingBox = subBox,
                    confidence = block.confidence,
                    initialBoundingBox = Rect(subBox),
                    elements = group.toList()
                )
            )
        }

        return if (result.isEmpty()) listOf(block) else result
    }

    private fun splitBySingleLettersViaWords(
        block: OcrBlock,
        text: String
    ): List<OcrBlock> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return listOf(block)

        val singleLetterIndices = ArrayList<Int>()
        for (i in words.indices) {
            val w = words[i]
            if (w.length == 1 && w[0] in 'A'..'Z') {
                singleLetterIndices.add(i)
            }
        }

        if (singleLetterIndices.isEmpty()) return listOf(block)

        val filteredIndices = ArrayList<Int>()
        for (idx in singleLetterIndices) {
            if (idx == 0 && words.size > 1) {
                val nextWord = words[1]
                if (nextWord.isNotEmpty() && nextWord[0].isLowerCase()) {
                    continue
                }
            }
            filteredIndices.add(idx)
        }

        if (filteredIndices.isEmpty()) return listOf(block)

        val parts = ArrayList<String>()
        var currentPart = StringBuilder()
        val sepSet = filteredIndices.toHashSet()

        for (i in words.indices) {
            val w = words[i]
            if (i in sepSet) {
                if (currentPart.isNotEmpty()) {
                    parts.add(currentPart.toString().trim())
                    currentPart = StringBuilder()
                }
            } else {
                if (currentPart.isNotEmpty()) currentPart.append(' ')
                currentPart.append(w)
            }
        }
        if (currentPart.isNotEmpty()) {
            parts.add(currentPart.toString().trim())
        }

        if (parts.size <= 1) return listOf(block)

        val result = ArrayList<OcrBlock>(parts.size)
        for (part in parts) {
            val partText = part.trim()
            if (partText.isEmpty()) continue
            if (!partText.any { it.isLetter() }) continue

            val matchingElement = block.elements.firstOrNull {
                it.text.trim().equals(partText, ignoreCase = true) ||
                        it.text.trim().contains(partText, ignoreCase = true)
            }

            val subBox = if (matchingElement != null) {
                Rect(matchingElement.boundingBox)
            } else {
                computeSubBbox(
                    originalText = text,
                    subText = partText,
                    sourceBox = block.boundingBox
                )
            }

            result.add(
                OcrBlock(
                    text = partText,
                    boundingBox = subBox,
                    confidence = block.confidence,
                    initialBoundingBox = Rect(subBox),
                    elements = if (matchingElement != null) listOf(matchingElement) else emptyList()
                )
            )
        }

        return if (result.isEmpty()) listOf(block) else result
    }

    private fun gapX(a: Rect, b: Rect): Int {
        if (a.right < b.left) return b.left - a.right
        if (b.right < a.left) return a.left - b.right
        return 0
    }

    private const val GAP_THRESHOLD_PX = 5

    // ============================================================
    // ПЕРЕСЧЁТ BBOX
    // ============================================================

    private fun computeSubBbox(
        originalText: String,
        subText: String,
        sourceBox: Rect
    ): Rect {
        if (originalText.isEmpty() || subText.isEmpty()) return Rect(sourceBox)
        if (sourceBox.width() <= 0 || sourceBox.height() <= 0) return Rect(sourceBox)

        var idx = originalText.indexOf(subText)
        if (idx < 0) {
            idx = originalText.indexOf(subText, ignoreCase = true)
        }
        if (idx < 0) {
            val lastWord = subText.substringAfterLast(' ').trim()
            if (lastWord.isNotEmpty()) {
                idx = originalText.indexOf(lastWord, ignoreCase = true)
                if (idx >= 0) {
                    val totalLen = originalText.length
                    val startFrac = idx.toFloat() / totalLen.toFloat()
                    val endFrac = (idx + lastWord.length).toFloat() / totalLen.toFloat()
                    val newLeft = sourceBox.left + (sourceBox.width() * startFrac).toInt()
                    val newRight = sourceBox.left + (sourceBox.width() * endFrac).toInt()
                    val newW = (newRight - newLeft).coerceAtLeast(1)
                    return Rect(newLeft, sourceBox.top, newLeft + newW, sourceBox.bottom)
                }
            }
        }

        if (idx < 0) {
            return Rect(sourceBox)
        }

        val totalLen = originalText.length
        if (totalLen <= 0) return Rect(sourceBox)

        val startFrac = idx.toFloat() / totalLen.toFloat()
        val endFrac = (idx + subText.length).toFloat() / totalLen.toFloat()

        val newLeft = sourceBox.left + (sourceBox.width() * startFrac).toInt()
        val newRight = sourceBox.left + (sourceBox.width() * endFrac).toInt()
        val newW = (newRight - newLeft).coerceAtLeast(1)

        return Rect(newLeft, sourceBox.top, newLeft + newW, sourceBox.bottom)
    }

    // ============================================================
    // ПРОВЕРКА ОСМЫСЛЕННОСТИ
    // ============================================================

    fun isMeaningfulText(raw: String): Boolean {
        val t = raw.trim()
        val n = t.length
        if (n < 3) return false

        var rareCount = 0
        for (c in t) {
            if (c in RARE_CHARS) {
                rareCount++
                if (rareCount > MAX_RARE_CHARS) return false
            }
        }

        var letters = 0
        var digits = 0
        var nonAlnum = 0
        var latin = 0
        var cyrillic = 0
        var upper = 0
        var hasLetter = false
        var digitBetweenLetters = false
        var prevWasLetter = false
        var prevWasDigit = false

        for (i in 0 until n) {
            val c = t[i]
            val isLetter = when {
                c in 'a'..'z' -> { latin++; true }
                c in 'A'..'Z' -> { latin++; upper++; true }
                c in 'а'..'я' || c in 'А'..'Я' -> {
                    cyrillic++
                    if (c in 'А'..'Я') upper++
                    true
                }
                c == 'ё' || c == 'Ё' -> {
                    cyrillic++
                    if (c == 'Ё') upper++
                    true
                }
                else -> false
            }
            val isDigit = c in '0'..'9'

            if (isLetter) {
                letters++
                hasLetter = true
                if (prevWasDigit && i + 1 < n && t[i + 1].isLetter()) {
                    digitBetweenLetters = true
                }
            } else if (isDigit) {
                digits++
                if (prevWasLetter && i + 1 < n && t[i + 1].isLetter()) {
                    digitBetweenLetters = true
                }
            } else if (c != ' ' && c != '\t') {
                nonAlnum++
            }

            prevWasLetter = isLetter
            prevWasDigit = isDigit
        }

        if (!hasLetter) return false

        if (LOG_REGEX.containsMatchIn(t)) return false
        if (URL_REGEX.containsMatchIn(t)) return false

        if (digitBetweenLetters) return false

        val totalNonSpace = letters + digits + nonAlnum
        if (totalNonSpace > 0 && nonAlnum * 100 / totalNonSpace > 40) return false

        if (latin > 0 && cyrillic > 0) {
            val totalLetters = latin + cyrillic
            if (latin * 100 / totalLetters > 30) return false
        }

        if (n >= 5 && letters > 0 && upper * 100 / letters > 50) return false

        for (i in 1 until n) {
            val c = t[i]
            val p = t[i - 1]
            if ((c in 'А'..'Я' || c == 'Ё') && (p in 'а'..'я' || p == 'ё')) {
                return false
            }
        }

        var wordStart = -1
        var wordIsLatin = false
        var wordHasDigit = false

        for (i in 0 until n + 1) {
            val c = if (i < n) t[i] else ' '
            val isLetter = i < n && c.isLetter()
            val isDigit = i < n && c in '0'..'9'
            val isLatinLetter = i < n && (c in 'a'..'z' || c in 'A'..'Z')

            if (isLetter || isDigit) {
                if (wordStart == -1) {
                    wordStart = i
                    wordIsLatin = true
                    wordHasDigit = false
                }
                if (isDigit) {
                    wordHasDigit = true
                }
                if (isLetter && !isLatinLetter) {
                    wordIsLatin = false
                }
            } else {
                if (wordStart != -1) {
                    val wordLen = i - wordStart

                    if (wordIsLatin && !wordHasDigit && wordLen >= 2) {
                        var hasUpperMid = false
                        var allUpper = true
                        for (j in wordStart until i) {
                            val cj = t[j]
                            if (cj in 'A'..'Z') {
                                if (j > wordStart) hasUpperMid = true
                            } else {
                                allUpper = false
                            }
                        }
                        if (hasUpperMid && !allUpper) return false
                    }

                    if (wordHasDigit && wordLen >= 4) {
                        return false
                    }

                    if (wordIsLatin && !wordHasDigit &&
                        wordLen >= MAX_CONSECUTIVE_CONSONANTS
                    ) {
                        val word = t.substring(wordStart, i)
                        if (hasTooManyConsonants(word)) {
                            return false
                        }
                    }

                    if (wordIsLatin && !wordHasDigit && wordLen >= 5) {
                        val word = t.substring(wordStart, i).lowercase()
                        var hasSuffix = false
                        for (suf in ENG_SUFFIXES) {
                            if (word.endsWith(suf) && word.length > suf.length) {
                                hasSuffix = true
                                break
                            }
                        }
                        if (!hasSuffix) {
                            var bigrams = 0
                            var j = wordStart
                            while (j < i - 1) {
                                val c1 = t[j].lowercaseChar()
                                val c2 = t[j + 1].lowercaseChar()
                                if (c1 in 'a'..'z' && c2 in 'a'..'z') {
                                    val bi = "$c1$c2"
                                    if (bi in ENG_BIGRAMS) {
                                        bigrams++
                                    }
                                }
                                j++
                            }
                            if (bigrams < 3) return false
                        }
                    }
                    wordStart = -1
                    wordIsLatin = false
                    wordHasDigit = false
                }
            }
        }

        val checkLen = if (n < 20) n else 20
        var hasVowel = false
        for (i in 0 until checkLen) {
            if (VOWELS.indexOf(t[i]) >= 0) {
                hasVowel = true
                break
            }
        }
        if (!hasVowel) return false

        return true
    }

    private fun hasTooManyConsonants(word: String): Boolean {
        var consecutive = 0
        for (c in word) {
            if (c.isLetter() && c.lowercaseChar() !in VOWELS) {
                consecutive++
                if (consecutive >= MAX_CONSECUTIVE_CONSONANTS) {
                    return true
                }
            } else {
                consecutive = 0
            }
        }
        return false
    }
}
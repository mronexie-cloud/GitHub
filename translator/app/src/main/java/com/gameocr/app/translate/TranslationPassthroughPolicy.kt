package com.gameocr.app.translate

internal fun shouldPassthroughNumericTranslation(source: String): Boolean {
    if (source.isBlank()) return false
    var hasNumber = false
    var offset = 0
    while (offset < source.length) {
        val codePoint = Character.codePointAt(source, offset)
        val type = Character.getType(codePoint)
        when (type) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.LETTER_NUMBER.toInt(),
            Character.OTHER_NUMBER.toInt() -> hasNumber = true

            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.FORMAT.toInt(),
            Character.SPACE_SEPARATOR.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt() -> Unit

            else -> if (!Character.isWhitespace(codePoint)) return false
        }
        offset += Character.charCount(codePoint)
    }
    return hasNumber
}

internal data class TranslationPassthroughBatchPlan(
    val sourceCount: Int,
    val translatableIndexes: List<Int>,
    val translatableSources: List<String>,
    val passthroughUpdates: List<BatchTranslationUpdate>,
) {
    fun originalIndexFor(translatableIndex: Int): Int? = translatableIndexes.getOrNull(translatableIndex)

    fun merge(translated: List<String?>): List<String?> {
        val merged = MutableList<String?>(sourceCount) { null }
        passthroughUpdates.forEach { update -> merged[update.index] = update.text }
        translatableIndexes.forEachIndexed { translatedIndex, sourceIndex ->
            merged[sourceIndex] = translated.getOrNull(translatedIndex)
        }
        return merged
    }
}

internal fun planNumericTranslationPassthrough(
    sources: List<String>,
): TranslationPassthroughBatchPlan {
    val translatableIndexes = mutableListOf<Int>()
    val translatableSources = mutableListOf<String>()
    val passthroughUpdates = mutableListOf<BatchTranslationUpdate>()
    sources.forEachIndexed { index, source ->
        if (shouldPassthroughNumericTranslation(source)) {
            passthroughUpdates += BatchTranslationUpdate(
                index = index,
                text = source,
                elapsedMs = 0L,
            )
        } else {
            translatableIndexes += index
            translatableSources += source
        }
    }
    return TranslationPassthroughBatchPlan(
        sourceCount = sources.size,
        translatableIndexes = translatableIndexes,
        translatableSources = translatableSources,
        passthroughUpdates = passthroughUpdates,
    )
}

/**
 * zlc fork: text that is already written in the target language is shown as-is instead of being
 * "translated" into itself, which only degrades it (seen with Chinese screens and a Chinese target).
 *
 * Only script-level evidence is used, so this covers Chinese, Japanese and Korean targets. Latin
 * targets are left alone because English and, say, Malay cannot be told apart by script.
 */
internal fun shouldPassthroughTargetLanguageText(source: String, targetLang: String): Boolean {
    val target = targetLang.trim().lowercase()
    val wantsChinese = target.startsWith("zh")
    val wantsJapanese = target.startsWith("ja")
    val wantsKorean = target.startsWith("ko")
    if (!wantsChinese && !wantsJapanese && !wantsKorean) return false

    var han = 0
    var kana = 0
    var hangul = 0
    var otherLetters = 0
    var offset = 0
    while (offset < source.length) {
        val codePoint = Character.codePointAt(source, offset)
        offset += Character.charCount(codePoint)
        if (!Character.isLetter(codePoint)) continue
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN -> han += 1
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana += 1
            Character.UnicodeScript.HANGUL -> hangul += 1
            else -> otherLetters += 1
        }
    }
    val letters = han + kana + hangul + otherLetters
    if (letters == 0) return false
    return when {
        wantsChinese -> kana == 0 && hangul == 0 && han >= 2 && han * 2 >= letters
        wantsJapanese -> kana > 0 && hangul == 0 && (han + kana) * 2 >= letters
        else -> hangul >= 2 && hangul * 2 >= letters
    }
}

internal fun shouldPassthroughTranslation(source: String, targetLang: String): Boolean =
    shouldPassthroughNumericTranslation(source) ||
        shouldPassthroughTargetLanguageText(source, targetLang)

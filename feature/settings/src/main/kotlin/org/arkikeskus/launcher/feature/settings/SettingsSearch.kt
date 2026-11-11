package org.arkikeskus.launcher.feature.settings

import java.text.Normalizer
import java.util.Locale

/** A row of Settings as the search reads it: the texts of its [SettingEntry] in the current language. */
internal class SearchableSetting(
    val id: String,
    val category: SettingsCategory?,
    val title: String,
    val description: String?,
    val keywords: List<String>,
)

/** A row the search found; [titleMatch] is where the first word of the query sits in the title, if it does. */
internal class SearchHit(val setting: SearchableSetting, val titleMatch: IntRange?)

/**
 * [text] as the search compares it: lower case, without accents (ä = a, é = e, ё = е), and with the letters that
 * do not come apart into a base letter and an accent brought to their plain form (the Turkish dotless ı, the Polish ł).
 */
internal fun foldForSearch(text: String): String = buildString { text.forEach { append(foldChar(it)) } }

private val COMBINING_MARKS = Regex("\\p{Mn}+")

private fun foldChar(c: Char): String {
    val plain = when (c) {
        'ı', 'İ' -> "i"
        'ł', 'Ł' -> "l"
        else -> COMBINING_MARKS.replace(Normalizer.normalize(c.toString(), Normalizer.Form.NFD), "")
    }
    return plain.lowercase(Locale.ROOT)
}

/** Where [word] (already folded) first sits in [text] as it is written, or null. */
private fun matchIn(text: String, word: String): IntRange? {
    // Folded letter by letter, remembering which written letter each folded one came from: an accent that stands
    // alone folds to nothing, so the two strings need not be the same length.
    val folded = StringBuilder()
    val origin = ArrayList<Int>(text.length)
    text.forEachIndexed { index, c ->
        val f = foldChar(c)
        folded.append(f)
        repeat(f.length) { origin += index }
    }
    val at = folded.indexOf(word)
    if (at < 0) return null
    return origin[at]..origin[at + word.length - 1]
}

/**
 * The rows of [settings] that [query] finds: every word of the query, in any order, is somewhere in the row's title,
 * description or extra words. Rows whose title holds the first word come first; otherwise the given order is kept.
 * Nothing typed finds nothing.
 */
internal fun searchSettings(query: String, settings: List<SearchableSetting>): List<SearchHit> {
    val words = foldForSearch(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val hits = settings.mapNotNull { setting ->
        val texts = buildList {
            add(foldForSearch(setting.title))
            setting.description?.let { add(foldForSearch(it)) }
            setting.keywords.forEach { add(foldForSearch(it)) }
        }
        if (words.all { word -> texts.any { word in it } }) SearchHit(setting, matchIn(setting.title, words.first())) else null
    }
    // A stable sort: title matches first, each group in the order it came.
    return hits.sortedBy { if (it.titleMatch != null) 0 else 1 }
}

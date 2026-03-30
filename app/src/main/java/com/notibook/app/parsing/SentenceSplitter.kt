package com.notibook.app.parsing

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Splits a block of plain text into display segments using Android's ICU-backed
 * BreakIterator, then merges any false sentence boundaries caused by abbreviations,
 * numbers, or Roman numerals before chunking long segments into equal-sized pieces.
 *
 * Design principle: always err on the side of merging rather than splitting.
 * Accidentally merging two sentences produces one slightly longer segment that the
 * equal-chunk algorithm handles gracefully. Accidentally splitting mid-sentence
 * interrupts the reading flow and is far more disruptive.
 */
object SentenceSplitter {

    private const val MAX_CHUNK_CHARS = 200

    /**
     * Known abbreviations whose trailing period must NOT be treated as a sentence end.
     * Stored without the final period; matched against the last whitespace-delimited
     * token of a raw BreakIterator segment (lowercased).
     */
    private val ABBREVIATIONS = setOf(
        // Titles & honorifics
        "mr", "mrs", "ms", "miss", "dr", "prof", "rev", "hon", "sen", "rep",
        "gen", "capt", "lt", "sgt", "cpl", "pvt", "insp", "pres", "gov", "supt", "sr", "jr",
        // Academic degrees
        "ph.d", "m.d", "b.a", "m.a", "b.sc", "m.sc", "ll.d", "ed.d",
        // Latin & common writing
        "e.g", "i.e", "etc", "et al", "vs", "cf", "viz", "al", "ca",
        // Publishing / citations
        "p", "pp", "vol", "vols", "ed", "eds", "no", "fig", "ch", "sec",
        // Time
        "a.m", "p.m", "mon", "tue", "wed", "thu", "fri", "sat", "sun",
        "jan", "feb", "mar", "apr", "aug", "sept", "oct", "nov", "dec",
        // Geography / addresses
        "st", "ave", "blvd", "rd", "ln", "ct", "pl", "mt", "ft", "u.s", "u.k", "u.n",
        // Business / legal
        "inc", "ltd", "corp", "co", "llc", "bros", "v", "art", "cl",
        // Era / historical
        "b.c", "a.d", "b.c.e", "c.e",
        // Units of measurement
        "km", "cm", "mm", "m", "kg", "g", "mg", "l", "ml",
        "oz", "lb", "lbs", "ft", "yd", "mi", "mph", "approx", "max", "min", "avg",
        // Compass directions
        "n", "s", "e", "w", "ne", "nw", "se", "sw",
        // Professional / organisational
        "dept", "div", "est", "mgr", "dir", "asst", "assoc", "admin",
        "natl", "intl", "govt"
    )

    /**
     * Matches valid Roman numerals (I–MMMCMXCIX), case-insensitive.
     * Used to detect e.g. "III." or "XII." as non-sentence-ending tokens.
     * A proper regex avoids false positives on ordinary words that happen to
     * contain only the letters I, V, X, L, C, D, M (e.g. "mild", "mixed").
     */
    private val ROMAN_NUMERAL_RE = Regex(
        "^m{0,4}(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$",
        RegexOption.IGNORE_CASE
    )

    // ── Public API ────────────────────────────────────────────────────────────

    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        // 1. Raw sentence boundaries from ICU BreakIterator
        val iter = BreakIterator.getSentenceInstance(Locale.getDefault())
        iter.setText(text)
        val raw = mutableListOf<String>()
        var start = iter.first()
        var end = iter.next()
        while (end != BreakIterator.DONE) {
            val s = text.substring(start, end).trim()
            if (s.isNotEmpty()) raw.add(s)
            start = end
            end = iter.next()
        }

        // 2. Merge segments where the boundary is a false positive.
        //    Covers abbreviations (Mr., Dr., etc.), any number (1., 03., 1995.),
        //    and Roman numerals (I., III., XII.).
        val merged = mutableListOf<String>()
        var i = 0
        while (i < raw.size) {
            var current = raw[i]
            while (i + 1 < raw.size && isFalseBoundary(current)) {
                i++
                current = "$current ${raw[i]}"
            }
            merged.add(current)
            i++
        }

        // 3. Chunk any remaining long segments into equal-sized pieces
        return merged.flatMap { chunkIfNeeded(it) }
    }

    // ── Boundary detection ────────────────────────────────────────────────────

    /**
     * Returns true if the trailing period of [sentence] is NOT a genuine sentence
     * terminator — i.e. the period belongs to an abbreviation, a number, or a
     * Roman numeral.
     */
    private fun isFalseBoundary(sentence: String): Boolean {
        val trimmed = sentence.trimEnd()
        if (!trimmed.endsWith(".")) return false
        // Last whitespace-delimited token before the period
        val lastWord = trimmed.dropLast(1).trimEnd().substringAfterLast(' ')
        val lower = lastWord.lowercase()
        return lower in ABBREVIATIONS                          // known abbreviation
            || lastWord.all { it.isDigit() }                  // any integer: 1, 03, 1995
            || ROMAN_NUMERAL_RE.matches(lastWord)             // Roman numeral: III, XII, MCMXCIX
    }

    // ── Chunking ──────────────────────────────────────────────────────────────

    /**
     * If [text] fits within MAX_CHUNK_CHARS, returns it as-is.
     * Otherwise divides it into N ≈ equal chunks split at word boundaries.
     * All but the last chunk get a trailing " …" to signal continuation.
     */
    private fun chunkIfNeeded(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)

        val n = ceil(text.length.toDouble() / MAX_CHUNK_CHARS).toInt()
        val chunkSize = text.length.toDouble() / n

        val rawChunks = mutableListOf<String>()
        var segStart = 0

        for (i in 1 until n) {
            val idealEnd = (i * chunkSize).roundToInt().coerceIn(segStart + 1, text.length)

            var splitAt = idealEnd
            while (splitAt > segStart + 1 && text[splitAt - 1] != ' ') splitAt--

            if (splitAt <= segStart) {
                splitAt = idealEnd
                while (splitAt < text.length && text[splitAt] != ' ') splitAt++
            }

            if (splitAt >= text.length) break

            rawChunks.add(text.substring(segStart, splitAt).trim())
            segStart = splitAt
            while (segStart < text.length && text[segStart] == ' ') segStart++
        }

        val remaining = text.substring(segStart).trim()
        if (remaining.isNotBlank()) rawChunks.add(remaining)

        return rawChunks.mapIndexed { i, chunk ->
            if (i < rawChunks.size - 1) "$chunk …" else chunk
        }
    }
}

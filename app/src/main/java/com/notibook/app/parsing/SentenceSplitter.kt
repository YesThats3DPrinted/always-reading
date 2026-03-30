package com.notibook.app.parsing

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Splits a block of plain text into individual sentences using Android's ICU-backed
 * BreakIterator, then merges any segment that ends with a known abbreviation into the
 * next segment (so "Mr. Smith" doesn't become two sentences). Any segment that still
 * exceeds MAX_CHUNK_CHARS is divided into N equal-length chunks split at word boundaries.
 */
object SentenceSplitter {

    private const val MAX_CHUNK_CHARS = 250

    /**
     * Abbreviations whose trailing period should NOT be treated as a sentence boundary.
     * Stored without the final period so we can match against `sentence.dropLast(1).lastWord()`.
     */
    private val ABBREVIATIONS = setOf(
        // Titles & honorifics
        "mr", "mrs", "ms", "miss", "dr", "prof", "rev", "hon", "sen", "rep",
        "gen", "capt", "lt", "sgt", "cpl", "pvt", "insp", "pres", "gov", "supt", "sr", "jr",
        // Academic degrees (multi-part stored as last segment, e.g. "ph.d" → lastWord = "ph.d")
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
        "inc", "ltd", "corp", "co", "llc", "bros"
    )

    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        // 1. Get raw sentence boundaries from ICU BreakIterator
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

        // 2. Merge any sentence that ends with a known abbreviation into the next one.
        //    e.g. BreakIterator may split "He spoke to Mr." | "Smith yesterday."
        //    → merged into "He spoke to Mr. Smith yesterday."
        val merged = mutableListOf<String>()
        var i = 0
        while (i < raw.size) {
            var current = raw[i]
            while (i + 1 < raw.size && endsWithAbbreviation(current)) {
                i++
                current = "$current ${raw[i]}"
            }
            merged.add(current)
            i++
        }

        // 3. Chunk any remaining long segments into equal-sized pieces
        return merged.flatMap { chunkIfNeeded(it) }
    }

    /**
     * Returns true if [sentence] ends with a period that belongs to a known abbreviation
     * rather than a genuine sentence terminator.
     */
    private fun endsWithAbbreviation(sentence: String): Boolean {
        val trimmed = sentence.trimEnd()
        if (!trimmed.endsWith(".")) return false
        // Drop the final period, take the last whitespace-delimited token, lowercase it
        val lastWord = trimmed.dropLast(1).trimEnd().substringAfterLast(' ').lowercase()
        return lastWord in ABBREVIATIONS
    }

    /**
     * If [text] fits within MAX_CHUNK_CHARS, returns it as-is.
     * Otherwise divides it into N ≈ equal chunks, each split at a word boundary.
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

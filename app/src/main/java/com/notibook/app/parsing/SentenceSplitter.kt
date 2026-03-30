package com.notibook.app.parsing

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Splits a block of plain text into individual sentences using Android's ICU-backed
 * BreakIterator, which correctly handles abbreviations (Mr., Dr., U.S.), decimal
 * numbers (3.14), ellipses, and dialogue without any custom rules.
 *
 * Any sentence that exceeds MAX_CHUNK_CHARS is divided into N equal-length chunks
 * where N = ceil(length / MAX_CHUNK_CHARS). Equal division avoids a tiny final page
 * (e.g. 900 chars → 4 pages of ~225 each, not 3×250 + 1×150). Splits are adjusted
 * to the nearest word boundary so no word is ever broken across two segments.
 */
object SentenceSplitter {

    private const val MAX_CHUNK_CHARS = 250

    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val iter = BreakIterator.getSentenceInstance(Locale.getDefault())
        iter.setText(text)

        val sentences = mutableListOf<String>()
        var start = iter.first()
        var end = iter.next()

        while (end != BreakIterator.DONE) {
            val sentence = text.substring(start, end).trim()
            if (sentence.isNotEmpty()) sentences.addAll(chunkIfNeeded(sentence))
            start = end
            end = iter.next()
        }

        return sentences
    }

    /**
     * If [text] fits within MAX_CHUNK_CHARS, returns it as-is.
     * Otherwise divides it into N ≈ equal chunks, each split at a word boundary.
     * All but the last chunk get a trailing " …" to signal continuation.
     */
    private fun chunkIfNeeded(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)

        // N equal chunks, e.g. 900 chars / 250 = ceil(3.6) = 4 chunks of ~225 each
        val n = ceil(text.length.toDouble() / MAX_CHUNK_CHARS).toInt()
        val chunkSize = text.length.toDouble() / n

        val rawChunks = mutableListOf<String>()
        var segStart = 0

        for (i in 1 until n) {
            val idealEnd = (i * chunkSize).roundToInt().coerceIn(segStart + 1, text.length)

            // Walk back from idealEnd to find the last space (never split mid-word)
            var splitAt = idealEnd
            while (splitAt > segStart + 1 && text[splitAt - 1] != ' ') splitAt--

            if (splitAt <= segStart) {
                // No space found walking back — walk forward to the next space
                splitAt = idealEnd
                while (splitAt < text.length && text[splitAt] != ' ') splitAt++
            }

            if (splitAt >= text.length) break  // remaining text becomes the last chunk

            rawChunks.add(text.substring(segStart, splitAt).trim())

            // Advance past the space(s) at the split point
            segStart = splitAt
            while (segStart < text.length && text[segStart] == ' ') segStart++
        }

        val remaining = text.substring(segStart).trim()
        if (remaining.isNotBlank()) rawChunks.add(remaining)

        // Add " …" to all but the last chunk to signal continuation
        return rawChunks.mapIndexed { i, chunk ->
            if (i < rawChunks.size - 1) "$chunk …" else chunk
        }
    }
}

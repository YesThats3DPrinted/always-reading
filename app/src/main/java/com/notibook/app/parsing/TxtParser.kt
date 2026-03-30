package com.notibook.app.parsing

import java.io.File

object TxtParser {

    /**
     * Reads the entire .txt file and splits it into segments.
     * Paragraph boundaries (two or more consecutive newlines) are respected —
     * sentences from different paragraphs are never merged into one segment.
     * Single newlines within a paragraph are treated as spaces.
     * All segments are returned with an empty chapter string.
     */
    fun parse(file: File): List<Pair<String, String>> {
        val text = file.readText(Charsets.UTF_8)
        return text
            .split(Regex("\\n{2,}"))          // split on blank lines = paragraph breaks
            .filter { it.isNotBlank() }
            .flatMap { paragraph ->
                // Normalise in-paragraph line breaks to spaces before sentence splitting
                SentenceSplitter.split(paragraph.replace('\n', ' '))
            }
            .map { segment -> segment to "" }
    }
}

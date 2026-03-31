package com.notibook.app.parsing

import java.io.File

object TxtParser {

    /**
     * Reads the entire .txt file and splits it into segments.
     * Paragraph boundaries (two or more consecutive newlines) are respected —
     * sentences from different paragraphs are never merged into one segment.
     * Single newlines within a paragraph are treated as spaces.
     *
     * Returns triples of (text, chapter, blockIndex) where blockIndex is the
     * 0-based paragraph index. All sentences from the same paragraph share the
     * same blockIndex, matching how EpubParser assigns blockIndex per block element.
     */
    fun parse(file: File): List<Triple<String, String, Int>> {
        val text = file.readText(Charsets.UTF_8)
        val result = mutableListOf<Triple<String, String, Int>>()
        var blockIndex = 0
        text.split(Regex("\\n{2,}"))
            .filter { it.isNotBlank() }
            .forEach { paragraph ->
                SentenceSplitter.split(paragraph.replace('\n', ' ')).forEach { segment ->
                    result.add(Triple(segment, "", blockIndex))
                }
                blockIndex++
            }
        return result
    }
}

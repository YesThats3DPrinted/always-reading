package com.notibook.app.epub

import com.notibook.app.data.db.SentenceEntity
import com.notibook.app.parsing.BLOCK_SELECTOR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File

/**
 * Merges all spine chapters into a single HTML string for the in-app reader.
 *
 * Each chapter is wrapped in <div id="chapter-N"> for chapter-jump support.
 * All relative resource URLs are converted to absolute file:// paths.
 *
 * Critically: block elements in each chapter are annotated with data-si="sentenceIndex"
 * using [sentencesBySpineIndex]. This uses the same [BLOCK_SELECTOR] as EpubParser, so
 * the Nth element selected here corresponds exactly to the Nth blockIndex in the DB.
 * At reader-close time, JS can call elementFromPoint() → read data-si → exact sentence.
 */
object EpubCombiner {

    suspend fun buildCombinedHtml(
        spineItems: List<SpineItem>,
        sentencesBySpineIndex: Map<Int, List<SentenceEntity>> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        buildString {
            append("<html><head><meta charset='UTF-8'></head><body>")
            for ((index, item) in spineItems.withIndex()) {
                append("\n<div id=\"chapter-$index\">")
                val file = File(item.absolutePath)
                if (file.exists()) {
                    try {
                        val doc     = Jsoup.parse(file, "UTF-8")
                        val baseDir = file.parentFile?.canonicalPath ?: ""

                        // Convert relative src attrs to absolute file://
                        doc.select("[src]").forEach { el ->
                            val src = el.attr("src")
                            if (src.isNotEmpty()
                                && !src.startsWith("http")
                                && !src.startsWith("file://")
                                && !src.startsWith("data:")
                            ) {
                                el.attr("src", "file://${File(baseDir, src).canonicalPath}")
                            }
                        }

                        // Convert relative href attrs on <a> to absolute file://
                        doc.select("a[href]").forEach { el ->
                            val href = el.attr("href")
                            if (href.isNotEmpty()
                                && !href.startsWith("http")
                                && !href.startsWith("file://")
                                && !href.startsWith("#")
                                && !href.startsWith("mailto:")
                            ) {
                                val fragment = if ('#' in href) "#${href.substringAfter('#')}" else ""
                                val pathPart = if ('#' in href) href.substringBefore('#') else href
                                val abs = File(baseDir, pathPart).canonicalPath
                                el.attr("href", "file://$abs$fragment")
                            }
                        }

                        // ── Annotate block elements with data-si ──────────────────────
                        // Build a map from blockIndex → first sentenceIndex for this spine item.
                        // Uses the same BLOCK_SELECTOR as EpubParser, so Nth element here
                        // has the same blockIndex as EpubParser assigned.
                        val sentences = sentencesBySpineIndex[index]
                        if (!sentences.isNullOrEmpty()) {
                            val blockToSentenceIndex: Map<Int, Int> = sentences
                                .groupBy { it.blockIndex }
                                .mapValues { (_, s) ->
                                    s.minByOrNull { it.sentenceIndex }!!.sentenceIndex
                                }

                            val body = doc.body() ?: doc
                            val blocks = body.select(BLOCK_SELECTOR)
                            blocks.forEachIndexed { blockIdx, el ->
                                val si = blockToSentenceIndex[blockIdx]
                                if (si != null) el.attr("data-si", si.toString())
                            }
                        }

                        append(doc.body().html())
                    } catch (_: Exception) {
                        append("<p style=\"color:#999\">[Chapter unavailable]</p>")
                    }
                } else {
                    append("<p style=\"color:#999\">[Chapter file not found]</p>")
                }
                append("\n</div>")
            }
            append("\n</body></html>")
        }
    }
}

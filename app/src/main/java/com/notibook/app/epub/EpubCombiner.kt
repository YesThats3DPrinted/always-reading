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

    /**
     * Initial CSS embedded directly in the HTML so the page has correct styling
     * the instant it loads — prevents white flash and blank-screen glitch.
     * The full CSS (with exact font size) is re-injected by JS after onPageFinished.
     */
    private fun initialCss(fontSize: Int) = """
        body * { color: #E0E0E0 !important; max-width: 100%; box-sizing: border-box;
                 overflow-wrap: break-word; word-break: break-word; }
        a, a * { color: #7CB9E8 !important; }
        html { height: 100%; overflow: hidden; clip-path: inset(0); }
        body { background: #1A1A1A !important; color: #E0E0E0 !important;
               margin: 0; padding: 0; overflow: visible;
               column-gap: 0; column-fill: auto; text-align: left;
               font-size: ${fontSize}px; font-family: serif; line-height: 1.6; }
        img { max-width: 100vw; width: auto; height: auto; max-height: 100vh;
              break-inside: avoid; page-break-inside: avoid; }
        figure { margin: 0; padding: 0; }
        table { width: 100%; }
        pre, code { white-space: pre-wrap; }
    """.trimIndent().replace("\n", " ")

    suspend fun buildCombinedHtml(
        spineItems: List<SpineItem>,
        sentencesBySpineIndex: Map<Int, List<SentenceEntity>> = emptyMap(),
        fontSize: Int = 16
    ): String = withContext(Dispatchers.IO) {
        buildString {
            append("<html><head>")
            append("<meta charset='UTF-8'>")
            append("<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>")
            append("<style id='__nb_css'>")
            append(initialCss(fontSize))
            append("</style></head><body>")
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

                        // ── Annotate block elements with sentence position data ────────
                        // Each block element gets:
                        //   data-si="N"              — sentenceIndex of first sentence (fallback)
                        //   data-sentences="N:L,..."  — all sentences as "index:textLength" pairs
                        //
                        // data-sentences allows JS to find the exact sentence at any character
                        // offset within the block (via caretRangeFromPoint), giving sentence-level
                        // precision for both notification position and orientation restore.
                        // DIVIDER sentences are excluded (they are skipped in notifications).
                        val sentences = sentencesBySpineIndex[index]
                        if (!sentences.isNullOrEmpty()) {
                            val blockToSentences: Map<Int, List<SentenceEntity>> = sentences
                                .filter { it.type != "DIVIDER" }
                                .groupBy { it.blockIndex }
                                .mapValues { (_, s) -> s.sortedBy { it.sentenceIndex } }

                            val body = doc.body() ?: doc
                            val blocks = body.select(BLOCK_SELECTOR)
                            blocks.forEachIndexed { blockIdx, el ->
                                val blockSentences = blockToSentences[blockIdx]
                                if (!blockSentences.isNullOrEmpty()) {
                                    el.attr("data-si", blockSentences.first().sentenceIndex.toString())
                                    el.attr("data-sentences", blockSentences.joinToString(",") {
                                        "${it.sentenceIndex}:${it.text.trim().length}"
                                    })
                                }
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

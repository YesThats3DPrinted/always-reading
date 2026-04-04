package com.notibook.app.epub

import com.notibook.app.data.db.SentenceEntity
import com.notibook.app.parsing.BLOCK_SELECTOR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.File

/**
 * Merges all spine chapters into a single HTML string for the in-app reader.
 *
 * Each chapter is wrapped in <div id="chapter-N"> for chapter-jump support.
 * All relative resource URLs are converted to absolute file:// paths.
 *
 * Sentence position is annotated with empty <span data-si="N"> markers injected
 * into the text at the exact character offset where each sentence begins.
 * This allows JS __getSentenceAtTop() to find the precise sentence at any
 * screen position, not just the first sentence of the enclosing block.
 */
object EpubCombiner {

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
        [id^="chapter-"]:not(#chapter-0) { break-before: column !important; page-break-before: always !important; }
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

                        // ── Inject per-sentence <span data-si="N"> markers ───────────
                        // Group sentences by blockIndex so multi-sentence blocks get
                        // an individual marker at the start of each sentence.
                        val sentences = sentencesBySpineIndex[index]
                        if (!sentences.isNullOrEmpty()) {
                            val sentencesByBlock: Map<Int, List<SentenceEntity>> = sentences
                                .filter { it.type != "DIVIDER" }
                                .groupBy { it.blockIndex }
                                .mapValues { (_, s) -> s.sortedBy { it.sentenceIndex } }

                            val body   = doc.body() ?: doc
                            val blocks = body.select(BLOCK_SELECTOR)
                            blocks.forEachIndexed { blockIdx, el ->
                                val blockSentences = sentencesByBlock[blockIdx]
                                if (!blockSentences.isNullOrEmpty()) {
                                    injectSentenceMarkers(el, blockSentences)
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
            append("\n<span id=\"__nb_end\"></span>")
            append("\n</body></html>")
        }
    }

    /**
     * Inserts an empty <span data-si="N"> marker just before the start of each
     * sentence in [sentences] within [block]. Markers are inserted into the DOM's
     * text nodes directly so inline formatting (<em>, <strong>, <a>, etc.) is
     * fully preserved.
     *
     * Processed in reverse sentence order so that earlier text-node char offsets
     * remain valid across successive TextNode.splitText() calls.
     */
    private fun injectSentenceMarkers(block: Element, sentences: List<SentenceEntity>) {
        if (sentences.isEmpty()) return

        // Collect all text nodes in document order with their cumulative char offsets.
        val textNodes = mutableListOf<Pair<TextNode, Int>>() // (node, cumStart)
        collectTextNodes(block, textNodes, intArrayOf(0))
        if (textNodes.isEmpty()) return

        val fullText = textNodes.joinToString("") { (n, _) -> n.wholeText }

        // Find where each sentence starts in the concatenated text.
        data class Marker(val charOff: Int, val si: Int)
        val markers = mutableListOf<Marker>()
        var searchFrom = 0
        for (s in sentences) {
            val probe = s.text.trimStart().take(30)
            if (probe.isEmpty()) continue
            val idx = fullText.indexOf(probe, searchFrom)
            if (idx >= 0) {
                markers.add(Marker(idx, s.sentenceIndex))
                searchFrom = idx + 1
            }
        }
        if (markers.isEmpty()) return

        // Insert spans in REVERSE order so earlier text-node offsets stay valid.
        for (m in markers.reversed()) {
            val (node, nodeStart) = textNodes.find { (n, s) ->
                m.charOff >= s && m.charOff < s + n.wholeText.length
            } ?: continue
            val localOff = m.charOff - nodeStart
            val spanHtml = "<span data-si=\"${m.si}\"></span>"
            if (localOff <= 0) {
                node.before(spanHtml)
            } else {
                val tail = node.splitText(localOff)
                tail.before(spanHtml)
            }
        }
    }

    /** Recursively collects all TextNode descendants of [parent] in document order. */
    private fun collectTextNodes(
        parent: Node,
        accumulator: MutableList<Pair<TextNode, Int>>,
        pos: IntArray
    ) {
        for (child in parent.childNodes()) {
            when (child) {
                is TextNode -> {
                    accumulator.add(Pair(child, pos[0]))
                    pos[0] += child.wholeText.length
                }
                is Element  -> collectTextNodes(child, accumulator, pos)
            }
        }
    }
}

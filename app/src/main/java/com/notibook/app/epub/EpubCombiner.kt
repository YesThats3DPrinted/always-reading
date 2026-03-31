package com.notibook.app.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File

/**
 * Merges all spine chapters into a single HTML string for the in-app reader.
 * Each chapter is wrapped in <div id="chapter-N"> for scroll-to-chapter jumping.
 * All relative resource URLs are converted to absolute file:// paths so the
 * combined document can be loaded without a per-chapter base URL.
 */
object EpubCombiner {

    suspend fun buildCombinedHtml(spineItems: List<SpineItem>): String =
        withContext(Dispatchers.IO) {
            buildString {
                append("<html><head><meta charset='UTF-8'></head><body>")
                for ((index, item) in spineItems.withIndex()) {
                    append("\n<div id=\"chapter-$index\">")
                    val file = File(item.absolutePath)
                    if (file.exists()) {
                        try {
                            val doc     = Jsoup.parse(file, "UTF-8")
                            val baseDir = file.parentFile?.canonicalPath ?: ""

                            // Convert relative src attrs (images, audio, video) to absolute file://
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

                            // Convert relative href attrs on <a> tags to absolute file://
                            // so that el.href in JS gives the correct canonical path.
                            // loadDataWithBaseURL's base URL is the EPUB root, not the
                            // chapter's own directory, so relative links would resolve wrong.
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

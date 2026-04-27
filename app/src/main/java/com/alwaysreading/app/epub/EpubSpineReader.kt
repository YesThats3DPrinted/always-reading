package com.alwaysreading.app.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File

/**
 * Reads the spine of an already-extracted EPUB directory and returns an ordered
 * list of [SpineItem]s for the in-app reader.
 *
 * This is the runtime companion to EpubParser: EpubParser is used once at import
 * time to build sentences; EpubSpineReader is used each time the reader opens to
 * build the chapter list for WebView navigation.
 */
object EpubSpineReader {

    /**
     * Reads spine items from [extractedDir] (the root of the extracted EPUB).
     * Returns an ordered list of [SpineItem]s.
     * Runs on [Dispatchers.IO].
     */
    suspend fun readSpine(extractedDir: File): List<SpineItem> = withContext(Dispatchers.IO) {
        // 1. Parse META-INF/container.xml → OPF path
        val containerFile = File(extractedDir, "META-INF/container.xml")
        val opfPath = parseContainerXml(containerFile.readText())

        // 2. Parse OPF → spine hrefs
        val opfFile = File(extractedDir, opfPath)
        val opfDir = opfFile.parentFile ?: extractedDir
        val spineHrefs = parseOpfSpine(opfFile.readText())

        // 3. Build SpineItem list
        spineHrefs.mapIndexed { index, href ->
            val htmlFile = resolveHref(opfDir, href)
            val chapterTitle = if (htmlFile.exists()) extractChapterTitle(htmlFile) else ""
            SpineItem(
                index = index,
                href = href,
                // Must use canonicalPath so it matches paths built in EpubCombiner,
                // which also uses canonicalPath. On Android /data/user/0 is a symlink
                // to /data/data — absolutePath vs canonicalPath differ, breaking link lookup.
                absolutePath = try { htmlFile.canonicalPath } catch (_: Exception) { htmlFile.absolutePath },
                chapterTitle = chapterTitle
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun parseContainerXml(xml: String): String {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.selectFirst("rootfile")?.attr("full-path")
            ?: throw IllegalArgumentException("No <rootfile> in container.xml")
    }

    private fun parseOpfSpine(xml: String): List<String> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())

        // Build manifest map: id → href
        val manifest = mutableMapOf<String, String>()
        doc.select("item").forEach { item ->
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = href
        }

        // Return hrefs in spine order
        return doc.select("itemref[idref]").mapNotNull { manifest[it.attr("idref")] }
    }

    private fun resolveHref(opfDir: File, href: String): File {
        // href may be relative (e.g. "Text/ch01.xhtml") or already absolute
        return if (href.startsWith("/")) File(href) else File(opfDir, href)
    }

    private fun extractChapterTitle(htmlFile: File): String {
        return try {
            val doc = Jsoup.parse(htmlFile, "UTF-8")
            doc.selectFirst("h1, h2, h3")?.text()
                ?: doc.selectFirst("title")?.text()
                ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}

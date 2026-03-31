package com.notibook.app.parsing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipInputStream

data class EpubMetadata(
    val title: String,
    val author: String,
    val coverPath: String?
)

data class EpubParseResult(
    val metadata: EpubMetadata,
    /** Each triple is (sentenceText, chapterTitle, spineItemIndex). */
    val sentences: List<Triple<String, String, Int>>
)

/**
 * Custom EPUB parser that treats the .epub as a ZIP archive and extracts text
 * without any third-party EPUB library dependency.
 *
 * Steps:
 *  1. Load all zip entries into memory.
 *  2. Parse META-INF/container.xml → find the OPF (package document) path.
 *  3. Parse the OPF → title, author, cover item id, and ordered spine hrefs.
 *  4. Extract and save the cover image to internal storage.
 *  5. For each spine HTML document: strip tags with Jsoup, split into sentences.
 */
object EpubParser {

    fun parse(context: Context, epubFile: File, bookId: Long): EpubParseResult {
        // ── 1. Read all zip entries ──────────────────────────────────────────
        val zip = mutableMapOf<String, ByteArray>()
        ZipInputStream(epubFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) zip[entry.name] = zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // ── 2. Locate OPF via container.xml ─────────────────────────────────
        val containerBytes = zip["META-INF/container.xml"]
            ?: throw IllegalArgumentException("Not a valid EPUB: missing META-INF/container.xml")
        val opfPath = parseContainerXml(containerBytes.toString(Charsets.UTF_8))
        val opfDir = opfPath.substringBeforeLast("/", "")

        // ── 3. Parse OPF ─────────────────────────────────────────────────────
        val opfBytes = zip[opfPath]
            ?: throw IllegalArgumentException("OPF not found at '$opfPath'")
        val (rawMeta, spineHrefs, coverItemId) = parseOpf(
            opfBytes.toString(Charsets.UTF_8), opfDir, zip
        )

        // ── 4. Extract cover image ───────────────────────────────────────────
        val coverPath = extractCover(context, zip, opfDir, coverItemId, bookId)

        // ── 5. Parse spine items → sentences ────────────────────────────────
        val allSentences = mutableListOf<Triple<String, String, Int>>()
        for ((spineIndex, href) in spineHrefs.withIndex()) {
            val key = if (opfDir.isEmpty()) href else "$opfDir/$href"
            val htmlBytes = zip[key] ?: zip[href] ?: continue
            val html = htmlBytes.toString(Charsets.UTF_8)
            val (chapterTitle, sentences) = parseHtmlChapter(html)
            sentences.forEach { allSentences.add(Triple(it, chapterTitle, spineIndex)) }
        }

        return EpubParseResult(
            metadata = rawMeta.copy(coverPath = coverPath),
            sentences = allSentences
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun parseContainerXml(xml: String): String {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.selectFirst("rootfile")?.attr("full-path")
            ?: throw IllegalArgumentException("No <rootfile> in container.xml")
    }

    private data class OpfResult(
        val metadata: EpubMetadata,
        val spineHrefs: List<String>,
        val coverItemId: String?
    )

    private fun parseOpf(
        xml: String,
        @Suppress("UNUSED_PARAMETER") opfDir: String,
        @Suppress("UNUSED_PARAMETER") zip: Map<String, ByteArray>
    ): OpfResult {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())

        val title = doc.selectFirst("dc|title")?.text()
            ?: doc.selectFirst("title")?.text()
            ?: "Unknown Title"
        val author = doc.selectFirst("dc|creator")?.text()
            ?: doc.selectFirst("creator")?.text()
            ?: ""

        // Cover item id from <meta name="cover" content="...">
        val coverItemId = doc.selectFirst("meta[name=cover]")?.attr("content")

        // Build manifest map: id → href
        val manifest = mutableMapOf<String, String>()
        doc.select("item").forEach { item ->
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = href
        }

        // Spine: ordered hrefs from idref attributes
        val spineHrefs = doc.select("itemref[idref]").mapNotNull { manifest[it.attr("idref")] }

        return OpfResult(
            metadata = EpubMetadata(title, author, null),
            spineHrefs = spineHrefs,
            coverItemId = coverItemId
        )
    }

    private fun extractCover(
        context: Context,
        zip: Map<String, ByteArray>,
        @Suppress("UNUSED_PARAMETER") opfDir: String,
        @Suppress("UNUSED_PARAMETER") coverItemId: String?,
        bookId: Long
    ): String? {
        // Try to find the cover by:
        //  a) Path containing "cover" with image extension
        //  b) Fall back to the very first image in the zip
        val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
        val coverEntry = zip.entries.firstOrNull { (path, _) ->
            val ext = path.substringAfterLast(".").lowercase()
            ext in imageExtensions && path.contains("cover", ignoreCase = true)
        } ?: zip.entries.firstOrNull { (path, _) ->
            path.substringAfterLast(".").lowercase() in imageExtensions
        } ?: return null

        return try {
            val coversDir = File(context.filesDir, "covers").also { it.mkdirs() }
            val coverFile = File(coversDir, "$bookId.jpg")
            val bitmap = BitmapFactory.decodeByteArray(coverEntry.value, 0, coverEntry.value.size)
                ?: return null
            coverFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
            coverFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun parseHtmlChapter(html: String): Pair<String, List<String>> {
        val doc = Jsoup.parse(html)

        // Chapter title: prefer headings, fall back to <title>
        val chapterTitle = doc.selectFirst("h1, h2, h3")?.text()
            ?: doc.selectFirst("title")?.text()
            ?: ""

        // Process each <p> element as a separate paragraph so that sentences from
        // different paragraphs are never merged into one segment.
        val body = doc.body() ?: doc
        val paragraphs = body.select("p")

        val sentences: List<String> = if (paragraphs.isEmpty()) {
            // No <p> tags — fall back to full body text (e.g. plain-text spine items)
            SentenceSplitter.split(body.text())
        } else {
            paragraphs.flatMap { p ->
                val text = p.text().trim()
                if (text.isBlank()) emptyList() else SentenceSplitter.split(text)
            }
        }

        return chapterTitle to sentences
    }
}

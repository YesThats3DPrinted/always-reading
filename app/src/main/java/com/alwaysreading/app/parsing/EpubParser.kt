package com.alwaysreading.app.parsing

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

/**
 * A single parsed unit — one sentence, or a placeholder for an image, table, or divider.
 *
 * [type] is one of: SENTENCE, IMAGE, TABLE, DIVIDER.
 * [blockIndex] is the 0-based index of the source block element within this spine item.
 *   Both EpubParser and EpubCombiner use the same element selector, so the same blockIndex
 *   value refers to the same DOM element in both contexts. This allows EpubCombiner to add
 *   data-si="sentenceIndex" to the correct element without any text matching.
 */
data class ParsedSentence(
    val text: String,
    val chapter: String,
    val spineItemIndex: Int,
    val blockIndex: Int,
    val type: String = TYPE_SENTENCE
) {
    companion object {
        const val TYPE_SENTENCE = "SENTENCE"
        const val TYPE_IMAGE    = "IMAGE"
        const val TYPE_TABLE    = "TABLE"
        const val TYPE_DIVIDER  = "DIVIDER"
    }
}

data class EpubParseResult(
    val metadata: EpubMetadata,
    val sentences: List<ParsedSentence>
)

/**
 * Selector used by BOTH EpubParser and EpubCombiner to enumerate block elements
 * in document order. Keeping this identical in both places ensures blockIndex values
 * are consistent for data-si annotation.
 */
const val BLOCK_SELECTOR = "p, h1, h2, h3, h4, h5, h6, table, hr, figure"

/**
 * Custom EPUB parser that treats the .epub as a ZIP archive and extracts text
 * without any third-party EPUB library dependency.
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
        val allSentences = mutableListOf<ParsedSentence>()
        for ((spineIndex, href) in spineHrefs.withIndex()) {
            val key = if (opfDir.isEmpty()) href else "$opfDir/$href"
            val htmlBytes = zip[key] ?: zip[href] ?: continue
            val html = htmlBytes.toString(Charsets.UTF_8)
            val (chapterTitle, sentences) = parseHtmlChapter(html, spineIndex, chapterTitle = null)
            allSentences.addAll(sentences.map { it.copy(chapter = chapterTitle) })
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

        val coverItemId = doc.selectFirst("meta[name=cover]")?.attr("content")

        val manifest = mutableMapOf<String, String>()
        doc.select("item").forEach { item ->
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = href
        }

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

    /**
     * Parses one spine item's HTML into a list of [ParsedSentence]s.
     *
     * Block elements are enumerated in document order using [BLOCK_SELECTOR].
     * Each block gets a [ParsedSentence.blockIndex] so that EpubCombiner can
     * find the matching DOM element and add the correct data-si attribute.
     *
     * Rules:
     * - <hr>: DIVIDER, one entry, no text
     * - <table>: TABLE, one entry, placeholder text
     * - <figure> or <p> with image and no text: IMAGE, one entry, placeholder text
     * - Decorative <p> (only *, —, •, etc.): DIVIDER, one entry
     * - Everything else with text: split into SENTENCE entries, all sharing the same blockIndex
     */
    fun parseHtmlChapter(
        html: String,
        spineItemIndex: Int,
        chapterTitle: String?
    ): Pair<String, List<ParsedSentence>> {
        val doc = Jsoup.parse(html)

        val title = chapterTitle
            ?: doc.selectFirst("h1, h2, h3")?.text()
            ?: doc.selectFirst("title")?.text()
            ?: ""

        val body = doc.body() ?: doc
        val results = mutableListOf<ParsedSentence>()
        var blockIdx = 0

        val elements = body.select(BLOCK_SELECTOR)

        for (el in elements) {
            val tag = el.tagName()
            val text = el.text().trim()

            when {
                tag == "hr" -> {
                    // Horizontal rule: scene break / divider
                    results.add(ParsedSentence("", title, spineItemIndex, blockIdx, ParsedSentence.TYPE_DIVIDER))
                }
                tag == "table" -> {
                    results.add(ParsedSentence("Table — open book to see", title, spineItemIndex, blockIdx, ParsedSentence.TYPE_TABLE))
                }
                (tag == "figure" || el.select("img").isNotEmpty()) && text.isBlank() -> {
                    // Image block (figure, or p/div containing only an img)
                    results.add(ParsedSentence("Image — open book to see", title, spineItemIndex, blockIdx, ParsedSentence.TYPE_IMAGE))
                }
                text.isBlank() || isDecorativeDivider(text) -> {
                    // Empty or decorative paragraph (*** or — — —)
                    results.add(ParsedSentence("", title, spineItemIndex, blockIdx, ParsedSentence.TYPE_DIVIDER))
                }
                else -> {
                    // Regular text block — split into sentences, all sharing this blockIndex
                    SentenceSplitter.split(text).forEach { sentence ->
                        results.add(ParsedSentence(sentence, title, spineItemIndex, blockIdx, ParsedSentence.TYPE_SENTENCE))
                    }
                }
            }
            blockIdx++
        }

        // Fallback: if no block elements found (plain-text spine items), treat entire body as one block
        if (results.isEmpty()) {
            val bodyText = body.text().trim()
            if (bodyText.isNotBlank()) {
                SentenceSplitter.split(bodyText).forEach { sentence ->
                    results.add(ParsedSentence(sentence, title, spineItemIndex, 0, ParsedSentence.TYPE_SENTENCE))
                }
            }
        }

        return title to results
    }

    /** Returns true if [text] contains only decorative characters (asterisks, dashes, bullets, etc.). */
    fun isDecorativeDivider(text: String): Boolean {
        val stripped = text.replace(Regex("[*•·—–\\-=#~\\s]"), "")
        return stripped.isEmpty() && text.isNotEmpty()
    }
}

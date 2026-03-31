package com.notibook.app.ui.reader

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notibook.app.NotiBookApp
import com.notibook.app.data.db.SentenceEntity
import com.notibook.app.epub.EpubCombiner
import com.notibook.app.epub.EpubExtractor
import com.notibook.app.epub.EpubSpineReader
import com.notibook.app.epub.SpineItem
import com.notibook.app.notification.NotificationHelper
import com.notibook.app.parsing.ParsedSentence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ReaderState {
    data object Loading : ReaderState
    data class Error(val message: String) : ReaderState
    data class Ready(
        val bookTitle: String,
        val spineItems: List<SpineItem>,
        val combinedHtml: String,
        val baseUrl: String,
        val restorePageIndex: Int
    ) : ReaderState
}

class EpubReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val app   = application as NotiBookApp
    private val repo  = app.repository
    private val prefs = ReaderPreferences(application)

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    // Current page index — drives LaunchedEffect in Screen → window.scrollTo / translateX
    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    private val _totalPages = MutableStateFlow(1)

    private val _currentChapterTitle = MutableStateFlow("")

    private val _scrollToChapterCommand = MutableStateFlow<Int?>(null)
    val scrollToChapterCommand: StateFlow<Int?> = _scrollToChapterCommand.asStateFlow()

    private val _topBarVisible = MutableStateFlow(true)
    val topBarVisible: StateFlow<Boolean> = _topBarVisible.asStateFlow()

    val readerPreferences: ReaderPreferences get() = prefs

    private var bookId: Long = -1L

    // ── Init ─────────────────────────────────────────────────────────────────

    fun init(bookId: Long) {
        if (this.bookId == bookId) return
        this.bookId = bookId
        viewModelScope.launch { loadReader(bookId) }
    }

    private suspend fun loadReader(bookId: Long) {
        _state.value = ReaderState.Loading
        try {
            val book = repo.getBook(bookId) ?: run {
                _state.value = ReaderState.Error("Book not found."); return
            }
            if (book.isParsing) {
                _state.value = ReaderState.Error("This book is still being processed. Please wait."); return
            }
            if (book.filePath.endsWith(".txt", ignoreCase = true)) loadTxtReader(book)
            else loadEpubReader(bookId, book)
        } catch (e: Exception) {
            _state.value = ReaderState.Error("Failed to open book: ${e.message}")
        }
    }

    private suspend fun loadEpubReader(bookId: Long, book: com.notibook.app.data.db.BookEntity) {
        EpubExtractor.ensureExtracted(getApplication(), bookId, book.filePath)
        val cacheDir   = EpubExtractor.getCacheDir(getApplication(), bookId)
        val spineItems = EpubSpineReader.readSpine(cacheDir)
        if (spineItems.isEmpty()) {
            _state.value = ReaderState.Error("Could not read book chapters."); return
        }

        // Fetch sentences per spine item so EpubCombiner can annotate data-si attributes
        val sentencesBySpineIndex: Map<Int, List<SentenceEntity>> = withContext(Dispatchers.IO) {
            spineItems.indices.associate { idx ->
                idx to repo.getSentencesForSpineItem(bookId, idx)
            }
        }

        val combinedHtml = EpubCombiner.buildCombinedHtml(spineItems, sentencesBySpineIndex, prefs.fontSize)
        val restorePage  = book.readerSpineIndex
        _currentPageIndex.value = restorePage
        _state.value = ReaderState.Ready(
            bookTitle        = book.title,
            spineItems       = spineItems,
            combinedHtml     = combinedHtml,
            baseUrl          = "file://${cacheDir.absolutePath}/",
            restorePageIndex = restorePage
        )
        onReaderOpened(book.notificationActive)
    }

    private suspend fun loadTxtReader(book: com.notibook.app.data.db.BookEntity) {
        val file = File(book.filePath)
        if (!file.exists()) { _state.value = ReaderState.Error("File not found."); return }
        val content = withContext(Dispatchers.IO) { file.readText() }

        // Fetch sentences to annotate paragraphs with data-si
        val sentences = withContext(Dispatchers.IO) {
            repo.getSentencesForSpineItem(book.id, 0)  // all TXT sentences have spineItemIndex=0
        }

        val restorePage = book.readerSpineIndex
        _currentPageIndex.value = restorePage
        _state.value = ReaderState.Ready(
            bookTitle        = book.title,
            spineItems       = emptyList(),
            combinedHtml     = buildTxtHtml(content, sentences),
            baseUrl          = "about:blank",
            restorePageIndex = restorePage
        )
        onReaderOpened(book.notificationActive)
    }

    private fun buildTxtHtml(content: String, sentences: List<SentenceEntity>): String {
        // Build blockIndex → first sentenceIndex mapping (same logic as EpubCombiner)
        val blockToFirstSi: Map<Int, Int> = sentences
            .groupBy { it.blockIndex }
            .mapValues { (_, s) -> s.minByOrNull { it.sentenceIndex }!!.sentenceIndex }

        val fontSize = prefs.fontSize
        val initialCss = """
            html, body { background: #1A1A1A !important; color: #E0E0E0 !important;
                         margin: 0; padding: 0; overflow: hidden; height: 100%; }
            #__content  { height: 100vh; padding: 56px 16px 16px 16px;
                          box-sizing: border-box; column-width: 100vw;
                          column-gap: 0; column-fill: auto; font-size: ${fontSize}px;
                          font-family: serif; line-height: 1.6; }
            * { max-width: 100%; box-sizing: border-box; }
            a { color: #7CB9E8; }
        """.trimIndent().replace("\n", " ")

        val paragraphs = content.split(Regex("\n{2,}")).filter { it.isNotBlank() }
        return buildString {
            append("<html><head><meta charset='UTF-8'>")
            append("<style id='__nb_css'>$initialCss</style>")
            append("</head><body><div id='__content'>")
            paragraphs.forEachIndexed { idx, para ->
                val si = blockToFirstSi[idx]
                val siAttr = if (si != null) " data-si=\"$si\"" else ""
                append("<p$siAttr>")
                append(
                    para.trim()
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\n", "<br>")
                )
                append("</p>")
            }
            append("</div></body></html>")
        }
    }

    // ── Notification lifecycle ────────────────────────────────────────────────

    private suspend fun onReaderOpened(notificationWasActive: Boolean) {
        repo.updateNotifWasActive(bookId, notificationWasActive)
        if (notificationWasActive) NotificationHelper.hide(getApplication(), bookId)
    }

    /**
     * Called from the Screen on any close path.
     *
     * [sentenceIndex] comes from JS via data-si attribute on the element at the
     * top of the current page — exact, no approximation, no text matching.
     * The current page index is read from [_currentPageIndex] which the ViewModel
     * always tracks precisely.
     */
    fun closeWithPosition(sentenceIndex: Int, startNotification: Boolean) {
        val capturedBookId       = bookId
        val capturedChapterTitle = _currentChapterTitle.value
        val capturedPageIndex    = _currentPageIndex.value
        val ctx = getApplication<Application>()

        Log.d("NotiBook", "close: page=$capturedPageIndex sentenceIndex=$sentenceIndex")

        app.appScope.launch {
            val book = repo.getBook(capturedBookId) ?: return@launch

            // Save page index so reader can restore next time it opens
            repo.updateReaderPosition(capturedBookId, capturedPageIndex)

            // Find the first non-DIVIDER sentence at or after sentenceIndex
            var actualSentenceIdx = sentenceIndex.coerceIn(0, (book.totalSentences - 1).coerceAtLeast(0))
            val targetSentence = repo.getSentence(capturedBookId, actualSentenceIdx)
            if (targetSentence?.type == ParsedSentence.TYPE_DIVIDER) {
                // Advance to next non-DIVIDER
                var next = actualSentenceIdx + 1
                while (next < book.totalSentences) {
                    val s = repo.getSentence(capturedBookId, next)
                    if (s != null && s.type != ParsedSentence.TYPE_DIVIDER) {
                        actualSentenceIdx = next; break
                    }
                    next++
                }
            }

            repo.updatePosition(capturedBookId, actualSentenceIdx, capturedChapterTitle)

            val shouldShow = startNotification || book.notifWasActiveBeforeReader
            if (shouldShow) {
                val updatedBook = repo.getBook(capturedBookId) ?: return@launch
                val sentence    = repo.getSentence(capturedBookId, actualSentenceIdx)
                if (sentence != null) {
                    repo.updateNotificationActive(capturedBookId, true)
                    NotificationHelper.show(ctx, updatedBook, sentence)
                }
            }
            repo.updateNotifWasActive(capturedBookId, false)
        }
    }

    // ── JS bridge callbacks ───────────────────────────────────────────────────

    fun onTotalPages(total: Int) {
        _totalPages.value = total.coerceAtLeast(1)
    }

    fun onPrevPage() {
        _currentPageIndex.value = (_currentPageIndex.value - 1).coerceAtLeast(0)
    }

    fun onNextPage() {
        _currentPageIndex.value = (_currentPageIndex.value + 1).coerceAtMost(_totalPages.value - 1)
    }

    fun onCenterTap() {
        _topBarVisible.value = !_topBarVisible.value
    }

    fun onChapterVisible(chapterIndex: Int) {
        val items = (state.value as? ReaderState.Ready)?.spineItems ?: return
        _currentChapterTitle.value = items.getOrNull(chapterIndex)?.chapterTitle ?: ""
    }

    /** Called from JS when navigating to a specific page (chapter jump or internal link). */
    fun onScrollToPage(page: Int) {
        _currentPageIndex.value = page.coerceIn(0, _totalPages.value - 1)
    }

    // ── Chapter jump ──────────────────────────────────────────────────────────

    fun scrollToChapter(spineIndex: Int) {
        _scrollToChapterCommand.value = spineIndex
    }

    fun clearScrollToChapterCommand() {
        _scrollToChapterCommand.value = null
    }

    // ── Internal link handler ─────────────────────────────────────────────────

    /**
     * Navigates to an internal EPUB link (file:// URL with optional #fragment).
     * With CSS columns, navigation is by page (column) index via onScrollToPage().
     * JS finds the target element's column index using offsetLeft / innerWidth.
     */
    fun handleInternalLink(resolvedHref: String, webView: android.webkit.WebView?) {
        webView ?: return
        val uri      = android.net.Uri.parse(resolvedHref)
        val path     = uri.path ?: ""
        val fragment = uri.fragment
        val spineItems = (state.value as? ReaderState.Ready)?.spineItems ?: return
        val spineIdx   = spineItems.indexOfFirst { it.absolutePath == path }

        val js = buildString {
            append("(function(){")
            if (spineIdx >= 0) {
                // Jump to the chapter div, then optionally to the fragment
                append("var ch=document.getElementById('chapter-$spineIdx');")
                append("var target=ch;")
                if (fragment != null) {
                    val safe = fragment.replace("'", "\\'")
                    append("var fr=document.getElementById('$safe')||document.querySelector('[name=\"$safe\"]');")
                    append("if(fr)target=fr;")
                }
                append("if(target){")
                append("var first=target.querySelector('p,h1,h2,h3')||target;")
                append("var page=Math.floor(first.offsetLeft/window.innerWidth);")
                append("NotiBook.onScrollToPage(page);")
                append("}")
            } else if (fragment != null) {
                val safe = fragment.replace("'", "\\'")
                append("var fr=document.getElementById('$safe')||document.querySelector('[name=\"$safe\"]');")
                append("if(fr){")
                append("var page=Math.floor(fr.offsetLeft/window.innerWidth);")
                append("NotiBook.onScrollToPage(page);")
                append("}")
            }
            append("})()")
        }

        if (js.isNotEmpty()) webView.post { webView.evaluateJavascript(js, null) }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun setFontSize(sp: Int) { prefs.fontSize = sp }
}

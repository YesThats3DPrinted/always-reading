package com.notibook.app.ui.reader

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notibook.app.NotiBookApp
import com.notibook.app.epub.EpubCombiner
import com.notibook.app.epub.EpubExtractor
import com.notibook.app.epub.EpubSpineReader
import com.notibook.app.epub.SpineItem
import com.notibook.app.notification.NotificationHelper
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
        val restorePageIndex: Int   // which page to open to
    ) : ReaderState
}

class EpubReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val app   = application as NotiBookApp
    private val repo  = app.repository
    private val prefs = ReaderPreferences(application)

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    // Drives LaunchedEffect in Screen → calls window.scrollTo(0, page * innerHeight)
    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    // Reported by JS after document loads; used for sentence approximation at close
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
        if (spineItems.isEmpty()) { _state.value = ReaderState.Error("Could not read book chapters."); return }
        val combinedHtml = EpubCombiner.buildCombinedHtml(spineItems)
        val restorePage  = restorePage(book)
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
        val restorePage = restorePage(book)
        _currentPageIndex.value = restorePage
        _state.value = ReaderState.Ready(
            bookTitle        = book.title,
            spineItems       = emptyList(),
            combinedHtml     = buildTxtHtml(content),
            baseUrl          = "about:blank",
            restorePageIndex = restorePage
        )
        onReaderOpened(book.notificationActive)
    }

    private fun buildTxtHtml(content: String): String {
        val paragraphs = content.split(Regex("\n{2,}")).filter { it.isNotBlank() }
        return buildString {
            append("<html><head><meta charset='UTF-8'></head><body>")
            paragraphs.forEach { para ->
                append("<p>")
                append(para.trim().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\n","<br>"))
                append("</p>")
            }
            append("</body></html>")
        }
    }

    /**
     * Page index to restore. Uses readerSpineIndex field (repurposed from chapter index
     * to page index now that the whole book is one combined document).
     * Falls back to deriving from currentIndex/totalSentences if page index not yet saved.
     */
    private fun restorePage(book: com.notibook.app.data.db.BookEntity): Int {
        return book.readerSpineIndex  // 0 for new books, saved page index for returning users
    }

    // ── Notification lifecycle ────────────────────────────────────────────────

    private suspend fun onReaderOpened(notificationWasActive: Boolean) {
        repo.updateNotifWasActive(bookId, notificationWasActive)
        if (notificationWasActive) NotificationHelper.hide(getApplication(), bookId)
    }

    /**
     * Called from the Screen on any close path.
     * [pageIndex] and [totalPages] are read from webView directly in Kotlin — exact integers.
     * [visibleText] is the text of the first paragraph visible at the top of the page,
     * used to find the exact sentence in DB rather than a linear approximation.
     * [startNotification] = true for "Close & start notification".
     */
    fun closeWithPosition(
        pageIndex: Int,
        totalPages: Int,
        visibleText: String,
        startNotification: Boolean
    ) {
        val capturedBookId       = bookId
        val capturedChapterTitle = _currentChapterTitle.value
        val ctx = getApplication<Application>()

        Log.d("NotiBook", "close: page=$pageIndex/$totalPages visibleText='${visibleText.take(50)}'")

        app.appScope.launch {
            val book = repo.getBook(capturedBookId) ?: return@launch

            // Save page index for next open
            repo.updateReaderPosition(capturedBookId, pageIndex, 0f)

            // Compute approximate sentence index from page position
            val approxIndex = if (book.totalSentences > 0 && totalPages > 0)
                ((pageIndex.toFloat() / totalPages) * book.totalSentences)
                    .toInt().coerceIn(0, book.totalSentences - 1)
            else 0

            // Try to find exact sentence by text match near the approximate index
            val sentenceIndex = if (visibleText.length >= 15) {
                val prefix = visibleText.take(40)
                val found  = repo.findSentenceByTextPrefix(capturedBookId, prefix)
                if (found != null && kotlin.math.abs(found.sentenceIndex - approxIndex) < 500) {
                    found.sentenceIndex
                } else {
                    approxIndex
                }
            } else approxIndex

            Log.d("NotiBook", "close: approx=$approxIndex exact=$sentenceIndex total=${book.totalSentences}")

            repo.updatePosition(capturedBookId, sentenceIndex, capturedChapterTitle)

            val shouldShow = startNotification || book.notifWasActiveBeforeReader
            if (shouldShow) {
                val updatedBook = repo.getBook(capturedBookId) ?: return@launch
                val sentence    = repo.getSentence(capturedBookId, sentenceIndex)
                if (sentence != null) {
                    repo.updateNotificationActive(capturedBookId, true)
                    NotificationHelper.show(ctx, updatedBook, sentence)
                }
            }
            repo.updateNotifWasActive(capturedBookId, false)
        }
    }

    // ── JS bridge callbacks ───────────────────────────────────────────────────

    /** JS reports total pages after document finishes rendering. */
    fun onTotalPages(total: Int) {
        _totalPages.value = total.coerceAtLeast(1)
    }

    /** JS tap on left zone or swipe right. */
    fun onPrevPage() {
        val prev = (_currentPageIndex.value - 1).coerceAtLeast(0)
        _currentPageIndex.value = prev
    }

    /** JS tap on right zone or swipe left. */
    fun onNextPage() {
        val next = (_currentPageIndex.value + 1).coerceAtMost(_totalPages.value - 1)
        _currentPageIndex.value = next
    }

    /** JS tap on center zone — toggle top bar. */
    fun onCenterTap() {
        _topBarVisible.value = !_topBarVisible.value
    }

    fun onChapterVisible(chapterIndex: Int) {
        val items = (state.value as? ReaderState.Ready)?.spineItems ?: return
        _currentChapterTitle.value = items.getOrNull(chapterIndex)?.chapterTitle ?: ""
    }

    // ── Chapter jump ──────────────────────────────────────────────────────────

    fun scrollToChapter(spineIndex: Int) {
        _scrollToChapterCommand.value = spineIndex
    }

    fun clearScrollToChapterCommand() {
        _scrollToChapterCommand.value = null
    }

    // ── Internal link handler ─────────────────────────────────────────────────

    fun handleInternalLink(resolvedHref: String, webView: android.webkit.WebView?) {
        webView ?: return
        val uri      = android.net.Uri.parse(resolvedHref)
        val path     = uri.path ?: ""
        val fragment = uri.fragment
        val spineItems = (state.value as? ReaderState.Ready)?.spineItems ?: return
        val spineIdx   = spineItems.indexOfFirst { it.absolutePath == path }
        val js = buildString {
            if (spineIdx >= 0) {
                append("(function(){")
                append("var ch=document.getElementById('chapter-$spineIdx');")
                append("if(ch)window.scrollTo(0,ch.offsetTop);")
                if (fragment != null) {
                    val safe = fragment.replace("'", "\\'")
                    append("setTimeout(function(){")
                    append("var fr=document.getElementById('$safe')||document.querySelector('[name=\"$safe\"]');")
                    append("if(fr)window.scrollTo(0,fr.getBoundingClientRect().top+window.pageYOffset);")
                    append("},80);")
                }
                append("})()")
            } else if (fragment != null) {
                val safe = fragment.replace("'", "\\'")
                append("(function(){")
                append("var fr=document.getElementById('$safe')||document.querySelector('[name=\"$safe\"]');")
                append("if(fr)window.scrollTo(0,fr.getBoundingClientRect().top+window.pageYOffset);")
                append("})()")
            }
        }
        if (js.isNotEmpty()) webView.post { webView.evaluateJavascript(js, null) }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun setFontSize(sp: Int) { prefs.fontSize = sp }
}

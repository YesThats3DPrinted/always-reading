package com.notibook.app.ui.reader

import android.app.Application
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ReaderState {
    data object Loading : ReaderState
    data class Error(val message: String) : ReaderState
    data class Ready(
        val bookTitle: String,
        val spineItems: List<SpineItem>,
        val combinedHtml: String,
        val baseUrl: String,
        /** Character offset to restore to. -1 means use restoreCurrentIndex instead. */
        val restoreCharOffset: Long,
        /** Sentence index to navigate to when restoreCharOffset == -1. */
        val restoreCurrentIndex: Int
    ) : ReaderState
}

class EpubReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val app   = application as NotiBookApp
    private val repo  = app.repository
    private val prefs = ReaderPreferences(application)

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    // Current page index — drives LaunchedEffect in Screen → translateX animation
    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    private val _currentChapterTitle = MutableStateFlow("")
    val currentChapterTitle: StateFlow<String> = _currentChapterTitle.asStateFlow()

    // Index of the spine item currently visible — used to highlight the active chapter
    private val _currentChapterIndex = MutableStateFlow(-1)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    // Approximate total page count reported by JS after columns are laid out.
    // Used only for the bottom scrubber display — not for any navigation clamping.
    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _scrollToChapterCommand = MutableStateFlow<Int?>(null)
    val scrollToChapterCommand: StateFlow<Int?> = _scrollToChapterCommand.asStateFlow()

    private val _topBarVisible = MutableStateFlow(true)
    val topBarVisible: StateFlow<Boolean> = _topBarVisible.asStateFlow()

    // True while the WebView is re-initializing columns after a screen resize.
    private val _isReiniting = MutableStateFlow(false)
    val isReiniting: StateFlow<Boolean> = _isReiniting.asStateFlow()

    // Character offset anchor for orientation restore.
    // Updated by JS after each page turn via onCharOffset().
    private val _restoreCharOffset = MutableStateFlow(-1L)
    val restoreCharOffset: Long get() = _restoreCharOffset.value

    // Freeze the char offset anchor during re-init so the LaunchedEffect's
    // post-animation onCharOffset call doesn't overwrite the saved position.
    private val skipNextCharOffsetUpdate = AtomicBoolean(false)

    // When JS calls onScrollToPage (e.g. tryRestore, chapter jump), skip the
    // LaunchedEffect animation since JS has already set the position directly.
    private val skipNextPageAnimation = AtomicBoolean(false)

    // Whether the notification is currently active — observed from DB
    private val _notificationActive = MutableStateFlow(false)
    val notificationActive: StateFlow<Boolean> = _notificationActive.asStateFlow()

    // Current sentence index and total — observed from DB for the bottom bar counter
    private val _currentSentenceIndex = MutableStateFlow(0)
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    private val _totalSentences = MutableStateFlow(0)
    val totalSentences: StateFlow<Int> = _totalSentences.asStateFlow()

    val readerPreferences: ReaderPreferences get() = prefs

    private var bookId: Long = -1L

    // ── Init ─────────────────────────────────────────────────────────────────

    fun init(bookId: Long) {
        if (this.bookId == bookId) return
        this.bookId = bookId
        viewModelScope.launch { loadReader(bookId) }
        // Observe book state from DB
        viewModelScope.launch {
            repo.getBookFlow(bookId).collect { book ->
                _notificationActive.value = book?.notificationActive ?: false
                _currentSentenceIndex.value = book?.currentIndex ?: 0
                _totalSentences.value = book?.totalSentences ?: 0
            }
        }
    }

    private suspend fun loadReader(bookId: Long) {
        _state.value = ReaderState.Loading
        try {
            val book = repo.getBook(bookId) ?: run {
                _state.value = ReaderState.Error("Book not found."); return
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

        val sentencesBySpineIndex: Map<Int, List<SentenceEntity>> = withContext(Dispatchers.IO) {
            spineItems.indices.associate { idx ->
                idx to repo.getSentencesForSpineItem(bookId, idx)
            }
        }

        val combinedHtml = EpubCombiner.buildCombinedHtml(spineItems, sentencesBySpineIndex, prefs.fontSize)

        val htmlFile = withContext(Dispatchers.IO) {
            File(cacheDir.canonicalFile, "__reader.html").also { it.writeText(combinedHtml) }
        }

        _currentPageIndex.value = 0
        _state.value = ReaderState.Ready(
            bookTitle           = book.title,
            spineItems          = spineItems,
            combinedHtml        = "",
            baseUrl             = "file://${htmlFile.canonicalPath}",
            restoreCharOffset   = book.readerCharOffset,
            restoreCurrentIndex = book.currentIndex
        )
    }

    private suspend fun loadTxtReader(book: com.notibook.app.data.db.BookEntity) {
        val file = File(book.filePath)
        if (!file.exists()) { _state.value = ReaderState.Error("File not found."); return }
        val content = withContext(Dispatchers.IO) { file.readText() }

        val sentences = withContext(Dispatchers.IO) {
            repo.getSentencesForSpineItem(book.id, 0)
        }

        _currentPageIndex.value = 0
        _state.value = ReaderState.Ready(
            bookTitle           = book.title,
            spineItems          = emptyList(),
            combinedHtml        = buildTxtHtml(content, sentences),
            baseUrl             = "about:blank",
            restoreCharOffset   = book.readerCharOffset,
            restoreCurrentIndex = book.currentIndex
        )
    }

    private fun buildTxtHtml(content: String, sentences: List<SentenceEntity>): String {
        // Group non-divider sentences by block (paragraph) index, sorted by position.
        val sentencesByBlock: Map<Int, List<SentenceEntity>> = sentences
            .filter { it.type != "DIVIDER" }
            .groupBy { it.blockIndex }
            .mapValues { (_, s) -> s.sortedBy { it.sentenceIndex } }

        val fontSize = prefs.fontSize
        val initialCss = """
            body * { color: #E0E0E0 !important; max-width: 100%; box-sizing: border-box;
                     overflow-wrap: break-word; word-break: break-word; }
            a, a * { color: #7CB9E8 !important; }
            html { height: 100%; overflow: hidden; clip-path: inset(0); }
            body { background: #1A1A1A !important; color: #E0E0E0 !important;
                   margin: 0; padding: 0; overflow: visible;
                   column-gap: 0; column-fill: auto;
                   font-size: ${fontSize}px; font-family: serif; line-height: 1.6; }
        """.trimIndent().replace("\n", " ")

        val paragraphs = content.split(Regex("\n{2,}")).filter { it.isNotBlank() }
        return buildString {
            append("<html><head>")
            append("<meta charset='UTF-8'>")
            append("<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>")
            append("<style id='__nb_css'>$initialCss</style>")
            append("</head><body>")
            paragraphs.forEachIndexed { idx, para ->
                val paraText  = para.trim()
                val blockSents = sentencesByBlock[idx]
                append("<p>")
                if (!blockSents.isNullOrEmpty()) {
                    // Insert an empty <span data-si="N"> marker at the start of each
                    // sentence within the original paragraph text, preserving the text.
                    var pos = 0
                    for (s in blockSents) {
                        val probe = s.text.trimStart().take(30)
                        val sIdx  = if (probe.isNotEmpty()) paraText.indexOf(probe, pos) else -1
                        if (sIdx >= 0) {
                            if (sIdx > pos) append(paraText.substring(pos, sIdx)
                                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                            append("<span data-si=\"${s.sentenceIndex}\"></span>")
                            pos = sIdx
                        }
                    }
                    if (pos < paraText.length) append(paraText.substring(pos)
                        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        .replace("\n", "<br>"))
                } else {
                    append(paraText
                        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        .replace("\n", "<br>"))
                }
                append("</p>")
            }
            append("<span id=\"__nb_end\"></span>")
            append("</body></html>")
        }
    }

    // ── JS bridge callbacks ───────────────────────────────────────────────────

    fun onPrevPage() {
        _currentPageIndex.value = (_currentPageIndex.value - 1).coerceAtLeast(0)
        _topBarVisible.value = false
    }

    fun onNextPage() {
        // No clamping — JS guards against going past the last page via sentinelVisible()
        _currentPageIndex.value = _currentPageIndex.value + 1
        _topBarVisible.value = false
    }

    fun onCenterTap() {
        _topBarVisible.value = !_topBarVisible.value
    }

    fun onChapterVisible(chapterIndex: Int) {
        val items = (state.value as? ReaderState.Ready)?.spineItems ?: return
        _currentChapterTitle.value = items.getOrNull(chapterIndex)?.chapterTitle ?: ""
        _currentChapterIndex.value = chapterIndex
    }

    fun onTotalPages(total: Int) {
        if (total > 0) _totalPages.value = total
    }

    /**
     * Called from JS when navigating to a specific page (tryRestore, chapter jump, internal link).
     * Sets skipNextPageAnimation so LaunchedEffect doesn't re-animate — JS already positioned.
     */
    fun onScrollToPage(page: Int) {
        skipNextPageAnimation.set(true)
        _currentPageIndex.value = page.coerceAtLeast(0)
    }

    /**
     * Called from JS bridge after each page turn with the character offset at the top of screen.
     * Skipped once after re-init so orientation change never drifts the anchor.
     */
    fun onCharOffset(offset: Long) {
        if (skipNextCharOffsetUpdate.getAndSet(false)) return
        if (offset >= 0) _restoreCharOffset.value = offset
    }

    /** Called by JS after every page turn and slider navigation with the sentence index at top. */
    fun onCurrentSentence(si: Int) {
        if (si >= 0) _currentSentenceIndex.value = si
    }

    /**
     * Called from JS when the reader is closed.
     * [sentenceIndex] is used to update the notification position.
     * [charOffset] is saved so the reader can reopen at the same place.
     */
    fun onClose(sentenceIndex: Int, charOffset: Long, startNotification: Boolean) {
        val capturedBookId       = bookId
        val capturedChapterTitle = _currentChapterTitle.value
        val ctx = getApplication<Application>()

        app.appScope.launch {
            val book = repo.getBook(capturedBookId) ?: return@launch

            // Save character offset for next reader open
            repo.updateReaderCharOffset(capturedBookId, charOffset)

            // Find the first non-DIVIDER sentence at or after sentenceIndex
            var actualSentenceIdx = sentenceIndex.coerceIn(0, (book.totalSentences - 1).coerceAtLeast(0))
            val targetSentence = repo.getSentence(capturedBookId, actualSentenceIdx)
            if (targetSentence?.type == ParsedSentence.TYPE_DIVIDER) {
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

            if (startNotification || book.notificationActive) {
                val updatedBook = repo.getBook(capturedBookId) ?: return@launch
                val sentence    = repo.getSentence(capturedBookId, actualSentenceIdx)
                if (sentence != null) {
                    repo.updateNotificationActive(capturedBookId, true)
                    NotificationHelper.show(ctx, updatedBook, sentence)
                }
            }
        }
    }

    // ── Notification toggle ───────────────────────────────────────────────────

    fun toggleNotification(enabled: Boolean) {
        val capturedBookId = bookId
        val ctx = getApplication<Application>()
        app.appScope.launch {
            val book = repo.getBook(capturedBookId) ?: return@launch
            if (enabled) {
                val sentence = repo.getSentence(capturedBookId, book.currentIndex) ?: return@launch
                repo.updateNotificationActive(capturedBookId, true)
                NotificationHelper.show(ctx, book, sentence)
            } else {
                repo.updateNotificationActive(capturedBookId, false)
                NotificationHelper.hide(ctx, capturedBookId)
            }
        }
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
            append("(function(){")
            if (spineIdx >= 0) {
                append("var ch=document.getElementById('chapter-$spineIdx');")
                append("var target=ch;")
                if (fragment != null) {
                    val safe = fragment.replace("'", "\\'")
                    append("var fr=document.getElementById('$safe')||document.querySelector('[name=\"$safe\"]');")
                    append("if(fr)target=fr;")
                }
                append("if(target){")
                append("var cw=window.__colW||window.innerWidth;")
                append("var page=Math.floor(target.offsetLeft/cw);")
                append("window.__currentPage=page;document.body.style.transform='translateX(-'+(page*cw)+'px)';")
                append("NotiBook.onScrollToPage(page);")
                append("}")
            } else if (fragment != null) {
                val safe = fragment.replace("'", "\\'")
                append("var fr=document.getElementById('$safe')||document.querySelector('[name=\"$safe\"]');")
                append("if(fr){")
                append("var cw=window.__colW||window.innerWidth;")
                append("var page=Math.floor(fr.offsetLeft/cw);")
                append("window.__currentPage=page;document.body.style.transform='translateX(-'+(page*cw)+'px)';")
                append("NotiBook.onScrollToPage(page);")
                append("}")
            }
            append("})()")
        }

        if (js.isNotEmpty()) webView.post { webView.evaluateJavascript(js, null) }
    }

    // ── Orientation re-init ───────────────────────────────────────────────────

    /** Called from JS bridge: columns fully re-laid-out after resize, hide overlay. */
    fun onReady() { _isReiniting.value = false }

    /** Called from Kotlin when screen dimensions change: show overlay before re-init JS fires. */
    fun startReinit() {
        _isReiniting.value = true
        skipNextCharOffsetUpdate.set(true)  // freeze anchor for this re-init cycle
    }

    fun isSkippingPageAnimation(): Boolean = skipNextPageAnimation.getAndSet(false)

    // ── Settings ──────────────────────────────────────────────────────────────

    fun setFontSize(sp: Int) { prefs.fontSize = sp }
}

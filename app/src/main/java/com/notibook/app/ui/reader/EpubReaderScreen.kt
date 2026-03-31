package com.notibook.app.ui.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notibook.app.epub.SpineItem

private val BG_COLOR   = Color(0xFF1A1A1A)
private val TEXT_COLOR = Color(0xFFE0E0E0)
private val BAR_COLOR  = Color(0xFF2C2C2C)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
    bookId: Long,
    onClose: () -> Unit,
    vm: EpubReaderViewModel = viewModel()
) {
    LaunchedEffect(bookId) { vm.init(bookId) }

    val state            by vm.state.collectAsState()
    val currentPageIndex by vm.currentPageIndex.collectAsState()
    val scrollToChapter  by vm.scrollToChapterCommand.collectAsState()
    val topBarVisible    by vm.topBarVisible.collectAsState()
    val prefs            = vm.readerPreferences

    var fontSize        by remember { mutableIntStateOf(prefs.fontSize) }
    var showMenu        by remember { mutableStateOf(false) }
    var showChapterList by remember { mutableStateOf(false) }

    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // Drive CSS-columns page navigation: whenever currentPageIndex changes,
    // apply translateX to move the content div to show the correct column.
    LaunchedEffect(currentPageIndex) {
        webViewRef.value?.evaluateJavascript(
            "(function(){var c=document.getElementById('__content');if(c)c.style.transform='translateX(-'+($currentPageIndex*100)+'vw)';})()",
            null
        )
    }

    // Jump to chapter when ViewModel emits the command
    LaunchedEffect(scrollToChapter) {
        val idx = scrollToChapter ?: return@LaunchedEffect
        webViewRef.value?.evaluateJavascript("""
            (function(){
                var ch=document.getElementById('chapter-$idx');
                if(!ch) return;
                var first=ch.querySelector('p,h1,h2,h3')||ch;
                var page=Math.floor(first.offsetLeft/window.innerWidth);
                var c=document.getElementById('__content');
                if(c) c.style.transform='translateX(-'+(page*100)+'vw)';
                NotiBook.onScrollToPage(page);
            })()
        """.trimIndent(), null)
        vm.clearScrollToChapterCommand()
    }

    fun closeReader(startNotification: Boolean) {
        val wv = webViewRef.value
        if (wv != null) {
            // Get data-si of the element at the top-centre of the current page.
            // Because all block elements were annotated at build time, this gives
            // the exact sentence index with no approximation or text matching.
            wv.evaluateJavascript("""
                (function(){
                    var el = document.elementFromPoint(window.innerWidth * 0.5, 60);
                    for (var i = 0; i < 8 && el; i++) {
                        if (el.dataset && el.dataset.si !== undefined) return el.dataset.si;
                        el = el.parentElement;
                    }
                    return '-1';
                })()
            """.trimIndent()) { raw ->
                val si = raw?.trim()?.removeSurrounding("\"")?.toIntOrNull() ?: 0
                val sentenceIndex = if (si < 0) 0 else si
                vm.closeWithPosition(sentenceIndex, startNotification)
                onClose()
            }
        } else {
            vm.closeWithPosition(0, startNotification)
            onClose()
        }
    }

    BackHandler { closeReader(startNotification = false) }

    val ready      = state as? ReaderState.Ready
    val spineItems = ready?.spineItems ?: emptyList()
    val bookTitle  = ready?.bookTitle ?: ""

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG_COLOR)
    ) {
        when (val s = state) {
            is ReaderState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            is ReaderState.Error -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(s.message, color = TEXT_COLOR, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { vm.init(bookId) }) { Text("Retry") }
            }

            is ReaderState.Ready -> ReaderContent(
                combinedHtml     = s.combinedHtml,
                baseUrl          = s.baseUrl,
                restorePageIndex = s.restorePageIndex,
                spineItems       = s.spineItems,
                fontSize         = fontSize,
                onPrevPage       = { vm.onPrevPage() },
                onNextPage       = { vm.onNextPage() },
                onCenterTap      = { vm.onCenterTap() },
                onTotalPages     = { total -> vm.onTotalPages(total) },
                onChapterVisible = { idx -> vm.onChapterVisible(idx) },
                onScrollToPage   = { page -> vm.onScrollToPage(page) },
                onInternalLink   = { href -> vm.handleInternalLink(href, webViewRef.value) },
                webViewRef       = webViewRef
            )
        }

        AnimatedVisibility(
            visible  = topBarVisible,
            enter    = slideInVertically(initialOffsetY = { -it }),
            exit     = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                title = {
                    Text(bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    if (spineItems.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { showChapterList = true }) {
                                Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Chapters")
                            }
                            DropdownMenu(
                                expanded = showChapterList,
                                onDismissRequest = { showChapterList = false }
                            ) {
                                spineItems.forEachIndexed { index, item ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = item.chapterTitle.ifBlank { "Chapter ${index + 1}" },
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        onClick = { showChapterList = false; vm.scrollToChapter(index) }
                                    )
                                }
                            }
                        }
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .width(200.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Font size",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        if (fontSize > ReaderPreferences.FONT_SIZE_RANGE.first) {
                                            fontSize -= 2; vm.setFontSize(fontSize)
                                        }
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(36.dp)
                                ) { Text("−", style = MaterialTheme.typography.titleMedium) }
                                Text(
                                    "${fontSize}sp",
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(44.dp)
                                )
                                TextButton(
                                    onClick = {
                                        if (fontSize < ReaderPreferences.FONT_SIZE_RANGE.last) {
                                            fontSize += 2; vm.setFontSize(fontSize)
                                        }
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(36.dp)
                                ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                            }

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text("Close & start notification") },
                                onClick = { showMenu = false; closeReader(startNotification = true) }
                            )
                            DropdownMenuItem(
                                text = { Text("Close") },
                                onClick = { showMenu = false; closeReader(startNotification = false) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor         = BAR_COLOR,
                    titleContentColor      = TEXT_COLOR,
                    actionIconContentColor = TEXT_COLOR
                )
            )
        }
    }
}

// ── WebView content ───────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReaderContent(
    combinedHtml: String,
    baseUrl: String,
    restorePageIndex: Int,
    @Suppress("UNUSED_PARAMETER") spineItems: List<SpineItem>,
    fontSize: Int,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onCenterTap: () -> Unit,
    onTotalPages: (Int) -> Unit,
    onChapterVisible: (Int) -> Unit,
    onScrollToPage: (Int) -> Unit,
    onInternalLink: (String) -> Unit,
    webViewRef: MutableState<WebView?>
) {
    fun buildCss() = buildString {
        // html clips the content; body is the scrolling column container
        append("html { overflow: hidden !important; height: 100% !important; }")
        append(" body {")
        append(" font-size: ${fontSize}px !important;")
        append(" font-family: serif !important;")
        append(" background: #1A1A1A !important;")
        append(" color: #E0E0E0 !important;")
        append(" line-height: 1.6 !important;")
        append(" margin: 0 !important;")
        append(" padding: 0 !important;")
        append(" overflow: hidden !important;")
        append(" height: 100% !important;")
        append(" }")
        // The __content div is the CSS columns container
        append(" #__content {")
        append(" height: 100vh !important;")
        append(" padding: 56px 16px 16px 16px !important;")   // top padding clears the TopAppBar
        append(" box-sizing: border-box !important;")
        append(" column-width: 100vw !important;")
        append(" column-gap: 0 !important;")
        append(" column-fill: auto !important;")
        append(" }")
        append(" * { max-width: 100% !important; box-sizing: border-box !important; }")
        append(" a { color: #7CB9E8 !important; }")
        append(" img { width: auto !important; height: auto !important; max-height: 80vh !important; object-fit: contain !important; }")
        append(" table { width: 100% !important; }")
        append(" pre, code { white-space: pre-wrap !important; word-break: break-word !important; }")
    }

    fun buildJs() = """
        (function() {
            // ── Block native touch scroll ─────────────────────────────────────
            document.addEventListener('touchmove', function(e) { e.preventDefault(); }, { passive: false });

            var content = document.getElementById('__content');

            // ── Chapter tracking ──────────────────────────────────────────────
            var chapters = [], i = 0;
            while (true) {
                var el = document.getElementById('chapter-' + i);
                if (!el) break;
                chapters.push(el); i++;
            }
            function currentChapter() {
                var page = window.__currentPage || 0;
                var cx = page * window.innerWidth + window.innerWidth * 0.5;
                var cur = 0;
                for (var j = 0; j < chapters.length; j++) {
                    if (chapters[j].offsetLeft <= cx) cur = j; else break;
                }
                return cur;
            }

            // ── Total pages reporting ─────────────────────────────────────────
            function reportTotalPages() {
                if (!content) return;
                var total = Math.max(1, Math.ceil(content.scrollWidth / window.innerWidth));
                NotiBook.onTotalPages(total);
            }

            // ── Tap & swipe handling ──────────────────────────────────────────
            var touchStartX = 0, touchStartY = 0, touchStartTime = 0;
            var SWIPE_THRESHOLD = 50;
            var TAP_MAX_MOVE    = 10;
            var TAP_MAX_MS      = 300;

            document.addEventListener('touchstart', function(e) {
                touchStartX    = e.touches[0].clientX;
                touchStartY    = e.touches[0].clientY;
                touchStartTime = Date.now();
            }, { passive: true });

            document.addEventListener('touchend', function(e) {
                var dx    = e.changedTouches[0].clientX - touchStartX;
                var dy    = e.changedTouches[0].clientY - touchStartY;
                var dt    = Date.now() - touchStartTime;
                var absDx = Math.abs(dx);
                var absDy = Math.abs(dy);

                if (absDx > SWIPE_THRESHOLD && absDx > absDy) {
                    if (dx < 0) NotiBook.onNextPage();
                    else        NotiBook.onPrevPage();
                } else if (absDx < TAP_MAX_MOVE && absDy < TAP_MAX_MOVE && dt < TAP_MAX_MS) {
                    var x = touchStartX;
                    var w = window.innerWidth;
                    if (x < w * 0.3)      NotiBook.onPrevPage();
                    else if (x > w * 0.7) NotiBook.onNextPage();
                    else                   NotiBook.onCenterTap();
                }
            }, { passive: true });

            // ── Internal link interception ────────────────────────────────────
            document.addEventListener('click', function(e) {
                var a = e.target;
                while (a && a.tagName !== 'A') a = a.parentElement;
                if (!a) return;
                var href = a.href;
                if (!href) return;
                if (href.startsWith('http://') || href.startsWith('https://')) return;
                e.preventDefault();
                NotiBook.onInternalLink(href);
            }, true);

            // ── Restore page position ─────────────────────────────────────────
            var targetPage = $restorePageIndex;
            window.__currentPage = targetPage;
            function tryRestore() {
                if (!content) { content = document.getElementById('__content'); }
                if (!content) { setTimeout(tryRestore, 200); return; }
                var total = Math.ceil(content.scrollWidth / window.innerWidth);
                if (total < 2 && targetPage > 0) { setTimeout(tryRestore, 300); return; }
                content.style.transform = 'translateX(-' + (targetPage * 100) + 'vw)';
                reportTotalPages();
                if (chapters.length > 0) NotiBook.onChapterVisible(currentChapter());
            }
            if (targetPage > 0) {
                setTimeout(tryRestore, 400);
            } else {
                setTimeout(reportTotalPages, 500);
            }
        })();
    """.trimIndent()

    val cssRef = rememberUpdatedState(buildCss())
    val jsRef  = rememberUpdatedState(buildJs())

    // Re-inject CSS when font size changes
    LaunchedEffect(fontSize) {
        val escaped = cssRef.value
            .replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        webViewRef.value?.evaluateJavascript(
            "(function(){var s=document.getElementById('__nb_css');if(s)s.textContent='$escaped';})()", null
        )
    }

    var isLoaded by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                webViewRef.value = this
                settings.apply {
                    javaScriptEnabled   = true
                    allowFileAccess     = true
                    domStorageEnabled   = false
                    builtInZoomControls = false
                    displayZoomControls = false
                    setSupportZoom(false)
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                }

                addJavascriptInterface(object {
                    @JavascriptInterface fun onPrevPage()                  = onPrevPage()
                    @JavascriptInterface fun onNextPage()                  = onNextPage()
                    @JavascriptInterface fun onCenterTap()                 = onCenterTap()
                    @JavascriptInterface fun onTotalPages(total: Int)      = onTotalPages(total)
                    @JavascriptInterface fun onChapterVisible(idx: Int)    = onChapterVisible(idx)
                    @JavascriptInterface fun onScrollToPage(page: Int)     = onScrollToPage(page)
                    @JavascriptInterface fun onInternalLink(href: String)  = onInternalLink(href)
                }, "NotiBook")

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            )
                        }
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        val css     = cssRef.value
                        val js      = jsRef.value
                        val escaped = css.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
                        // Inject CSS style tag and wrap body content in #__content column div
                        view.evaluateJavascript("""
                            (function(){
                                // Inject CSS
                                var s = document.getElementById('__nb_css');
                                if (!s) {
                                    s = document.createElement('style');
                                    s.id = '__nb_css';
                                    document.head.appendChild(s);
                                }
                                s.textContent = '$escaped';

                                // Wrap body children in #__content if not already done
                                if (!document.getElementById('__content')) {
                                    var div = document.createElement('div');
                                    div.id = '__content';
                                    while (document.body.firstChild) {
                                        div.appendChild(document.body.firstChild);
                                    }
                                    document.body.appendChild(div);
                                }
                            })();
                        """.trimIndent(), null)
                        view.evaluateJavascript(js, null)
                    }
                }
            }
        },
        update = { wv ->
            if (!isLoaded) {
                isLoaded = true
                wv.loadDataWithBaseURL(baseUrl, combinedHtml, "text/html", "UTF-8", null)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

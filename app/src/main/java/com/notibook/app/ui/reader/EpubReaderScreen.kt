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

    // Drive page turns: whenever currentPageIndex changes, scroll WebView to that page
    LaunchedEffect(currentPageIndex) {
        webViewRef.value?.evaluateJavascript(
            "window.scrollTo(0, $currentPageIndex * window.innerHeight);",
            null
        )
    }

    // Jump to chapter when ViewModel emits the command
    LaunchedEffect(scrollToChapter) {
        val idx = scrollToChapter ?: return@LaunchedEffect
        webViewRef.value?.evaluateJavascript(
            "(function(){var el=document.getElementById('chapter-$idx');if(el)window.scrollTo({top:el.offsetTop,behavior:'instant'});})()",
            null
        )
        vm.clearScrollToChapterCommand()
    }

    fun closeReader(startNotification: Boolean) {
        val wv = webViewRef.value
        if (wv != null) {
            // Page index from Kotlin: exact integer, always reliable
            val pageIndex  = if (wv.height > 0) wv.scrollY / wv.height else 0
            @Suppress("DEPRECATION")
            val totalPages = if (wv.height > 0) {
                val contentPx = (wv.contentHeight * wv.scale).toInt()
                ((contentPx + wv.height - 1) / wv.height).coerceAtLeast(1)
            } else 1

            // Get visible text at top of current page for precise sentence matching
            wv.evaluateJavascript("""
                (function(){
                    var el = document.elementFromPoint(window.innerWidth * 0.5, 70);
                    for (var i = 0; i < 6 && el; i++) {
                        var t = (el.tagName || '').toUpperCase();
                        if (['P','LI','H1','H2','H3','H4','H5','H6','BLOCKQUOTE','TD'].indexOf(t) >= 0) break;
                        el = el.parentElement;
                    }
                    return el ? (el.textContent || '').replace(/\s+/g,' ').trim().substring(0, 80) : '';
                })()
            """.trimIndent()) { raw ->
                val text = raw?.trim()
                    ?.removeSurrounding("\"")
                    ?.replace("\\n", " ")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\'", "'")
                    ?: ""
                vm.closeWithPosition(pageIndex, totalPages, text, startNotification)
                onClose()
            }
        } else {
            vm.closeWithPosition(0, 1, "", startNotification)
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
                onInternalLink   = { href -> vm.handleInternalLink(href, webViewRef.value) },
                webViewRef       = webViewRef
            )
        }

        // Top bar slides in/out from the top
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
    spineItems: List<SpineItem>,
    fontSize: Int,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onCenterTap: () -> Unit,
    onTotalPages: (Int) -> Unit,
    onChapterVisible: (Int) -> Unit,
    onInternalLink: (String) -> Unit,
    webViewRef: MutableState<WebView?>
) {
    fun buildCss() = buildString {
        // overflow:hidden prevents native scroll; JS handles all navigation
        append("html { overflow: hidden !important; max-width: 100% !important; }")
        append(" body {")
        append(" font-size: ${fontSize}px !important;")
        append(" font-family: serif !important;")
        append(" background: #1A1A1A !important;")
        append(" color: #E0E0E0 !important;")
        append(" line-height: 1.6 !important;")
        append(" margin: 0 !important;")
        append(" padding: 16px !important;")
        append(" overflow-x: hidden !important;")
        append(" max-width: 100% !important;")
        append(" box-sizing: border-box !important;")
        append(" }")
        append(" * { max-width: 100% !important; box-sizing: border-box !important; }")
        append(" a { color: #7CB9E8 !important; }")
        append(" img { width: auto !important; height: auto !important; }")
        append(" table { width: 100% !important; }")
        append(" pre, code { white-space: pre-wrap !important; word-break: break-word !important; }")
    }

    fun buildJs() = """
        (function() {
            // ── Block native touch scroll ─────────────────────────────────────
            document.addEventListener('touchmove', function(e) { e.preventDefault(); }, { passive: false });

            // ── Chapter tracking ──────────────────────────────────────────────
            var chapters = [], i = 0;
            while (true) {
                var el = document.getElementById('chapter-' + i);
                if (!el) break;
                chapters.push(el); i++;
            }
            function currentChapter(scrollY) {
                var cur = 0;
                for (var j = 0; j < chapters.length; j++) {
                    if (chapters[j].offsetTop <= scrollY + 50) cur = j; else break;
                }
                return cur;
            }

            // ── Total pages reporting ─────────────────────────────────────────
            function reportTotalPages() {
                var total = Math.max(1, Math.ceil(document.documentElement.scrollHeight / window.innerHeight));
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
                    // Horizontal swipe: left = next page, right = prev page
                    if (dx < 0) NotiBook.onNextPage();
                    else        NotiBook.onPrevPage();
                } else if (absDx < TAP_MAX_MOVE && absDy < TAP_MAX_MOVE && dt < TAP_MAX_MS) {
                    // Tap zone: left 30% = prev, right 30% = next, center = toggle bar
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
            if (targetPage > 0) {
                var attempts = 0;
                function tryRestore() {
                    var totalH = document.documentElement.scrollHeight;
                    var pages  = Math.ceil(totalH / window.innerHeight);
                    if (pages < 2 && ++attempts < 30) { setTimeout(tryRestore, 300); return; }
                    window.scrollTo(0, targetPage * window.innerHeight);
                    reportTotalPages();
                    if (chapters.length > 0) NotiBook.onChapterVisible(currentChapter(window.pageYOffset));
                    setTimeout(function() {
                        var actual = Math.round(window.pageYOffset / window.innerHeight);
                        if (actual !== targetPage && attempts < 30) { ++attempts; tryRestore(); }
                    }, 200);
                }
                setTimeout(tryRestore, 400);
            } else {
                setTimeout(reportTotalPages, 500);
            }
        })();
    """.trimIndent()

    val cssRef = rememberUpdatedState(buildCss())
    val jsRef  = rememberUpdatedState(buildJs())

    LaunchedEffect(fontSize) {
        val escaped = cssRef.value
            .replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        webViewRef.value?.evaluateJavascript(
            "(function(){var s=document.getElementById('__nb');if(s)s.textContent='$escaped';})()", null
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
                    @JavascriptInterface fun onPrevPage()                 = onPrevPage()
                    @JavascriptInterface fun onNextPage()                 = onNextPage()
                    @JavascriptInterface fun onCenterTap()                = onCenterTap()
                    @JavascriptInterface fun onTotalPages(total: Int)     = onTotalPages(total)
                    @JavascriptInterface fun onChapterVisible(idx: Int)   = onChapterVisible(idx)
                    @JavascriptInterface fun onInternalLink(href: String) = onInternalLink(href)
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
                        view.evaluateJavascript("""
                            (function(){
                                var s=document.getElementById('__nb');
                                if(!s){s=document.createElement('style');s.id='__nb';document.head.appendChild(s);}
                                s.textContent='$escaped';
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

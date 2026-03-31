package com.notibook.app.ui.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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

    // CSS-columns pagination: use window.__colW (set by buildJs) — same value used for column-width
    LaunchedEffect(currentPageIndex) {
        webViewRef.value?.evaluateJavascript(
            "(function(){var cw=window.__colW||window.innerWidth;window.__currentPage=$currentPageIndex;document.body.style.transform='translateX(-'+($currentPageIndex*cw)+'px)';})()",
            null
        )
    }

    // Jump to chapter when ViewModel emits the command
    LaunchedEffect(scrollToChapter) {
        val idx = scrollToChapter ?: return@LaunchedEffect
        webViewRef.value?.evaluateJavascript("""
            (function(){
                var cw=window.__colW||window.innerWidth;
                var ch=document.getElementById('chapter-$idx');
                if(!ch) return;
                var page=Math.floor(ch.offsetLeft/cw);
                window.__currentPage=page;
                document.body.style.transform='translateX(-'+(page*cw)+'px)';
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
            .windowInsetsPadding(WindowInsets.systemBars)
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
                windowInsets = WindowInsets(0, 0, 0, 0),
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
        // body * — NOT * — so body itself is never max-width constrained
        // (body must expand freely to hold all CSS columns)
        append("body * { color: #E0E0E0 !important; max-width: 100% !important;")
        append(" box-sizing: border-box !important;")
        append(" overflow-wrap: break-word !important; word-break: break-word !important; }")
        append(" a, a * { color: #7CB9E8 !important; }")
        // html clips to exactly one viewport — overflow:hidden here, NOT on body
        append(" html { height: 100% !important; overflow: hidden !important; }")
        // body expands freely (overflow:visible) so CSS columns can lay out horizontally;
        // exact width/height set in JS via window.innerWidth/innerHeight (no vh/vw)
        append(" body { background: #1A1A1A !important; color: #E0E0E0 !important;")
        append(" margin: 0 !important; padding: 0 !important;")
        append(" overflow: visible !important;")
        append(" column-gap: 0px !important; column-fill: auto !important;")
        append(" font-size: ${fontSize}px !important;")
        append(" font-family: serif !important;")
        append(" line-height: 1.6 !important; }")
        append(" img { color: transparent !important; width: auto !important; height: auto !important;")
        append(" max-height: 80vh !important; object-fit: contain !important; }")
        append(" table { width: 100% !important; }")
        append(" pre, code { white-space: pre-wrap !important; }")
    }

    // w/h passed from Kotlin measured dimensions — never rely on window.innerWidth/Height
    // in JS which can be 0 if onPageFinished fires before the view is laid out.
    fun buildJs(w: Int, h: Int) = """
        (function() {
            // ── Block native scroll (we handle all navigation ourselves) ──────
            document.addEventListener('touchmove', function(e) { e.preventDefault(); }, { passive: false });

            // ── Dimensions from Kotlin layout (always correct) ────────────────
            var w = $w; var h = $h;
            // Store column width globally so all navigation uses the same value
            window.__colW = w;
            // Hard clip at html bounds (overflow:hidden on root propagates to viewport, not a clip)
            document.documentElement.style.setProperty('clip-path', 'inset(0)', 'important');

            // ── Chapter tracking (offsetLeft in column layout) ────────────────
            var chapters = [], i = 0;
            while (true) {
                var el = document.getElementById('chapter-' + i);
                if (!el) break;
                chapters.push(el); i++;
            }
            function currentChapter() {
                var pageX = (window.__currentPage || 0) * w;
                var cur = 0;
                for (var j = 0; j < chapters.length; j++) {
                    if (chapters[j].offsetLeft <= pageX + 10) cur = j; else break;
                }
                return cur;
            }

            // ── Total pages ───────────────────────────────────────────────────
            function reportTotalPages() {
                var total = Math.max(1, Math.ceil(document.body.scrollWidth / w));
                NotiBook.onTotalPages(total);
            }

            // ── Navigate to page (transform body left by page * width) ────────
            function goToPage(page) {
                window.__currentPage = page;
                document.body.style.transform = 'translateX(-' + (page * w) + 'px)';
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
                // Wait until browser has laid out columns (scrollWidth grows beyond one viewport)
                if (document.body.scrollWidth <= w + 5 && targetPage > 0) {
                    setTimeout(tryRestore, 300); return;
                }
                goToPage(targetPage);
                reportTotalPages();
                if (chapters.length > 0) NotiBook.onChapterVisible(currentChapter());
            }
            // Small delay lets the browser finish column layout before we read scrollWidth
            setTimeout(function() {
                tryRestore();
                if (targetPage === 0) reportTotalPages();
            }, 600);
        })();
    """.trimIndent()

    val cssRef = rememberUpdatedState(buildCss())

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
            WebView.setWebContentsDebuggingEnabled(true)
            WebView(ctx).apply {
                webViewRef.value = this
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled   = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                        Log.d("NotiBook_JS", "${msg.messageLevel()} ${msg.message()} [${msg.sourceId()}:${msg.lineNumber()}]")
                        return true
                    }
                }
                settings.apply {
                    javaScriptEnabled   = true
                    allowFileAccess     = true
                    domStorageEnabled   = false
                    builtInZoomControls = false
                    displayZoomControls = false
                    setSupportZoom(false)
                    useWideViewPort     = false   // never use 980px desktop viewport
                    loadWithOverviewMode = false  // never scale page to fit
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
                    @JavascriptInterface fun debugReport(msg: String)      = Log.d("NotiBook", "JS: $msg")
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
                        val escaped = css.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

                        // Use Android-measured dimensions (always reliable) rather than
                        // window.innerWidth/Height which can be 0 if layout hasn't happened yet.
                        val density = view.context.resources.displayMetrics.density
                        val wCss = (view.width  / density).toInt()
                        val hCss = (view.height / density).toInt()

                        // If the view hasn't been measured yet, defer until it is.
                        if (wCss <= 0 || hCss <= 0) {
                            view.post { onPageFinished(view, url) }
                            return
                        }

                        view.evaluateJavascript("""
                            (function(){
                                var s=document.getElementById('__nb_css');
                                if(s) s.textContent='$escaped';
                                // Dimensions from Android layout — guaranteed correct
                                var w=$wCss; var h=$hCss;
                                var de=document.documentElement;
                                de.style.setProperty('width',     w+'px',    'important');
                                de.style.setProperty('height',    h+'px',    'important');
                                de.style.setProperty('overflow',  'hidden',  'important');
                                // clip-path clips rendered output at element bounds —
                                // unlike overflow:hidden on root, it is NOT propagated to viewport
                                de.style.setProperty('clip-path', 'inset(0)', 'important');
                                var b=document.body;
                                b.style.setProperty('margin',       '0',       'important');
                                b.style.setProperty('padding',      '0',       'important');
                                b.style.setProperty('background',   '#1A1A1A', 'important');
                                b.style.setProperty('color',        '#E0E0E0', 'important');
                                b.style.setProperty('width',        w+'px',    'important');
                                b.style.setProperty('height',       h+'px',    'important');
                                b.style.setProperty('overflow',     'visible', 'important');
                                b.style.setProperty('column-width', w+'px',    'important');
                                b.style.setProperty('column-gap',   '0px',     'important');
                                b.style.setProperty('column-fill',  'auto',    'important');
                            })()
                        """.trimIndent(), null)
                        view.evaluateJavascript(buildJs(wCss, hCss), null)
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
        modifier = Modifier
            .padding(top = 60.dp, start = 20.dp, end = 20.dp, bottom = 16.dp)
            .fillMaxSize()
    )
}

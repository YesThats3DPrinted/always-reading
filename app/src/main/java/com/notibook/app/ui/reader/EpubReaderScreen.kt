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
    val isReiniting      by vm.isReiniting.collectAsState()
    val prefs            = vm.readerPreferences

    var fontSize        by remember { mutableIntStateOf(prefs.fontSize) }
    var showMenu        by remember { mutableStateOf(false) }
    var showChapterList by remember { mutableStateOf(false) }

    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // CSS-columns pagination: animate the slide, use __colW for exact column alignment.
    // Also reports data-si of the top element after the animation so the ViewModel always
    // has a text-level anchor for orientation-change restoration.
    LaunchedEffect(currentPageIndex) {
        webViewRef.value?.evaluateJavascript(
            """(function(){
                var cw=window.__colW||window.innerWidth;
                window.__currentPage=$currentPageIndex;
                document.body.style.setProperty('transition','transform 0.25s ease','important');
                document.body.style.transform='translateX(-'+($currentPageIndex*cw)+'px)';
                setTimeout(function(){
                    var el=document.elementFromPoint(cw*0.5,60);
                    for(var i=0;i<8&&el;i++){
                        if(el.dataset&&el.dataset.si!==undefined){NotiBook.onDataSi(parseInt(el.dataset.si));return;}
                        el=el.parentElement;
                    }
                },300);
            })()""",
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
            // Use __getSentenceAtTop (defined in buildJs) which combines caretRangeFromPoint
            // with data-sentences to find the exact sentence at the top of the current page.
            wv.evaluateJavascript("""
                (function(){
                    var si = window.__getSentenceAtTop
                        ? window.__getSentenceAtTop(window.innerWidth * 0.5, 60)
                        : -1;
                    return si >= 0 ? '' + si : '-1';
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
                combinedHtml          = s.combinedHtml,
                baseUrl               = s.baseUrl,
                restorePageIndex      = s.restorePageIndex,
                spineItems            = s.spineItems,
                fontSize              = fontSize,
                onPrevPage            = { vm.onPrevPage() },
                onNextPage            = { vm.onNextPage() },
                onCenterTap           = { vm.onCenterTap() },
                onChapterVisible      = { idx -> vm.onChapterVisible(idx) },
                onScrollToPage        = { page -> vm.onScrollToPage(page) },
                onInternalLink        = { href -> vm.handleInternalLink(href, webViewRef.value) },
                onDataSi              = { si -> vm.onDataSi(si) },
                onReady               = { vm.onReady() },
                onStartReinit         = { vm.startReinit() },
                restoreSentenceIndex  = { vm.restoreSentenceIndex },
                webViewRef            = webViewRef
            )
        }

        // Opaque overlay shown while CSS columns re-initialize after orientation change.
        // Hides the brief reflow animation so the user sees a clean transition.
        if (isReiniting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BG_COLOR)
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
    onChapterVisible: (Int) -> Unit,
    onScrollToPage: (Int) -> Unit,
    onInternalLink: (String) -> Unit,
    onDataSi: (Int) -> Unit,
    onReady: () -> Unit,
    onStartReinit: () -> Unit,
    restoreSentenceIndex: () -> Int,   // lambda so factory always reads latest value
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
        append(" text-align: left !important;")
        append(" font-size: ${fontSize}px !important;")
        append(" font-family: serif !important;")
        append(" line-height: 1.6 !important; }")
        // Use vw/vh (viewport units) not % for max-width/max-height on images.
        // In Android WebView CSS columns, the containing block for elements in
        // overflow columns resolves to 0px, so max-width:100% collapses images to 0×0.
        // Viewport units are always based on the actual viewport size, never 0.
        append(" img { max-width: 100vw !important; width: auto !important;")
        append(" height: auto !important; max-height: 100vh !important;")
        append(" break-inside: avoid !important; page-break-inside: avoid !important; }")
        // Zero out browser default figure margins (40px each side) so images sit flush
        append(" figure { margin: 0 !important; padding: 0 !important; }")
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
            // Store column width and height globally so fixImages and reinit use them
            window.__colW = w;
            window.__colH = h;
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

            // ── End-of-book guard ─────────────────────────────────────────────
            // Returns true if there is a next page by checking whether the sentinel
            // span at the end of the document is currently visible in the viewport.
            // getBoundingClientRect() accounts for the CSS transform on the body,
            // so rect.left reflects the element's actual on-screen position.
            function sentinelVisible() {
                var end = document.getElementById('__nb_end');
                if (!end) return false;
                var rect = end.getBoundingClientRect();
                return rect.left >= 0 && rect.left < w;
            }

            // ── Exact sentence lookup via caretRangeFromPoint + data-sentences ──
            // Returns the exact sentence index at viewport point (x, y).
            // Uses caretRangeFromPoint to get the character position, then walks
            // up the DOM to the nearest block with data-sentences, then uses the
            // sentence lengths in that attribute to find the exact sentence.
            // Falls back to data-si walk if data-sentences is not present.
            window.__getSentenceAtTop = function(x, y) {
                var range = null;
                try {
                    if (document.caretRangeFromPoint) {
                        range = document.caretRangeFromPoint(x, y);
                    } else if (document.caretPositionFromPoint) {
                        var pos = document.caretPositionFromPoint(x, y);
                        if (pos) {
                            range = document.createRange();
                            range.setStart(pos.offsetNode, pos.offset);
                        }
                    }
                } catch(e) {}

                // Walk up to nearest block with data-sentences
                var node = range ? range.startContainer : document.elementFromPoint(x, y);
                var blockEl = (node && node.nodeType !== 1) ? node.parentNode : node;
                var depth = 0;
                while (blockEl && !blockEl.dataset.sentences && depth < 12) {
                    blockEl = blockEl.parentElement; depth++;
                }

                if (!blockEl || !blockEl.dataset.sentences) {
                    // Fallback: walk up looking for data-si
                    var el = document.elementFromPoint(x, y);
                    for (var i = 0; i < 8 && el; i++) {
                        if (el.dataset && el.dataset.si !== undefined) return parseInt(el.dataset.si);
                        el = el.parentElement;
                    }
                    return -1;
                }

                // Get char offset within block using TreeWalker
                var charOffset = 0;
                if (range && range.startContainer) {
                    try {
                        var walker = document.createTreeWalker(
                            blockEl, NodeFilter.SHOW_TEXT, null, false);
                        var tn;
                        while ((tn = walker.nextNode())) {
                            if (tn === range.startContainer) {
                                charOffset += range.startOffset; break;
                            }
                            charOffset += tn.textContent.length;
                        }
                    } catch(e) {}
                }

                // Find sentence at charOffset using data-sentences
                var entries = blockEl.dataset.sentences.split(',');
                var cumLen = 0;
                for (var i = 0; i < entries.length; i++) {
                    var parts = entries[i].split(':');
                    var si = parseInt(parts[0]);
                    var len = parseInt(parts[1]);
                    cumLen += len + 1; // +1 for space between sentences
                    if (charOffset < cumLen) return si;
                }
                return parseInt(entries[entries.length - 1].split(':')[0]);
            };

            // ── data-si tracking ──────────────────────────────────────────────
            // Reports the exact sentence index at the top of the current page.
            function reportDataSi() {
                var si = window.__getSentenceAtTop(w * 0.5, 60);
                if (si >= 0) NotiBook.onDataSi(si);
            }

            // ── Navigate to page instantly (restore/chapter-jump — no animation) ─
            function goToPage(page) {
                window.__currentPage = page;
                document.body.style.setProperty('transition', 'none', 'important');
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
                    if (dx < 0) { if (!sentinelVisible()) NotiBook.onNextPage(); }
                    else        NotiBook.onPrevPage();
                } else if (absDx < TAP_MAX_MOVE && absDy < TAP_MAX_MOVE && dt < TAP_MAX_MS) {
                    var x = touchStartX;
                    if (x < w * 0.3)      NotiBook.onPrevPage();
                    else if (x > w * 0.7) { if (!sentinelVisible()) NotiBook.onNextPage(); }
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
                if (chapters.length > 0) NotiBook.onChapterVisible(currentChapter());
                // Report data-si after page is set so ViewModel has an anchor for re-init
                setTimeout(reportDataSi, 100);
            }
            // Small delay lets the browser finish column layout before we read scrollWidth
            setTimeout(function() { tryRestore(); }, 600);

            // ── Chapter page breaks ───────────────────────────────────────────
            // Force each chapter (except the first) to start on a fresh column.
            // break-before: column is the CSS multi-column equivalent of page-break-before.
            var chapterEls = document.querySelectorAll('[id^="chapter-"]');
            for (var ci = 1; ci < chapterEls.length; ci++) {
                chapterEls[ci].style.setProperty('break-before',      'column', 'important');
                chapterEls[ci].style.setProperty('page-break-before', 'always', 'important');
            }

            // ── Fix image sizing via JS (CSS % and vw unreliable in columns) ───
            // Inline styles set via JS have the highest cascade priority.
            function fixImages() {
                var cw = window.__colW || window.innerWidth || $w;
                var ch = window.__colH || window.innerHeight || $h;
                var imgs = document.querySelectorAll('img');
                console.log('fixImages: found ' + imgs.length + ' imgs, colW=' + cw + ' colH=' + ch);
                for (var i = 0; i < imgs.length; i++) {
                    var img = imgs[i];
                    console.log('img[' + i + '] src=' + img.src.substring(img.src.lastIndexOf('/')+1)
                        + ' natural=' + img.naturalWidth + 'x' + img.naturalHeight
                        + ' complete=' + img.complete);
                    if (img.naturalWidth <= 0) continue;
                    // Always block-level: inline images in CSS columns don't create
                    // a proper layout box and get skipped by the column algorithm.
                    img.style.setProperty('display', 'block', 'important');
                    // Constrain by both width AND height so portrait images don't
                    // overflow the viewport vertically in landscape orientation.
                    var scaleW = Math.min(1, cw / img.naturalWidth);
                    var scaleH = Math.min(1, ch / img.naturalHeight);
                    var scale  = Math.min(scaleW, scaleH);
                    var pw = Math.round(img.naturalWidth  * scale);
                    var ph = Math.round(img.naturalHeight * scale);
                    img.style.setProperty('width',      pw + 'px', 'important');
                    img.style.setProperty('height',     ph + 'px', 'important');
                    img.style.setProperty('max-width',  pw + 'px', 'important');
                    img.style.setProperty('max-height', ph + 'px', 'important');
                }
            }
            // Expose globally so buildReinitJs can call it after orientation change
            window.__fixImages = fixImages;

            // Run fixImages after layout. Total pages is no longer pre-reported —
            // hasNextPage() reads scrollWidth fresh on every navigation attempt.
            setTimeout(function() { fixImages(); }, 800);
            window.addEventListener('load', function() { fixImages(); });
        })();
    """.trimIndent()

    val cssRef = rememberUpdatedState(buildCss())

    // rememberUpdatedState ensures the factory lambda (which runs once) always
    // reads the current callbacks even after recomposition.
    val onDataSiRef       = rememberUpdatedState(onDataSi)
    val onReadyRef        = rememberUpdatedState(onReady)
    val onStartReinitRef  = rememberUpdatedState(onStartReinit)
    val restoreSiRef      = rememberUpdatedState(restoreSentenceIndex)

    // Builds the JS that re-initializes CSS columns after an orientation change.
    // - Sets new viewport dimensions on html/body.
    // - Waits one frame for the browser to reflow columns.
    // - Finds the element with the saved data-si and jumps to its new page.
    // - Re-fixes images and reports new total pages.
    // - Calls NotiBook.onReady() to hide the overlay.
    fun buildReinitJs(w: Int, h: Int, si: Int) = """
        (function(){
            var w=$w; var h=$h;
            window.__colW = w;
            window.__colH = h;
            var de = document.documentElement;
            de.style.setProperty('width',     w+'px',    'important');
            de.style.setProperty('height',    h+'px',    'important');
            de.style.setProperty('clip-path', 'inset(0)', 'important');
            var b = document.body;
            b.style.setProperty('width',        w+'px', 'important');
            b.style.setProperty('height',       h+'px', 'important');
            b.style.setProperty('column-width', w+'px', 'important');
            b.style.setProperty('transition',   'none', 'important');
            // Transform is NOT reset here. We wait for the overlay to be definitely
            // visible (250ms >> one Compose frame), then reset to 0 inside setTimeout
            // so that rects[0].left == layout position (no offset math needed).
            setTimeout(function(){
                // Overlay is covering the screen — safe to reset transform temporarily.
                b.style.transform = 'translateX(0px)';

                var targetPage = 0;
                var restoreSi = $si;

                if (restoreSi >= 0) {
                    // Find the block containing restoreSi via data-sentences
                    var allBlocks = document.querySelectorAll('[data-sentences]');
                    var targetBlock = null, normOffset = 0, normTotal = 0;
                    for (var bi = 0; bi < allBlocks.length && !targetBlock; bi++) {
                        var entries = allBlocks[bi].dataset.sentences.split(',');
                        var cumLen = 0;
                        var blockNormTotal = 0;
                        for (var ei = 0; ei < entries.length; ei++) {
                            var parts = entries[ei].split(':');
                            var len = parseInt(parts[1]);
                            blockNormTotal += len + 1;
                            if (parseInt(parts[0]) === restoreSi) {
                                targetBlock = allBlocks[bi];
                                normOffset = cumLen;   // normalized chars before this sentence
                                normTotal = blockNormTotal; // total normalized chars in block
                            }
                            cumLen += len + 1;
                        }
                        if (targetBlock) normTotal = cumLen;
                    }

                    if (targetBlock) {
                        try {
                            // Map normalized char offset → raw char offset proportionally.
                            // sentenceCharOffset comes from DB (Jsoup-normalized lengths),
                            // but text nodes have raw lengths. Proportional mapping bridges
                            // the gap caused by whitespace normalization differences.
                            var rawLen = targetBlock.textContent.length;
                            var rawOffset = normTotal > 0
                                ? Math.round(normOffset * rawLen / normTotal)
                                : 0;
                            console.log('reinit: si=' + restoreSi
                                + ' normOffset=' + normOffset + '/' + normTotal
                                + ' rawOffset=' + rawOffset + '/' + rawLen
                                + ' blockStart=' + targetBlock.offsetLeft);

                            // Walk text nodes to find the node containing rawOffset
                            var walker = document.createTreeWalker(
                                targetBlock, NodeFilter.SHOW_TEXT, null, false);
                            var cumRaw = 0, rangeNode = null, rangeOff = 0, tn;
                            while ((tn = walker.nextNode())) {
                                var len = tn.textContent.length;
                                if (cumRaw + len > rawOffset) {
                                    rangeNode = tn;
                                    rangeOff = rawOffset - cumRaw;
                                    break;
                                }
                                cumRaw += len;
                            }

                            if (rangeNode) {
                                var range = document.createRange();
                                range.setStart(rangeNode,
                                    Math.min(rangeOff, rangeNode.textContent.length));
                                var rects = range.getClientRects();
                                if (rects && rects.length > 0) {
                                    // Transform is 0, so rects[0].left == layout X directly
                                    targetPage = Math.max(0, Math.floor(rects[0].left / w));
                                    console.log('reinit: rectLeft=' + rects[0].left
                                        + ' targetPage=' + targetPage);
                                } else {
                                    targetPage = Math.floor(targetBlock.offsetLeft / w);
                                }
                            } else {
                                targetPage = Math.floor(targetBlock.offsetLeft / w);
                            }
                        } catch(e) {
                            console.log('reinit error: ' + e);
                            targetPage = Math.floor(targetBlock.offsetLeft / w);
                        }
                    } else {
                        // Fallback: find block by data-si
                        var el = document.querySelector('[data-si="'+restoreSi+'"]');
                        if (el) targetPage = Math.floor(el.offsetLeft / w);
                    }
                }

                window.__currentPage = targetPage;
                b.style.transform = 'translateX(-'+(targetPage*w)+'px)';
                if (window.__fixImages) window.__fixImages();
                NotiBook.onScrollToPage(targetPage);
                NotiBook.onReady();
            }, 250);
        })()
    """.trimIndent()

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
                    // loadDataWithBaseURL with file:// base can get a null security origin
                    // on some Android versions; these flags let it load local EPUB images.
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = true
                }

                addJavascriptInterface(object {
                    @JavascriptInterface fun onPrevPage()                  = onPrevPage()
                    @JavascriptInterface fun onNextPage()                  = onNextPage()
                    @JavascriptInterface fun onCenterTap()                 = onCenterTap()
                    @JavascriptInterface fun onChapterVisible(idx: Int)    = onChapterVisible(idx)
                    @JavascriptInterface fun onScrollToPage(page: Int)     = onScrollToPage(page)
                    @JavascriptInterface fun onInternalLink(href: String)  = onInternalLink(href)
                    @JavascriptInterface fun onDataSi(si: Int)             = onDataSiRef.value(si)
                    @JavascriptInterface fun onReady()                     = onReadyRef.value()
                    @JavascriptInterface fun debugReport(msg: String)      = Log.d("NotiBook", "JS: $msg")
                }, "NotiBook")

                // Detect orientation changes: when the WebView width changes significantly
                // after it has already loaded, trigger a column re-init.
                addOnLayoutChangeListener { _, left, top, right, bottom,
                                            oldLeft, _, oldRight, _ ->
                    val newW = right - left
                    val newH = bottom - top
                    val oldW = oldRight - oldLeft
                    // oldW == 0 means this is the initial layout — skip it.
                    // Only act when the width changes meaningfully (rotation).
                    if (oldW > 0 && Math.abs(newW - oldW) > 20 && newW > 0 && newH > 0) {
                        val density = context.resources.displayMetrics.density
                        val wCss = (newW / density).toInt()
                        val hCss = (newH / density).toInt()
                        val si   = restoreSiRef.value()   // latest data-si from ViewModel
                        onStartReinitRef.value()           // show overlay immediately
                        post {
                            evaluateJavascript(buildReinitJs(wCss, hCss, si), null)
                        }
                    }
                }

                webViewClient = object : WebViewClient() {
                    // Intercept all file:// sub-resource requests (images, CSS, fonts) and
                    // serve bytes directly — bypasses any WebView security origin checks that
                    // might silently block file:// images loaded from a file:// page.
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: android.webkit.WebResourceRequest
                    ): android.webkit.WebResourceResponse? {
                        val url = request.url
                        if (url.scheme == "file") {
                            val path = url.path ?: return null
                            return try {
                                val file = java.io.File(path)
                                if (file.exists() && file.isFile) {
                                    val mime = java.net.URLConnection.guessContentTypeFromName(file.name)
                                        ?: "application/octet-stream"
                                    android.webkit.WebResourceResponse(mime, null, file.inputStream())
                                } else null
                            } catch (_: Exception) { null }
                        }
                        return null
                    }

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
                // EPUB books: combinedHtml is written to a real file and baseUrl is that
                // file's file:// URL — gives a genuine origin so images load correctly.
                // TXT books: combinedHtml is non-empty and loaded via loadDataWithBaseURL.
                if (combinedHtml.isEmpty()) {
                    wv.loadUrl(baseUrl)
                } else {
                    wv.loadDataWithBaseURL(baseUrl, combinedHtml, "text/html", "UTF-8", null)
                }
            }
        },
        modifier = Modifier
            .padding(top = 60.dp, start = 20.dp, end = 20.dp, bottom = 16.dp)
            .fillMaxSize()
    )
}

package com.notibook.app.ui.reader

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notibook.app.R
import com.notibook.app.epub.SpineItem

private val BG_COLOR   = Color(0xFF1A1A1A)
private val TEXT_COLOR = Color(0xFFE0E0E0)
private val BAR_COLOR  = Color(0xFF2C2C2C)

@Composable
fun EpubReaderScreen(
    bookId: Long,
    onClose: () -> Unit,
    vm: EpubReaderViewModel = viewModel()
) {
    LaunchedEffect(bookId) { vm.init(bookId) }

    val state               by vm.state.collectAsState()
    val currentPageIndex    by vm.currentPageIndex.collectAsState()
    val scrollToChapter     by vm.scrollToChapterCommand.collectAsState()
    val topBarVisible       by vm.topBarVisible.collectAsState()
    val isReiniting         by vm.isReiniting.collectAsState()
    val notificationActive  by vm.notificationActive.collectAsState()
    val chapterTitle        by vm.currentChapterTitle.collectAsState()
    val currentChapterIndex    by vm.currentChapterIndex.collectAsState()
    val currentSentenceIndex   by vm.currentSentenceIndex.collectAsState()
    val totalSentences         by vm.totalSentences.collectAsState()
    val prefs                  = vm.readerPreferences

    var fontSize        by remember { mutableIntStateOf(prefs.fontSize) }
    var orientationMode by remember { mutableIntStateOf(prefs.orientationMode) }
    var showChapterList by remember { mutableStateOf(false) }

    // Scrubber drag state — tracks thumb during drag without changing currentPageIndex
    var sliderDragging  by remember { mutableStateOf(false) }
    var sliderDragValue by remember { mutableFloatStateOf(0f) }
    // After the user releases the slider, lock the counter to the sentence they chose.
    // Cleared when a new drag starts so the live preview resumes immediately.
    // This is ONLY for the counter label — sliderDragValue (thumb) is unaffected.
    var lockedDisplaySi by remember { mutableStateOf<Int?>(null) }

    // Keep sliderDragValue in sync with actual position when not actively dragging.
    // This is the ONLY place sliderDragValue is written outside of the drag gesture.
    LaunchedEffect(currentSentenceIndex, totalSentences) {
        if (!sliderDragging && totalSentences > 1) {
            sliderDragValue = currentSentenceIndex.toFloat() / (totalSentences - 1)
        }
    }

    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // Apply/restore orientation lock
    val activity = LocalContext.current as? Activity
    LaunchedEffect(orientationMode) {
        activity?.requestedOrientation = when (orientationMode) {
            0    -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            1    -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            2    -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            3    -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Restore auto-rotate when leaving the reader
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Android 13+ notification permission
    val context = LocalContext.current
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.toggleNotification(true) }

    fun requestNotificationToggle(enable: Boolean) {
        if (!enable) { vm.toggleNotification(false); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) vm.toggleNotification(true)
            else notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.toggleNotification(true)
        }
    }

    // CSS-columns pagination: animate the slide (unless JS already positioned),
    // then report char offset for the orientation-change anchor.
    LaunchedEffect(currentPageIndex) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        val skipAnim = vm.isSkippingPageAnimation()
        val animJs = if (!skipAnim) """
            window.__currentPage=$currentPageIndex;
            document.body.style.setProperty('transition','transform 0.25s ease','important');
            document.body.style.transform='translateX(-'+($currentPageIndex*cw)+'px)';
        """ else ""
        wv.evaluateJavascript("""
            (function(){
                var cw=window.__colW||window.innerWidth;
                $animJs
                setTimeout(function(){
                    var offset=window.__getCharOffset?window.__getCharOffset(cw*0.5,60):0;
                    NotiBook.onCharOffset(offset);
                    var si=window.__getSentenceAtTop?window.__getSentenceAtTop(1,1):0;
                    NotiBook.onCurrentSentence(si);
                },300);
            })()
        """.trimIndent(), null)
    }

    // Jump to chapter
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
            wv.evaluateJavascript("""
                (function(){
                    var w=window.__colW||window.innerWidth;
                    var charOffset=window.__getCharOffset?window.__getCharOffset(w*0.5,60):0;
                    var si=window.__getSentenceAtTop?window.__getSentenceAtTop(1,1):0;
                    return ''+charOffset+'|'+si;
                })()
            """.trimIndent()) { raw ->
                val clean = raw?.trim()?.removeSurrounding("\"") ?: "0|0"
                val parts = clean.split("|")
                val charOffset = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                val si = parts.getOrNull(1)?.toIntOrNull() ?: 0
                vm.onClose(si, charOffset, startNotification)
                onClose()
            }
        } else {
            vm.onClose(0, -1L, startNotification)
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
                baseUrl              = s.baseUrl,
                combinedHtml         = s.combinedHtml,
                restoreCharOffset    = s.restoreCharOffset,
                restoreCurrentIndex  = s.restoreCurrentIndex,
                spineItems           = s.spineItems,
                fontSize             = fontSize,
                onPrevPage           = { vm.onPrevPage() },
                onNextPage           = { vm.onNextPage() },
                onCenterTap          = { vm.onCenterTap() },
                onChapterVisible     = { idx -> vm.onChapterVisible(idx) },
                onScrollToPage       = { page -> vm.onScrollToPage(page) },
                onInternalLink       = { href -> vm.handleInternalLink(href, webViewRef.value) },
                onCharOffset         = { offset -> vm.onCharOffset(offset) },
                onCurrentSentence    = { si -> vm.onCurrentSentence(si) },
                onReady              = { vm.onReady() },
                onStartReinit        = { vm.startReinit() },
                restoreCharOffsetRef = { vm.restoreCharOffset },
                webViewRef           = webViewRef
            )
        }

        // Overlay during column re-init
        if (isReiniting) {
            Box(modifier = Modifier.fillMaxSize().background(BG_COLOR))
        }

        // ── Top bar (two rows, slides in from top) ────────────────────────────
        AnimatedVisibility(
            visible  = topBarVisible,
            enter    = slideInVertically(initialOffsetY = { -it }),
            exit     = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BAR_COLOR)
                    .padding(horizontal = 4.dp)
            ) {
                // Row 1: book title · chapter title
                val titleLine = buildString {
                    append(bookTitle)
                    if (chapterTitle.isNotBlank()) append(" · $chapterTitle")
                }
                Text(
                    text     = titleLine,
                    color    = TEXT_COLOR,
                    style    = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, top = 16.dp, end = 12.dp)
                )

                // Row 2: chapters | orientation | A- A+ | spacer | notification toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier          = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                ) {
                    // Chapter list — highlights current chapter
                    if (spineItems.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { showChapterList = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.FormatListBulleted,
                                    contentDescription = "Chapters",
                                    tint               = TEXT_COLOR
                                )
                            }
                            DropdownMenu(
                                expanded         = showChapterList,
                                onDismissRequest = { showChapterList = false },
                                modifier         = Modifier.background(BAR_COLOR)
                            ) {
                                spineItems.forEachIndexed { index, item ->
                                    val isCurrent = index == currentChapterIndex
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text     = item.chapterTitle.ifBlank { "Chapter ${index + 1}" },
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                color    = if (isCurrent) TEXT_COLOR else TEXT_COLOR.copy(alpha = 0.5f)
                                            )
                                        },
                                        onClick = {
                                            showChapterList = false
                                            vm.scrollToChapter(index)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Orientation picker
                    var showOrientMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showOrientMenu = true }) {
                            Icon(
                                painter = painterResource(
                                    when (orientationMode) {
                                        1    -> R.drawable.ic_orient_portrait_flip
                                        2    -> R.drawable.ic_orient_landscape_left
                                        3    -> R.drawable.ic_orient_landscape_right
                                        else -> R.drawable.ic_orient_portrait
                                    }
                                ),
                                contentDescription = "Orientation",
                                tint = TEXT_COLOR
                            )
                        }
                        DropdownMenu(
                            expanded          = showOrientMenu,
                            onDismissRequest  = { showOrientMenu = false },
                            modifier          = Modifier.background(BAR_COLOR).width(IntrinsicSize.Min)
                        ) {
                            val orientItems = listOf(
                                Triple(0, R.drawable.ic_orient_portrait,       Icons.Default.ArrowUpward),
                                Triple(1, R.drawable.ic_orient_portrait_flip,  Icons.Default.ArrowDownward),
                                Triple(2, R.drawable.ic_orient_landscape_left, Icons.AutoMirrored.Filled.ArrowBack),
                                Triple(3, R.drawable.ic_orient_landscape_right,Icons.AutoMirrored.Filled.ArrowForward)
                            )
                            orientItems.forEach { (mode, iconRes, arrowIcon) ->
                                val itemTint = if (orientationMode == mode) TEXT_COLOR else TEXT_COLOR.copy(alpha = 0.5f)
                                DropdownMenuItem(
                                    text = {},
                                    leadingIcon = {
                                        Row(
                                            verticalAlignment    = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                painter            = painterResource(iconRes),
                                                contentDescription = null,
                                                tint               = itemTint
                                            )
                                            Icon(
                                                imageVector        = arrowIcon,
                                                contentDescription = null,
                                                modifier           = Modifier.size(16.dp),
                                                tint               = itemTint
                                            )
                                        }
                                    },
                                    onClick = {
                                        orientationMode = mode
                                        prefs.orientationMode = mode
                                        showOrientMenu = false
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor        = TEXT_COLOR,
                                        leadingIconColor = TEXT_COLOR
                                    )
                                )
                            }
                        }
                    }

                    // Font size buttons — grouped close together
                    TextButton(
                        onClick        = {
                            if (fontSize > ReaderPreferences.FONT_SIZE_RANGE.first) {
                                fontSize -= 2; vm.setFontSize(fontSize)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "A−",
                            color = TEXT_COLOR.copy(
                                alpha = if (fontSize > ReaderPreferences.FONT_SIZE_RANGE.first) 1f else 0.35f
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(
                        onClick        = {
                            if (fontSize < ReaderPreferences.FONT_SIZE_RANGE.last) {
                                fontSize += 2; vm.setFontSize(fontSize)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "A+",
                            color = TEXT_COLOR.copy(
                                alpha = if (fontSize < ReaderPreferences.FONT_SIZE_RANGE.last) 1f else 0.35f
                            ),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Notification toggle — full TEXT_COLOR when on, dim when off
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (notificationActive)
                                    TEXT_COLOR.copy(alpha = 0.15f)
                                else
                                    Color.Transparent
                            )
                            .clickable { requestNotificationToggle(!notificationActive) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter            = painterResource(R.drawable.ic_notification_reader),
                            contentDescription = if (notificationActive) "Disable notification" else "Enable notification",
                            modifier           = Modifier.size(22.dp),
                            tint               = if (notificationActive) TEXT_COLOR else TEXT_COLOR.copy(alpha = 0.35f)
                        )
                    }
                }
            }
        }

        // ── Bottom bar (scrubber, slides in from bottom at same time as top bar) ─
        AnimatedVisibility(
            visible  = topBarVisible,
            enter    = slideInVertically(initialOffsetY = { it }),
            exit     = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BAR_COLOR)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // Sentence counter label.
                // During drag: live preview from sliderDragValue.
                // After release: locked to the user's chosen sentence (lockedDisplaySi) so
                //   the counter doesn't jump to the actual top-of-page sentence on navigation.
                // Otherwise: the confirmed currentSentenceIndex from the last page turn.
                val displayIndex = when {
                    sliderDragging   -> (sliderDragValue * max(1, totalSentences - 1)).roundToInt()
                    lockedDisplaySi != null -> lockedDisplaySi!!
                    else             -> currentSentenceIndex
                }
                val sentenceDisplay = if (totalSentences > 0)
                    "Sentence ${"%,d".format(displayIndex + 1)} of ${"%,d".format(totalSentences)}"
                else ""
                if (sentenceDisplay.isNotEmpty()) {
                    Text(
                        text     = sentenceDisplay,
                        color    = TEXT_COLOR.copy(alpha = 0.7f),
                        style    = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                // Sentence scrubber — position and navigation are sentence-index based,
                // no page count or scrollWidth involved.
                // sliderDragValue is the single source of truth: the LaunchedEffect above
                // syncs it from currentSentenceIndex when not dragging.
                Slider(
                    value = sliderDragValue,
                    onValueChange = { v ->
                        lockedDisplaySi = null   // clear lock so live preview shows during drag
                        sliderDragging  = true
                        sliderDragValue = v
                    },
                    onValueChangeFinished = {
                        val targetSi = (sliderDragValue * max(1, totalSentences - 1)).roundToInt()
                        lockedDisplaySi = targetSi  // hold counter at chosen sentence after release
                        sliderDragging = false
                        webViewRef.value?.evaluateJavascript("""
                            (function(){
                                var targetSi = $targetSi;
                                var els = document.querySelectorAll('[data-si]');
                                if (els.length === 0) return;
                                // Binary search: find the last marker with data-si <= targetSi
                                var lo = 0, hi = els.length - 1, el = els[0];
                                while (lo <= hi) {
                                    var mid = (lo + hi) >> 1;
                                    if (parseInt(els[mid].dataset.si) <= targetSi) {
                                        el = els[mid]; lo = mid + 1;
                                    } else {
                                        hi = mid - 1;
                                    }
                                }
                                var r = document.createRange();
                                r.setStart(document.body, 0);
                                r.setEnd(el, 0);
                                var co = r.toString().replace(/\s+/g,' ').length;
                                if (window.__restoreByCharOffset) window.__restoreByCharOffset(co);
                            })()
                        """.trimIndent(), null)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor         = TEXT_COLOR,
                        activeTrackColor   = TEXT_COLOR,
                        inactiveTrackColor = TEXT_COLOR.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ── WebView content ───────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReaderContent(
    baseUrl: String,
    combinedHtml: String,
    restoreCharOffset: Long,
    restoreCurrentIndex: Int,
    @Suppress("UNUSED_PARAMETER") spineItems: List<SpineItem>,
    fontSize: Int,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onCenterTap: () -> Unit,
    onChapterVisible: (Int) -> Unit,
    onScrollToPage: (Int) -> Unit,
    onInternalLink: (String) -> Unit,
    onCharOffset: (Long) -> Unit,
    onCurrentSentence: (Int) -> Unit,
    onReady: () -> Unit,
    onStartReinit: () -> Unit,
    restoreCharOffsetRef: () -> Long,
    webViewRef: MutableState<WebView?>
) {
    fun buildCss() = buildString {
        append("body * { color: #E0E0E0 !important; max-width: 100% !important;")
        append(" box-sizing: border-box !important;")
        append(" overflow-wrap: break-word !important; word-break: break-word !important; }")
        append(" a, a * { color: #7CB9E8 !important; }")
        append(" html { height: 100% !important; overflow: hidden !important; }")
        append(" body { background: #1A1A1A !important; color: #E0E0E0 !important;")
        append(" margin: 0 !important; padding: 0 !important;")
        append(" overflow: visible !important;")
        append(" column-gap: 0px !important; column-fill: auto !important;")
        append(" text-align: left !important;")
        append(" font-size: ${fontSize}px !important;")
        append(" font-family: serif !important;")
        append(" line-height: 1.6 !important; }")
        append(" img { max-width: 100vw !important; width: auto !important;")
        append(" height: auto !important; max-height: 100vh !important;")
        append(" break-inside: avoid !important; page-break-inside: avoid !important; }")
        append(" figure { margin: 0 !important; padding: 0 !important; }")
        append(" table { width: 100% !important; }")
        append(" pre, code { white-space: pre-wrap !important; }")
    }

    fun buildJs(w: Int, h: Int) = """
        (function() {
            document.addEventListener('touchmove', function(e) { e.preventDefault(); }, { passive: false });

            var w = $w; var h = $h;
            window.__colW = w;
            window.__colH = h;
            document.documentElement.style.setProperty('clip-path', 'inset(0)', 'important');

            // ── Chapter tracking ──────────────────────────────────────────────
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

            // ── End-of-book sentinel ──────────────────────────────────────────
            function sentinelVisible() {
                var end = document.getElementById('__nb_end');
                if (!end) return false;
                var rect = end.getBoundingClientRect();
                return rect.left >= 0 && rect.left < w;
            }

            // ── Navigate to page without animation ────────────────────────────
            // Always reads window.__colW so post-reinit calls use the updated width.
            function goToPage(page) {
                window.__currentPage = page;
                var cw = window.__colW || w;
                document.body.style.setProperty('transition', 'none', 'important');
                document.body.style.transform = 'translateX(-' + (page * cw) + 'px)';
            }

            // ── Character offset — save position ──────────────────────────────
            window.__getCharOffset = function(x, y) {
                try {
                    var caret = null;
                    if (document.caretRangeFromPoint) {
                        caret = document.caretRangeFromPoint(x, y);
                    } else if (document.caretPositionFromPoint) {
                        var pos = document.caretPositionFromPoint(x, y);
                        if (pos) { caret = document.createRange(); caret.setStart(pos.offsetNode, pos.offset); }
                    }
                    if (!caret) return 0;
                    var r = document.createRange();
                    r.setStart(document.body, 0);
                    r.setEnd(caret.startContainer, caret.startOffset);
                    return r.toString().replace(/\s+/g, ' ').length;
                } catch(e) { return 0; }
            };

            // ── Character offset — restore position ───────────────────────────
            window.__restoreByCharOffset = function(targetOffset) {
                if (targetOffset <= 0) { goToPage(0); NotiBook.onScrollToPage(0); return; }
                try {
                    var rawText = document.body.textContent;
                    var normLen = 0, rawIdx = 0, inWs = false;
                    while (rawIdx < rawText.length && normLen < targetOffset) {
                        var isWs = /\s/.test(rawText[rawIdx]);
                        if (isWs) { if (!inWs) { normLen++; inWs = true; } }
                        else      { normLen++; inWs = false; }
                        rawIdx++;
                    }
                    var walker = document.createTreeWalker(
                        document.body, NodeFilter.SHOW_TEXT, null, false);
                    var cumRaw = 0, tn;
                    while ((tn = walker.nextNode())) {
                        var len = tn.textContent.length;
                        if (cumRaw + len > rawIdx) {
                            var localOff = rawIdx - cumRaw;
                            // Reset to absolute baseline so getClientRects() returns
                            // natural layout positions, not viewport-relative ones.
                            // All of this is synchronous — the browser won't repaint
                            // until after goToPage() sets the final transform.
                            document.body.style.setProperty('transition', 'none', 'important');
                            document.body.style.transform = 'translateX(0)';
                            var range = document.createRange();
                            var clampedOff = Math.min(localOff, len);
                            range.setStart(tn, clampedOff);
                            range.setEnd(tn, Math.min(clampedOff + 1, len));
                            var rects = range.getClientRects();
                            var cw2 = window.__colW || w;
                            var page;
                            if (rects && rects.length > 0) {
                                page = Math.max(0, Math.floor(rects[0].left / cw2));
                            } else {
                                page = Math.max(0, Math.floor((tn.parentElement ? tn.parentElement.offsetLeft : 0) / cw2));
                            }
                            goToPage(page);
                            NotiBook.onScrollToPage(page);
                            return;
                        }
                        cumRaw += len;
                    }
                } catch(e) { console.log('NotiBook restoreByCharOffset error: ' + e); }
            };

            // ── Sentence at top ───────────────────────────────────────────────
            // Finds the first visible character at (x, y), then walks backwards
            // through the DOM from that position until it hits a [data-si] marker.
            // This correctly handles long sentences that span multiple pages:
            // the marker will be somewhere before the current position in the DOM,
            // and backwards traversal always finds it regardless of where it renders.
            window.__getSentenceAtTop = function(x, y) {
                var range = document.caretRangeFromPoint
                    ? document.caretRangeFromPoint(x, y)
                    : (document.caretPositionFromPoint
                        ? (function(){ var p=document.caretPositionFromPoint(x,y); if(!p) return null; var r=document.createRange(); r.setStart(p.offsetNode,p.offset); return r; })()
                        : null);
                if (!range) return 0;

                // Walk backwards from the caret node through the DOM
                var node = range.startContainer;
                // Start from the caret's position within its text node — step back
                // into the preceding sibling/ancestor chain
                while (node) {
                    // Check this node itself if it's an element with data-si
                    if (node.nodeType === 1 && node.dataset && node.dataset.si !== undefined) {
                        return parseInt(node.dataset.si);
                    }
                    // Walk to previous sibling, descending into its last descendant
                    if (node.previousSibling) {
                        node = node.previousSibling;
                        while (node.lastChild) node = node.lastChild;
                    } else {
                        node = node.parentNode;
                    }
                }
                return 0;
            };

            // ── Touch handling ────────────────────────────────────────────────
            var touchStartX = 0, touchStartY = 0, touchStartTime = 0;
            var SWIPE_THRESHOLD = 50, TAP_MAX_MOVE = 10, TAP_MAX_MS = 300;

            document.addEventListener('touchstart', function(e) {
                touchStartX = e.touches[0].clientX;
                touchStartY = e.touches[0].clientY;
                touchStartTime = Date.now();
            }, { passive: true });

            document.addEventListener('touchend', function(e) {
                var dx = e.changedTouches[0].clientX - touchStartX;
                var dy = e.changedTouches[0].clientY - touchStartY;
                var dt = Date.now() - touchStartTime;
                var absDx = Math.abs(dx), absDy = Math.abs(dy);
                if (absDx > SWIPE_THRESHOLD && absDx > absDy) {
                    if (dx < 0) { if (!sentinelVisible()) NotiBook.onNextPage(); }
                    else        NotiBook.onPrevPage();
                } else if (absDx < TAP_MAX_MOVE && absDy < TAP_MAX_MOVE && dt < TAP_MAX_MS) {
                    var x = touchStartX;
                    var cw = window.__colW || $w;
                    if (x < cw * 0.3)      NotiBook.onPrevPage();
                    else if (x > cw * 0.7) { if (!sentinelVisible()) NotiBook.onNextPage(); }
                    else                    NotiBook.onCenterTap();
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

            // ── Chapter page breaks ───────────────────────────────────────────
            var chapterEls = document.querySelectorAll('[id^="chapter-"]');
            for (var ci = 1; ci < chapterEls.length; ci++) {
                chapterEls[ci].style.setProperty('break-before',      'column', 'important');
                chapterEls[ci].style.setProperty('page-break-before', 'always', 'important');
            }

            // ── Image sizing ──────────────────────────────────────────────────
            function fixImages() {
                var cw = window.__colW || window.innerWidth || $w;
                var ch = window.__colH || window.innerHeight || $h;
                var imgs = document.querySelectorAll('img');
                for (var i = 0; i < imgs.length; i++) {
                    var img = imgs[i];
                    if (img.naturalWidth <= 0) continue;
                    img.style.setProperty('display', 'block', 'important');
                    var scale = Math.min(Math.min(1, cw / img.naturalWidth), Math.min(1, ch / img.naturalHeight));
                    var pw = Math.round(img.naturalWidth * scale);
                    var ph = Math.round(img.naturalHeight * scale);
                    img.style.setProperty('width',      pw + 'px', 'important');
                    img.style.setProperty('height',     ph + 'px', 'important');
                    img.style.setProperty('max-width',  pw + 'px', 'important');
                    img.style.setProperty('max-height', ph + 'px', 'important');
                }
            }
            window.__fixImages = fixImages;
            setTimeout(function() { fixImages(); }, 800);
            window.addEventListener('load', function() { fixImages(); });

            // ── Restore position on initial load ──────────────────────────────
            var targetCharOffset = $restoreCharOffset;
            var targetSi         = $restoreCurrentIndex;
            window.__currentPage = 0;

            function tryRestore() {
                if (document.body.scrollWidth <= w + 5 && (targetCharOffset > 0 || targetSi > 0)) {
                    setTimeout(tryRestore, 300); return;
                }
                if (targetCharOffset >= 0) {
                    window.__restoreByCharOffset(targetCharOffset);
                } else if (targetSi > 0) {
                    var el = document.querySelector('[data-si="' + targetSi + '"]');
                    if (el) {
                        var r = document.createRange();
                        r.setStart(document.body, 0);
                        r.setEnd(el, 0);
                        var co = r.toString().replace(/\s+/g, ' ').length;
                        window.__restoreByCharOffset(co);
                    }
                }
                if (chapters.length > 0) NotiBook.onChapterVisible(currentChapter());
                setTimeout(function(){
                    var offset = window.__getCharOffset ? window.__getCharOffset(1, 1) : 0;
                    NotiBook.onCharOffset(offset);
                    var si = window.__getSentenceAtTop ? window.__getSentenceAtTop(1, 1) : 0;
                    NotiBook.onCurrentSentence(si);
                }, 200);
            }
            setTimeout(tryRestore, 600);
        })();
    """.trimIndent()

    fun buildReinitJs(w: Int, h: Int, charOffset: Long) = """
        (function(){
            var w=$w; var h=$h;
            window.__reinitId = (window.__reinitId || 0) + 1;
            var myId = window.__reinitId;

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

            setTimeout(function(){
                if (window.__reinitId !== myId) return;
                b.style.transform = 'translateX(0px)';

                requestAnimationFrame(function(){
                    if (window.__reinitId !== myId) return;
                    requestAnimationFrame(function(){
                        if (window.__reinitId !== myId) return;

                        var targetPage = 0;
                        var targetOffset = $charOffset;

                        if (targetOffset >= 0) {
                            try {
                                var rawText = b.textContent;
                                var normLen = 0, rawIdx = 0, inWs = false;
                                while (rawIdx < rawText.length && normLen < targetOffset) {
                                    var isWs = /\s/.test(rawText[rawIdx]);
                                    if (isWs) { if (!inWs) { normLen++; inWs = true; } }
                                    else       { normLen++; inWs = false; }
                                    rawIdx++;
                                }
                                var walker = document.createTreeWalker(b, NodeFilter.SHOW_TEXT, null, false);
                                var cumRaw = 0, tn;
                                while ((tn = walker.nextNode())) {
                                    var len = tn.textContent.length;
                                    if (cumRaw + len > rawIdx) {
                                        var localOff = rawIdx - cumRaw;
                                        var range = document.createRange();
                                        range.setStart(tn, Math.min(localOff, len));
                                        var rects = range.getClientRects();
                                        if (rects && rects.length > 0) {
                                            targetPage = Math.max(0, Math.floor(rects[0].left / w));
                                        } else {
                                            var pel = tn.parentElement;
                                            if (pel) targetPage = Math.max(0, Math.floor(pel.offsetLeft / w));
                                        }
                                        break;
                                    }
                                    cumRaw += len;
                                }
                            } catch(e) { console.log('NotiBook reinit error: ' + e); }
                        }

                        window.__currentPage = targetPage;
                        b.style.transform = 'translateX(-' + (targetPage * w) + 'px)';
                        NotiBook.onScrollToPage(targetPage);

                        if (window.__fixImages) window.__fixImages();

                        requestAnimationFrame(function(){
                            if (window.__reinitId !== myId) return;
                            NotiBook.onReady();
                        });
                    });
                });
            }, 250);
        })()
    """.trimIndent()

    val cssRef           = rememberUpdatedState(buildCss())
    val onCharOffsetRef       = rememberUpdatedState(onCharOffset)
    val onCurrentSentenceRef  = rememberUpdatedState(onCurrentSentence)
    val onReadyRef       = rememberUpdatedState(onReady)
    val onStartReinitRef = rememberUpdatedState(onStartReinit)
    val restoreCharRef   = rememberUpdatedState(restoreCharOffsetRef)

    LaunchedEffect(fontSize) {
        val escaped = cssRef.value
            .replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        webViewRef.value?.evaluateJavascript(
            "(function(){var s=document.getElementById('__nb_css');if(s)s.textContent='$escaped';})()",
            null
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
                    javaScriptEnabled    = true
                    allowFileAccess      = true
                    domStorageEnabled    = false
                    builtInZoomControls  = false
                    displayZoomControls  = false
                    setSupportZoom(false)
                    useWideViewPort      = false
                    loadWithOverviewMode = false
                    @Suppress("DEPRECATION") allowFileAccessFromFileURLs          = true
                    @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs     = true
                }

                addJavascriptInterface(object {
                    @JavascriptInterface fun onPrevPage()                 = onPrevPage()
                    @JavascriptInterface fun onNextPage()                 = onNextPage()
                    @JavascriptInterface fun onCenterTap()                = onCenterTap()
                    @JavascriptInterface fun onChapterVisible(idx: Int)   = onChapterVisible(idx)
                    @JavascriptInterface fun onScrollToPage(page: Int)    = onScrollToPage(page)
                    @JavascriptInterface fun onInternalLink(href: String) = onInternalLink(href)
                    @JavascriptInterface fun onCharOffset(offset: Long)      = onCharOffsetRef.value(offset)
                    @JavascriptInterface fun onCurrentSentence(si: Int)     = onCurrentSentenceRef.value(si)
                    @JavascriptInterface fun onReady()                    = onReadyRef.value()
                    @JavascriptInterface fun debugReport(msg: String)     = Log.d("NotiBook", "JS: $msg")
                }, "NotiBook")

                addOnLayoutChangeListener { _, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom ->
                    val newW = right  - left
                    val newH = bottom - top
                    val oldW = oldRight  - oldLeft
                    val oldH = oldBottom - oldTop
                    val widthChanged  = oldW > 0 && Math.abs(newW - oldW) > 1 && newW > 0 && newH > 0
                    val heightChanged = oldH > 0 && Math.abs(newH - oldH) > 1 && newW > 0 && newH > 0
                    if (widthChanged || heightChanged) {
                        val density = context.resources.displayMetrics.density
                        val wCss    = (newW / density).toInt()
                        val hCss    = (newH / density).toInt()
                        val offset  = restoreCharRef.value()
                        onStartReinitRef.value()
                        post { evaluateJavascript(buildReinitJs(wCss, hCss, offset), null) }
                    }
                }

                webViewClient = object : WebViewClient() {
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
                        val density = view.context.resources.displayMetrics.density
                        val wCss = (view.width  / density).toInt()
                        val hCss = (view.height / density).toInt()
                        if (wCss <= 0 || hCss <= 0) { view.post { onPageFinished(view, url) }; return }

                        view.evaluateJavascript("""
                            (function(){
                                var s=document.getElementById('__nb_css');
                                if(s) s.textContent='$escaped';
                                var w=$wCss; var h=$hCss;
                                var de=document.documentElement;
                                de.style.setProperty('width',     w+'px',    'important');
                                de.style.setProperty('height',    h+'px',    'important');
                                de.style.setProperty('overflow',  'hidden',  'important');
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
                if (combinedHtml.isEmpty()) wv.loadUrl(baseUrl)
                else wv.loadDataWithBaseURL(baseUrl, combinedHtml, "text/html", "UTF-8", null)
            }
        },
        // WebView fills the full screen — both bars float as overlays on top of it.
        // No top/bottom padding so text uses the full available height.
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxSize()
    )
}

# NotiBook — Android Ebook Reader via Persistent Notifications

## What This App Does

NotiBook lets you read books passively throughout your day. Instead of sitting down and opening a reading app, you get one sentence at a time delivered as a persistent notification. You can tap the arrows in the notification to go forward or back, and tap the title to open the full in-app reader whenever you want to read more actively.

**Supported formats:** EPUB, TXT
**Target devices:** Android 7+ (API 24+)

---

## High-Level Architecture

The app has four main concerns:

1. **Parsing** — when you import a book, it gets split into individual sentences and stored in a database
2. **Notifications** — sentences are shown one at a time in a persistent foreground-service notification with ← → navigation
3. **Reader** — a full in-app WebView reader for when you want to read continuously
4. **Sync** — when you close the reader, your position is saved and the notification picks up exactly where you left off

---

## File-by-File Breakdown

### Notifications (`notification/`)

**`NotificationHelper.kt`**
The one place responsible for building and showing notifications. It reads the current sentence from the DB and constructs a custom notification layout. Each book gets its own notification ID (using `bookId.toInt()`) so multiple books can have active notifications simultaneously. Checks dark/light mode at build time and sets text colors accordingly, since custom notification layouts don't get automatic theming.

`show()` starts `ReadingNotificationService` as a foreground service before posting the notification. This makes the notification `setOngoing(true)` — non-swipeable and always expanded.

`hide()` cancels the notification AND stops the foreground service. Callers are responsible for only calling `hide()` on the last active notification (otherwise pass `stopService = false`).

**`NotificationActionReceiver.kt`**
Listens for button taps inside the notification (← →, dismiss). Uses `goAsync()` so it can do database work without timing out. After a successful NEXT or PREV navigation, calls `repo.clearReaderCharOffset(bookId)` — this signals the reader to open at the notification's current sentence rather than the old char-offset position.

**`ReadingNotificationService.kt`**
Foreground service that keeps reading notifications alive and non-swipeable. Started by `NotificationHelper.show()`, stopped by `NotificationHelper.hide()`. Returns `START_STICKY` so Android restarts it if killed.

**`BootReceiver.kt`**
When the phone restarts, Android kills all notifications. This receiver fires on boot and re-posts any notifications that were active when the phone was shut down.

---

### Book Parsing (`parsing/`)

When you import a book, a background WorkManager job runs the full parsing pipeline.

**`EpubParser.kt`**
Reads the EPUB file and extracts text from each chapter in spine order. Respects paragraph boundaries.

**`TxtParser.kt`**
Reads a plain text file and splits it into paragraphs on double newlines (`\n\n`).

**`SentenceSplitter.kt`**
Takes paragraphs and breaks them into individual sentences using Android's ICU `BreakIterator`. Then does two passes of cleanup:
- **Merge false boundaries** — if a sentence ends with `Mr.` or `1.` or `III.` etc., it gets merged with the next one because ICU wrongly treated the period as a sentence end
- **Split long sentences** — anything over 250 characters gets split into equal-sized chunks so no notification text is absurdly long. Non-last chunks get a trailing `" …"`.

**`ParseWorker.kt`**
The WorkManager task that ties it all together: calls the parser, calls the splitter, writes every sentence to the database with its index, spine item, and block position.

---

### EPUB Processing (`epub/`)

These files handle turning a raw `.epub` file into something the reader can display.

**`EpubExtractor.kt`**
An EPUB is just a ZIP file. This unzips it into `cache/epub_<bookId>/` preserving the original directory structure, so all the relative paths inside the HTML files (images, CSS, fonts) still resolve correctly. Uses `canonicalPath` everywhere — important because Android has a symlink situation where `/data/user/0/` and `/data/data/` point to the same place but are different strings. Always use canonical to avoid mismatches.

**`EpubSpineReader.kt`**
Reads `content.opf` inside the extracted EPUB to find the spine — the ordered list of HTML files that make up the book. Returns a list of `SpineItem` objects.

**`SpineItem.kt`**
Simple data class: chapter title + absolute path to the HTML file.

**`EpubCombiner.kt`**
Takes all the spine items and merges them into one big HTML document. For each chapter:
- Wraps it in `<div id="chapter-N">` so we can jump to chapters by ID
- Converts all relative `src` and `href` attributes to absolute `file://` paths so images and links work when loaded from cache
- Injects an empty `<span data-si="N"></span>` marker at the exact character offset where each sentence begins, using `TextNode.splitText()` so inline formatting (`<em>`, `<strong>`, links) is fully preserved. This gives sentence-level position tracking — not just block-level.
- Adds `<span id="__nb_end"></span>` at the very end of the document — the end-of-book sentinel

The combined HTML is written to `__reader.html` in the cache dir and loaded via a `file://` URL.

`buildTxtHtml()` in `EpubReaderViewModel` does the same `<span data-si>` injection for TXT files, inserting markers at the correct character positions within each paragraph.

---

### In-App Reader (`ui/reader/`)

**`ReaderPreferences.kt`**
Simple SharedPreferences wrapper that stores the user's font size preference (12–28sp, step 2).

**`EpubReaderViewModel.kt`**
Manages all reader state. Key responsibilities:

- **Loading** — calls EpubExtractor + EpubCombiner to prepare the HTML, then emits a `Ready` state with the file URL and restore parameters
- **Page tracking** — maintains `currentPageIndex` as a StateFlow. Kotlin only ever increments or decrements this number — JS is responsible for deciding when it's allowed
- **Character offset anchor** — maintains `_restoreCharOffset`, the exact normalized character count from the start of the book to the top of the current page. Updated by JS after every page turn via `onCharOffset(offset: Long)`. Used to restore position after an orientation change or on next reader open.
- **Orientation re-init** — `startReinit()` shows an opaque overlay and sets `skipNextCharOffsetUpdate = true` (so the first post-reflow `onCharOffset` call doesn't overwrite the anchor). `onReady()` hides the overlay once JS finishes re-laying out.
- **Notification toggle** — `notificationActive: StateFlow<Boolean>` observed from DB. `toggleNotification(enabled)` starts/stops the notification.
- **Closing** — `onClose(sentenceIndex, charOffset, startNotification)` saves charOffset to DB (for next reader open) and sentenceIndex to DB (for the notification to display). Optionally starts the notification.
- **Skip animation** — `skipNextPageAnimation: AtomicBoolean`. Set by `onScrollToPage()` so `LaunchedEffect(currentPageIndex)` skips the slide animation when JS already positioned the view (during restore, chapter jump, internal link).

**`EpubReaderScreen.kt`**
The actual UI. A WebView fills most of the screen, with an animated two-row top bar.

**Top bar (two rows):**
- Row 1: book title · chapter title (single line, ellipsis)
- Row 2: chapter list | orientation | A− A+ | spacer | notification toggle

The notification toggle icon (`ic_notification_reader.xml`) is full opacity when active, 35% opacity when inactive. Tapping it toggles the notification; on Android 13+ it requests `POST_NOTIFICATIONS` permission if not already granted.

The screen is organized around several pieces of JS injected at different times:

**At page load (`onPageFinished`):**
Sets up the CSS column layout. The body is made exactly one viewport wide and tall, and `column-width` is set to the same value — so the browser lays out content as side-by-side columns, each one screen wide. Dimensions come from Kotlin's measured view size (always reliable) not `window.innerWidth` (can be 0 at load time).

**`buildJs(w, h)` — the main JS, injected once after load:**

- Blocks all native touch scrolling (we handle all navigation ourselves)
- Stores `window.__colW` and `window.__colH` globally
- **`sentinelVisible()`** — end-of-book check. `getBoundingClientRect()` on `<span id="__nb_end">`. If that span's left edge is currently on screen, we're on the last page.
- **Touch handling** — swipe left/right and tap left/right/center zones. Checks `sentinelVisible()` before going forward.
- **`window.__getCharOffset(x, y)`** — counts normalized characters (whitespace collapsed with `replace(/\s+/g,' ')`) from the start of the document body to the caret at `(x, y)`. Uses `caretRangeFromPoint` + `Range.toString()`. This number is stable across font size, orientation, and viewport changes.
- **`window.__restoreByCharOffset(targetOffset)`** — inverse of `__getCharOffset`. Scans `document.body.textContent` character-by-character simulating the same whitespace normalization to find the raw DOM offset, then uses TreeWalker to find the text node, then `getClientRects()[0].left / colW` to find the column. Calls `NotiBook.onScrollToPage(page)`.
- **`window.__getSentenceAtTop(x, y)`** — scans all `[data-si]` marker spans via `querySelectorAll`, finds the one with the highest sentence index whose bounding rect is on the current visible column (`rect.left` within `[-1, colW+1]`) and at or above `y`. Returns that sentence index. Used on every page turn (to update the live counter) and on close (to save the notification position). More accurate than a DOM walk-up because markers are sentence-level, not block-level.
- **`tryRestore()`** — restores position on first load. If `readerCharOffset >= 0`, calls `__restoreByCharOffset` (last action was in-reader). If `-1`, navigates to `data-si == restoreCurrentIndex` element (last action was notification). Polls `scrollWidth > viewport` before navigating so columns are fully laid out.
- **`fixImages()`** — sets explicit pixel dimensions on `<img>` elements. CSS % and viewport units don't work reliably inside CSS columns. Exposed as `window.__fixImages` for reinit use.
- **Chapter tracking** — after every page turn checks which chapter's `<div>` the current page is inside and reports it via `onChapterVisible()`.
- **Chapter page breaks** — `break-before: column` on each `<div id="chapter-N">` so chapters always start on a fresh page.

**`buildReinitJs(w, h, charOffset)` — injected when orientation/size changes:**
1. Updates `window.__colW`, `window.__colH`, and CSS column dimensions
2. Waits 250ms (overlay is visible)
3. Resets the body's transform to 0
4. Calls `window.__restoreByCharOffset(charOffset)` — navigates to the saved char offset
5. Calls `window.__fixImages()` and `NotiBook.onReady()` to hide overlay

**`LaunchedEffect(currentPageIndex)`:**
Animates to the new page (unless `isSkippingPageAnimation()` is true — set by JS-driven navigation). After 300ms, calls `__getCharOffset(cw*0.5, 60)` and reports via `NotiBook.onCharOffset()`.

**`addOnLayoutChangeListener`:**
Fires on layout changes (rotation, split screen, PiP). Threshold: any width **or** height change > 1px. The small threshold catches all real layout changes (rotation, split screen entering/exiting, divider drag) without false positives since nothing else changes the WebView dimensions.

**Chapter jumps (LaunchedEffect on `scrollToChapterCommand`):**
Finds the chapter's `<div>`, computes `offsetLeft / colW`, navigates there.

**`handleInternalLink()` in ViewModel:**
Intercepts link taps inside the book, finds the target element (by spine index + fragment ID), and navigates to its column.

**Bottom bar:**
Sentence counter label ("Sentence X of Y") above a horizontal scrubber slider. The counter updates live on every page turn and slider drag via `onCurrentSentence`. The slider navigates by CSS column page; the label shows sentence position independently.

**JS bridge methods:**
- `onPrevPage()`, `onNextPage()`, `onCenterTap()`, `onChapterVisible(idx)`, `onScrollToPage(page)`, `onInternalLink(href)` — standard navigation
- `onCharOffset(offset: Long)` — called after page turns and after restore; updates the ViewModel's char offset anchor
- `onCurrentSentence(si: Int)` — called after every page turn, slider drag, `tryRestore`, and reinit with the sentence index at the top of the screen; updates the live bottom-bar counter and is used by `closeReader` for the notification position
- `onReady()` — called by `buildReinitJs` when re-init is complete; hides the overlay

---

### Database (`data/db/`)

Room ORM. Two tables:

**`books`**
One row per imported book. Key fields:
- `currentIndex` — which sentence the notification is currently showing
- `currentChapter` — chapter title, stored so the notification can display it
- `readerCharOffset` — exact character offset saved when the reader closes. `-1` means "use notification's currentIndex on next reader open" (set by `NotificationActionReceiver` after each NEXT/PREV). `>= 0` means restore to this exact character position.
- `notificationActive` — whether this book's notification is currently showing
- `isParsing` — true while the parse worker is running; library card is non-clickable while true

**`sentences`**
One row per sentence. Key fields:
- `sentenceIndex` — global 0-based index across the whole book
- `spineItemIndex` — which chapter (spine item) this sentence is in
- `blockIndex` — which block element within that chapter (used to match sentences back to HTML elements for `data-si` annotation)
- `type` — SENTENCE, IMAGE, TABLE, or DIVIDER

**Schema version: 4**

---

### UI (`ui/`)

**`LibraryScreen.kt`**
Shows all imported books as cards. Tapping a card opens the reader, but only if `!book.isParsing` — cards are non-clickable while parsing (the progress bar is already visible as feedback). Long-press enters selection mode for deletion.

**`AppNavigation.kt`**
Defines the nav graph. Also observes `pendingOpenBookId` on `NotiBookApp` — when the user taps a notification while the app is already open, this triggers navigation to the correct book without restarting the activity.

**`MainActivity.kt`**
Reads `bookId` from the intent extras set by notification taps. Passes it to `AppNavigation` as the initial book to open. Also handles `onNewIntent` for the case where the app is already open and another notification is tapped.

**`NotiBookApp.kt`**
Application class. Creates the notification channel, calls `restoreActiveNotifications()` on startup, and exposes `appScope` — a coroutine scope that survives ViewModel lifecycle. Position saves on reader close use `appScope` not `viewModelScope`, because `viewModelScope` cancels the moment you navigate away.

---

## Key Design Decisions

### Why foreground service?
`setOngoing(true)` on the notification combined with a running foreground service makes notifications non-swipeable and always-expanded. `ReadingNotificationService` is started when the first notification is shown and stopped when the last one is dismissed. On Android 14+, `foregroundServiceType="dataSync"` is required.

### Why one big HTML document instead of one chapter at a time?
The whole book is combined into one HTML file and paginated with CSS columns. This makes inter-chapter links, chapter jumps, and smooth chapter-boundary navigation trivial.

### Why character offset for position saving?
The reader saves and restores position using a character count from the start of the document, not a page number or sentence index. This is stable across:
- Font size changes (different column counts)
- Orientation changes (different column widths)
- Screen size changes (split screen, PiP)

`window.__getCharOffset(x, y)` counts normalized characters (whitespace collapsed with `replace(/\s+/g,' ')`) using a Range from body start to the caret. `window.__restoreByCharOffset(target)` scans `textContent` to find the equivalent raw offset, walks text nodes, and navigates to the column.

### Why `readerCharOffset = -1` when notification navigates?
When the user taps ← or → in the notification, they're advancing through the book via the notification, not the reader. The next time the reader opens, it should show the notification's current sentence — not the old reader position. `NotificationActionReceiver` sets `readerCharOffset = -1` after each successful navigation. The reader's `tryRestore()` checks: if `-1`, navigate to `currentIndex` sentence; if `>= 0`, restore by char offset.

### Why `canonicalPath` everywhere?
Android has `/data/user/0/com.notibook.app/` as a symlink to `/data/data/com.notibook.app/`. If you use `absolutePath` on one side and `canonicalPath` on the other, path comparisons silently fail.

### Why does JS own the end-of-book decision?
The reader has no concept of "total pages" in Kotlin. The browser computes CSS columns lazily. Instead, JS checks whether `<span id="__nb_end">` is currently visible on screen via `getBoundingClientRect()` before every forward navigation. If the sentinel is on screen, we're on the last page.

### Why `appScope` for position saves?
`viewModelScope` is cancelled immediately when you navigate back. Any database write launched there may never complete. `appScope` lives for the lifetime of the process.

### Why `configChanges="orientation|screenSize"` in the manifest?
Prevents Android from destroying and recreating the Activity on rotation. The WebView stays loaded; we just update CSS column dimensions and restore position via `buildReinitJs`.

### Why `skipNextPageAnimation`?
When JS calls `NotiBook.onScrollToPage(page)` (after restore, chapter jump, internal link), Kotlin updates `currentPageIndex` which triggers `LaunchedEffect`. Without the flag, `LaunchedEffect` would slide-animate to a page that JS has already positioned — causing a visible snap. The `AtomicBoolean` lets JS tell Kotlin "I already moved, skip the animation."

---

## How Position Tracking Works End-to-End

**While reading:**
After every page turn, JS waits 300ms for the animation, then calls `window.__getCharOffset(centerX, 60)`. This uses `caretRangeFromPoint` to find the character at the center-top of the screen, then creates a Range from body start to that point and returns the normalized length. Sent to `onCharOffset()` in the ViewModel, stored as `_restoreCharOffset`.

**On orientation change:**
`startReinit()` sets `skipNextCharOffsetUpdate = true` so the first `onCharOffset()` after re-init doesn't overwrite the anchor. `buildReinitJs` is injected with the frozen anchor value. Inside `buildReinitJs`, after a 250ms delay (overlay is covering the screen), `__restoreByCharOffset(anchor)` navigates to the correct column in the new layout.

**On close:**
JS calls `__getCharOffset(w*0.5, 60)` for the char offset, and `__getSentenceAtTop(w*0.5, 60)` for the exact sentence index (scanning all `[data-si]` markers, finding the last one at or above y=60 on the current column). Both are returned to `vm.onClose()`, which saves charOffset to `readerCharOffset` and sentenceIndex to `currentIndex` in the DB.

**On next open:**
`loadEpubReader` reads `book.readerCharOffset` and `book.currentIndex`, passes them to `ReaderState.Ready`. `buildJs` embeds them as `targetCharOffset` and `targetSi`. `tryRestore()` uses whichever is appropriate.

---

## Build & Test

```bash
# Build
cd "/Users/aliciavazquezmartinez/Downloads/Claude Code/NotiBook"
./gradlew assembleDebug

# Install
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# View logs
~/Library/Android/sdk/platform-tools/adb logcat -s NotiBook NotiBook_JS

# Clean rebuild
./gradlew clean assembleDebug
```

**After changing sentence parsing:** remove and re-import all books.

**After changing EpubCombiner or buildTxtHtml:** just close and reopen any book in the reader — `__reader.html` is regenerated fresh on every reader launch, so no re-import is needed.

---

## DB Schema (version 4)

### `books` table
| Column | Type | Notes |
|--------|------|-------|
| id | Long PK | auto-generated |
| title, author | String | from EPUB metadata or filename |
| coverPath | String? | path to extracted cover image |
| filePath | String | original file path |
| totalSentences | Int | 0 while parsing |
| parsedSentenceCount | Int | for progress tracking |
| isParsing | Boolean | card non-clickable while true |
| currentIndex | Int | current notification sentence |
| currentChapter | String | shown in notification |
| notificationActive | Boolean | is notification currently showing |
| readerCharOffset | Long | -1 = use currentIndex; ≥0 = exact char offset |

### `sentences` table
| Column | Type | Notes |
|--------|------|-------|
| id | Long PK | auto-generated |
| bookId | Long | foreign key to books |
| sentenceIndex | Int | 0-based, global across whole book |
| text | String | the sentence text |
| chapter | String | chapter title |
| spineItemIndex | Int | which spine item (0 for TXT) |
| blockIndex | Int | which block element within spine item |
| type | String | SENTENCE / IMAGE / TABLE / DIVIDER |

---

## Git Workflow

**Push BEFORE large architectural changes**, not after. This gives a clean rollback point if the refactor goes wrong.

Push after logical feature groups otherwise — not after every single change.

```bash
git log --oneline    # view history
git push             # push to GitHub
```

Each commit message includes `Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>`.

---

## Color Palette

The entire app uses a single dark color palette — no Material3 theme colors, no purple. All screens use these hardcoded values:

| Name | Hex | Usage |
|------|-----|-------|
| `BG_COLOR` | `#1A1A1A` | Screen backgrounds (reader body, library scaffold) |
| `CARD_COLOR` / `BAR_COLOR` | `#2C2C2C` | Cards, top/bottom bars, dropdowns, FAB, TopAppBar |
| `TEXT_COLOR` | `#E0E0E0` | All primary text, active icons, slider track |
| `DIM_COLOR` | `#8A8A8A` | Secondary/metadata text, inactive icons |
| `SELECTED_COLOR` | `#3C3C3C` | Selected card background in library |
| `COVER_PLACEHOLDER` | `#404040` | Book cover placeholder, notification toggle active bg (library) |

### Rules
- **Never use `MaterialTheme.colorScheme.primary`** (purple) anywhere in the UI. Replace with `TEXT_COLOR` for active states.
- **Never use `MaterialTheme.colorScheme.surface/primaryContainer/onSurface`** for visible UI elements — hardcode from the palette above.
- Active/selected states use `TEXT_COLOR` (full or `copy(alpha=0.15f)` background) not a tint color.
- Inactive states use `TEXT_COLOR.copy(alpha = 0.35f)` or `DIM_COLOR.copy(alpha = 0.5f)`.
- The same constants are defined in both `EpubReaderScreen.kt` (as `BG_COLOR`, `BAR_COLOR`, `TEXT_COLOR`) and `LibraryScreen.kt` (as `BG_COLOR`, `CARD_COLOR`, `TEXT_COLOR`, `DIM_COLOR`) with identical values.

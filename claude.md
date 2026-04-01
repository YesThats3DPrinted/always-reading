# NotiBook — Android Ebook Reader via Persistent Notifications

## What This App Does

NotiBook lets you read books passively throughout your day. Instead of sitting down and opening a reading app, you get one sentence at a time delivered as a persistent notification. You can tap the arrows in the notification to go forward or back, and tap the title to open the full in-app reader whenever you want to read more actively.

**Supported formats:** EPUB, TXT
**Target devices:** Android 7+ (API 24+)

---

## High-Level Architecture

The app has four main concerns:

1. **Parsing** — when you import a book, it gets split into individual sentences and stored in a database
2. **Notifications** — sentences are shown one at a time in a persistent notification with ← → navigation
3. **Reader** — a full in-app WebView reader for when you want to read continuously
4. **Sync** — when you close the reader, your position is saved and the notification picks up exactly where you left off

---

## File-by-File Breakdown

### Notifications (`notification/`)

**`NotificationHelper.kt`**
The one place responsible for building and showing notifications. It reads the current sentence from the DB and constructs a custom notification layout. Each book gets its own notification ID (using `bookId.toInt()`) so multiple books can have active notifications simultaneously. Checks dark/light mode at build time and sets text colors accordingly, since custom notification layouts don't get automatic theming.

**`NotificationActionReceiver.kt`**
Listens for button taps inside the notification (← →, dismiss, snooze). Uses `goAsync()` so it can do database work without timing out — Android gives BroadcastReceivers very little time by default. Updates the sentence index in the DB, then rebuilds and posts the notification with the new sentence.

**`BootReceiver.kt`**
When the phone restarts, Android kills all notifications. This receiver fires on boot and re-posts any notifications that were active when the phone was shut down.

**`SnoozeAlarmReceiver.kt`**
Handles the snooze feature — when a snooze alarm fires, re-posts the notification.

**`NotificationService.kt`**
Empty stub. We removed the foreground service entirely because on MIUI phones, foreground service notifications can't be swiped away and behave strangely. Notifications are posted directly via `NotificationManagerCompat.notify()` instead.

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
- Annotates every block element (paragraph, heading, etc.) with `data-si` (the sentence index of the first sentence in that block) and `data-sentences` (all sentences in that block as `"index:length"` pairs) — used for exact position tracking when closing the reader
- Adds `<span id="__nb_end"></span>` at the very end of the document — the end-of-book sentinel

The combined HTML is written to `__reader.html` in the cache dir and loaded via a `file://` URL. This gives it a real file origin so images load correctly.

---

### In-App Reader (`ui/reader/`)

**`ReaderPreferences.kt`**
Simple SharedPreferences wrapper that stores the user's font size preference (12–28sp, step 2).

**`EpubReaderViewModel.kt`**
Manages all reader state. Key responsibilities:

- **Loading** — checks if the book is still parsing, then calls EpubExtractor + EpubCombiner to prepare the HTML, then emits a `Ready` state with the file URL
- **Page tracking** — maintains `currentPageIndex` as a StateFlow. Kotlin only ever increments or decrements this number — JS is responsible for deciding when it's allowed
- **Position anchor** — maintains `restoreSentenceIndex`, the sentence index at the top of the current page. Updated by JS after every page turn via `onDataSi()`. Used to restore position after an orientation change
- **Orientation re-init** — `startReinit()` shows an opaque overlay and sets a flag to skip the next `onDataSi()` call (so rotating the phone doesn't drift your reading position). `onReady()` hides the overlay once JS finishes re-laying out the columns
- **Closing** — `closeWithPosition()` saves the current page index and sentence index to the DB, and optionally starts or restores the notification

**`EpubReaderScreen.kt`**
The actual UI. A WebView fills most of the screen, with an animated top bar that appears/disappears on center tap.

The screen is organized around several pieces of JS that get injected at different times:

**At page load (`onPageFinished`):**
Sets up the CSS column layout. The body is made exactly one viewport wide and tall, and `column-width` is set to the same value — so the browser lays out content as side-by-side columns, each one screen wide. Dimensions come from Kotlin's measured view size (always reliable) not `window.innerWidth` (can be 0 at load time).

**`buildJs(w, h)` — the main JS, injected once after load:**

- Blocks all native touch scrolling (we handle all navigation ourselves)
- Stores `window.__colW` and `window.__colH` (viewport dimensions) globally
- **`sentinelVisible()`** — the end-of-book check. Calls `getBoundingClientRect()` on `<span id="__nb_end">`. If that span's left edge is currently on screen, we're on the last page. `getBoundingClientRect()` accounts for the CSS transform on the body, so it always reflects the element's actual on-screen position — no math required.
- **Touch handling** — swipe left/right and tap left/right/center zones for navigation. Before calling `NotiBook.onNextPage()`, checks `sentinelVisible()` — if the sentinel is on screen, swipe right does nothing.
- **`window.__getSentenceAtTop(x, y)`** — finds the exact sentence at a given point on screen using `caretRangeFromPoint()` (gets the exact character at that point), then walks up the DOM to find the enclosing block's `data-sentences` attribute, then uses the sentence lengths stored there to figure out which specific sentence is at that character position. Used both when closing the reader (to save position for the notification) and after page turns (to update the orientation-restore anchor).
- **`tryRestore()`** — restores the saved page position when the book first opens. Polls until `scrollWidth > viewport width` so it knows the browser has finished laying out at least one column's worth of content before navigating.
- **`fixImages()`** — CSS percentage widths and viewport units don't work reliably inside CSS columns. This function iterates every `<img>` element and sets explicit pixel dimensions via inline styles, constraining by both width and height so images always fit within one page. Exposed as `window.__fixImages` so `buildReinitJs` can call it after orientation change.
- **Chapter tracking** — after every page turn, checks which chapter's `<div>` the current page is inside and reports it to the ViewModel via `onChapterVisible()`

**`buildReinitJs(w, h, si)` — injected when orientation changes:**
When the phone rotates, the WebView width changes. `addOnLayoutChangeListener` detects this and calls `startReinit()` (shows overlay, freezes position anchor) then runs this JS:
1. Updates `window.__colW`, `window.__colH`, and all the CSS column dimensions to the new viewport size
2. Waits 250ms (the overlay is definitely visible by then, safe to do invisible work)
3. Resets the body's transform to 0 (so layout coordinates are clean)
4. Finds the block containing `restoreSentenceIndex` via `data-sentences`
5. Computes the character offset within that block proportionally (DB lengths are Jsoup-normalized, JS text nodes are raw — proportional mapping bridges the difference)
6. Uses `getClientRects()` on that character position to find what column it's now in
7. Navigates to that column and calls `NotiBook.onReady()` to hide the overlay

**Chapter jumps (LaunchedEffect):**
When the user picks a chapter from the dropdown, finds that chapter's `<div>`, reads its `offsetLeft`, divides by column width to get the page number, and navigates there.

**`handleInternalLink()` in ViewModel:**
When the user taps an internal link inside the book (e.g. a footnote), finds the target element, computes what page it's on, and navigates there.

---

### Database (`data/db/`)

Room ORM. Two tables:

**`books`**
One row per imported book. Key fields:
- `currentIndex` — which sentence the notification is currently showing
- `currentChapter` — chapter title, stored so the notification can display it
- `readerSpineIndex` — the page index the reader was on when last closed (used to restore position on re-open)
- `notificationActive` — whether this book's notification is currently showing
- `notifWasActiveBeforeReader` — when you open the reader, if the notification was active it gets hidden. This flag remembers that so it can be restored when you close the reader.
- `isParsing` — true while the parse worker is running; reader shows an error if you try to open a still-parsing book

**`sentences`**
One row per sentence. Key fields:
- `sentenceIndex` — global 0-based index across the whole book
- `spineItemIndex` — which chapter (spine item) this sentence is in
- `blockIndex` — which block element within that chapter (used to match sentences back to HTML elements for `data-sentences` annotation)
- `type` — SENTENCE, IMAGE, TABLE, or DIVIDER

**Schema version: 2**

---

### UI (`ui/`)

**`LibraryScreen.kt`**
Shows all imported books as cards. Tapping any book opens the reader (`reader/{bookId}` route). All books — EPUB and TXT — go through the same reader.

**`AppNavigation.kt`**
Defines the nav graph. Also observes `pendingOpenBookId` on `NotiBookApp` — when the user taps a notification while the app is already open, this triggers navigation to the correct book without restarting the activity.

**`BookDetailScreen.kt`**
Currently unused for navigation (all books go to the reader), but still exists for the notification toggle, restart from beginning, and remove book functionality.

**`MainActivity.kt`**
Reads `bookId` from the intent extras set by notification taps. Passes it to `AppNavigation` as the initial book to open. Also handles `onNewIntent` for the case where the app is already open and another notification is tapped.

**`NotiBookApp.kt`**
Application class. Creates the notification channel, calls `restoreActiveNotifications()` on startup (re-shows any notifications that were active when the app was last killed), and exposes `appScope` — a coroutine scope that survives ViewModel lifecycle. Important: position saves when closing the reader use `appScope` not `viewModelScope`, because `viewModelScope` cancels the moment you navigate away, which would abort the save.

---

## Key Design Decisions

### Why no foreground service?
On MIUI (Xiaomi phones), foreground service notifications are force-expanded and can't be swiped away. Removed it entirely — notifications are posted directly. Trade-off: Android can kill the notification under memory pressure, but that's acceptable for this use case.

### Why one big HTML document instead of one chapter at a time?
The whole book is combined into one HTML file and paginated with CSS columns. This makes inter-chapter links, chapter jumps, and smooth chapter-boundary navigation trivial. The downside is memory usage and the browser's lazy column layout computation — but for text-heavy books the memory footprint is manageable, and we've designed around the lazy layout issue (sentinel for end-of-book, proportional restore for orientation changes).

### Why `canonicalPath` everywhere?
Android has `/data/user/0/com.notibook.app/` as a symlink to `/data/data/com.notibook.app/`. If you use `absolutePath` on one side and `canonicalPath` on the other, path comparisons silently fail. Always use `canonicalPath` in the epub package.

### Why does JS own the end-of-book decision?
The reader has no concept of "total pages" in Kotlin. The browser computes CSS columns lazily — it only lays out columns near the current viewport. Reading `scrollWidth` (the total document width) too early gives a wrong answer for large books. Instead, JS checks whether `<span id="__nb_end">` is currently visible on screen via `getBoundingClientRect()` before every forward navigation. If the sentinel is on screen, we're on the last page. This requires zero math and zero cross-thread communication.

### Why `appScope` for position saves?
`viewModelScope` is cancelled immediately when you navigate back. Any database write launched there may never complete. `appScope` lives for the lifetime of the process and guarantees the save finishes.

### Why `configChanges="orientation|screenSize"` in the manifest?
Prevents Android from destroying and recreating the Activity on rotation. Without this, the WebView would reload the entire book on every orientation change. With it, the Activity survives, the WebView stays loaded, and we just need to update the CSS column dimensions and restore the position — handled by `buildReinitJs`.

### Why is `@JavascriptInterface` threading important?
Methods marked `@JavascriptInterface` run on a background thread, not the main thread. Any UI operations must be dispatched via `webView.post { }`. The bridge is sequential though — calls made from JS in order are received in order — which is why calling `onTotalPages()` before `onNextPage()` (back when that existed) was safe.

---

## How Position Tracking Works End-to-End

This is the most complex part of the app. Here's the full flow:

**While reading:**
After every page turn, JS waits 300ms (for the animation to finish), then calls `window.__getSentenceAtTop(screenCenterX, 60)`. This function uses `caretRangeFromPoint()` to find the exact character at that point, walks up the DOM to the enclosing block's `data-sentences` attribute, and uses the stored sentence lengths to identify the exact sentence. The result is sent to `onDataSi()` in the ViewModel, which stores it as `restoreSentenceIndex`.

**On orientation change:**
`startReinit()` freezes `restoreSentenceIndex` (by setting `skipNextDataSiUpdate = true`) so the first `onDataSi()` call after re-init — which comes from the page animation, not the user — doesn't drift the anchor. `buildReinitJs` uses the frozen `restoreSentenceIndex` to find the sentence's new position in the reflowed columns and navigate there.

**On close:**
JS calls `window.__getSentenceAtTop()` one more time to get the sentence at the top of the current page, passes it to `closeWithPosition()` in the ViewModel, which writes it to the DB. The notification then uses that sentence index.

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

**After changing sentence parsing (SentenceSplitter, EpubParser, TxtParser):** remove and re-import all books — sentences are stored in the DB and won't be re-split automatically.

**After changing EpubCombiner or buildTxtHtml:** re-import books to regenerate `__reader.html` with the latest HTML structure.

---

## DB Schema (version 2)

### `books` table
| Column | Type | Notes |
|--------|------|-------|
| id | Long PK | auto-generated |
| title, author | String | from EPUB metadata or filename |
| coverPath | String? | path to extracted cover image |
| filePath | String | original file path |
| totalSentences | Int | 0 while parsing |
| parsedSentenceCount | Int | for progress tracking |
| isParsing | Boolean | reader blocks if true |
| currentIndex | Int | current notification sentence |
| currentChapter | String | shown in notification |
| notificationActive | Boolean | is notification currently showing |
| readerSpineIndex | Int | last page index in reader |
| readerScrollPercent | Float | unused, to be removed in v3 |
| notifWasActiveBeforeReader | Boolean | restore notif on reader close |

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

Push after logical feature groups, not after every change.

```bash
git log --oneline    # view history
git push             # push to GitHub
```

Each commit message includes `Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>`.

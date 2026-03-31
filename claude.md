# NotiBook — Android Ebook Reader via Persistent Notifications

## Project Overview

**NotiBook** is an Android ebook reader application that displays books one sentence at a time in persistent notifications. Users can navigate through sentences using arrow buttons (← →) in the notification, and tap the title to open the full in-app reader. This unique interface makes reading ambient and asynchronous — you can read while doing other tasks.

**Supported formats:** EPUB, TXT
**Target devices:** Android 7+ (API 24+)
**Notification behavior:** Custom RemoteViews (expanded view only), no foreground service

---

## Architecture

### Core Layers

1. **Notification System** (`notification/`)
   - `NotificationHelper.kt` — builds and posts notifications via `NotificationManagerCompat.notify()`; `makeOpenAppIntent` passes `bookId` extra, uses `bookId.toInt()` as requestCode for unique PendingIntents
   - `NotificationActionReceiver.kt` — handles navigation (← →), dismiss, snooze via BroadcastReceiver with `goAsync()` + coroutines
   - `BootReceiver.kt` — restores active notifications on device boot
   - `SnoozeAlarmReceiver.kt` — fires snooze alarms set by user
   - `NotificationService.kt` — empty stub (foreground service removed entirely)

2. **Book Parsing** (`parsing/`)
   - `EpubParser.kt` — extracts text from EPUB files, respects `<p>` paragraph boundaries; **planned: also annotate HTML with `data-si` attributes and save annotated files to cache**
   - `TxtParser.kt` — reads TXT files, splits on `\n{2,}` for paragraphs
   - `SentenceSplitter.kt` — ICU BreakIterator + abbreviation/number merging + equal-chunk splitting
   - `ParseWorker.kt` — WorkManager task for async parsing without blocking UI

3. **EPUB Reader** (`epub/`)
   - `EpubExtractor.kt` — unzips EPUB to app cache dir; uses `File.canonicalPath` (not `absolutePath`) to avoid symlink mismatch between `/data/user/0/` and `/data/data/`
   - `EpubSpineReader.kt` — reads spine order from `content.opf`; uses `canonicalPath`
   - `EpubCombiner.kt` — merges all spine chapters into one HTML document, absolutizes `src` and `href` attrs; **planned: read annotated HTML files instead of raw files**
   - `SpineItem.kt` — data class: `(chapterTitle, htmlFile, canonicalPath)`

4. **In-App Reader** (`ui/reader/`)
   - `EpubReaderScreen.kt` — WebView-based reader; CSS columns pagination (planned); tap zones (left 30% prev, right 30% next, center toggle bar); swipe detection; JS bridge callbacks
   - `EpubReaderViewModel.kt` — manages reader state; `currentPageIndex`, `totalPages`, `topBarVisible`; `closeWithPosition(pageIndex, totalPages, visibleText, startNotification)`; `restorePage` uses `book.readerSpineIndex` (repurposed as page index)
   - `ReaderPreferences.kt` — SharedPreferences wrapper; stores `fontSize` (12–28, step 2)

5. **Database** (`data/db/`)
   - Room ORM with `BookEntity` and `SentenceEntity`
   - `BookDao.kt`, `SentenceDao.kt` for queries
   - Current schema version: **2**
   - **Planned schema version 3:** add `type` field to `SentenceEntity` (`SENTENCE`, `IMAGE`, `TABLE`, `DIVIDER`); remove `readerScrollPercent` from `BookEntity`

6. **UI** (`ui/`)
   - `LibraryScreen.kt` — `onBookClick: (bookId: Long)` (no `isEpub` param; all books use same reader)
   - `AppNavigation.kt` — all books route to `reader/{bookId}`; observes `pendingOpenBookId` for notification-tap navigation while app is open
   - `BookDetailScreen.kt` — displays current sentence, notification toggle, restart/remove
   - `BookDetailViewModel.kt` — manages UI state, calls notification system directly

7. **App Initialization** (`NotiBookApp.kt`)
   - Creates notification channel: `IMPORTANCE_DEFAULT`, no sound/vibration
   - `restoreActiveNotifications()` called at app startup
   - `appScope: CoroutineScope` — public, survives ViewModel lifecycle (use for saves that must complete after nav pop)
   - `pendingOpenBookId: MutableStateFlow<Long>` — for notification-tap navigation while app is open

8. **`MainActivity.kt`**
   - Reads `bookId` from intent extras (set by notification tap)
   - Passes `startBookId` to `AppNavigation`
   - Handles `onNewIntent` for notification tap while app is already open

### Data Flow

```
Import Book (user clicks "Import")
→ ParseWorker parses file (EPUB/TXT)
→ SentenceSplitter breaks into sentences + merges abbreviations + equal chunks
→ (EPUB) EpubParser annotates HTML with data-si attributes, saves annotated files  [PLANNED]
→ Room DB stores all sentences (with type: SENTENCE/IMAGE/TABLE/DIVIDER)           [PLANNED]
→ NotificationHelper.show() posts first sentence notification
↓
User taps ← or →
→ BroadcastReceiver updates DB position
→ NotificationHelper.show() updates notification with new sentence
↓
User taps notification title
→ MainActivity opens with bookId extra
→ AppNavigation routes to reader/{bookId}
→ EpubReaderViewModel.init(bookId) loads book, combines HTML, restores page
↓
User closes reader ("Close" or "Close & start notification")
→ webView.scrollY / webView.height = exact pageIndex (Kotlin, always reliable)
→ JS elementFromPoint(cx, cy) → data-si attribute = exact sentenceIndex            [PLANNED]
→ vm.closeWithPosition(pageIndex, totalPages, sentenceIndex, startNotification)
→ Book position saved to DB
```

---

## Key Technical Decisions & Constraints

### 1. No Foreground Service

**Decision:** Removed the foreground service entirely. Notifications now posted via `NotificationManagerCompat.notify()` directly.

**Why:** On MIUI, foreground service notifications are force-expanded and cannot be swiped away. Removing the service allows:
- Swipe-to-dismiss to work
- Both collapsed and expanded states to be visible
- Dismissal without force-killing the app

**Trade-off:** Without a foreground service, the system can kill notifications under memory pressure. Acceptable — user can re-enable via the app.

**Implementation:** All notification posting goes through `NotificationHelper.show()`. BroadcastReceivers use `goAsync()` + coroutines for async DB work.

---

### 2. RemoteViews Whitelist & Custom Layouts

**Constraint:** Android's notification RemoteViews only support a whitelist of view classes:
- ✅ Allowed: `FrameLayout`, `LinearLayout`, `RelativeLayout`, `GridLayout`, `TextView`, `ImageView`, `ImageButton`, `Button`, `ProgressBar`, `Chronometer`, `ListView`, `GridView`, `StackView`, `ViewFlipper`, `ViewStub`
- ❌ Not allowed: `ScrollView`, `RecyclerView`, `WebView`, `VideoView`, custom views, etc.

**Implication:** No scrolling in notifications. Long sentences are capped at `maxLines=8` + `ellipsize=end`. The equal-chunk algorithm keeps most segments short enough to avoid this.

**Layout:**
- `notification_expanded.xml` — title row (← title →) with rounded grey button backgrounds, sentence text below (maxLines=8)
- No custom collapsed layout — Android's standard template handles the collapsed state with correct theming

---

### 3. Notification Background Color & Dark Mode

**Current approach — Dark mode detection:**
- `NotificationHelper` checks `Configuration.UI_MODE_NIGHT_MASK` at notification build time
- Sets white text in dark mode, black text in light mode via `setInt("setTextColor", color)`
- Collapsed state uses Android's standard template (no custom view) — system handles its own theming correctly
- Expanded state uses custom view with runtime-detected colors

**Key insight:** Custom RemoteViews must set text colors explicitly. Standard template notifications handle theming automatically.

---

### 4. Single Notification Layout (Expanded Only)

**Decision:** Only one custom layout — the expanded view (`notification_expanded.xml`).

**Why:** The collapsed state is handled by Android's standard template which automatically handles light/dark mode and works correctly on all OEMs.

**Notification title format:** `"0% · Book Title · Chapter"` — percentage first so it's always visible even on long titles that would otherwise be truncated.

---

### 5. Sentence Splitting Design Philosophy

**Core principle: always err on the side of merging, never on splitting.**

#### Pipeline

```
Raw text
  ↓ ICU BreakIterator (sentence boundaries)
  ↓ Abbreviation/number/Roman numeral merging
  ↓ Equal-chunk splitting (MAX_CHUNK_CHARS = 250)
Final segments stored in DB
```

#### Step 2 — False boundary merging
Consecutive segments merged when the first ends with:
- **Known abbreviations:** `Mr.` `Dr.` `etc.` `e.g.` `Jan.` `Ave.` `Inc.` and ~80 others
  - Categories: titles/honorifics, academic degrees, Latin, publishing, time, geography, business/legal, era (B.C./A.D.), units (km/kg/oz...), compass directions, professional terms
- **Any integer:** `1.` `03.` `1995.` — covers ordinals and European date formats
- **Roman numerals:** `I.` `III.` `XII.` — validated with regex to avoid false matches

#### Step 3 — Equal-chunk splitting
Segments over 250 chars divided into N equal pieces; each split at nearest word boundary; non-last chunks get trailing `" …"`.

**Note:** Re-importing books is required after any changes to the splitting logic.

---

### 6. Android WebView Scroll Position — Critical Gotcha

**`document.documentElement.scrollTop` is ALWAYS 0 in Android WebView** when the user scrolls via native touch. This caused all previous position save/restore attempts to fail silently.

| Method | Works? | Notes |
|--------|--------|-------|
| `document.documentElement.scrollTop` (JS) | ❌ Always 0 | Android WebView native touch scroll doesn't update it |
| `window.pageYOffset` (JS) | ✅ | Works for reading current scroll position in JS |
| `window.scrollTo(0, y)` (JS) | ✅ | Works for programmatic scrolling |
| `webView.scrollY` (Kotlin) | ✅ | Most reliable; always correct |

**Rule:** For reading scroll position at close time, always use `webView.scrollY` in Kotlin, not JS.

---

### 7. `absolutePath` vs `canonicalPath` — Symlink Gotcha

On Android, `/data/user/0/com.notibook.app/` is a symlink to `/data/data/com.notibook.app/`. Using `absolutePath` on one side and `canonicalPath` on the other causes path comparison mismatches (e.g. internal link navigation fails silently because the paths never match).

**Rule:** Always use `File.canonicalPath` everywhere in the epub package. Never mix with `absolutePath`.

---

### 8. `@JavascriptInterface` Threading

`@JavascriptInterface` methods run on a **background thread**, not the main thread. Any UI or View operations must be dispatched via `webView.post { }`.

---

### 9. ViewModel Lifecycle vs. AppScope

`viewModelScope` is cancelled when the ViewModel is cleared, which happens immediately when the user navigates back. Any coroutine launched in `viewModelScope` to save data on close may never complete.

**Rule:** Use `app.appScope.launch` for save operations that must survive navigation pop. Capture all state **synchronously** before launching the coroutine.

---

### 10. In-App Reader — Pagination Architecture

**Current (partial):** Viewport-height slicing — one long HTML document, pages = `scrollY / height`. Text bleeds across page boundaries (a line visible at top of page N is the last line from page N-1).

**Planned: CSS columns pagination** (same technique used by Readium, Apple Books, Kobo):
```css
body {
  height: 100vh;
  column-width: 100vw;
  column-gap: 0;
  column-fill: auto;
  overflow: hidden;
}
```
- Browser handles all text flow and page breaks — never cuts mid-line
- Navigation: `translateX(-pageIndex * 100vw)`
- Total pages: `body.scrollWidth / window.innerWidth`
- Font size changes: browser reflows automatically, just recalculate total pages

---

### 11. Position Tracking — Parse-Time HTML Annotation (Planned)

**Problem:** Mapping "which page are you on" to "which sentence index" is imprecise with text matching (same text can appear multiple times) and inaccurate with linear approximation (text density varies).

**Solution:** During `EpubParser`, annotate each block element with the index of the first sentence it contains:
```html
<p data-si="1042">She walked into the room. Their eyes met.</p>
```
Save annotated HTML as `ch01.annotated.html` alongside extracted EPUB files. `EpubCombiner` reads annotated files. At reader close, JS does `elementFromPoint(cx, cy)` → walk up DOM → `data-si` value = exact sentence index. No text matching, no approximation.

**Granularity:** Paragraph-level (not sentence-level). If sentences 1042 and 1043 are in the same `<p>`, the element is annotated with 1042. This is accurate enough — position is off by at most a few sentences within a paragraph.

---

### 12. Rich Media in Notifications (Planned)

`SentenceEntity` will gain a `type` field: `SENTENCE | IMAGE | TABLE | DIVIDER`.

- `IMAGE` entries: `text = "Image — open book to see"`, shown in notification like any sentence
- `TABLE` entries: `text = "Table — open book to see"`, shown in notification like any sentence
- `DIVIDER` entries (em-dashes, decorative section breaks): `showInNotification = false`, skipped in notifications, rendered in reader
- These entries participate in the sentence index sequence, so position tracking never silently jumps over a full-page image

---

## File Structure

```
NotiBook/
├── app/src/main/
│   ├── java/com/notibook/app/
│   │   ├── MainActivity.kt                        # Reads bookId from intent; handles onNewIntent
│   │   ├── NotiBookApp.kt                         # appScope (public); pendingOpenBookId
│   │   ├── notification/
│   │   │   ├── NotificationHelper.kt              # Build & post; dark mode detection; unique PendingIntents per bookId
│   │   │   ├── NotificationActionReceiver.kt      # ← → dismiss snooze
│   │   │   ├── BootReceiver.kt
│   │   │   ├── SnoozeAlarmReceiver.kt
│   │   │   └── NotificationService.kt             # empty stub
│   │   ├── parsing/
│   │   │   ├── EpubParser.kt                      # EPUB text extraction; planned: HTML annotation
│   │   │   ├── TxtParser.kt                       # TXT, \n\n boundaries
│   │   │   ├── SentenceSplitter.kt                # BreakIterator + merge + chunk
│   │   │   └── ParseWorker.kt
│   │   ├── epub/
│   │   │   ├── EpubExtractor.kt                   # Unzip EPUB to cache; canonicalPath
│   │   │   ├── EpubSpineReader.kt                 # Read spine from content.opf; canonicalPath
│   │   │   ├── EpubCombiner.kt                    # Merge spine chapters into one HTML doc
│   │   │   └── SpineItem.kt                       # (chapterTitle, htmlFile, canonicalPath)
│   │   ├── data/
│   │   │   ├── BookRepository.kt
│   │   │   └── db/
│   │   │       ├── AppDatabase.kt                 # Version 2 (planned: 3)
│   │   │       ├── BookEntity.kt                  # readerSpineIndex (= page index); notifWasActiveBeforeReader
│   │   │       ├── SentenceEntity.kt              # spineItemIndex; planned: type field
│   │   │       ├── BookDao.kt
│   │   │       └── SentenceDao.kt
│   │   └── ui/
│   │       ├── reader/
│   │       │   ├── EpubReaderScreen.kt            # WebView reader; tap zones; CSS columns (planned)
│   │       │   ├── EpubReaderViewModel.kt         # Page-based state; closeWithPosition
│   │       │   └── ReaderPreferences.kt           # fontSize SharedPreferences
│   │       ├── detail/
│   │       │   ├── BookDetailScreen.kt
│   │       │   └── BookDetailViewModel.kt
│   │       ├── library/
│   │       │   ├── LibraryScreen.kt               # onBookClick: (bookId: Long)
│   │       │   └── LibraryViewModel.kt
│   │       ├── navigation/AppNavigation.kt        # reader/{bookId}; pendingOpenBookId observer
│   │       └── theme/Theme.kt
│   ├── res/
│   │   ├── layout/
│   │   │   ├── notification_expanded.xml          # Only custom layout (arrows + sentence)
│   │   │   └── notification_collapsed.xml         # Kept but not used
│   │   ├── drawable/
│   │   │   ├── notif_btn_bg.xml                   # Rounded grey bg for arrows + title
│   │   │   ├── ic_book.xml
│   │   │   └── [other icons]
│   │   └── values/
│   │       ├── strings.xml
│   │       └── themes.xml
│   └── AndroidManifest.xml
├── build.gradle.kts
├── .gitignore
└── CLAUDE.md
```

---

## DB Schema

### `books` table (version 2)
| Column | Type | Notes |
|--------|------|-------|
| id | Long PK | |
| title, author | String | |
| coverPath | String? | |
| filePath | String | |
| totalSentences | Int | 0 while parsing |
| parsedSentenceCount | Int | for progress bar |
| isParsing | Boolean | |
| currentIndex | Int | 0-based, current notification sentence |
| currentChapter | String | |
| notificationActive | Boolean | |
| readerSpineIndex | Int | repurposed: page index in reader |
| readerScrollPercent | Float | **to be removed in v3** |
| notifWasActiveBeforeReader | Boolean | restore notif on reader close |

### `sentences` table (version 2)
| Column | Type | Notes |
|--------|------|-------|
| id | Long PK | |
| bookId | Long | |
| sentenceIndex | Int | 0-based, global across book |
| text | String | |
| chapter | String | |
| spineItemIndex | Int | 0 for TXT |
| type | String | **planned v3**: SENTENCE/IMAGE/TABLE/DIVIDER |

---

## Planned Next Work (in order)

1. **DB migration 2→3:** Add `type` to `SentenceEntity`; remove `readerScrollPercent` from `BookEntity`
2. **EpubParser — HTML annotation:** Add `data-si="N"` to block elements during parsing; emit IMAGE/TABLE/DIVIDER entries; save `.annotated.html` files to cache
3. **EpubCombiner — read annotated files:** Use `.annotated.html` if present, fall back to raw
4. **EpubReaderScreen — CSS columns:** Replace viewport-height slicing with CSS columns; `translateX` navigation; total pages from `scrollWidth`
5. **EpubReaderViewModel — exact sentence index:** Drop text prefix matching; JS returns `data-si` value at close
6. **Cleanup:** Remove `findSentenceByTextPrefix` from DAO/repository; remove `readerScrollPercent` usages

---

## Build & Test Workflow

```bash
# Build
cd ~/Downloads/Claude\ Code/NotiBook
./gradlew assembleDebug

# Install (overwrite existing)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Clean rebuild if needed
./gradlew clean assembleDebug

# View logs
adb logcat -s NotiBook
```

**After changing SentenceSplitter or EpubParser:** remove and re-import all books — parsed sentences are stored in the DB and won't be re-split automatically.

### Manual Testing Checklist
- [ ] Import EPUB and TXT
- [ ] Notification appears in correct color for light/dark mode
- [ ] Expand notification: ← title → row with grey button backgrounds, sentence text below
- [ ] Tap ← / → : navigate sentences; arrow disappears at first/last
- [ ] Tap notification title: opens reader at correct book
- [ ] Tap notification title while app open: navigates to correct book
- [ ] Reader: tap left zone → prev page, right zone → next page, center → toggle top bar
- [ ] Reader: swipe left → next page, swipe right → prev page
- [ ] Reader: close → reopening restores to same page
- [ ] Reader: "Close & start notification" → notification starts at correct sentence
- [ ] Chapter dropdown in reader → jumps to chapter
- [ ] Internal links in EPUB → navigate correctly
- [ ] Images visible in reader; "Image — open book to see" in notification (planned)
- [ ] Font size change → reflows correctly (planned)

---

## Known Limitations

1. **Text bleeds at page boundaries** — viewport-height slicing cuts lines mid-display. Fix: CSS columns (planned).
2. **Position tracking imprecise** — text prefix matching is fragile; `data-si` annotation will fix this (planned).
3. **No scrolling in expanded notification** — RemoteViews whitelist excludes ScrollView. Long segments capped at 8 lines.
4. **No custom collapsed layout** — collapsed state uses Android's standard template (no arrows).
5. **Notification lifespan** — no foreground service means system can kill under memory pressure. Intentional trade-off.
6. **Re-import required after parser changes** — DB stores pre-parsed sentences; no auto-migration.

---

## Git Workflow & Commit Strategy

**When to push to GitHub:**
- After a major architectural or feature change
- When 3–5 small changes have accumulated and form a logical unit
- Before major refactoring or risky changes (for safety)
- **NOT** after every tiny adjustment

```bash
git log --oneline        # view history
git show <hash>          # inspect a commit
git revert <hash>        # safely undo
git push                 # push to GitHub
```

Each commit message includes `Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>`.

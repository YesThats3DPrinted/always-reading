# NotiBook — Android Ebook Reader via Persistent Notifications

## Project Overview

**NotiBook** is an Android ebook reader application that displays books one sentence at a time in persistent notifications. Users can navigate through sentences using arrow buttons (← →) in the notification, and tap the title to open the full app. This unique interface makes reading ambient and asynchronous — you can read while doing other tasks.

**Supported formats:** EPUB, TXT
**Target devices:** Android 7+ (API 24+)
**Notification behavior:** Custom RemoteViews, no foreground service

---

## Architecture

### Core Layers

1. **Notification System** (`notification/`)
   - `NotificationHelper.kt` — builds and posts notifications via `NotificationManagerCompat.notify()`
   - `NotificationActionReceiver.kt` — handles navigation (← →), dismiss, snooze via BroadcastReceiver with `goAsync()` + coroutines
   - `BootReceiver.kt` — restores active notifications on device boot
   - `SnoozeAlarmReceiver.kt` — fires snooze alarms set by user
   - `NotificationService.kt` — empty stub (removed foreground service entirely)

2. **Book Parsing** (`parsing/`)
   - `EpubParser.kt` — extracts text from EPUB files, respects `<p>` paragraph boundaries
   - `TxtParser.kt` — reads TXT files, splits on `\n{2,}` for paragraphs
   - `SentenceSplitter.kt` — uses ICU `BreakIterator` for sentence splitting, then **equal-chunk splitting** for long sentences
   - `ParseWorker.kt` — WorkManager task for async parsing without blocking UI

3. **Database** (`data/db/`)
   - Room ORM with `BookEntity` and `SentenceEntity`
   - `BookDao.kt`, `SentenceDao.kt` for queries
   - Stored data: book title/chapter, sentence text, positions, parsing state

4. **UI** (`ui/`)
   - Jetpack Compose for main app (library and detail screens)
   - `BookDetailScreen` — displays current sentence, jump controls, import/remove buttons
   - `BookDetailViewModel` — manages UI state, calls notification system directly

5. **App Initialization** (`NotiBookApp.kt`)
   - Creates notification channel: `IMPORTANCE_DEFAULT`, no sound/vibration
   - `restoreActiveNotifications()` called at app startup — queries active books and re-posts notifications

### Data Flow

```
Import Book (user clicks "Import")
→ ParseWorker parses file (EPUB/TXT)
→ SentenceSplitter breaks into sentences + equal chunks
→ Room DB stores all sentences
→ NotificationHelper.show() posts first sentence notification
↓
User taps ← or →
→ BroadcastReceiver updates DB position
→ NotificationHelper.show() updates notification with new sentence
↓
User taps title
→ MainActivity opens, shows current sentence detail screen
↓
User dismisses notification
→ BroadcastReceiver sets `notificationActive = false` in DB
```

---

## Key Technical Decisions & Constraints

### 1. No Foreground Service

**Decision:** Removed the foreground service entirely. Notifications now posted via `NotificationManagerCompat.notify()` directly.

**Why:** On MIUI, foreground service notifications are force-expanded and cannot be swiped away. Removing the service allows users to:
- Swipe away notifications
- See both collapsed and expanded states
- Dismiss without force-killing the app

**Trade-off:** Notifications are no longer guaranteed to persist forever (system can kill them under memory pressure), but users can re-enable via the app.

**Implementation:** All notification posting is now through `NotificationHelper.show()` which calls `NotificationManagerCompat.from(context).notify()`. BroadcastReceivers use `goAsync()` + `lifecycleScope.launch { }` for async DB work without a service.

---

### 2. RemoteViews Whitelist & Custom Layouts

**Constraint:** Android's notification RemoteViews only support a whitelist of view classes:
- ✅ Allowed: `FrameLayout`, `LinearLayout`, `RelativeLayout`, `GridLayout`, `TextView`, `ImageView`, `ImageButton`, `Button`, `ProgressBar`, `Chronometer`, `ListView`, `GridView`, `StackView`, `ViewFlipper`, `ViewStub`
- ❌ Not allowed: `ScrollView`, `RecyclerView`, `WebView`, `VideoView`, custom views, etc.

**Implication:** We cannot add scrolling to the expanded notification. Long sentences are clipped via `maxLines=8` + `ellipsize=end`. Users must open the app to read the full sentence if it exceeds ~8 lines.

**Our layouts:**
- `notification_collapsed.xml` — title row (← title →), FrameLayout with sentence preview (64dp fixed height, clipChildren=true), bottom fade overlay
- `notification_expanded.xml` — same title row, larger sentence area (maxLines=8, no fixed height)

---

### 3. Notification Background Color: MIUI vs Samsung

**Research:** Examined NotiNotes GitHub repo to understand cross-device color handling.

**NotiNotes approach:**
- Uses standard notification template (`BigTextStyle`)
- Calls `builder.createContentView()` to get the system-generated template RemoteViews
- Sets background color on the ROOT of that template: `setInt(rootId, "setBackgroundColor", blendedColor)`
- Works because the root of the system template IS the full notification card visible on all OEMs

**Our approach:**
- Uses fully custom RemoteViews layouts (needed for navigation arrows)
- Sets background color on our root view: `setInt(R.id.notif_root_collapsed, "setBackgroundColor", PURPLE)`
- **Works on MIUI:** MIUI's card chrome is transparent; our colored root fills the visible area
- **Limited on Samsung One UI:** Samsung wraps the notification in an opaque grey card. Our custom view sits inside it. Only our inner area is purple; the card chrome remains grey.

**Why we didn't adopt NotiNotes's approach:**
- NotiNotes doesn't need navigation arrows; they use standard template + action buttons
- Our arrows are core to the UX (quick sentence nav without opening app)
- Moving arrows to action buttons would break the button row design

**Current status:**
- Purple background works perfectly on MIUI
- On Samsung One UI: notification is functional but has a grey card border around the purple content
- Using `setColorized(true)` + `setColor(PURPLE)` provides fallback coloring on some OEMs

**Known limitation:** Full-bleed purple background on all OEMs is not achievable while keeping custom navigation arrows. We chose functional arrows over perfect colors.

---

### 4. Equal-Chunk Splitting Algorithm

**Constraint:** ICU `BreakIterator` gives us sentences, but they can be very long (e.g., 1000+ chars). Displaying such sentences in a 64dp-tall notification is illegible.

**Old approach (greedy):** Split at 250-char boundaries, but this could leave a tiny final chunk (e.g., 2000-char sentence → 250+250+250+250+20).

**New approach (equal-size):**
```
1. If sentence ≤ 250 chars: use as-is
2. Otherwise: N = ceil(length / 250)
3. Divide text into N equal parts: chunkSize = length / N
4. Find word boundaries near each division point
5. Append "…" to non-last chunks to show continuation
```

**Example:** 600-char sentence → N=3 → split into 3 ~200-char chunks: "chunk1 …", "chunk2 …", "chunk3"

**Benefit:** Users see balanced chunks. Final chunk is not tiny.

**Files:** `SentenceSplitter.kt` — `chunkIfNeeded()` method

---

### 5. Paragraph Boundary Support

**Requirement:** Books have paragraphs (EPUB `<p>` tags, TXT blank lines). Respecting these boundaries improves readability.

**Implementation:**
- **EPUB:** `EpubParser.parseHtmlChapter()` iterates over `<p>` elements separately; falls back to full body text if no `<p>` tags
- **TXT:** `TxtParser` splits on `\n{2,}` regex first, then sentence-splits each paragraph

**Result:** Paragraph breaks become natural stopping points between sentences (a new `<p>` or double-newline ends the current sentence).

---

### 6. Title-Only Click Behavior

**Constraint:** Early builds had accidental app opens when tapping near arrow buttons. This frustrated users during quick navigation.

**Solution:**
- Removed global `setContentIntent()` from notification builder
- Only `tv_notification_title` has explicit `setOnClickPendingIntent()` → opens app
- Arrow buttons have click listeners for navigation broadcasts
- Sentence text has no click listener (was causing accidental opens)

**Benefit:** Users can safely tap arrows or sentence text without launching the app.

---

## File Structure

```
NotiBook/
├── app/
│   ├── src/main/
│   │   ├── java/com/notibook/app/
│   │   │   ├── MainActivity.kt                    # Main app activity
│   │   │   ├── NotiBookApp.kt                     # App initialization, notification channel
│   │   │   ├── notification/
│   │   │   │   ├── NotificationHelper.kt          # Build & post notifications
│   │   │   │   ├── NotificationActionReceiver.kt  # Handle ← → dismiss snooze
│   │   │   │   ├── BootReceiver.kt                # Restore on boot
│   │   │   │   ├── SnoozeAlarmReceiver.kt         # Snooze timer
│   │   │   │   └── NotificationService.kt         # [empty, removed]
│   │   │   ├── parsing/
│   │   │   │   ├── EpubParser.kt                  # EPUB extraction
│   │   │   │   ├── TxtParser.kt                   # TXT parsing
│   │   │   │   ├── SentenceSplitter.kt            # Sentence + equal-chunk splitting
│   │   │   │   └── ParseWorker.kt                 # WorkManager task
│   │   │   ├── data/
│   │   │   │   ├── BookRepository.kt              # DB + parser facade
│   │   │   │   └── db/
│   │   │   │       ├── AppDatabase.kt             # Room DB setup
│   │   │   │       ├── BookEntity.kt              # Book data class
│   │   │   │       ├── SentenceEntity.kt          # Sentence data class
│   │   │   │       ├── BookDao.kt                 # Book queries
│   │   │   │       └── SentenceDao.kt             # Sentence queries
│   │   │   └── ui/
│   │   │       ├── detail/
│   │   │       │   ├── BookDetailScreen.kt        # Detail screen UI
│   │   │       │   └── BookDetailViewModel.kt     # Detail screen state
│   │   │       ├── library/
│   │   │       │   ├── LibraryScreen.kt           # Library screen UI
│   │   │       │   └── LibraryViewModel.kt        # Library screen state
│   │   │       ├── navigation/
│   │   │       │   └── AppNavigation.kt           # Nav graph setup
│   │   │       └── theme/
│   │   │           └── Theme.kt                   # Compose theme
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── notification_collapsed.xml     # Small notification layout
│   │   │   │   ├── notification_expanded.xml      # Large notification layout
│   │   │   │   └── notification_main.xml          # [legacy, may remove]
│   │   │   ├── drawable/
│   │   │   │   ├── notif_bottom_fade.xml          # Gradient fade overlay (collapsed)
│   │   │   │   ├── notif_bg_purple.xml            # [legacy]
│   │   │   │   ├── ic_book.xml                    # Notification icon
│   │   │   │   └── [other icons]
│   │   │   └── values/
│   │   │       ├── strings.xml
│   │   │       └── themes.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── ...
├── build.gradle.kts
├── gradle/
│── settings.gradle.kts
├── .gitignore
└── claude.md                                      # This file
```

---

## Current State & Latest Changes

### Session 2 Accomplishments (Current)

1. **Equal-Chunk Splitting** — `SentenceSplitter.kt` rewritten to divide long sentences into N equal-sized chunks instead of greedy 250-char cuts. Non-last chunks get "…" suffix.

2. **Collapsed Notification Fade Overlay** — `notification_collapsed.xml` redesigned with:
   - FrameLayout (64dp fixed height, `clipChildren=true`) containing sentence text + gradient fade
   - Fade overlay: 32dp tall TextView at bottom with `notif_bottom_fade.xml` gradient (purple with transparency)
   - Visual hint that notification can be expanded

3. **Title-Only Click** — `NotificationHelper.kt` updated to remove `setOnClickPendingIntent()` on sentence text. Only title opens the app.

4. **GitHub Repository** — Full project pushed to `https://github.com/YesThats3DPrinted/notibook` with `.gitignore` and initial commit.

### Session 1 Accomplishments (Previous)

- Removed foreground service entirely → swipe-to-dismiss works, both notification sizes visible
- Purple background using `setInt("setBackgroundColor")` technique from NotiNotes
- Notification redesign: arrows in title row, no separate nav row
- Equal-size navigation hit boxes (weight-based layout)
- Paragraph boundary support (EPUB `<p>`, TXT `\n\n`)

---

## Build & Test Workflow

### Build APK
```bash
cd ~/Downloads/Claude\ Code/NotiBook
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Install APK
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Clean Rebuild (if needed)
```bash
./gradlew clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Manual Testing Checklist
- [ ] Import a book (EPUB or TXT)
- [ ] Notification appears with purple background
- [ ] Collapsed notification: title + sentence preview + fade hint at bottom
- [ ] Expand notification: full sentence visible
- [ ] Tap ← : previous sentence (greyed out if first)
- [ ] Tap → : next sentence (greyed out if last)
- [ ] Tap title: app opens to detail screen
- [ ] Tap sentence text: nothing happens (no accidental open)
- [ ] Swipe away notification: dismiss intent fires, `notificationActive` updated
- [ ] Re-open app: previous notification state restored

---

## Known Limitations & Trade-Offs

### 1. Samsung One UI Grey Card Border
- **Issue:** Notification has a grey/dark card background visible around the purple content on Samsung devices
- **Root cause:** Samsung wraps all notifications in an OEM card UI. We can only color our custom inner view, not the card chrome.
- **Why not fixed:** Would require either (a) using standard template (loses custom arrows) or (b) device-specific branching (complexity vs. minor visual issue)
- **Impact:** Functional, just not full-bleed purple. Works fine on MIUI.

### 2. No Scrolling in Expanded Notification
- **Issue:** Long sentences clipped at maxLines=8
- **Root cause:** RemoteViews whitelist doesn't include ScrollView or RecyclerView
- **Workaround:** User taps title to open app and see full sentence
- **Acceptable because:** Most sentences are under 8 lines; users can tap title if needed

### 3. Re-import Books for New Chunking
- **Issue:** Books parsed with old greedy algorithm still use old chunks in DB
- **Solution:** Users need to remove and re-import books to get equal-size chunks
- **Automation:** Could write a migration, but not urgent since user base is small

### 4. Notification Lifespan
- **Issue:** Without foreground service, system can kill notifications under memory pressure
- **Acceptable because:** User can re-enable via app; persistent notifications felt intrusive. The tradeoff is intentional.

---

## Next Steps (If Needed)

1. **Samsung grey border:** Accept as OEM quirk, or investigate `setColorized(true)` behavior on One UI with custom views
2. **Visual polish:** Test on various Android versions (7, 10, 12, 14) to ensure consistent appearance
3. **Book management:** Add book metadata editing, favorites, reading time tracking
4. **Parsing robustness:** Handle edge cases in EPUB (nested tags, CSS styling, images)
5. **Performance:** Profile parsing on large books (1000+ pages) to optimize WorkManager tasks

---

## Useful References

- **NotiNotes GitHub:** https://github.com/Yanndroid/NotiNotes (reference for cross-device notification coloring)
- **Android RemoteViews Whitelist:** Only the view classes listed above are allowed in notification layouts
- **Room Database:** `androidx.room:room-runtime`
- **WorkManager:** `androidx.work:work-runtime-ktx` for async parsing
- **Jetpack Compose:** UI framework for main app screens

---

## Git Workflow (Going Forward)

All changes are committed locally and pushed to GitHub immediately. Each commit includes:
- Clear message describing the change
- "Co-Authored-By: Claude Haiku 4.5" line for transparency
- Logical grouping (one feature = one commit, not one file = one commit)

To revert or check history:
```bash
git log --oneline
git show <commit-hash>
git revert <commit-hash>
```

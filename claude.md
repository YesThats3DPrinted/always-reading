# NotiBook — Android Ebook Reader via Persistent Notifications

## Project Overview

**NotiBook** is an Android ebook reader application that displays books one sentence at a time in persistent notifications. Users can navigate through sentences using arrow buttons (← →) in the notification, and tap the title to open the full app. This unique interface makes reading ambient and asynchronous — you can read while doing other tasks.

**Supported formats:** EPUB, TXT
**Target devices:** Android 7+ (API 24+)
**Notification behavior:** Custom RemoteViews (expanded view only), no foreground service

---

## Architecture

### Core Layers

1. **Notification System** (`notification/`)
   - `NotificationHelper.kt` — builds and posts notifications via `NotificationManagerCompat.notify()`
   - `NotificationActionReceiver.kt` — handles navigation (← →), dismiss, snooze via BroadcastReceiver with `goAsync()` + coroutines
   - `BootReceiver.kt` — restores active notifications on device boot
   - `SnoozeAlarmReceiver.kt` — fires snooze alarms set by user
   - `NotificationService.kt` — empty stub (foreground service removed entirely)

2. **Book Parsing** (`parsing/`)
   - `EpubParser.kt` — extracts text from EPUB files, respects `<p>` paragraph boundaries
   - `TxtParser.kt` — reads TXT files, splits on `\n{2,}` for paragraphs
   - `SentenceSplitter.kt` — ICU BreakIterator + abbreviation/number merging + equal-chunk splitting
   - `ParseWorker.kt` — WorkManager task for async parsing without blocking UI

3. **Database** (`data/db/`)
   - Room ORM with `BookEntity` and `SentenceEntity`
   - `BookDao.kt`, `SentenceDao.kt` for queries
   - Stored data: book title/chapter, sentence text, positions, parsing state

4. **UI** (`ui/`)
   - Jetpack Compose for main app (library and detail screens)
   - `BookDetailScreen` — displays current sentence, notification toggle, restart/remove
   - `BookDetailViewModel` — manages UI state, calls notification system directly

5. **App Initialization** (`NotiBookApp.kt`)
   - Creates notification channel: `IMPORTANCE_DEFAULT`, no sound/vibration
   - `restoreActiveNotifications()` called at app startup — queries active books and re-posts notifications

### Data Flow

```
Import Book (user clicks "Import")
→ ParseWorker parses file (EPUB/TXT)
→ SentenceSplitter breaks into sentences + merges abbreviations + equal chunks
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
→ BroadcastReceiver sets notificationActive = false in DB
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

**History of decisions:**

**Attempt 1 — Purple hardcoded background:**
- Used `setInt("setBackgroundColor", PURPLE)` on root view (technique from NotiNotes GitHub repo)
- Worked on MIUI (transparent card chrome), but Samsung One UI showed purple box inside grey card
- NotiNotes's approach works because they use the standard template; we need custom layouts for arrows

**Attempt 2 — Removed all custom colors:**
- Let Android handle theming automatically
- Problem: custom RemoteViews don't inherit system notification text colors — text defaulted to black, invisible in dark mode

**Current approach — Dark mode detection:**
- `NotificationHelper` checks `Configuration.UI_MODE_NIGHT_MASK` at notification build time
- Sets white text in dark mode, black text in light mode via `setInt("setTextColor", color)`
- Collapsed state uses Android's standard template (no custom view) — system handles its own theming correctly
- Expanded state uses custom view with runtime-detected colors

**Key insight:** Custom RemoteViews must set text colors explicitly. Standard template notifications handle theming automatically.

---

### 4. Single Notification Layout (Expanded Only)

**Decision:** Only one custom layout — the expanded view (`notification_expanded.xml`).

**Why:** The collapsed state is handled by Android's standard template (`setContentTitle` + `setContentText`) which:
- Automatically handles light/dark mode text colors
- Works correctly on all OEMs including Samsung
- No grey-card-around-purple-box issue

**Trade-off:** Collapsed state has no arrows. User must expand the notification to navigate. Accepted as reasonable UX.

**Notification title format:** `"0% · Book Title · Chapter"` — percentage first so it's always visible even on long titles that would otherwise be truncated.

---

### 5. Sentence Splitting Design Philosophy

**Core principle: always err on the side of merging, never on splitting.**

Accidentally merging two sentences produces one slightly longer segment, which the equal-chunk algorithm handles gracefully by splitting into balanced pieces. No reading flow is interrupted.

Accidentally splitting mid-sentence (e.g. breaking on "Mr.") interrupts the reading flow and is far more disruptive to the user experience.

#### Pipeline

```
Raw text
  ↓ ICU BreakIterator (sentence boundaries)
  ↓ Abbreviation/number/Roman numeral merging
  ↓ Equal-chunk splitting (MAX_CHUNK_CHARS = 250)
Final segments stored in DB
```

#### Step 1 — BreakIterator
ICU's `BreakIterator.getSentenceInstance()` identifies sentence boundaries.

#### Step 2 — False boundary merging
After BreakIterator runs, consecutive segments are merged when the first ends with:
- **Known abbreviations:** `Mr.` `Dr.` `etc.` `e.g.` `Jan.` `Ave.` `Inc.` and ~80 others (see `ABBREVIATIONS` set in `SentenceSplitter.kt`)
  - Categories: titles/honorifics, academic degrees, Latin, publishing, time, geography, business/legal, era (B.C./A.D.), units (km/kg/oz...), compass directions, professional terms
- **Any integer:** `1.` `03.` `1995.` — covers ordinal numbers and European date formats like `03. III. 1995.`
- **Roman numerals:** `I.` `III.` `XII.` `MCMXCIX.` — validated with a proper regex to avoid false matches on ordinary words (`mild`, `mixed`, etc.)

#### Step 3 — Equal-chunk splitting
Any segment exceeding 250 chars is divided into N equal pieces:
- `N = ceil(length / 250)` — e.g. 900 chars → 4 chunks of ~225 each
- Each split adjusted to the nearest word boundary
- Non-last chunks get a trailing `" …"` to signal continuation

**Note:** Re-importing books is required after any changes to the splitting logic, since parsed sentences are stored in the DB.

---

### 6. Title-Only Click Behavior

**Decision:** Only the title TextView opens the app. Tapping arrows or sentence text does nothing.

**Implementation:**
- No `setContentIntent()` on the notification builder
- Only `setOnClickPendingIntent(R.id.tv_notification_title, ...)` opens the app
- Arrows have their own broadcast click listeners for navigation
- Sentence text has no click listener

**Why:** Prevents accidental app launches when tapping near arrows during quick navigation.

---

### 7. Arrow Visibility at Boundaries

**Decision:** Arrows become `INVISIBLE` (not `GONE`) at first/last sentence.

- `INVISIBLE` hides the arrow but preserves its space → layout doesn't shift
- `GONE` would cause the title to expand and jump — jarring UX
- At first sentence: `btn_prev` is invisible
- At last sentence: `btn_next` is invisible

---

## File Structure

```
NotiBook/
├── app/src/main/
│   ├── java/com/notibook/app/
│   │   ├── MainActivity.kt
│   │   ├── NotiBookApp.kt
│   │   ├── notification/
│   │   │   ├── NotificationHelper.kt          # Build & post; dark mode detection
│   │   │   ├── NotificationActionReceiver.kt  # ← → dismiss snooze
│   │   │   ├── BootReceiver.kt
│   │   │   ├── SnoozeAlarmReceiver.kt
│   │   │   └── NotificationService.kt         # empty stub
│   │   ├── parsing/
│   │   │   ├── EpubParser.kt                  # EPUB, per-<p> boundaries
│   │   │   ├── TxtParser.kt                   # TXT, \n\n boundaries
│   │   │   ├── SentenceSplitter.kt            # BreakIterator + merge + chunk
│   │   │   └── ParseWorker.kt
│   │   ├── data/
│   │   │   ├── BookRepository.kt
│   │   │   └── db/
│   │   │       ├── AppDatabase.kt
│   │   │       ├── BookEntity.kt
│   │   │       ├── SentenceEntity.kt
│   │   │       ├── BookDao.kt
│   │   │       └── SentenceDao.kt
│   │   └── ui/
│   │       ├── detail/
│   │       │   ├── BookDetailScreen.kt        # Notif toggle, restart, remove
│   │       │   └── BookDetailViewModel.kt
│   │       ├── library/
│   │       │   ├── LibraryScreen.kt
│   │       │   └── LibraryViewModel.kt
│   │       ├── navigation/AppNavigation.kt
│   │       └── theme/Theme.kt
│   ├── res/
│   │   ├── layout/
│   │   │   ├── notification_expanded.xml      # Only custom layout (arrows + sentence)
│   │   │   └── notification_collapsed.xml     # Kept but no longer used
│   │   ├── drawable/
│   │   │   ├── notif_btn_bg.xml               # Rounded grey bg for arrows + title
│   │   │   ├── notif_bottom_fade.xml          # Legacy — not currently used
│   │   │   ├── ic_book.xml
│   │   │   └── [other icons]
│   │   └── values/
│   │       ├── strings.xml
│   │       └── themes.xml
│   └── AndroidManifest.xml
├── build.gradle.kts
├── .gitignore
└── claude.md
```

---

## Current State (Session 3)

### Session 3 Changes

1. **Dark mode text fix** — detect `UI_MODE_NIGHT_MASK` at notification build time; white text in dark mode, black in light mode applied via `setInt("setTextColor")`

2. **Single layout** — removed `setCustomContentView()` (custom collapsed layout). Collapsed state now uses Android's standard template. Custom expanded view only via `setCustomBigContentView()`.

3. **Percentage first in title** — changed from `"Book · Chapter · 0%"` to `"0% · Book · Chapter"` so percentage is always visible even on long truncated titles

4. **Button backgrounds on notification** — added `notif_btn_bg.xml` (rounded 8dp corners, `#22808080` semi-transparent grey) applied to `btn_prev`, `btn_next`, and `tv_notification_title` to make them look tappable

5. **Removed jump controls** — removed "Jump to sentence" and "Jump to page" fields from `BookDetailScreen`. Will be replaced with better navigation later.

6. **Abbreviation merging in splitter** — extended `SentenceSplitter` to merge false sentence boundaries: known abbreviations (~80 terms), any integer, Roman numerals

7. **Expanded abbreviation list** — added era (B.C./A.D.), units (km/kg/oz/etc.), compass directions (N/S/E/W), professional terms (dept/mgr/assoc/etc.), legal (v./art./cl.)

8. **Number & Roman numeral rules** — any integer token before a period is never treated as a sentence end; Roman numerals validated with proper regex to avoid false matches on common words

### Session 2 Changes (Previous)
- Equal-chunk splitting algorithm (N = ceil/250)
- Collapsed notification fade overlay (later removed)
- Title-only click behavior
- GitHub repository setup
- Purple notification background (later removed for cross-device compatibility)

### Session 1 Changes (Previous)
- Removed foreground service → swipe-to-dismiss works
- Notification redesign: arrows in title row
- Equal-size navigation hit boxes (weight-based layout)
- Paragraph boundary support (EPUB `<p>`, TXT `\n\n`)

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
```

**After changing SentenceSplitter:** remove and re-import all books — parsed sentences are stored in the DB and won't be re-split automatically.

### Manual Testing Checklist
- [ ] Import EPUB and TXT
- [ ] Notification appears in correct color for light/dark mode
- [ ] Expand notification: ← title → row with grey button backgrounds, sentence text below
- [ ] Tap ← / → : navigate sentences; arrow disappears at first/last
- [ ] Tap title: opens app
- [ ] Tap sentence text: nothing happens
- [ ] Swipe away: fires dismiss intent, notificationActive updated
- [ ] Title shows `"0% · Book · Chapter"` format
- [ ] "Mr. Smith" is not split across two segments
- [ ] "03. III. 1995." date is not split
- [ ] Re-open app after force-close: notification still present

---

## Known Limitations

1. **No scrolling in expanded notification** — RemoteViews whitelist excludes ScrollView. Long segments capped at 8 lines. User can tap title to read full text in app.

2. **No custom collapsed layout** — collapsed state uses Android's standard template (no arrows). User must expand to navigate.

3. **Notification lifespan** — no foreground service means system can kill under memory pressure. Intentional trade-off (MIUI compatibility).

4. **Re-import required after splitter changes** — DB stores pre-parsed sentences; no auto-migration.

5. **Samsung grey card border** (historical) — custom RemoteViews sit inside Samsung's card chrome. Solved by removing the purple background and using standard template for collapsed state.

---

## Git Workflow & Commit Strategy

**When to push to GitHub:**
- After a major architectural or feature change (e.g., removing foreground service, switching notification layouts, adding abbreviation merging)
- When 3–5 small changes have accumulated and form a logical unit
- Before major refactoring or risky changes (for safety)
- **NOT** after every tiny adjustment (single-line fixes, minor tweaks)

This keeps the commit history clean, navigable, and meaningful rather than hundreds of granular commits. Use `git log --oneline` to review what's been committed locally before deciding to push.

**Local commits between pushes** are fine — they're safe on disk and can be amended/squashed if needed before pushing. Use:

```bash
git log --oneline        # view local + remote history
git show <hash>          # inspect a commit
git revert <hash>        # safely undo a commit
git commit --amend       # modify the last local commit (before push)
git push                 # push accumulated commits to GitHub
```

Each commit message includes `Co-Authored-By: Claude Haiku/Sonnet 4.x <noreply@anthropic.com>` to track AI contributions.

package com.notibook.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val coverPath: String? = null,
    val filePath: String,
    /** Total sentence count once parsing is done; 0 while parsing. */
    val totalSentences: Int = 0,
    /** Updated in batches during parsing so the UI can show a progress bar. */
    val parsedSentenceCount: Int = 0,
    val isParsing: Boolean = true,
    /** 0-based index of the sentence currently shown in the notification. */
    val currentIndex: Int = 0,
    /** Chapter name of the current sentence (may be blank). */
    val currentChapter: String = "",
    /** Whether the notification is currently active (shown in the shade). */
    val notificationActive: Boolean = false,

    // ── In-app reader position ───────────────────────────────────────────────
    /** 0-based index of the spine item currently open in the EPUB reader. */
    val readerSpineIndex: Int = 0,
    /** Scroll position within the current spine item (0.0 = top, 1.0 = bottom). */
    val readerScrollPercent: Float = 0f,
    /** True if the notification was active when the reader was opened (so we can restore it on close). */
    val notifWasActiveBeforeReader: Boolean = false
)

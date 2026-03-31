package com.notibook.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sentences",
    indices = [Index(value = ["bookId", "sentenceIndex"])]
)
data class SentenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val sentenceIndex: Int,
    val text: String,
    val chapter: String = "",
    /** 0-based index of the EPUB spine item this sentence belongs to (0 for TXT). */
    val spineItemIndex: Int = 0,
    /** 0-based index of the block element (p, h1, table, img, etc.) within this spine item. */
    val blockIndex: Int = 0,
    /** SENTENCE, IMAGE, TABLE, or DIVIDER. DIVIDER entries are skipped in notifications. */
    val type: String = "SENTENCE"
)

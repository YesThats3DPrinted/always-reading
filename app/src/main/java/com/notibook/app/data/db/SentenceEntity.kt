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
    val chapter: String = ""
)

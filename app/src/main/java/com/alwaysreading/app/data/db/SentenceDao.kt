package com.alwaysreading.app.data.db

import androidx.room.*

@Dao
interface SentenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sentences: List<SentenceEntity>)

    @Query("SELECT * FROM sentences WHERE bookId = :bookId AND sentenceIndex = :index LIMIT 1")
    suspend fun getSentence(bookId: Long, index: Int): SentenceEntity?

    @Query("DELETE FROM sentences WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long)

    @Query("SELECT * FROM sentences WHERE bookId = :bookId AND chapter = :chapter ORDER BY sentenceIndex")
    suspend fun getSentencesForChapter(bookId: Long, chapter: String): List<SentenceEntity>

    @Query("SELECT * FROM sentences WHERE bookId = :bookId AND spineItemIndex = :spineItemIndex ORDER BY sentenceIndex")
    suspend fun getSentencesForSpineItem(bookId: Long, spineItemIndex: Int): List<SentenceEntity>

    @Query("SELECT * FROM sentences WHERE bookId = :bookId ORDER BY sentenceIndex")
    suspend fun getAllForBook(bookId: Long): List<SentenceEntity>
}

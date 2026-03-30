package com.notibook.app.data.db

import androidx.room.*

@Dao
interface SentenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sentences: List<SentenceEntity>)

    @Query("SELECT * FROM sentences WHERE bookId = :bookId AND sentenceIndex = :index LIMIT 1")
    suspend fun getSentence(bookId: Long, index: Int): SentenceEntity?

    @Query("DELETE FROM sentences WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long)
}

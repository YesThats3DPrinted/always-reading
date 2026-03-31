package com.notibook.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY id DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun getBookFlow(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBook(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE notificationActive = 1")
    suspend fun getActiveBooks(): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity): Long

    @Update
    suspend fun updateBook(book: BookEntity)

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Query("UPDATE books SET currentIndex = :index, currentChapter = :chapter WHERE id = :bookId")
    suspend fun updatePosition(bookId: Long, index: Int, chapter: String)

    @Query("UPDATE books SET notificationActive = :active WHERE id = :bookId")
    suspend fun updateNotificationActive(bookId: Long, active: Boolean)

    @Query("UPDATE books SET parsedSentenceCount = :count WHERE id = :bookId")
    suspend fun updateParsedCount(bookId: Long, count: Int)

    @Query("UPDATE books SET totalSentences = :total, isParsing = 0 WHERE id = :bookId")
    suspend fun markParsingComplete(bookId: Long, total: Int)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteById(bookId: Long)

    @Query("UPDATE books SET readerSpineIndex = :spineIndex, readerScrollPercent = :scrollPct WHERE id = :bookId")
    suspend fun updateReaderPosition(bookId: Long, spineIndex: Int, scrollPct: Float)

    @Query("UPDATE books SET notifWasActiveBeforeReader = :was WHERE id = :bookId")
    suspend fun updateNotifWasActive(bookId: Long, was: Boolean)
}

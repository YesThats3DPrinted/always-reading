package com.notibook.app.data

import com.notibook.app.data.db.BookDao
import com.notibook.app.data.db.BookEntity
import com.notibook.app.data.db.SentenceDao
import com.notibook.app.data.db.SentenceEntity
import kotlinx.coroutines.flow.Flow

class BookRepository(
    private val bookDao: BookDao,
    private val sentenceDao: SentenceDao
) {
    fun getAllBooks(): Flow<List<BookEntity>> = bookDao.getAllBooks()

    fun getBookFlow(id: Long): Flow<BookEntity?> = bookDao.getBookFlow(id)

    suspend fun getBook(id: Long): BookEntity? = bookDao.getBook(id)

    suspend fun getActiveBooks(): List<BookEntity> = bookDao.getActiveBooks()

    suspend fun insertBook(book: BookEntity): Long = bookDao.insertBook(book)

    suspend fun updateBook(book: BookEntity) = bookDao.updateBook(book)

    suspend fun deleteBook(book: BookEntity) {
        sentenceDao.deleteByBookId(book.id)
        bookDao.deleteById(book.id)
    }

    suspend fun updatePosition(bookId: Long, index: Int, chapter: String) =
        bookDao.updatePosition(bookId, index, chapter)

    suspend fun updateNotificationActive(bookId: Long, active: Boolean) =
        bookDao.updateNotificationActive(bookId, active)

    suspend fun updateParsedCount(bookId: Long, count: Int) =
        bookDao.updateParsedCount(bookId, count)

    suspend fun markParsingComplete(bookId: Long, total: Int) =
        bookDao.markParsingComplete(bookId, total)

    suspend fun insertSentences(sentences: List<SentenceEntity>) =
        sentenceDao.insertAll(sentences)

    suspend fun getSentence(bookId: Long, index: Int): SentenceEntity? =
        sentenceDao.getSentence(bookId, index)
}

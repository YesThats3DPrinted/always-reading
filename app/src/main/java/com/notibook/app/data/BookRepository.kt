package com.notibook.app.data

import android.content.Context
import com.notibook.app.data.db.BookDao
import com.notibook.app.data.db.BookEntity
import com.notibook.app.data.db.SentenceDao
import com.notibook.app.data.db.SentenceEntity
import com.notibook.app.epub.EpubExtractor
import kotlinx.coroutines.flow.Flow

class BookRepository(
    private val bookDao: BookDao,
    private val sentenceDao: SentenceDao,
    private val context: Context
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
        EpubExtractor.clearCache(context, book.id)
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

    suspend fun getSentencesForChapter(bookId: Long, chapter: String): List<SentenceEntity> =
        sentenceDao.getSentencesForChapter(bookId, chapter)

    suspend fun getSentencesForSpineItem(bookId: Long, spineItemIndex: Int): List<SentenceEntity> =
        sentenceDao.getSentencesForSpineItem(bookId, spineItemIndex)

    suspend fun getAllSentencesForBook(bookId: Long): List<SentenceEntity> =
        sentenceDao.getAllForBook(bookId)

    /** Save exact character offset when reader closes. */
    suspend fun updateReaderCharOffset(bookId: Long, charOffset: Long) =
        bookDao.updateReaderCharOffset(bookId, charOffset)

    /**
     * Clear reader position so the next open uses the notification's currentIndex.
     * Called after the user navigates via notification arrows.
     */
    suspend fun clearReaderCharOffset(bookId: Long) =
        bookDao.clearReaderCharOffset(bookId)
}

package com.notibook.app.parsing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.notibook.app.NotiBookApp
import com.notibook.app.data.db.SentenceEntity
import java.io.File

class ParseWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_BOOK_ID = "book_id"
        private const val BATCH_SIZE = 200
    }

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        if (bookId == -1L) return Result.failure()

        val repo = (context.applicationContext as NotiBookApp).repository
        val book = repo.getBook(bookId) ?: return Result.failure()

        return try {
            val file = File(book.filePath)
            if (!file.exists()) {
                repo.markParsingComplete(bookId, 0)
                return Result.failure()
            }

            // Parse into (sentenceText, chapterTitle, spineItemIndex) triples.
            // TXT files use Pair<String,String> — we lift them to triples with spineItemIndex=0.
            val rawSentences: List<Triple<String, String, Int>> = when {
                book.filePath.endsWith(".epub", ignoreCase = true) -> {
                    val result = EpubParser.parse(context, file, bookId)
                    // Update title/author/cover from EPUB metadata if they were guessed
                    val updatedBook = repo.getBook(bookId) ?: return Result.failure()
                    repo.updateBook(
                        updatedBook.copy(
                            title = result.metadata.title.ifBlank { updatedBook.title },
                            author = result.metadata.author.ifBlank { updatedBook.author },
                            coverPath = result.metadata.coverPath ?: updatedBook.coverPath
                        )
                    )
                    result.sentences
                }
                book.filePath.endsWith(".txt", ignoreCase = true) -> {
                    TxtParser.parse(file).map { (text, chapter) -> Triple(text, chapter, 0) }
                }
                else -> {
                    repo.markParsingComplete(bookId, 0)
                    return Result.failure()
                }
            }

            // Insert sentences in batches, updating progress count after each batch
            val buffer = mutableListOf<SentenceEntity>()
            rawSentences.forEachIndexed { index, (text, chapter, spineItemIndex) ->
                buffer.add(
                    SentenceEntity(
                        bookId = bookId,
                        sentenceIndex = index,
                        text = text,
                        chapter = chapter,
                        spineItemIndex = spineItemIndex
                    )
                )
                if (buffer.size >= BATCH_SIZE) {
                    repo.insertSentences(buffer.toList())
                    repo.updateParsedCount(bookId, index + 1)
                    buffer.clear()
                }
            }
            if (buffer.isNotEmpty()) {
                repo.insertSentences(buffer)
            }

            repo.markParsingComplete(bookId, rawSentences.size)
            Result.success()
        } catch (e: Exception) {
            // Leave isParsing = false with 0 sentences so the UI shows an error state
            repo.markParsingComplete(bookId, 0)
            Result.failure()
        }
    }
}

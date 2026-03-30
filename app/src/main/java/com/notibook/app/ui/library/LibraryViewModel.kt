package com.notibook.app.ui.library

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.notibook.app.NotiBookApp
import com.notibook.app.data.db.BookEntity
import com.notibook.app.parsing.ParseWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as NotiBookApp).repository

    val books: StateFlow<List<BookEntity>> = repo.getAllBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun importBook(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<NotiBookApp>()
            val resolver = context.contentResolver

            // Resolve the display name from the content URI
            val fileName = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && col >= 0) cursor.getString(col) else null
            } ?: "book"

            val ext = fileName.substringAfterLast(".", "").lowercase()
            if (ext !in listOf("epub", "txt")) return@launch   // unsupported format

            // Copy to internal storage so we can re-parse later without storage permissions
            val booksDir = File(context.filesDir, "books").also { it.mkdirs() }
            val destFile = File(booksDir, "${System.currentTimeMillis()}.$ext")
            try {
                resolver.openInputStream(uri)?.use { it.copyTo(destFile.outputStream()) }
                    ?: return@launch
            } catch (e: Exception) {
                destFile.delete()
                return@launch
            }

            // Guess title from file name until the EPUB parser fills in the real one
            val titleGuess = fileName.substringBeforeLast(".")

            val bookId = repo.insertBook(
                BookEntity(title = titleGuess, author = "", filePath = destFile.absolutePath)
            )

            // Hand off to WorkManager so parsing survives process death
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ParseWorker>()
                    .setInputData(Data.Builder().putLong(ParseWorker.KEY_BOOK_ID, bookId).build())
                    .build()
            )
        }
    }
}

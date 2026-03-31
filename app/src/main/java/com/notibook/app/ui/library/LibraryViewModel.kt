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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as NotiBookApp).repository

    val books: StateFlow<List<BookEntity>> = repo.getAllBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Selection state ───────────────────────────────────────────────────────

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    val isSelectionMode: StateFlow<Boolean> =
        MutableStateFlow(false).also { flow ->
            viewModelScope.launch {
                _selectedIds.collect { flow.value = it.isNotEmpty() }
            }
        }

    fun toggleSelection(bookId: Long) {
        _selectedIds.update { current ->
            if (bookId in current) current - bookId else current + bookId
        }
    }

    fun selectAll() {
        _selectedIds.value = books.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun deleteSelected() {
        val toDelete = _selectedIds.value.toSet()
        _selectedIds.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            toDelete.forEach { bookId ->
                val book = repo.getBook(bookId) ?: return@forEach
                repo.deleteBook(book)
            }
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    fun importBook(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<NotiBookApp>()
            val resolver = context.contentResolver

            val fileName = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && col >= 0) cursor.getString(col) else null
            } ?: "book"

            val ext = fileName.substringAfterLast(".", "").lowercase()
            if (ext !in listOf("epub", "txt")) return@launch

            val booksDir = File(context.filesDir, "books").also { it.mkdirs() }
            val destFile = File(booksDir, "${System.currentTimeMillis()}.$ext")
            try {
                resolver.openInputStream(uri)?.use { it.copyTo(destFile.outputStream()) }
                    ?: return@launch
            } catch (e: Exception) {
                destFile.delete()
                return@launch
            }

            val titleGuess = fileName.substringBeforeLast(".")
            val bookId = repo.insertBook(
                BookEntity(title = titleGuess, author = "", filePath = destFile.absolutePath)
            )

            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ParseWorker>()
                    .setInputData(Data.Builder().putLong(ParseWorker.KEY_BOOK_ID, bookId).build())
                    .build()
            )
        }
    }
}

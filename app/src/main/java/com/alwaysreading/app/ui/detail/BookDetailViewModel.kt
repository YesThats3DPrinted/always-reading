package com.alwaysreading.app.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alwaysreading.app.AlwaysReadingApp
import com.alwaysreading.app.data.db.BookEntity
import com.alwaysreading.app.data.db.SentenceEntity
import com.alwaysreading.app.notification.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as AlwaysReadingApp).repository

    private val _bookId = MutableStateFlow(-1L)

    @OptIn(ExperimentalCoroutinesApi::class)
    val book: StateFlow<BookEntity?> = _bookId
        .filter { it != -1L }
        .flatMapLatest { id -> repo.getBookFlow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentSentence: StateFlow<SentenceEntity?> = book
        .filterNotNull()
        .flatMapLatest { b ->
            flow { emit(repo.getSentence(b.id, b.currentIndex)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun load(bookId: Long) {
        if (_bookId.value != bookId) _bookId.value = bookId
    }

    fun toggleNotification(enable: Boolean) {
        val b = book.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repo.updateNotificationActive(b.id, enable)
            val ctx = getApplication<AlwaysReadingApp>()
            if (enable) {
                val sentence = repo.getSentence(b.id, b.currentIndex) ?: return@launch
                NotificationHelper.show(ctx, b, sentence)
                // Battery optimisation dialog — shown after notification is already posted
                withContext(Dispatchers.Main) { ctx.requestBatteryOptimisationExemption() }
            } else {
                NotificationHelper.hide(ctx, b.id)
            }
        }
    }

    fun jumpToSentence(zeroBasedIndex: Int) {
        val b = book.value ?: return
        val clamped = zeroBasedIndex.coerceIn(0, (b.totalSentences - 1).coerceAtLeast(0))
        viewModelScope.launch(Dispatchers.IO) {
            val sentence = repo.getSentence(b.id, clamped) ?: return@launch
            repo.updatePosition(b.id, clamped, sentence.chapter)
            if (b.notificationActive) {
                NotificationHelper.show(getApplication(), b, sentence)
            }
        }
    }

    fun jumpToPage(oneBasedPage: Int) = jumpToSentence((oneBasedPage - 1) * 15)

    fun restart() = jumpToSentence(0)

    fun removeBook(onRemoved: () -> Unit) {
        val b = book.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (b.notificationActive) NotificationHelper.hide(getApplication(), b.id)
            repo.deleteBook(b)
            launch(Dispatchers.Main) { onRemoved() }
        }
    }
}

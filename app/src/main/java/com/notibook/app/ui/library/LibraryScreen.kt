package com.notibook.app.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.notibook.app.R
import com.notibook.app.data.db.BookEntity
import java.io.File

// ── App-wide color palette (same values as reader) ───────────────────────────
private val BG_COLOR   = Color(0xFF1A1A1A)
private val CARD_COLOR = Color(0xFF2C2C2C)
private val TEXT_COLOR = Color(0xFFE0E0E0)
private val DIM_COLOR  = Color(0xFF8A8A8A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (bookId: Long) -> Unit,
    vm: LibraryViewModel = viewModel()
) {
    val books         by vm.books.collectAsState()
    val selectedIds   by vm.selectedIds.collectAsState()
    val isSelectionMode by vm.isSelectionMode.collectAsState()

    BackHandler(enabled = isSelectionMode) { vm.clearSelection() }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importBook(it) } }

    val topBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor       = CARD_COLOR,
        titleContentColor    = TEXT_COLOR,
        actionIconContentColor = TEXT_COLOR
    )

    Scaffold(
        containerColor = BG_COLOR,
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title   = { Text("${selectedIds.size} selected") },
                    colors  = topBarColors,
                    actions = {
                        TextButton(onClick = { vm.selectAll() }) {
                            Text("Select all", color = TEXT_COLOR)
                        }
                        IconButton(onClick = { vm.deleteSelected() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected", tint = TEXT_COLOR)
                        }
                    }
                )
            } else {
                TopAppBar(
                    title  = { Text("Always Reading") },
                    colors = topBarColors
                )
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(
                    onClick          = { filePicker.launch(arrayOf("application/epub+zip", "text/plain", "*/*")) },
                    containerColor   = CARD_COLOR,
                    contentColor     = TEXT_COLOR
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Import book")
                }
            }
        }
    ) { padding ->
        if (books.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No books yet.\nTap + to import an EPUB or TXT file.",
                    color     = DIM_COLOR,
                    style     = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                contentPadding      = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier            = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(books, key = { it.id }) { book ->
                    val isSelected = book.id in selectedIds
                    BookCard(
                        book            = book,
                        isSelectionMode = isSelectionMode,
                        isSelected      = isSelected,
                        onClick = {
                            if (isSelectionMode) vm.toggleSelection(book.id)
                            else if (!book.isParsing) onBookClick(book.id)
                        },
                        onLongClick          = { vm.toggleSelection(book.id) },
                        onNotificationToggle = { vm.toggleNotification(book) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookEntity,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onNotificationToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors    = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF3C3C3C) else CARD_COLOR
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {

            // Selection checkbox
            if (isSelectionMode) {
                Icon(
                    imageVector        = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (isSelected) "Selected" else "Not selected",
                    tint               = if (isSelected) TEXT_COLOR else DIM_COLOR,
                    modifier           = Modifier
                        .size(24.dp)
                        .padding(end = 4.dp)
                )
                Spacer(Modifier.width(8.dp))
            }

            // Cover thumbnail
            if (book.coverPath != null) {
                AsyncImage(
                    model              = File(book.coverPath),
                    contentDescription = "Cover of ${book.title}",
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(width = 64.dp, height = 90.dp)
                )
            } else {
                Surface(
                    modifier = Modifier.size(width = 64.dp, height = 90.dp),
                    color    = Color(0xFF404040)
                ) {}
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = book.title,
                    color      = TEXT_COLOR,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis
                )
                if (book.author.isNotBlank()) {
                    Text(
                        text  = book.author,
                        color = DIM_COLOR,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (book.isParsing) {
                    Text("Parsing…", color = DIM_COLOR, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    if (book.parsedSentenceCount > 0) {
                        val approxTotal = (book.parsedSentenceCount * 1.2f).coerceAtLeast(1f)
                        LinearProgressIndicator(
                            progress = { (book.parsedSentenceCount / approxTotal).coerceIn(0f, 0.95f) },
                            modifier = Modifier.fillMaxWidth(),
                            color    = TEXT_COLOR,
                            trackColor = DIM_COLOR.copy(alpha = 0.3f)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier   = Modifier.fillMaxWidth(),
                            color      = TEXT_COLOR,
                            trackColor = DIM_COLOR.copy(alpha = 0.3f)
                        )
                    }
                } else {
                    val display = if (book.totalSentences > 0)
                        "Sentence ${book.currentIndex + 1} of ${"%,d".format(book.totalSentences)}"
                    else "Empty or unreadable file"
                    Text(display, color = DIM_COLOR, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (!isSelectionMode && !book.isParsing) {
                IconButton(onClick = { onNotificationToggle() }) {
                    AnimatedContent(
                        targetState    = book.notificationActive,
                        transitionSpec = {
                            fadeIn(tween(350)) togetherWith fadeOut(tween(350))
                        },
                        label = "notif_icon_fade"
                    ) { active ->
                        Icon(
                            painter            = painterResource(
                                if (active) R.drawable.ic_notification_reader_on
                                else        R.drawable.ic_notification_reader_off
                            ),
                            contentDescription = if (active) "Disable notification" else "Enable notification",
                            modifier           = Modifier.size(26.dp),
                            tint               = Color.Unspecified
                        )
                    }
                }
            }
        }
    }
}

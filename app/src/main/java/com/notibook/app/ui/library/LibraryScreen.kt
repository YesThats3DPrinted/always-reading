package com.notibook.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.notibook.app.data.db.BookEntity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (bookId: Long) -> Unit,
    vm: LibraryViewModel = viewModel()
) {
    val books by vm.books.collectAsState()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importBook(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("NotiBook") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                filePicker.launch(
                    // Offer common MIME types; the "*/*" fallback covers .epub on picky devices
                    arrayOf("application/epub+zip", "text/plain", "*/*")
                )
            }) {
                Icon(Icons.Default.Add, contentDescription = "Import book")
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
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        onClick = { onBookClick(book.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BookCard(book: BookEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {

            // Cover thumbnail (or a plain coloured placeholder)
            if (book.coverPath != null) {
                AsyncImage(
                    model = File(book.coverPath),
                    contentDescription = "Cover of ${book.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 64.dp, height = 90.dp)
                )
            } else {
                Surface(
                    modifier = Modifier.size(width = 64.dp, height = 90.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {}
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.author.isNotBlank()) {
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (book.isParsing) {
                    Text("Parsing…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    // Show determinate progress once we have a sentence count to work with
                    if (book.parsedSentenceCount > 0) {
                        val approxTotal = (book.parsedSentenceCount * 1.2f).coerceAtLeast(1f)
                        LinearProgressIndicator(
                            progress = { (book.parsedSentenceCount / approxTotal).coerceIn(0f, 0.95f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    val display = if (book.totalSentences > 0)
                        "Sentence ${book.currentIndex + 1} of ${"%,d".format(book.totalSentences)}"
                    else "Empty or unreadable file"
                    Text(display, style = MaterialTheme.typography.bodySmall)
                }

                if (book.notificationActive) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "● Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

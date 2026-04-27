package com.alwaysreading.app.ui.detail

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: Long,
    onBack: () -> Unit,
    vm: BookDetailViewModel = viewModel()
) {
    LaunchedEffect(bookId) { vm.load(bookId) }

    val book by vm.book.collectAsState()
    val sentence by vm.currentSentence.collectAsState()
    val context = LocalContext.current

    var showRestartDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    // Android 13+ requires runtime permission for POST_NOTIFICATIONS
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.toggleNotification(true)
        else {
            // Guide user to settings if permanently denied
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            )
        }
    }

    if (book == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val b = book!!

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(b.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Author
            if (b.author.isNotBlank()) {
                Text(b.author, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Current sentence preview
            if (b.isParsing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Parsing book, please wait…", style = MaterialTheme.typography.bodySmall)
            } else if (b.totalSentences == 0) {
                Text("Could not extract any text from this file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            } else {
                Text(
                    "Sentence ${b.currentIndex + 1} of ${"%,d".format(b.totalSentences)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                sentence?.let { s ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = s.text,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            HorizontalDivider()

            // ── Notification toggle ───────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show notification", style = MaterialTheme.typography.bodyLarge)
                    Text("Keep this book visible in the notification shade",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = b.notificationActive,
                    enabled = !b.isParsing && b.totalSentences > 0,
                    onCheckedChange = { enable ->
                        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.toggleNotification(enable)
                        }
                    }
                )
            }

            HorizontalDivider()

            // ── Restart ───────────────────────────────────────────────────
            OutlinedButton(
                onClick = { showRestartDialog = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !b.isParsing && b.totalSentences > 0
            ) { Text("Restart from sentence 1") }

            // ── Remove book ───────────────────────────────────────────────
            Button(
                onClick = { showRemoveDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) { Text("Remove book") }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("Restart?") },
            text = { Text("Reset back to sentence 1? Your current position will be lost.") },
            confirmButton = {
                TextButton(onClick = { vm.restart(); showRestartDialog = false }) {
                    Text("Restart")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Remove book?") },
            text = { Text("\"${b.title}\" and all its data will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeBook(onRemoved = onBack)
                    showRemoveDialog = false
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) { Text("Cancel") }
            }
        )
    }
}

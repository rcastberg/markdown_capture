package org.castberg.markdowncapture.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.castberg.markdowncapture.data.CaptureRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureDetailScreen(
    record: CaptureRecord,
    tabNames: List<String>,
    onResubmit: (tabIndex: Int) -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    var showResubmitSheet by remember { mutableStateOf(false) }
    var showOverflow      by remember { mutableStateOf(false) }
    var renderedView      by remember { mutableStateOf(true) }

    // Load the full .md file from SAF so images appended by saveFiles() are included
    var fullMarkdown by remember { mutableStateOf(record.markdown) }
    LaunchedEffect(record.baseName) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val folder = DocumentFile.fromTreeUri(context, Uri.parse(record.folderUri))
                val mdDoc  = folder?.findFile("${record.baseName}.md")
                mdDoc?.let { context.contentResolver.openInputStream(it.uri)?.use { s -> s.bufferedReader().readText() } }
            }.getOrNull()
        }
        if (loaded != null) fullMarkdown = loaded
    }

    val blocks by remember(fullMarkdown) {
        mutableStateOf(parseMarkdown(fullMarkdown))
    }

    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val dateTime  = Instant.ofEpochMilli(record.timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(record.baseName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            formatter.format(dateTime),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                },
                actions = {
                    // Raw / Rendered toggle
                    TextButton(onClick = { renderedView = !renderedView }) {
                        Text(if (renderedView) "Raw" else "Rendered")
                    }
                    // Share overflow menu
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Share")
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share as Markdown") },
                                onClick = {
                                    showOverflow = false
                                    shareMarkdownFile(context, record.baseName, record.folderUri)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share as PDF") },
                                onClick = {
                                    showOverflow = false
                                    scope.launch {
                                        generateAndSharePdf(context, record.baseName, record.folderUri, blocks)
                                    }
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (renderedView) {
                RenderedMarkdown(
                    blocks    = blocks,
                    folderUri = record.folderUri
                )
            } else {
                Text(
                    fullMarkdown,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
            }

            Spacer(Modifier.height(16.dp))
            if (tabNames.isNotEmpty()) {
                OutlinedButton(
                    onClick  = { showResubmitSheet = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Resubmit with different mode")
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showResubmitSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showResubmitSheet = false },
            sheetState       = sheetState
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Resubmit with", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The original image will be re-analysed using the selected mode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                tabNames.forEachIndexed { index, name ->
                    OutlinedButton(
                        onClick  = { showResubmitSheet = false; onResubmit(index) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(name) }
                }
                TextButton(
                    onClick  = { showResubmitSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancel") }
            }
        }
    }
}

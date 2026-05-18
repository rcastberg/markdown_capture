package org.castberg.markdowncapture.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.castberg.markdowncapture.data.CaptureRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    records: List<CaptureRecord>,
    onSelect: (CaptureRecord) -> Unit,
    onDismiss: () -> Unit
) {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Recent Captures",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
        )

        if (records.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No captures yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(records) { record ->
                    val dateTime = Instant.ofEpochMilli(record.timestamp)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()

                    ListItem(
                        headlineContent = { Text(record.baseName) },
                        supportingContent = {
                            Text(
                                formatter.format(dateTime),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        trailingContent = if (record.hasError) {
                            { Icon(Icons.Default.Warning, contentDescription = "Analysis failed", tint = MaterialTheme.colorScheme.error) }
                        } else null,
                        modifier = Modifier.clickable { onSelect(record) }
                    )
                    HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

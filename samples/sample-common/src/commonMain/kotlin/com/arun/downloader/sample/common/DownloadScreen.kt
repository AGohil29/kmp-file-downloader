package com.arun.downloader.sample.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arun.downloader.core.FileDownloader
import com.arun.downloader.core.model.ConflictStrategy
import com.arun.downloader.core.model.DownloadPriority
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloadState
import kotlinx.coroutines.launch

private const val SAMPLE_GROUP = "sample_app"

data class SampleDownload(val label: String, val url: String, val fileName: String)

val SAMPLE_DOWNLOADS = listOf(
    SampleDownload(
        "Test 1mb",
        "https://cdn.truefilesize.com/test/test-1mb.bin",
        "Test_1_mb.bin"
    ),
    SampleDownload(
        "Test 5mb",
        "https://cdn.truefilesize.com/test/test-5mb.bin",
        "Test_5_mb.bin"
    ),
    SampleDownload(
        "Test 10mb",
        "https://cdn.truefilesize.com/test/test-10mb.bin",
        "Test_10_mb.bin"
    ),
    SampleDownload(
        "Test 50mb",
        "https://cdn.truefilesize.com/test/test-50mb.bin",
        "Test_50_mb.bin"
    ),
    SampleDownload(
        "Test 100mb",
        "https://cdn.truefilesize.com/test/test-100mb.bin",
        "Test_100_mb.bin"
    ),
    SampleDownload(
        "Test 200mb",
        "https://cdn.truefilesize.com/test/test-200mb.bin",
        "Test_200_mb.bin"
    )
)

@Composable
fun DownloadScreen(
    downloader: FileDownloader,
    paths: PlatformPaths,
    modifier: Modifier = Modifier,
    onEnqueueBackground: ((url: String, fileName: String?) -> Unit)? = null
) {
    var urlInput by remember { mutableStateOf(SAMPLE_DOWNLOADS.first().url) }
    var fileNameInput by remember { mutableStateOf(SAMPLE_DOWNLOADS.first().fileName) }
    var downloads by remember { mutableStateOf<List<DownloadState>>(emptyList()) }

    val scope = rememberCoroutineScope()

    fun enqueue(url: String, fileName: String?) {
        scope.launch {
            downloader.enqueue(
                DownloadRequest(
                    url = url.trim(),
                    destinationDirectory = paths.downloadDirectory,
                    fileName = fileName?.takeIf { it.isNotBlank() },
                    priority = DownloadPriority.HIGH,
                    conflictStrategy = ConflictStrategy.RENAME_NEW_INCREMENTAL,
                    group = SAMPLE_GROUP
                )
            )
        }
    }

    LaunchedEffect(downloader) {
        downloader.observeAll().collect { states ->
            downloads = states
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "KMP Downloader",
                                style = MaterialTheme.typography.h6.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                "Unified Compose Multiplatform Client",
                                style = MaterialTheme.typography.caption.copy(color = Color.White.copy(alpha = 0.8f))
                            )
                        }
                    },
                    backgroundColor = Color(0xFF1565C0),
                    contentColor = Color.White,
                    elevation = 4.dp
                )
            },
            modifier = modifier
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Enqueue New Download Card
                Card(
                    elevation = 3.dp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Enqueue New Download",
                            style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Bold)
                        )

                        Text(
                            text = "Samples",
                            style = MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Bold)
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(SAMPLE_DOWNLOADS) { sample ->
                                OutlinedButton(
                                    onClick = {
                                        urlInput = sample.url
                                        fileNameInput = sample.fileName
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(sample.label, fontSize = 12.sp)
                                }
                            }
                        }

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("Download URL (HTTP/HTTPS)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = fileNameInput,
                            onValueChange = { fileNameInput = it },
                            label = { Text("Target File Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = "Save directory: ${paths.downloadDirectory}",
                            style = MaterialTheme.typography.caption.copy(color = Color.Gray),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            OutlinedButton(
                                onClick = { SAMPLE_DOWNLOADS.forEach { enqueue(it.url, it.fileName) } },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Enqueue All Samples")
                            }
                            if (onEnqueueBackground != null) {
                                OutlinedButton(
                                    onClick = {
                                        if (urlInput.isNotBlank()) {
                                            onEnqueueBackground(
                                                urlInput.trim(),
                                                fileNameInput.takeIf { it.isNotBlank() }
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Background")
                                }
                            }
                            Button(
                                onClick = {
                                    if (urlInput.isNotBlank()) enqueue(urlInput, fileNameInput)
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Enqueue", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Section 2: Bulk Operations Toolbar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tasks (${downloads.size})",
                        style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Bold)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { scope.launch { downloader.pauseGroup(SAMPLE_GROUP) } },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Pause All", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { scope.launch { downloader.resumeGroup(SAMPLE_GROUP) } },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Resume All", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { scope.launch { downloader.cancelGroup(SAMPLE_GROUP) } },
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
                        ) {
                            Text("Cancel All", fontSize = 12.sp)
                        }
                    }
                }

                Divider()

                // Section 3: Reactive Downloads Queue
                if (downloads.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No active downloads in persistent queue",
                            style = MaterialTheme.typography.body2.copy(color = Color.Gray)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(downloads, key = { it.id.raw }) { state ->
                            DownloadItemCard(
                                state = state,
                                onPause = { scope.launch { downloader.pause(state.id) } },
                                onResume = { scope.launch { downloader.resume(state.id) } },
                                onRetry = { scope.launch { downloader.retry(state.id) } },
                                onDelete = { scope.launch { downloader.delete(state.id, deleteFiles = true) } }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadItemCard(
    state: DownloadState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        elevation = 2.dp,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.id.raw,
                    style = MaterialTheme.typography.subtitle2.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(state)
            }

            // Body Metrics & Status Description
            when (state) {
                is DownloadState.Downloading -> {
                    val progress = state.totalBytes?.let { total ->
                        if (total > 0) (state.downloadedBytes.toFloat() / total) else 0f
                    } ?: 0f

                    LinearProgressIndicator(
                        progress = progress,
                        color = Color(0xFF1976D2),
                        backgroundColor = Color(0xFFE0E0E0),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatByteProgress(state.downloadedBytes, state.totalBytes),
                            style = MaterialTheme.typography.caption.copy(color = Color.DarkGray)
                        )
                        Text(
                            text = formatSpeed(state.speedBytesPerSecond),
                            style = MaterialTheme.typography.caption.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1565C0)
                            )
                        )
                    }
                }

                is DownloadState.Paused -> {
                    Text(
                        text = "Paused at ${formatBytes(state.downloadedBytes)}",
                        style = MaterialTheme.typography.body2.copy(color = Color.DarkGray)
                    )
                }

                is DownloadState.Completed -> {
                    Text(
                        text = "Saved: ${state.absolutePath}",
                        style = MaterialTheme.typography.body2.copy(color = Color(0xFF2E7D32)),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                is DownloadState.Failed -> {
                    Text(
                        text = "Error: ${state.error.message ?: "Transfer encountered an unrecoverable error"}",
                        style = MaterialTheme.typography.body2.copy(color = Color(0xFFC62828)),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                is DownloadState.Connecting -> {
                    Text(
                        text = "Negotiating HTTP headers and checking Range support...",
                        style = MaterialTheme.typography.body2.copy(color = Color(0xFFE65100))
                    )
                }

                is DownloadState.Queued -> {
                    Text(
                        text = "Awaiting available concurrency slot...",
                        style = MaterialTheme.typography.body2.copy(color = Color.Gray)
                    )
                }

                else -> Unit
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (state) {
                    is DownloadState.Downloading, is DownloadState.Connecting -> {
                        Button(
                            onClick = onPause,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFFFA000)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Pause", color = Color.White, fontSize = 12.sp)
                        }
                    }

                    is DownloadState.Paused -> {
                        Button(
                            onClick = onResume,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Resume", color = Color.White, fontSize = 12.sp)
                        }
                    }

                    is DownloadState.Failed -> {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00897B)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Retry", color = Color.White, fontSize = 12.sp)
                        }
                    }

                    else -> Unit
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828)),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Delete", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(state: DownloadState) {
    val (label, bg, fg) = when (state) {
        is DownloadState.Downloading -> Triple("DOWNLOADING", Color(0xFFE3F2FD), Color(0xFF0D47A1))
        is DownloadState.Paused -> Triple("PAUSED", Color(0xFFFFF3E0), Color(0xFFE65100))
        is DownloadState.Completed -> Triple("COMPLETED", Color(0xFFE8F5E9), Color(0xFF1B5E20))
        is DownloadState.Failed -> Triple("FAILED", Color(0xFFFFEBEE), Color(0xFFB71C1C))
        is DownloadState.Connecting -> Triple("CONNECTING", Color(0xFFEDE7F6), Color(0xFF4A148C))
        is DownloadState.Queued -> Triple("QUEUED", Color(0xFFECEFF1), Color(0xFF37474F))
        is DownloadState.Cancelled -> Triple("CANCELLED", Color(0xFFFAFAFA), Color(0xFF616161))
        else -> Triple("IDLE", Color(0xFFEEEEEE), Color(0xFF757575))
    }

    Box(
        modifier = Modifier
            .background(bg, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun formatByteProgress(downloaded: Long, total: Long?): String {
    val downloadedMb = roundToOneDecimal(downloaded / (1024.0 * 1024.0))
    return if (total != null && total > 0) {
        val totalMb = roundToOneDecimal(total / (1024.0 * 1024.0))
        val percent = (downloaded.toDouble() / total.toDouble() * 100.0).toInt()
        "$downloadedMb MB / $totalMb MB ($percent%)"
    } else {
        "$downloadedMb MB downloaded"
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = roundToOneDecimal(bytes / (1024.0 * 1024.0))
    return "$mb MB"
}

private fun formatSpeed(bytesPerSec: Long): String {
    val kbPerSec = bytesPerSec / 1024.0
    return if (kbPerSec >= 1024.0) {
        val mbPerSec = roundToOneDecimal(kbPerSec / 1024.0)
        "$mbPerSec MB/s"
    } else {
        "${kbPerSec.toInt()} KB/s"
    }
}

/**
 * Pure Kotlin KMP decimal formatting (rounds to 1 decimal place without java.lang.String.format)
 */
private fun roundToOneDecimal(value: Double): String {
    val rounded = (kotlin.math.round(value * 10.0) / 10.0)
    val integerPart = rounded.toLong()
    val decimalPart = kotlin.math.abs(((rounded - integerPart) * 10).toLong())
    return "$integerPart.$decimalPart"
}
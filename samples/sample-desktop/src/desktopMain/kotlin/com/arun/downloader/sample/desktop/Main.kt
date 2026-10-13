package com.arun.downloader.sample.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arun.downloader.core.FileDownloader
import com.arun.downloader.core.model.ConflictStrategy
import com.arun.downloader.core.model.DownloadPriority
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloadState
import com.arun.downloader.core.model.DownloaderConfig
import com.arun.downloader.filesystem.OkioDownloadFileSystem
import com.arun.downloader.network.KtorNetworkTransport
import com.arun.downloader.runtime.DefaultFileDownloader
import com.arun.downloader.runtime.desktop.DesktopPathResolver
import com.arun.downloader.runtime.desktop.DesktopShutdownHook
import com.arun.downloader.storage.db.DownloadDatabase
import com.arun.downloader.storage.db.DownloadRecordEntity
import com.arun.downloader.storage.repository.SqlDelightDownloadRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import java.io.File

fun main() {
    // Resolve Desktop System Directories
    val downloadDir = DesktopPathResolver.getDefaultDownloadDirectory()
    val dbDirectoryPath = DesktopPathResolver.getDefaultDatabaseDirectory()
    val dbDir = File(dbDirectoryPath.toString())
    dbDir.mkdirs()
    val dbFile = File(dbDir, "downloader_sample.db")

    // Initialize Storage Engine via SQLite JDBC
    val driver = com.arun.downloader.storage.db.DatabaseDriverFactory(dbFile).createDriver()

    val database = DownloadDatabase(
        driver = driver,
        DownloadRecordEntityAdapter = DownloadRecordEntity.Adapter(
            priorityAdapter = EnumColumnAdapter(),
            conflict_strategyAdapter = EnumColumnAdapter()
        )
    )

    val repository = SqlDelightDownloadRepository(
        database = database,
        ioDispatcher = Dispatchers.IO
    )

    // Initialize Network and Filesystem Transports
    val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = null   // null disables the overall request timeout for long downloads
            connectTimeoutMillis = 30_000L                        // 30 seconds connection timeout
            socketTimeoutMillis = 30_000L                         // 30 seconds between byte reads
        }
    }
    val networkTransport = KtorNetworkTransport(httpClient)
    val fileSystem = OkioDownloadFileSystem()

    val config = DownloaderConfig(
        maxConcurrentDownloads = 3,
        stagingDirectory = downloadDir
    )

    // Initialize Public FileDownloader Façade
    val downloader: FileDownloader = DefaultFileDownloader(
        config = config,
        repository = repository,
        network = networkTransport,
        fileSystem = fileSystem
    )

    // Register Cooperative JVM Shutdown Hook
    DesktopShutdownHook.register {
        try {
            downloader.shutdown()
        } finally {
            driver.close()
        }
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "KMP File Downloader - Desktop Sample"
        ) {
            App(downloader, downloadDir.toString())
        }
    }
}

@Composable
fun App(downloader: FileDownloader, defaultDownloadPath: String) {
//    var urlInput by remember { mutableStateOf("https://speed.cloudflare.com/__down?bytes=10485760") }
    var urlInput by remember { mutableStateOf("https://speed.cloudflare.com/__down?bytes=52428800") }
    var fileNameInput by remember { mutableStateOf("sample_100mb.zip") }
    var downloads by remember { mutableStateOf<List<DownloadState>>(emptyList()) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(downloader) {
        downloader.observeAll().collect { states ->
            downloads = states
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("KMP File Downloader - Desktop Production Suite") },
                    backgroundColor = Color(0xFF1976D2),
                    contentColor = Color.White,
                    elevation = 4.dp
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Enqueue Card
                Card(elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Enqueue New Download", style = MaterialTheme.typography.h6)

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("Download URL (HTTP/HTTPS)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = fileNameInput,
                            onValueChange = { fileNameInput = it },
                            label = { Text("File Name") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            "Destination: $defaultDownloadPath",
                            style = MaterialTheme.typography.caption
                        )

                        Button(
                            onClick = {
                                scope.launch {
                                    val request = DownloadRequest(
                                        url = urlInput,
                                        destinationDirectory = defaultDownloadPath.toPath(),
                                        fileName = fileNameInput,
                                        priority = DownloadPriority.HIGH,
                                        conflictStrategy = ConflictStrategy.RENAME_NEW_INCREMENTAL
                                    )
                                    downloader.enqueue(request)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Start Download", color = Color.White)
                        }
                    }
                }

                Text(
                    "Active & History Queue (${downloads.size})",
                    style = MaterialTheme.typography.h6
                )

                // Download List
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(downloads, key = { it.id.raw }) { state ->
                        DownloadItemCard(state, downloader, scope)
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadItemCard(
    state: DownloadState,
    downloader: FileDownloader,
    scope: CoroutineScope
) {
    Card(elevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("ID: ${state.id.raw}", style = MaterialTheme.typography.subtitle2)
                Text(
                    "Status: ${state::class.simpleName}",
                    style = MaterialTheme.typography.body2,
                    color = Color.Gray
                )
            }

            when (state) {
                is DownloadState.Downloading -> {
                    val total = state.totalBytes
                    if (total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "Downloaded: ${state.downloadedBytes} / ${state.totalBytes ?: "Unknown"} bytes | " +
                                "Speed: ${state.speedBytesPerSecond / 1024} KB/s"
                    )
                }
                is DownloadState.Paused -> {
                    Text("Paused at ${state.downloadedBytes} bytes", color = Color.DarkGray)
                }
                is DownloadState.Completed -> {
                    Text("Saved to: ${state.absolutePath}", color = Color(0xFF2E7D32))
                }
                is DownloadState.Failed -> {
                    Text("Error: ${state.error.message ?: "Download failed"}", color = Color.Red)
                }
                is DownloadState.Connecting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Connecting to server...")
                }
                is DownloadState.Validating -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Validating download...")
                }
                is DownloadState.Queued -> Text("Waiting in queue...")
                is DownloadState.Cancelled -> Text("Cancelled")
                is DownloadState.Idle -> Text("Ready")
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                when (state) {
                    is DownloadState.Downloading, is DownloadState.Connecting -> {
                        Button(onClick = { scope.launch { downloader.pause(state.id) } }) {
                            Text("Pause")
                        }
                    }
                    is DownloadState.Paused -> {
                        Button(onClick = { scope.launch { downloader.resume(state.id) } }) {
                            Text("Resume")
                        }
                    }
                    is DownloadState.Failed -> {
                        Button(onClick = { scope.launch { downloader.retry(state.id) } }) {
                            Text("Retry")
                        }
                    }
                    else -> Unit
                }

                Button(
                    onClick = { scope.launch { downloader.delete(state.id, deleteFiles = true) } },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828))
                ) {
                    Text("Delete", color = Color.White)
                }
            }
        }
    }
}
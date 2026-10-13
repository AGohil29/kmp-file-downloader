package com.arun.downloader.sample.android

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.arun.downloader.core.FileDownloader
import com.arun.downloader.core.model.ConflictStrategy
import com.arun.downloader.core.model.DownloadPriority
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloadState
import com.arun.downloader.core.model.DownloaderConfig
import com.arun.downloader.filesystem.OkioDownloadFileSystem
import com.arun.downloader.network.KtorNetworkTransport
import com.arun.downloader.runtime.DefaultFileDownloader
import com.arun.downloader.storage.db.DownloadDatabase
import com.arun.downloader.storage.db.DownloadRecordEntity
import com.arun.downloader.storage.repository.SqlDelightDownloadRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val model = ViewModelProvider(this)[SampleDownloaderViewModel::class.java]
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    AndroidDownloadScreen(model.downloader, model.stagingDir.toString())
                }
            }
        }
    }
}

class SampleDownloaderViewModel(application: Application) : AndroidViewModel(application) {
    val stagingDir = application.filesDir.toOkioPath()
    private val driver = AndroidSqliteDriver(
        schema = DownloadDatabase.Schema,
        context = application,
        name = "downloader_sample.db"
    )
    val downloader: FileDownloader

    init {
        val config = DownloaderConfig(
            maxConcurrentDownloads = 3,
            stagingDirectory = stagingDir
        )

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

        val networkTransport = KtorNetworkTransport(HttpClient(OkHttp))
        val fileSystem = OkioDownloadFileSystem()

        downloader = DefaultFileDownloader(
            config = config,
            repository = repository,
            network = networkTransport,
            fileSystem = fileSystem
        )

    }

    override fun onCleared() {
        super.onCleared()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                downloader.shutdown()
            } finally {
                driver.close()
            }
        }
    }
}

@Composable
fun AndroidDownloadScreen(downloader: FileDownloader, defaultDest: String) {
    var url by remember { mutableStateOf("https://speed.cloudflare.com/__down?bytes=10485760") }
    var downloads by remember { mutableStateOf<List<DownloadState>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(downloader) {
        downloader.observeAll().collect {
            downloads = it
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("KMP Downloader - Android Sample", style = MaterialTheme.typography.h6)

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Download URL") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                scope.launch {
                    val request = DownloadRequest(
                        url = url,
                        destinationDirectory = defaultDest.toPath(),
                        fileName = "android_sample.bin",
                        priority = DownloadPriority.HIGH,
                        conflictStrategy = ConflictStrategy.RENAME_NEW_INCREMENTAL
                    )
                    downloader.enqueue(request)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Download")
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(downloads, key = { it.id.raw }) { state ->
                Card(elevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("ID: ${state.id.raw}", style = MaterialTheme.typography.caption)
                        Text("Status: ${state::class.simpleName}")

                        if (state is DownloadState.Connecting || state is DownloadState.Validating) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (state is DownloadState.Downloading) {
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
                                "${state.downloadedBytes} / ${state.totalBytes ?: "?"} bytes " +
                                        "(${state.speedBytesPerSecond / 1024} KB/s)"
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state is DownloadState.Downloading || state is DownloadState.Connecting) {
                                Button(onClick = { scope.launch { downloader.pause(state.id) } }) {
                                    Text("Pause")
                                }
                            } else if (state is DownloadState.Paused) {
                                Button(onClick = { scope.launch { downloader.resume(state.id) } }) {
                                    Text("Resume")
                                }
                            }
                            Button(onClick = { scope.launch { downloader.cancel(state.id) } }) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    scope.launch { downloader.delete(state.id, deleteFiles = true) }
                                }
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}


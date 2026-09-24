package com.arun.downloader.core.engine

import com.arun.downloader.core.model.ChecksumValidation
import com.arun.downloader.core.model.DownloadError
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadRequest
import com.arun.downloader.core.model.DownloadState
import com.arun.downloader.core.model.NetworkRequest
import com.arun.downloader.core.repository.DownloadFileSystem
import com.arun.downloader.core.repository.DownloadRepository
import com.arun.downloader.core.repository.NetworkTransport
import com.arun.downloader.core.statemachine.DownloadStateEvent
import com.arun.downloader.core.statemachine.DownloadStateMachine
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okio.Closeable
import okio.HashingSink
import okio.Path
import okio.Sink
import okio.buffer
import kotlin.coroutines.cancellation.CancellationException

class DownloadTask(
    val id: DownloadId,
    val request: DownloadRequest,
    private val stateMachine: DownloadStateMachine,
    private val repository: DownloadRepository,
    private val network: NetworkTransport,
    private val fileSystem: DownloadFileSystem,
    private val stagingDirectory: Path,
    private val progressUpdateIntervalMs: Long = 100L,
    private val bufferSize: Int = 8192,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    val state: StateFlow<DownloadState> = stateMachine.state

    suspend fun execute() = withContext(ioDispatcher) {
        var rawSink: Sink? = null
        var hashingSink: HashingSink? = null
        var bufferedSink: okio.BufferedSink? = null

        val stagingDir = stagingDirectory / ".downloader_staging"
        fileSystem.createDirectories(stagingDir)
        val stagingPartFile = stagingDir / "${id.raw}.part"

        try {
            stateMachine.transition(DownloadStateEvent.Enqueue(request.priority, getEpochMs()))
            stateMachine.transition(DownloadStateEvent.AcquireSlot)
            repository.updateState(id, "CONNECTING", getEpochMs())

            val netRequest = NetworkRequest(
                url = request.url,
                headers = request.headers
            )

            val netResponse = network.execute(netRequest)
            val totalBytes = netResponse.contentLength

            stateMachine.transition(
                DownloadStateEvent.HeaderReceived(
                    totalBytes = totalBytes,
                    etag = netResponse.etag,
                    lastModified = netResponse.lastModified
                )
            )

            repository.updateConnectionHeaders(
                id = id,
                totalBytes = totalBytes,
                etag = netResponse.etag,
                lastModified = netResponse.lastModified,
                timestampEpochMs = getEpochMs()
            )

            // Prepare sink and optional hashing sink
            rawSink = fileSystem.sink(stagingPartFile, append = false)
            val activeSink = if (request.expectedChecksum != null) {
                hashingSink = createHashingSink(rawSink, request.expectedChecksum)
                hashingSink
            } else {
                rawSink
            }
            bufferedSink = activeSink.buffer()

            // Stream byte pump
            pumpBytes(
                channel = netResponse.bodyChannel,
                sink = bufferedSink,
                totalBytes = totalBytes
            )

            bufferedSink.flush()

            // Verification & Finalization
            stateMachine.transition(DownloadStateEvent.StartValidation)

            // Validate byte count
            val finalDiskSize = fileSystem.size(stagingPartFile)
            if (totalBytes != null && finalDiskSize != totalBytes) {
                throw DownloadError.NonRetryable.HttpError(
                    httpCode = 200,
                    message = "Payload size mismatch. Expected: $totalBytes, received: $finalDiskSize"
                )
            }

            // Validate Checksum if requested
            if (hashingSink != null && request.expectedChecksum != null) {
                val actualHash = hashingSink.hash.hex().lowercase()
                val expectedHash = request.expectedChecksum.expectedHash.lowercase()
                if (actualHash != expectedHash) {
                    throw DownloadError.NonRetryable.ChecksumMismatch(expectedHash, actualHash)
                }
            }

            // Resolve target path and perform atomic commit
            val finalFileName = request.fileName ?: fileSystem.sanitizeFileName(request.url.substringAfterLast('/'))
            val finalDestinationPath = fileSystem.resolveUniquePath(
                directory = request.destinationDirectory,
                baseName = finalFileName,
                conflictStrategy = request.conflictStrategy
            )

            // Close file handles prior to atomic move (Mandatory for Windows file-locking correctness)
            bufferedSink.close()
            bufferedSink = null
            rawSink = null

            fileSystem.atomicMove(stagingPartFile, finalDestinationPath)

            val completionTime = getEpochMs()
            stateMachine.transition(
                DownloadStateEvent.Complete(
                    finalPath = finalDestinationPath.toString(),
                    totalBytes = finalDiskSize,
                    timestampEpochMs = completionTime
                )
            )
            repository.markCompleted(id, completionTime)
        } catch (cancellation: CancellationException) {
            closeQuietly(bufferedSink, rawSink)
            withContext(NonCancellable) {
                stateMachine.transition(DownloadStateEvent.Pause)
                repository.updateState(id, "PAUSED", getEpochMs())
            }
            throw cancellation
        } catch (e: Throwable) {
            closeQuietly(bufferedSink, rawSink)
            val downloadError =
                e as? DownloadError ?: DownloadError.NonRetryable.Unknown(e.message ?: "Unknown error", e)
            val canRetry = downloadError is DownloadError.Retryable

            stateMachine.transition(DownloadStateEvent.Fail(downloadError, canRetry))
            repository.markFailed(id, downloadError, getEpochMs())
        } finally {
            closeQuietly(bufferedSink, rawSink)
        }
    }

    private suspend fun pumpBytes(
        channel: ByteReadChannel,
        sink: okio.BufferedSink,
        totalBytes: Long?
    ) {
        val buffer = ByteArray(bufferSize)
        var totalBytesRead = 0L
        var lastEmittedTime = getEpochMs()
        var bytesSinceLastTick = 0L

        while (currentCoroutineContext().isActive && !channel.isClosedForRead) {
            val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
            if (bytesRead == -1) break
            if (bytesRead > 0) {
                sink.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                bytesSinceLastTick += bytesRead

                val now = getEpochMs()
                val elapsed = now - lastEmittedTime

                if (elapsed >= progressUpdateIntervalMs) {
                    val speed = if (elapsed > 0) (bytesSinceLastTick * 1000L) / elapsed else 0L

                    stateMachine.transition(
                        DownloadStateEvent.ProgressUpdate(
                            downloadedBytes = totalBytesRead,
                            speedBytesPerSecond = speed
                        )
                    )
                    repository.updateProgress(id, totalBytesRead, now)

                    lastEmittedTime = now
                    bytesSinceLastTick = 0L
                }
            }
        }
    }

    private fun createHashingSink(sink: Sink, checksum: ChecksumValidation): HashingSink {
        return when (checksum.algorithm) {
            ChecksumValidation.Algorithm.MD5 -> HashingSink.md5(sink)
            ChecksumValidation.Algorithm.SHA1 -> HashingSink.sha1(sink)
            ChecksumValidation.Algorithm.SHA256 -> HashingSink.sha256(sink)
        }
    }

    private fun closeQuietly(vararg closeables: Closeable?) {
        for (c in closeables) {
            try {
                c?.close()
            } catch (_: Exception) {}
        }
    }

    private fun getEpochMs(): Long = getSystemClockEpochMs()
}

internal expect fun getSystemClockEpochMs(): Long
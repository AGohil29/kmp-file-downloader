# HTTP Range Resume Support

This document covers the newest set of changes that teach `DownloadTask` how to **resume a
partially downloaded file** using HTTP byte-range requests, instead of always restarting from
byte zero. It explains every file touched or added:

- `downloader-core/src/commonMain/kotlin/com/arun/downloader/core/engine/RangeHeaderParser.kt` (new)
- `downloader-core/src/commonMain/kotlin/com/arun/downloader/core/engine/DownloadTask.kt` (modified)
- `downloader-runtime/src/desktopTest/kotlin/com/arun/downloader/runtime/engine/HttpRangeResumeTest.kt` (new)

It builds directly on top of the design described in
[`docs/engine/download-task.md`](download-task.md) and the range/resume fields already present in
`NetworkRequest`/`NetworkResponse` (see
[`docs/networking/downloader-network-module.md`](../networking/downloader-network-module.md)).
Previously those fields existed on the wire types but `DownloadTask` never populated or acted on
them — this change wires them up end-to-end.

## Why this was needed

Before this change, every call to `DownloadTask.execute()` issued a plain `GET` with no `Range`
header, even if a `.part` staging file already existed on disk from a previous paused/failed
attempt. The engine would silently overwrite (`append = false`) that partial file and re-download
the entire payload from scratch — wasting bandwidth and time on large files or flaky connections.

Now, when a staging file already has bytes on disk, `DownloadTask` asks the server for only the
missing suffix (`Range: bytes=<localSize>-`) and, if the server honors it (`206 Partial Content`
with a matching `Content-Range`), appends the new bytes instead of re-downloading everything.

## `RangeHeaderParser.kt` — `ContentRange`

A small, dependency-free value type + parser for the HTTP `Content-Range` response header:

```kotlin
data class ContentRange(
    val rangeStart: Long,
    val rangeEnd: Long,
    val totalBytes: Long?
) {
    companion object {
        fun parseOrNull(headerValue: String?): ContentRange?
    }
}
```

- Matches headers of the form `bytes <start>-<end>/<total>` (e.g. `"bytes 5000-9999/10000"`) or
  the wildcard-total form `bytes <start>-<end>/*` (server doesn't know/report the total size), via
  the regex `bytes\s+(\d+)-(\d+)/(\d+|\*)`.
- Returns `null` for: a missing/blank header, a header that doesn't match the expected shape, or
  a semantically invalid range (`start > end`, or `end >= total` when `total` is known). This
  means malformed or nonsensical `Content-Range` values are treated the same as "no header" by
  callers, rather than crashing or producing garbage offsets.
- `totalBytes` is `null` for the wildcard case (`/*`), signalling "server didn't report the full
  size for this partial response."

This parser is the single source of truth `DownloadTask` uses to validate that a `206` response
actually resumes at the offset it asked for.

## `DownloadTask.kt` changes

### 1. Reading local partial-file state before issuing the request

```kotlin
val localFileSize = if (fileSystem.exists(stagingPartFile)) {
    fileSystem.size(stagingPartFile)
} else {
    0L
}

val persistedRecord = repository.getById(id)
val storedEtag = persistedRecord?.etag
val storedLastModified = persistedRecord?.lastModified
val ifRange = storedEtag ?: storedLastModified

val requestRangeStart = if (localFileSize > 0L) localFileSize else null
```

Before contacting the network, the task checks how many bytes are already on disk in the staging
`.part` file, and looks up the previously-persisted `etag`/`lastModified` validators from the
repository (saved by an earlier attempt via `updateConnectionHeaders`). If there are local bytes,
`requestRangeStart` is set to that size and an `If-Range` header value (preferring `etag`) is
attached — `If-Range` tells the server "only give me the partial range if the resource is
*unchanged* since I last saw this validator; otherwise send the whole thing," which protects
against resuming into a file that has since changed on the server.

### 2. Sending the range request

```kotlin
val netRequest = NetworkRequest(
    url = request.url,
    headers = request.headers,
    rangeStart = requestRangeStart,
    ifRange = if (requestRangeStart != null) ifRange else null
)
```

This simply populates the `rangeStart`/`ifRange` fields on `NetworkRequest` that
`KtorNetworkTransport` already knew how to translate into `Range`/`If-Range` HTTP headers (see the
networking module doc) — no changes were needed on the network layer for this feature.

### 3. Classifying the response — resume, reset, or fresh start

```kotlin
val isResuming: Boolean
val totalBytes: Long?
var currentBytes: Long

if (netResponse.isPartialContent && requestRangeStart != null) {
    val contentRange = ContentRange.parseOrNull(netResponse.getHeader("Content-Range"))
    val rangeMatches = contentRange != null && contentRange.rangeStart == localFileSize

    if (rangeMatches) {
        isResuming = true
        currentBytes = localFileSize
        totalBytes = contentRange?.totalBytes ?: netResponse.contentLength?.let { it + localFileSize }
    } else {
        // Invalid Content-Range: server returned unexpected slice. Reset and restart.
        fileSystem.delete(stagingPartFile)
        isResuming = false
        currentBytes = 0L
        totalBytes = netResponse.contentLength
    }
} else {
    // HTTP 200 OK: server ignored Range or file changed. Truncate local partial file.
    if (localFileSize > 0L) {
        fileSystem.delete(stagingPartFile)
    }
    isResuming = false
    currentBytes = 0L
    totalBytes = netResponse.contentLength
}
```

Three outcomes are handled explicitly:

1. **Successful resume** — the server responded `206` *and* the `Content-Range` start offset
   matches exactly what was requested (`localFileSize`). `isResuming = true`, and the byte
   counter (`currentBytes`) is seeded with the bytes already on disk so progress reporting and
   final-size validation account for them. `totalBytes` prefers the authoritative total from
   `Content-Range` (e.g. `.../10000`), falling back to `contentLength + localFileSize` if the
   server used the wildcard total form.
2. **Malformed/unexpected partial response** — the server said `206` but the `Content-Range`
   header is missing, unparsable, or starts at a different offset than requested (e.g. server-side
   inconsistency). Rather than trusting a response that doesn't line up with local state, the task
   **deletes the staging file** and falls back to treating it as a fresh download.
3. **Server ignored the range request** (`200 OK` instead of `206`, e.g. server or resource
   doesn't support ranges, or the `If-Range` validator no longer matched) — any local partial data
   is deleted since the incoming stream is the full file from byte zero, and the task proceeds as
   a fresh download.

In all three cases `totalBytes` and `currentBytes` end up consistent with whatever data will
actually end up in the file, so downstream progress events and the final size check remain
correct.

### 4. Opening the sink in append vs. fresh mode

```kotlin
rawSink = fileSystem.sink(stagingPartFile, append = isResuming)

val activeSink = if (request.expectedChecksum != null && !isResuming) {
    hashingSink = createHashingSink(rawSink, request.expectedChecksum)
    hashingSink
} else {
    rawSink
}
```

- When resuming, the sink is opened with `append = true` so new bytes are written after the
  existing prefix instead of truncating it.
- **Checksum validation is skipped when resuming.** A streaming `HashingSink` can only compute a
  digest over the bytes that pass through it in the current process — it has no way to "replay"
  the hash state for bytes written in an earlier run. Rather than compute an incorrect partial
  hash (or add complexity to persist/restore hash state across runs), the task simply doesn't wrap
  the sink in a `HashingSink` when `isResuming` is true, so `expectedChecksum` validation is
  silently skipped for resumed downloads. Fresh downloads (including ones that fell back to a
  full restart per case 2/3 above) still get full checksum validation as before.

### 5. Seeding the byte-pump counter and adding a final progress flush

```kotlin
pumpBytes(
    channel = netResponse.bodyChannel,
    sink = bufferedSink,
    initialDownloadedBytes = currentBytes,
    totalBytes = totalBytes
)
```

`pumpBytes` gained a new `initialDownloadedBytes` parameter, used to seed `totalBytesRead` so that
progress events (`DownloadStateEvent.ProgressUpdate`) and the persisted `downloadedBytes` reflect
the **whole file's** progress (bytes already on disk + newly streamed bytes), not just the bytes
transferred in this run — otherwise a resumed download's progress bar would appear to jump
backwards to a lower percentage than before the pause.

A final flush was also added after the read loop exits:

```kotlin
// Final progress flush
val finalNow = getEpochMs()
stateMachine.transition(
    DownloadStateEvent.ProgressUpdate(
        downloadedBytes = totalBytesRead,
        speedBytesPerSecond = 0L
    )
)
repository.updateProgress(id, totalBytesRead, finalNow)
```

Previously, progress was only emitted on a throttled interval (`progressUpdateIntervalMs`, default
100ms) *while bytes were still arriving*. If the stream finished between throttle ticks (e.g. a
short file, or the last chunk landed just after the last emitted tick), the persisted/observed
`downloadedBytes` could be stale — under-reporting bytes that were in fact written to disk. This
final flush guarantees the state machine and repository both see the true final byte count
(with `speedBytesPerSecond = 0` since the transfer has ended) before validation runs.

## `HttpRangeResumeTest.kt` (new, in `downloader-runtime`)

A `desktopTest` suite (uses `JdbcSqliteDriver.IN_MEMORY` + `FakeFileSystem`, matching the pattern
in `DownloadTaskTest`) that exercises the new logic end-to-end:

- **`contentRangeParserParsesStandardAndWildcardHeaders`** — unit-tests `ContentRange.parseOrNull`
  directly: a standard `"bytes 500-999/1000"` header, a wildcard-total `"bytes 0-499/*"` header,
  an unparsable string, and an invalid `start > end` range (`"bytes 1000-500/1000"`), asserting
  `null` is returned for both bad cases.
- **`resumeWith206AppendsRemainingBytesAndCompletes`** — seeds a staging `.part` file with the
  first 10 bytes of a 20-byte file, persists a `PAUSED` record with a stored `ETag`, then runs a
  mock `NetworkTransport` that asserts the outgoing request has `rangeStart = 10L` and
  `ifRange = "\"etag-v1\""`, and responds with `206` + `Content-Range: bytes 10-19/20` carrying
  only the remaining 10 bytes. Asserts the task reaches `DownloadState.Completed` and the final
  file on disk is the full, correctly-concatenated 20 bytes (`"0123456789ABCDEFGHIJ"`) —
  confirming the append + resume path works correctly.
- **`serverReturns200OnRangeRequestTruncatesPartialFileAndRestarts`** — seeds a staging `.part`
  file with unrelated stale bytes, then mocks a server that ignores the range request and replies
  `200 OK` with a completely different, full payload. Asserts the task still completes
  successfully and the final file contains *only* the fresh payload (stale bytes were correctly
  discarded rather than incorrectly prepended), validating the fallback-to-fresh-download path.

Both scenarios reuse the same `SqlDelightDownloadRepository` (in-memory SQLite via
`JdbcSqliteDriver`) and `OkioDownloadFileSystem` (backed by Okio's `FakeFileSystem`) test
infrastructure already established by `DownloadTaskTest`, so no real disk or network I/O occurs.

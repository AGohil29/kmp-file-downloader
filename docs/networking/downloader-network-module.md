# `downloader-network` Module

This document explains the new `downloader-network` module: what each file does, how the
pieces fit together, and how it depends on `downloader-core`. It covers all the files added or
changed as part of introducing HTTP networking support (Ktor-based streaming downloads with
byte-range/resume support and error mapping into `DownloadError`).

## Module overview

```
downloader-network/
├── build.gradle.kts
└── src/
    ├── commonMain/kotlin/com/arun/downloader/network/
    │   ├── NetworkTransport.kt
    │   ├── NetworkClientFactory.kt
    │   ├── KtorNetworkTransport.kt
    │   └── model/
    │       └── NetworkModels.kt
    └── commonTest/kotlin/com/arun/downloader/network/
        └── KtorNetworkTransportTest.kt
```

`downloader-network` depends on `downloader-core` (for `DownloadError`) and on Ktor's
multiplatform HTTP client (`ktor-client-core`, with `okhttp`/`cio`/`darwin` engines per
platform). It is the layer responsible for issuing HTTP(S) requests, streaming response bodies,
and translating transport-level failures (timeouts, HTTP status codes, I/O errors) into the
domain's `DownloadError` types so the rest of the app never has to deal with raw Ktor
exceptions.

## `build.gradle.kts`

Configures the Kotlin Multiplatform source sets for the module:

- **`commonMain`**: depends on `downloader-core` (for `DownloadError`) and
  `ktor-client-core` (the multiplatform HTTP client API).
- **`androidMain`**: adds `ktor-client-okhttp`, the OkHttp-backed engine used on Android.
- **`desktopMain`**: adds `ktor-client-cio`, the pure-Kotlin coroutine-based engine used on
  Desktop (JVM).
- **`iosMain`**: adds `ktor-client-darwin`, the engine backed by Apple's `NSURLSession` for
  iOS/macOS targets.
- **`commonTest`**: adds `kotlin.test`, `ktor-client-mock` (for stubbing HTTP responses in
  tests), `kotlinx-coroutines-test`, `turbine`, and `kotest-assertions`.
- **`desktopTest` / `androidUnitTest`**: adds `kotlin-test-junit` so JVM-based test targets can
  run with JUnit.

Each engine is selected per platform so the same `commonMain` code (`KtorNetworkTransport`)
works everywhere without platform-specific branching.

## `NetworkTransport.kt`

Defines the core abstraction the rest of the app (e.g., a future download engine/orchestrator)
programs against, rather than depending on Ktor directly:

```kotlin
interface NetworkTransport {
    suspend fun execute(request: NetworkRequest): NetworkResponse
    fun close()
}
```

- `execute(request)` performs an HTTP GET and returns a `NetworkResponse` whose body is a
  **streaming** channel — the caller pulls bytes as needed (e.g., to write to disk) instead of
  buffering the whole file in memory.
- `close()` releases the underlying HTTP client/connection pool. Callers are responsible for
  consuming or closing the returned body channel.

Keeping this as an interface allows swapping in a fake/mock transport for unit tests of higher
layers, and decouples the rest of the codebase from Ktor.

## `NetworkClientFactory.kt`

A small object that builds a preconfigured Ktor `HttpClient`:

```kotlin
object NetworkClientFactory {
    fun createDefault(
        connectTimeoutMs: Long = 15_000L,
        readTimeoutMs: Long = 30_000L
    ): HttpClient
}
```

- Installs the `HttpTimeout` plugin with a 15s connect timeout and 30s socket (read) timeout by
  default (both overridable).
- Sets `expectSuccess = false` so Ktor does **not** throw on non-2xx responses automatically —
  status codes are instead inspected and translated manually in `KtorNetworkTransport`, giving
  precise control over which errors are retryable.
- Enables `followRedirects = true` so redirected download URLs (common with CDNs) work
  transparently.

This factory is the single place that owns default client configuration, so all platforms get
consistent timeout/redirect behavior.

## `model/NetworkModels.kt`

Defines the plain data types used at the network boundary:

- **`NetworkRequest`**: `url`, optional `headers`, and optional `rangeStart` / `rangeEnd` /
  `ifRange`. The range fields support **resumable downloads** — when set, the transport adds an
  HTTP `Range` (and optionally `If-Range`) header so only the missing portion of a file is
  fetched.
- **`NetworkResponse`**: `statusCode`, `headers`, a streaming `bodyChannel` (Ktor
  `ByteReadChannel`), `contentLength`, `isPartialContent` (true for HTTP 206), and validators
  `etag` / `lastModified` (used later to confirm a partially-downloaded file on disk still
  matches the remote resource before resuming). It also exposes `getHeader(name)` for
  case-insensitive header lookup.

These models intentionally avoid leaking Ktor types beyond `ByteReadChannel`, keeping the
public surface minimal and stable for consumers.

## `KtorNetworkTransport.kt`

The concrete `NetworkTransport` implementation backed by a Ktor `HttpClient`. Responsibilities:

1. **Building the request** — issues a GET via `client.prepareRequest`, forwarding custom
   headers and, when `rangeStart`/`rangeEnd` are present, adding an HTTP `Range` header
   (`bytes=<start>-<end>`) plus an optional `If-Range` header for conditional resume.
2. **Streaming execution** — uses `prepareRequest(...).execute()` so the response body is
   available as a channel (`bodyAsChannel()`) rather than being fully read into memory,
   enabling large file downloads with bounded memory usage.
3. **HTTP error translation** (`mapAndThrowHttpError`) — for status codes ≥ 400, throws a typed
   `DownloadError` instead of letting Ktor's own exception hierarchy leak out:
   - `429 Too Many Requests` → `DownloadError.Retryable.RateLimited` (parses `Retry-After` into
     milliseconds).
   - `408 Request Timeout` / `504 Gateway Timeout` → `DownloadError.Retryable.NetworkTimeout`.
   - `502 Bad Gateway` / `503 Service Unavailable` / `500 Internal Server Error` →
     `DownloadError.Retryable.ServerUnavailable`.
   - `416 Requested Range Not Satisfiable` → `DownloadError.NonRetryable.ServerDoesNotSupportRange`.
   - Any other 4xx/5xx → `DownloadError.NonRetryable.HttpError`.
4. **Successful response mapping** — extracts headers (case-preserving map), `Content-Length`,
   `ETag`, `Last-Modified`, and whether the response is `206 Partial Content`, then wraps
   everything (plus the live body channel) into a `NetworkResponse`.
5. **Exception translation** (`mapNetworkException`) — catches lower-level exceptions and maps
   them to `DownloadError`:
   - `CancellationException` is always rethrown unmodified so coroutine cancellation continues
     to propagate correctly.
   - A `DownloadError` thrown internally (e.g., from step 3) is rethrown as-is.
   - `HttpRequestTimeoutException` → `DownloadError.Retryable.NetworkTimeout`.
   - `okio.IOException` (connection drops, socket errors) → `DownloadError.Retryable.ConnectionInterrupted`.
   - Anything else whose class name contains "Timeout" (covers platform-specific timeout
     exceptions from OkHttp/CIO/Darwin engines) → `DownloadError.Retryable.NetworkTimeout`.
   - All remaining unexpected exceptions → `DownloadError.NonRetryable.Unknown`.
6. **`close()`** — delegates to `client.close()` to release the underlying engine's resources.

This centralizes all error classification in one place, so upstream retry/backoff logic can
simply check `error is DownloadError.Retryable` instead of inspecting exception types itself.

## `KtorNetworkTransportTest.kt`

Common-test suite using `io.ktor.client.engine.mock.MockEngine` to simulate server responses
without real network I/O. It verifies:

- **`streamingReadsChunksSequentiallyWithoutFullBuffer`** — a 64 KB mocked body is read back in
  8 KB chunks via `bodyChannel.readAvailable`, confirming the response is truly streamed (not
  buffered) and that `contentLength`/`etag`/`isPartialContent` are parsed correctly for a plain
  200 OK response.
- **`rangeRequestSendsCorrectHeaderAndParses206`** — asserts that setting `rangeStart` and
  `ifRange` on a `NetworkRequest` produces the correct `Range`/`If-Range` request headers, and
  that a `206 Partial Content` response is reflected in `statusCode`/`isPartialContent`/
  `contentLength`.
- **`serverError503MapsToRetryableServerUnavailable`** — a mocked `503` response causes
  `execute()` to throw `DownloadError.Retryable.ServerUnavailable` with the correct `httpCode`.
- **`clientError404MapsToNonRetryableHttpError`** — a mocked `404` response causes `execute()`
  to throw `DownloadError.NonRetryable.HttpError` with the correct `httpCode`.
- **`coroutineCancellationClosesStreamGracefully`** — starts reading an endless mocked stream in
  a coroutine, cancels the job mid-read, and confirms the job cancels cleanly without leaking
  exceptions other than cancellation.

Together these tests validate streaming behavior, resume/range support, and the HTTP-error-to-
`DownloadError` mapping contract that the rest of the download engine will rely on.

## Related changes in other modules

### `downloader-core/src/commonMain/.../model/DownloadError.kt`

`DownloadError` was changed from a `sealed interface` to a `sealed class` that extends
`Exception`:

```kotlin
sealed class DownloadError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)
```

- **Why**: `downloader-network` needs to `throw` `DownloadError` instances directly (see
  `KtorNetworkTransport.mapAndThrowHttpError`/`mapNetworkException`). A `sealed interface`
  cannot be thrown with `throw`, so the type had to become a `Throwable`/`Exception` subtype.
  `Retryable` and `NonRetryable` changed from `sealed interface` to `sealed class` for the same
  reason (they are intermediate throwable types).
- Every leaf error type (`NetworkTimeout`, `ConnectionInterrupted`, `ServerUnavailable`,
  `RateLimited`, `HttpError`, `InsufficientDiskSpace`, `FileSystemPermissionDenied`,
  `MalformedUrl`, `FileAlreadyExists`, `ServerDoesNotSupportRange`, `ChecksumMismatch`,
  `PathTraversalDetected`, `Unknown`) now supplies a **default human-readable `message`**
  (e.g., `NetworkTimeout` defaults to `"Network timed out"`), so callers can throw them with
  minimal boilerplate (`DownloadError.Retryable.NetworkTimeout("HTTP timeout (504).")` still
  works by overriding the default), while the network layer often constructs them with a fully
  custom message describing the specific HTTP failure.
- Behavior for consumers of `DownloadError` (e.g., pattern matching in `when` expressions) is
  unchanged since it is still a `sealed` hierarchy; the only difference is that instances are
  now also valid `Throwable`s.

### `downloader-core/build.gradle.kts`

Removed the `implementation(project(":downloader-network"))` dependency from `downloader-core`.
`downloader-core` defines domain models/state machine and must not depend on the networking
layer — instead, `downloader-network` depends on `downloader-core` (the correct direction, since
`KtorNetworkTransport` needs `DownloadError`). This removes what would otherwise be a circular
module dependency.

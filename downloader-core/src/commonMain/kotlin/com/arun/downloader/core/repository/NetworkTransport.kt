package com.arun.downloader.core.repository

import com.arun.downloader.core.model.NetworkRequest
import com.arun.downloader.core.model.NetworkResponse

interface NetworkTransport {
    /**
     * Returns a streaming response as soon as headers are available, without buffering the body.
     * The caller must invoke [NetworkResponse.close] in a finally block, even on cancellation
     * or when failing before the body is read.
     */
    suspend fun execute(request: NetworkRequest): NetworkResponse

    /** Releases connection pools and underlying HTTP client instances. */
    fun close()
}
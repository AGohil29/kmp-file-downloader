package com.arun.downloader.core.repository

import com.arun.downloader.core.model.NetworkRequest
import com.arun.downloader.core.model.NetworkResponse

interface NetworkTransport {
    /**
     * Executes the HTTP request and returns a streaming response.
     * The caller is responsible for consuming or closing the underlying channel.
     */
    suspend fun execute(request: NetworkRequest): NetworkResponse

    /** Releases connection pools and underlying HTTP client instances. */
    fun close()
}
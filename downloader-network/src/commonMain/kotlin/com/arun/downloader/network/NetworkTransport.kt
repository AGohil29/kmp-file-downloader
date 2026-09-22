package com.arun.downloader.network

import com.arun.downloader.network.model.NetworkRequest
import com.arun.downloader.network.model.NetworkResponse

interface NetworkTransport {
    /**
     * Executes the HTTP request and returns a streaming response.
     * The caller is responsible for consuming or closing the underlying channel.
     */
    suspend fun execute(request: NetworkRequest): NetworkResponse

    /** Releases connection pools and underlying HTTP client instances. */
    fun close()
}
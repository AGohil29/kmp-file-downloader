package com.arun.downloader.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

object NetworkClientFactory {
    fun createDefault(
        connectTimeoutMs: Long = 15_000L,
        readTimeoutMs: Long = 30_000L
    ): HttpClient {
        return HttpClient {
            install(HttpTimeout) {
                this.connectTimeoutMillis = connectTimeoutMs
                this.socketTimeoutMillis = readTimeoutMs
            }
            expectSuccess = false // Allow explicit status code handling
            followRedirects = true
        }
    }
}
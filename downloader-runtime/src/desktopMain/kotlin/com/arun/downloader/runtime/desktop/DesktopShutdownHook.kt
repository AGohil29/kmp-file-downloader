package com.arun.downloader.runtime.desktop

import kotlinx.coroutines.runBlocking

object DesktopShutdownHook {

    fun register(onShutdown: suspend () -> Unit) {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runBlocking {
                    try {
                        onShutdown()
                    } catch (_: Exception) {
                        // Best effort flush during JVM termination
                    }
                }
            }
        )
    }
}
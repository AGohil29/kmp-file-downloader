package com.arun.downloader.runtime.desktop

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class DesktopShutdownHookTest {

    @Test
    fun registeringShutdownHookDoesNotThrow() {
        var hookTriggered = false

        // Registering a valid suspendable lambda with the JVM runtime
        DesktopShutdownHook.register {
            delay(10.milliseconds)
            hookTriggered = true
        }

        // Registration completes without uncaught runtime exceptions
        hookTriggered shouldBe false
    }
}
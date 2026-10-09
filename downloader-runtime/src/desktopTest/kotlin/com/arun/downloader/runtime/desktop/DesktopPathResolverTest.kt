package com.arun.downloader.runtime.desktop

import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotBeEmpty
import kotlin.test.Test

class DesktopPathResolverTest {

    @Test
    fun defaultDownloadDirectoryResolvesSuccessfully() {
        val path = DesktopPathResolver.getDefaultDownloadDirectory()
        path.toString().shouldNotBeEmpty()
        path.toString() shouldEndWith "Downloads"
    }

    @Test
    fun defaultDatabaseDirectoryContainsAppName() {
        val appName = "TestApp"
        val path = DesktopPathResolver.getDefaultDatabaseDirectory(appName)
        path.toString().shouldNotBeEmpty()
        path.toString() shouldEndWith appName
    }
}
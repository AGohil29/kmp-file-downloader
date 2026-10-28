package com.arun.downloader.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlFileNameTest {
    @Test
    fun stripsQueryAndFragment() {
        assertEquals("file.zip", fileNameFromUrl("https://x/file.zip?token=abc"))
        assertEquals("file.zip", fileNameFromUrl("https://x/dir/file.zip#section"))
        assertEquals("file.zip", fileNameFromUrl("https://x/file.zip?a=1#frag"))
        assertEquals("file.zip", fileNameFromUrl("https://x/file.zip?redirect=https://y/other/path"))
    }

    @Test
    fun decodesPercentEncoding() {
        assertEquals("my file.zip", fileNameFromUrl("https://x/my%20file.zip"))
        assertEquals("caf\u00e9.txt", fileNameFromUrl("https://x/caf%C3%A9.txt"))
        assertEquals("100%.txt", fileNameFromUrl("https://x/100%.txt"))
    }

    @Test
    fun returnsEmptyWhenNoNameCanBeDerived() {
        assertEquals("", fileNameFromUrl("https://host"))
        assertEquals("", fileNameFromUrl("https://host/"))
        assertEquals("", fileNameFromUrl("https://host/dir/"))
        assertEquals("", fileNameFromUrl("https://host/?x=1"))
    }

    @Test
    fun plainNamesAreUnchanged() {
        assertEquals("a.bin", fileNameFromUrl("https://host/a/b/a.bin"))
        assertEquals("a.bin", fileNameFromUrl("a.bin"))
    }
}


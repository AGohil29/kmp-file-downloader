package com.arun.downloader.core.engine

import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime_nsec_np

internal actual fun getSystemClockEpochMs(): Long =
    (clock_gettime_nsec_np(CLOCK_REALTIME.toUInt()) / 1000000u).toLong()
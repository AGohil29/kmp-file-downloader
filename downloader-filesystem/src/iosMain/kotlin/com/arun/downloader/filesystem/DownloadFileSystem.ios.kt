package com.arun.downloader.filesystem

import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np

internal actual fun getMonotonicClockNs(): Long = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.toUInt()).toLong()
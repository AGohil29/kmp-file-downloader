package com.arun.downloader.core.model

import android.os.SystemClock

internal actual fun getMonotonicTimestampNs(): Long = SystemClock.elapsedRealtimeNanos()
package com.arun.downloader.runtime.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arun.downloader.core.model.DownloadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val downloadIdRaw = intent.getStringExtra(DownloadNotificationManager.EXTRA_DOWNLOAD_ID) ?: return
        val downloadId = DownloadId(downloadIdRaw)

        when (intent.action) {
            DownloadNotificationManager.ACTION_PAUSE -> {
                CoroutineScope(Dispatchers.Default).launch {
                    //DownloadForegroundService.downloaderInstance?.pause(downloadId)
                }
            }
            DownloadNotificationManager.ACTION_CANCEL -> {
                CoroutineScope(Dispatchers.Default).launch {
                    //DownloadForegroundService.downloaderInstance?.cancel(downloadId)
                }
            }
        }
    }

}
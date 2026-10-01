package com.arun.downloader.core.scheduler

import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.core.model.DownloadPriority
import com.arun.downloader.core.model.QueuedDownloadItem

class SchedulerQueue {
    private val items = mutableListOf<QueuedDownloadItem>()

    private val comparator = Comparator<QueuedDownloadItem> { a, b ->
        val priorityComparison = b.priority.weight.compareTo(a.priority.weight)
        if (priorityComparison != 0) {
            priorityComparison
        } else {
            a.createdAtEpochMs.compareTo(b.createdAtEpochMs)
        }
    }

    fun enqueue(id: DownloadId, priority: DownloadPriority, createdAtEpochMs: Long) {
        val item = QueuedDownloadItem(id, priority, createdAtEpochMs)
        items.add(item)
        items.sortWith(comparator)
    }

    fun remove(id: DownloadId): Boolean {
        return items.removeAll { it.id == id }
    }

    fun pollNext(): QueuedDownloadItem? {
        if (items.isEmpty()) return null
        return items.removeAt(0)
    }

    fun peek(): QueuedDownloadItem? = items.firstOrNull()

    fun contains(id: DownloadId): Boolean = items.any { it.id == id }

    fun size(): Int = items.size

    fun clear() {
        items.clear()
    }
}
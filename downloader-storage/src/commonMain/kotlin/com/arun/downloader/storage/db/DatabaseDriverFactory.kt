package com.arun.downloader.storage.db

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import com.arun.downloader.core.model.ConflictStrategy
import com.arun.downloader.core.model.DownloadPriority

expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

fun createDownloadDatabase(driverFactory: DatabaseDriverFactory): DownloadDatabase {
    val driver = driverFactory.createDriver()
    return DownloadDatabase(
        driver = driver,
        DownloadRecordEntityAdapter = DownloadRecordEntity.Adapter(
            priorityAdapter = EnumColumnAdapter<DownloadPriority>(),
            conflict_strategyAdapter = EnumColumnAdapter<ConflictStrategy>()
        )
    )
}
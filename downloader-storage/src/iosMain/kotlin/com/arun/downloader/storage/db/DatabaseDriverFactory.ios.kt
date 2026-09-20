package com.arun.downloader.storage.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DatabaseDriverFactory(
    private val dbName: String = "downloader_metadata.db"
) {
    actual fun createDriver(): SqlDriver {
        return NativeSqliteDriver(
            schema = DownloadDatabase.Schema,
            name = dbName
        )
    }
}
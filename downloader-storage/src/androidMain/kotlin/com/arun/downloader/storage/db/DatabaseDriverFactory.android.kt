package com.arun.downloader.storage.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DatabaseDriverFactory(
    private val context: Context,
    private val dbName: String = "downloader_metadata.db"
) {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = DownloadDatabase.Schema,
            context = context,
            name = dbName
        )
    }
}
package com.arun.downloader.storage.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DatabaseDriverFactory(
    private val dbFile: File? = null
) {
    actual fun createDriver(): SqlDriver {
        val url = if (dbFile != null) {
            dbFile.parentFile?.mkdirs()
            "jdbc:sqlite:${dbFile.absolutePath}"
        } else {
            JdbcSqliteDriver.IN_MEMORY
        }
        val driver = JdbcSqliteDriver(url)
        if (dbFile == null || !dbFile.exists() || dbFile.length() == 0L) {
            DownloadDatabase.Schema.create(driver)
        }
        return driver
    }
}
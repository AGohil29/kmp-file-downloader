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
        } else {
            migrateLegacySchema(driver)
        }
        return driver
    }

    private fun migrateLegacySchema(driver: SqlDriver) {
        val columns = mutableSetOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info(DownloadRecordEntity)",
            mapper = { cursor ->
                while (cursor.next().value) {
                    cursor.getString(1)?.let(columns::add)
                }
                app.cash.sqldelight.db.QueryResult.Unit
            },
            parameters = 0
        )
        if (columns.isEmpty()) {
            DownloadDatabase.Schema.create(driver)
            return
        }
        if ("download_group" !in columns) {
            driver.execute(null, "ALTER TABLE DownloadRecordEntity ADD COLUMN download_group TEXT", 0)
            driver.execute(
                null,
                "CREATE INDEX IF NOT EXISTS idx_download_record_group ON DownloadRecordEntity(download_group)",
                0
            )
        }
        if ("final_path" !in columns) {
            driver.execute(null, "ALTER TABLE DownloadRecordEntity ADD COLUMN final_path TEXT", 0)
        }
        for (column in listOf("request_headers", "checksum_algorithm", "checksum_hash")) {
            if (column !in columns) {
                driver.execute(null, "ALTER TABLE DownloadRecordEntity ADD COLUMN $column TEXT", 0)
            }
        }
        if ("background_task_id" !in columns) {
            driver.execute(null, "ALTER TABLE DownloadRecordEntity ADD COLUMN background_task_id INTEGER", 0)
        }
        if ("resume_data" !in columns) {
            driver.execute(null, "ALTER TABLE DownloadRecordEntity ADD COLUMN resume_data BLOB", 0)
        }
    }
}
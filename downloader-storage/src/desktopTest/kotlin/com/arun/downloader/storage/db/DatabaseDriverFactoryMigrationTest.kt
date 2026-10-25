package com.arun.downloader.storage.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.arun.downloader.core.model.DownloadId
import com.arun.downloader.storage.repository.SqlDelightDownloadRepository
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An on-disk database created before `final_path` existed must be upgraded when reopened,
 * otherwise every query fails with "no such column: DownloadRecordEntity.final_path".
 */
class DatabaseDriverFactoryMigrationTest {

    @Test
    fun legacyDatabaseFileIsUpgradedWithFinalPathColumn() = runTest {
        val dbFile = File.createTempFile("legacy_downloader", ".db").apply { deleteOnExit() }

        // Create the v1 (pre-final_path) table and a row in it.
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}").use { legacy ->
            legacy.execute(
                null,
                """
                CREATE TABLE DownloadRecordEntity (
                    id TEXT NOT NULL PRIMARY KEY,
                    url TEXT NOT NULL,
                    destination_directory TEXT NOT NULL,
                    file_name TEXT,
                    state TEXT NOT NULL,
                    priority TEXT NOT NULL DEFAULT 'NORMAL',
                    downloaded_bytes INTEGER NOT NULL DEFAULT 0,
                    total_bytes INTEGER,
                    etag TEXT,
                    last_modified TEXT,
                    conflict_strategy TEXT NOT NULL DEFAULT 'RENAME_NEW_INCREMENTAL',
                    error_type TEXT,
                    error_message TEXT,
                    created_at_epoch_ms INTEGER NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL,
                    download_group TEXT
                )
                """.trimIndent(),
                0
            )
            legacy.execute(
                null,
                "INSERT INTO DownloadRecordEntity(id, url, destination_directory, file_name, state, " +
                    "created_at_epoch_ms, updated_at_epoch_ms) " +
                    "VALUES ('legacy', 'https://x/a.zip', '/downloads', 'a.zip', 'DOWNLOADING', 1, 1)",
                0
            )
        }

        val repository = SqlDelightDownloadRepository(createDownloadDatabase(DatabaseDriverFactory(dbFile)))
        val id = DownloadId("legacy")

        // Reading the legacy row must not throw, and it has no final path yet.
        assertEquals(null, repository.getById(id)!!.finalPath)

        repository.markCompleted(id, "/downloads/a (1).zip".toPath(), 2L)
        assertEquals("/downloads/a (1).zip".toPath(), repository.getById(id)!!.finalPath)
    }
}


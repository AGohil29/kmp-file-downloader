package com.arun.downloader.core.model

/**
 * Resolution policy when a target file already exists at the specified destination path.
 */
enum class ConflictStrategy {
    /** Overwrites the existing file if present. */
    OVERWRITE,

    /** Appends an incremental suffix: `filename (1).ext`, `filename (2).ext`. */
    RENAME_NEW_INCREMENTAL,

    /** Aborts the download immediately with a FileAlreadyExists error. */
    FAIL
}
package dev.mayo.ymcl.domain

/** Progress snapshot for one install run (files + bytes + current file). */
data class DownloadProgress(
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val totalBytes: Long = 0,
    val completedBytes: Long = 0,
    val currentFile: String = "",
    val failedFiles: Int = 0,
)

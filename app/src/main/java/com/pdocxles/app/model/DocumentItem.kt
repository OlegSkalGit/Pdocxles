package com.pdocxles.app.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DocumentItem(
    val name: String,
    val path: String,
    val uriString: String? = null,
    val type: DocumentType,
    val sizeBytes: Long,
    val lastModified: Long
) {
    val formattedSize: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", sizeBytes / (1024.0 * 1024.0))
        }

    val formattedDate: String
        get() = if (lastModified > 0) {
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            sdf.format(Date(lastModified))
        } else {
            "—"
        }
}

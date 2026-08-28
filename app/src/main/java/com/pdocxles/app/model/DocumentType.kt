package com.pdocxles.app.model

import java.io.File
import java.util.Locale

enum class DocumentType(
    val extension: String,
    val title: String,
    val mimeType: String,
    val colorHex: Int
) {
    PDF("pdf", "PDF Document", "application/pdf", 0xFFE53935.toInt()),
    DOCX("docx", "Word Document", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 0xFF1E88E5.toInt()),
    XLSX("xlsx", "Excel Spreadsheet", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 0xFF2E7D32.toInt()),
    PPTX("pptx", "PowerPoint Presentation", "application/vnd.openxmlformats-officedocument.presentationml.presentation", 0xFFE65100.toInt()),
    UNKNOWN("", "Unknown Document", "application/octet-stream", 0xFF757575.toInt());

    companion object {
        fun fromFileName(fileName: String?): DocumentType {
            if (fileName.isNullOrBlank()) return UNKNOWN
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (ext) {
                "pdf" -> PDF
                "docx", "doc" -> DOCX
                "xlsx", "xls" -> XLSX
                "pptx", "ppt" -> PPTX
                else -> UNKNOWN
            }
        }

        fun fromFile(file: File): DocumentType = fromFileName(file.name)

        fun fromMimeType(mimeType: String?): DocumentType {
            if (mimeType.isNullOrBlank()) return UNKNOWN
            return when {
                mimeType.contains("pdf", ignoreCase = true) -> PDF
                mimeType.contains("word", ignoreCase = true) || mimeType.contains("docx", ignoreCase = true) -> DOCX
                mimeType.contains("sheet", ignoreCase = true) || mimeType.contains("excel", ignoreCase = true) || mimeType.contains("spreadsheet", ignoreCase = true) -> XLSX
                mimeType.contains("presentation", ignoreCase = true) || mimeType.contains("powerpoint", ignoreCase = true) || mimeType.contains("pptx", ignoreCase = true) -> PPTX
                else -> UNKNOWN
            }
        }
    }
}

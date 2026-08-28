package com.pdocxles.app.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.pdocxles.app.model.DocumentItem
import com.pdocxles.app.model.DocumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object DocumentStorageManager {

    private const val CACHE_SUBDIR = "cached_docs"

    /**
     * Resolves a Uri to a local File safely.
     * Uses deterministic file naming to prevent creating duplicate timestamped files.
     */
    suspend fun resolveUriToLocalFile(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            when (uri.scheme) {
                "file" -> {
                    val path = uri.path ?: return@withContext null
                    val file = File(path)
                    if (file.exists() && file.canRead()) file else null
                }
                "content" -> {
                    val rawName = getFileNameFromUri(context, uri) ?: "document"
                    val safeName = sanitizeFileName(rawName)
                    val cacheDir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
                    
                    val destFile = File(cacheDir, safeName)
                    
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        FileOutputStream(destFile).use { outputStream ->
                            copyStream(inputStream, outputStream)
                        }
                    }
                    if (destFile.exists() && destFile.length() > 0) destFile else null
                }
                else -> {
                    uri.path?.let { path ->
                        val f = File(path)
                        if (f.exists()) f else null
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Extracts actual human-readable file name from Uri using OpenableColumns or lastPathSegment.
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val segment = uri.lastPathSegment?.substringAfterLast('/')
        return if (!segment.isNullOrBlank()) segment else null
    }

    /**
     * Scans cache and accessible standard storage folders for supported documents.
     * Deduplicates items by name and size to ensure no duplicate files are shown.
     */
    suspend fun scanDocuments(context: Context): List<DocumentItem> = withContext(Dispatchers.IO) {
        val itemsMap = mutableMapOf<String, DocumentItem>() // key: "name_size"

        fun addFile(file: File) {
            if (file.isFile && file.length() > 0) {
                val type = DocumentType.fromFile(file)
                if (type != DocumentType.UNKNOWN) {
                    val cleanDisplayName = file.name.replace(Regex("^\\d{10,14}_"), "")
                    val deduplicationKey = "${cleanDisplayName.lowercase()}_${file.length()}"

                    // If not yet seen, or if external file replacing internal cache copy
                    val existing = itemsMap[deduplicationKey]
                    if (existing == null || (!file.absolutePath.contains(CACHE_SUBDIR) && existing.path.contains(CACHE_SUBDIR))) {
                        itemsMap[deduplicationKey] = DocumentItem(
                            name = cleanDisplayName,
                            path = file.absolutePath,
                            type = type,
                            sizeBytes = file.length(),
                            lastModified = file.lastModified()
                        )
                    }
                }
            }
        }

        // 1. Scan standard external public directories (Downloads, Documents)
        val searchDirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            context.getExternalFilesDir(null)
        )

        for (dir in searchDirs) {
            if (dir.exists() && dir.canRead()) {
                dir.listFiles()?.take(50)?.forEach { addFile(it) }
            }
        }

        // 2. Scan application internal cache folder
        val cacheDir = File(context.cacheDir, CACHE_SUBDIR)
        if (cacheDir.exists() && cacheDir.isDirectory) {
            cacheDir.listFiles()?.forEach { addFile(it) }
        }

        itemsMap.values.sortedByDescending { it.lastModified }
    }

    private fun copyStream(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(32 * 1024)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
        output.flush()
    }

    /**
     * Sanitizes file name by removing only invalid filesystem characters,
     * preserving Cyrillic, Latin, digits, spaces, dots and dashes.
     */
    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (cleaned.isNotEmpty()) cleaned else "document"
    }

    /**
     * Cleans old cache files if cache exceeds 100MB, or purges obsolete timestamped files.
     */
    fun trimCache(context: Context) {
        try {
            val cacheDir = File(context.cacheDir, CACHE_SUBDIR)
            if (!cacheDir.exists()) return
            val files = cacheDir.listFiles() ?: return

            // Delete old timestamped duplicate files if any
            files.filter { it.name.matches(Regex("^\\d{10,14}_.*")) }.forEach { it.delete() }

            val remainingFiles = cacheDir.listFiles() ?: return
            val totalSize = remainingFiles.sumOf { it.length() }
            if (totalSize > 100 * 1024 * 1024) {
                remainingFiles.sortedBy { it.lastModified() }
                    .take(remainingFiles.size / 2)
                    .forEach { it.delete() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

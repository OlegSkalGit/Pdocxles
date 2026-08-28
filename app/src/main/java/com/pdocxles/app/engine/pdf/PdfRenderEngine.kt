package com.pdocxles.app.engine.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PdfRenderEngine(private val file: File) : AutoCloseable {

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderLock = Mutex()

    var pageCount: Int = 0
        private set

    // LRU Cache for rendered page bitmaps (Limit to ~30MB memory footprint)
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceIn(1024 * 16, 1024 * 48) // 16MB - 48MB

    private val bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && oldValue != newValue && !oldValue.isRecycled) {
                // Recycle evicted bitmaps
                oldValue.recycle()
            }
        }
    }

    suspend fun initialize(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("File is empty or not found: ${file.absolutePath}"))
            }

            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            fileDescriptor = pfd
            val pdfRenderer = PdfRenderer(pfd)
            renderer = pdfRenderer
            pageCount = pdfRenderer.pageCount
            Result.success(pageCount)
        } catch (e: Exception) {
            close()
            Result.failure(e)
        }
    }

    /**
     * Renders a specific page index to Bitmap (0-indexed).
     * Automatically retrieves from LRU cache if already rendered.
     */
    suspend fun renderPage(pageIndex: Int, targetWidth: Int = 1080): Bitmap? = withContext(Dispatchers.IO) {
        if (pageIndex < 0 || pageIndex >= pageCount) return@withContext null

        // 1. Check LRU Cache
        bitmapCache.get(pageIndex)?.let { cachedBitmap ->
            if (!cachedBitmap.isRecycled) return@withContext cachedBitmap
        }

        // 2. Synchronized render via PdfRenderer (PdfRenderer allows only 1 open page at a time)
        renderLock.withLock {
            val currentRenderer = renderer ?: return@withContext null
            try {
                currentRenderer.openPage(pageIndex).use { page ->
                    val pageWidth = page.width
                    val pageHeight = page.height

                    val scale = (targetWidth.toFloat() / pageWidth.toFloat()).coerceIn(1.0f, 3.0f)
                    val bitmapWidth = (pageWidth * scale).toInt().coerceAtLeast(1)
                    val bitmapHeight = (pageHeight * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                    // Fill background white
                    bitmap.eraseColor(Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    bitmapCache.put(pageIndex, bitmap)
                    bitmap
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Returns the aspect ratio (height / width) of a given page without rendering full bitmap.
     */
    suspend fun getPageAspectRatio(pageIndex: Int): Float = withContext(Dispatchers.IO) {
        if (pageIndex < 0 || pageIndex >= pageCount) return@withContext 1.414f // Standard A4 default
        renderLock.withLock {
            val currentRenderer = renderer ?: return@withContext 1.414f
            try {
                currentRenderer.openPage(pageIndex).use { page ->
                    page.height.toFloat() / page.width.toFloat()
                }
            } catch (e: Exception) {
                1.414f
            }
        }
    }

    override fun close() {
        try {
            bitmapCache.evictAll()
            renderer?.close()
            fileDescriptor?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            renderer = null
            fileDescriptor = null
        }
    }
}

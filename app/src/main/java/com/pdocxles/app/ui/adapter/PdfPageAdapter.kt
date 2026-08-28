package com.pdocxles.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import com.pdocxles.app.R
import com.pdocxles.app.engine.pdf.PdfRenderEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfPageAdapter(
    private val pageCount: Int,
    private val renderEngine: PdfRenderEngine,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<PdfPageAdapter.PdfPageViewHolder>() {

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfPageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false)
        return PdfPageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PdfPageViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun onViewRecycled(holder: PdfPageViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelJob()
    }

    inner class PdfPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPdfPage: ImageView = itemView.findViewById(R.id.ivPdfPage)
        private val pageLoadingBar: ProgressBar = itemView.findViewById(R.id.pageLoadingBar)
        private var renderJob: Job? = null

        fun bind(pageIndex: Int) {
            cancelJob()
            ivPdfPage.setImageDrawable(null)
            pageLoadingBar.visibility = View.VISIBLE

            renderJob = scope.launch {
                val displayWidth = itemView.resources.displayMetrics.widthPixels
                val bitmap = renderEngine.renderPage(pageIndex, targetWidth = displayWidth)
                withContext(Dispatchers.Main) {
                    if (bitmap != null && !bitmap.isRecycled) {
                        ivPdfPage.setImageBitmap(bitmap)
                        pageLoadingBar.visibility = View.GONE
                    }
                }
            }
        }

        fun cancelJob() {
            renderJob?.cancel()
            renderJob = null
        }
    }
}

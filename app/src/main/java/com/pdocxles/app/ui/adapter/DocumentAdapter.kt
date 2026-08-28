package com.pdocxles.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pdocxles.app.R
import com.pdocxles.app.model.DocumentItem
import com.pdocxles.app.model.DocumentType

class DocumentAdapter(
    private val onItemClick: (DocumentItem) -> Unit
) : ListAdapter<DocumentItem, DocumentAdapter.DocumentViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_document, parent, false)
        return DocumentViewHolder(view)
    }

    override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DocumentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconBadge: FrameLayout = itemView.findViewById(R.id.iconBadge)
        private val ivDocIcon: ImageView = itemView.findViewById(R.id.ivDocIcon)
        private val tvDocName: TextView = itemView.findViewById(R.id.tvDocName)
        private val tvDocMeta: TextView = itemView.findViewById(R.id.tvDocMeta)

        fun bind(item: DocumentItem) {
            tvDocName.text = item.name
            tvDocMeta.text = "${item.formattedSize} • ${item.formattedDate}"

            when (item.type) {
                DocumentType.PDF -> {
                    iconBadge.setBackgroundResource(R.drawable.bg_badge_pdf)
                    ivDocIcon.setImageResource(R.drawable.ic_pdf)
                }
                DocumentType.DOCX -> {
                    iconBadge.setBackgroundResource(R.drawable.bg_badge_docx)
                    ivDocIcon.setImageResource(R.drawable.ic_docx)
                }
                DocumentType.XLSX -> {
                    iconBadge.setBackgroundResource(R.drawable.bg_badge_xlsx)
                    ivDocIcon.setImageResource(R.drawable.ic_xlsx)
                }
                DocumentType.PPTX -> {
                    iconBadge.setBackgroundResource(R.drawable.bg_badge_pptx)
                    ivDocIcon.setImageResource(R.drawable.ic_pptx)
                }
                DocumentType.UNKNOWN -> {
                    iconBadge.setBackgroundResource(R.drawable.bg_badge_unknown)
                    ivDocIcon.setImageResource(R.drawable.ic_docx)
                }
            }

            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<DocumentItem>() {
        override fun areItemsTheSame(oldItem: DocumentItem, newItem: DocumentItem): Boolean =
            oldItem.path == newItem.path

        override fun areContentsTheSame(oldItem: DocumentItem, newItem: DocumentItem): Boolean =
            oldItem == newItem
    }
}

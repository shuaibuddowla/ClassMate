package com.shuaib.classmate.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.databinding.ItemDigestStudySuggestionBinding
import com.shuaib.classmate.models.PdfFile
import com.shuaib.classmate.utils.applyClickAnimation

class DigestStudySuggestionAdapter(
    private var suggestions: List<PdfFile> = emptyList(),
    private val onStudyClick: (PdfFile) -> Unit
) : RecyclerView.Adapter<DigestStudySuggestionAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemDigestStudySuggestionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDigestStudySuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pdf = suggestions[position]
        
        holder.binding.apply {
            tvSuggestionSubject.text = pdf.subject
            tvSuggestionTitle.text = pdf.title
            tvSuggestionType.text = pdf.fileType.uppercase()
            
            tvStudyNow.applyClickAnimation {
                onStudyClick(pdf)
            }
        }
    }

    override fun getItemCount() = suggestions.size

    fun updateList(newSuggestions: List<PdfFile>) {
        suggestions = newSuggestions
        notifyDataSetChanged()
    }
}

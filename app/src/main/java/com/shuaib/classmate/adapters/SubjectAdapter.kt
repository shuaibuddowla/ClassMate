package com.shuaib.classmate.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.databinding.ItemSubjectCardBinding
import com.shuaib.classmate.utils.Subject
import com.shuaib.classmate.utils.SubjectVisuals
import com.shuaib.classmate.utils.applyClickAnimation

class SubjectAdapter(
    private var subjects: List<Subject>,
    private var pdfCounts: Map<String, Int> = emptyMap(),
    private val onItemClick: (Subject) -> Unit
) : RecyclerView.Adapter<SubjectAdapter.SubjectViewHolder>() {

    inner class SubjectViewHolder(val binding: ItemSubjectCardBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubjectViewHolder {
        val binding = ItemSubjectCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SubjectViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SubjectViewHolder, position: Int) {
        val subject = subjects[position]
        holder.binding.apply {
            tvSubjectCode.text = subject.code.ifBlank { "LIB0000" }
            tvSubjectName.text = subject.name
            SubjectVisuals.applyTo(iconContainer, ivSubjectIcon, subject.name, radiusDp = 13f)

            val count = pdfCounts[subject.name] ?: 0
            val fileText = when (count) {
                0 -> "No resources"
                1 -> "1 resource"
                else -> "$count resources"
            }
            tvPdfCount.text = fileText

            root.applyClickAnimation {
                onItemClick(subject)
            }
        }
    }

    override fun getItemCount(): Int = subjects.size

    fun updateList(newSubjects: List<Subject>, newPdfCounts: Map<String, Int>) {
        val oldSubjects = subjects
        val oldCounts = pdfCounts
        subjects = newSubjects
        pdfCounts = newPdfCounts
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldSubjects.size
            override fun getNewListSize(): Int = newSubjects.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldSubjects[oldItemPosition].name == newSubjects[newItemPosition].name
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = oldSubjects[oldItemPosition]
                val new = newSubjects[newItemPosition]
                return old == new && oldCounts[old.name] == newPdfCounts[new.name]
            }
        }).dispatchUpdatesTo(this)
    }

}

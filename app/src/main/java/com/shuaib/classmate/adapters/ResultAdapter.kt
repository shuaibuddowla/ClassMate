/*
 * C:/Users/USER/AndroidStudioProjects/ClassMate/app/src/main/java/com/shuaib/classmate/adapters/ResultAdapter.kt
 */
package com.shuaib.classmate.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.databinding.ItemResultBinding
import com.shuaib.classmate.models.Result
import com.shuaib.classmate.utils.applyClickAnimation
import java.text.SimpleDateFormat
import java.util.*

class ResultAdapter(
    private var results: List<Result>,
    private val rootView: ViewGroup? = null,
    private val onResultClick: (Result) -> Unit
) : RecyclerView.Adapter<ResultAdapter.ResultViewHolder>() {

    var onItemBound: ((ResultViewHolder, Int) -> Unit)? = null

    inner class ResultViewHolder(val binding: ItemResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val binding = ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        val result = results[position]
        holder.binding.apply {
            tvResultTitle.text = result.title
            
            val timestamp = result.timestamp
            if (timestamp != null) {
                val date = timestamp.toDate()
                val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                tvUploadDate.text = "Published: ${sdf.format(date)}"
            } else {
                tvUploadDate.text = "Date unknown"
            }

            root.applyClickAnimation { onResultClick(result) }
        }
        onItemBound?.invoke(holder, position)
    }

    override fun getItemCount(): Int = results.size

    fun updateList(newList: List<Result>) {
        results = newList
        notifyDataSetChanged()
    }
}

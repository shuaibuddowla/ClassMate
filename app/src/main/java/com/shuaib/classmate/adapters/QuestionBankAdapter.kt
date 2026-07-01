package com.shuaib.classmate.adapters

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.shuaib.classmate.R
import com.shuaib.classmate.models.QuestionPaper
import com.shuaib.classmate.utils.applyClickAnimation

class QuestionBankAdapter(
    private var items: List<QuestionPaper>,
    private var isAdmin: Boolean
) : RecyclerView.Adapter<QuestionBankAdapter.VH>() {

    var onDeleteClick: ((QuestionPaper) -> Unit)? = null

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvQpTitle)
        val tvExamType: TextView = view.findViewById(R.id.tvQpExamType)
        val tvYear: TextView = view.findViewById(R.id.tvQpYear)
        val tvSemester: TextView = view.findViewById(R.id.tvQpSemester)
        val tvMeta: TextView = view.findViewById(R.id.tvQpMeta)
        val btnOpen: TextView = view.findViewById(R.id.btnOpen)
        val btnDelete: ImageView = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_question_paper, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val qp = items[position]
        val ctx = holder.itemView.context

        holder.tvTitle.text = buildTitle(qp)
        holder.tvExamType.text = qp.examType.uppercase()
        holder.tvYear.text = qp.year.ifBlank { "—" }
        holder.tvSemester.text = if (qp.semester.isNotBlank()) "${qp.semester} Sem" else ""
        holder.tvSemester.isVisible = qp.semester.isNotBlank()
        holder.tvMeta.text = buildMeta(qp)

        holder.btnDelete.isVisible = isAdmin
        holder.btnDelete.applyClickAnimation { onDeleteClick?.invoke(qp) }

        holder.btnOpen.applyClickAnimation { openUrl(ctx, qp) }
        holder.itemView.applyClickAnimation { openUrl(ctx, qp) }
    }

    override fun getItemCount() = items.size

    fun updateList(newItems: List<QuestionPaper>, admin: Boolean) {
        items = newItems
        isAdmin = admin
        notifyDataSetChanged()
    }

    private fun buildTitle(qp: QuestionPaper): String {
        val parts = mutableListOf<String>()
        if (qp.examType.isNotBlank()) parts += qp.examType
        if (qp.year.isNotBlank()) parts += qp.year
        val courseLabel = qp.courseCode.ifBlank { qp.subject }
        if (courseLabel.isNotBlank()) parts += "— $courseLabel"
        return if (parts.isEmpty()) qp.title.ifBlank { "Question Paper" }
        else parts.joinToString(" ").also { if (qp.title.isNotBlank() && qp.title != it) return qp.title }
    }

    private fun buildMeta(qp: QuestionPaper): String {
        val uploader = qp.uploadedBy.ifBlank { "Admin" }
        val time = qp.timestamp?.toDate()?.time?.let {
            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        } ?: "recently"
        return "By $uploader · $time"
    }

    private fun openUrl(ctx: Context, qp: QuestionPaper) {
        val url = qp.downloadUrl.ifBlank { qp.driveUrl.ifBlank { qp.telegramUrl } }
        if (url.isBlank()) {
            Toast.makeText(ctx, "Link not available yet", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(ctx, "Opening…", Toast.LENGTH_SHORT).show()
        val uri = Uri.parse(url)
        try {
            val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
            intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.launchUrl(ctx, uri)
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
                Toast.makeText(ctx, "Unable to open link", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

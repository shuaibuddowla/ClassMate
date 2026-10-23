package com.shuaib.classmate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

object R2StorageDialog {

    fun show(context: Context) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_r2_storage_sheet, null)
        dialog.setContentView(view)

        val pbLoading = view.findViewById<ProgressBar>(R.id.pbR2Loading)
        val nsvContent = view.findViewById<View>(R.id.nsvR2Content)
        val btnRefresh = view.findViewById<ImageButton>(R.id.btnR2Refresh)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnR2Close)

        val tvUsedGb = view.findViewById<TextView>(R.id.tvR2UsedGb)
        val tvStatusBadge = view.findViewById<TextView>(R.id.tvR2StatusBadge)
        val pbStorageMeter = view.findViewById<ProgressBar>(R.id.pbR2StorageMeter)
        val tvRemainingText = view.findViewById<TextView>(R.id.tvR2RemainingText)

        val tvActiveFiles = view.findViewById<TextView>(R.id.tvR2ActiveFiles)
        val tvTotalStored = view.findViewById<TextView>(R.id.tvR2TotalStored)
        val tvQuotaUsedPercent = view.findViewById<TextView>(R.id.tvR2QuotaUsedPercent)

        val llCourses = view.findViewById<LinearLayout>(R.id.llR2CoursesContainer)
        val llUploaders = view.findViewById<LinearLayout>(R.id.llR2UploadersContainer)

        btnClose.setOnClickListener { dialog.dismiss() }

        fun loadStats() {
            pbLoading.visibility = View.VISIBLE
            nsvContent.visibility = View.GONE
            btnRefresh.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response = ClassMateAuthApi.rpc("get_r2_storage_stats", JSONObject())
                    withContext(Dispatchers.Main) {
                        pbLoading.visibility = View.GONE
                        nsvContent.visibility = View.VISIBLE
                        btnRefresh.isEnabled = true
                        bindData(
                            context = context,
                            data = response,
                            tvUsedGb = tvUsedGb,
                            tvStatusBadge = tvStatusBadge,
                            pbStorageMeter = pbStorageMeter,
                            tvRemainingText = tvRemainingText,
                            tvActiveFiles = tvActiveFiles,
                            tvTotalStored = tvTotalStored,
                            tvQuotaUsedPercent = tvQuotaUsedPercent,
                            llCourses = llCourses,
                            llUploaders = llUploaders
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        pbLoading.visibility = View.GONE
                        nsvContent.visibility = View.VISIBLE
                        btnRefresh.isEnabled = true
                        Toast.makeText(context, "Failed to load storage records: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnRefresh.setOnClickListener { loadStats() }
        loadStats()
        dialog.show()
    }

    private fun bindData(
        context: Context,
        data: JSONObject,
        tvUsedGb: TextView,
        tvStatusBadge: TextView,
        pbStorageMeter: ProgressBar,
        tvRemainingText: TextView,
        tvActiveFiles: TextView,
        tvTotalStored: TextView,
        tvQuotaUsedPercent: TextView,
        llCourses: LinearLayout,
        llUploaders: LinearLayout
    ) {
        val totalBytes = data.optLong("total_bytes", 0L)
        val totalFiles = data.optInt("total_files", 0)
        val quotaBytes = data.optLong("quota_bytes", 10_737_418_240L) // 10 GB
        val limitBytes = data.optLong("limit_bytes", 10_630_044_057L) // 9.9 GB
        val status = data.optString("status", "normal")

        val usedGb = totalBytes.toDouble() / (1024 * 1024 * 1024)
        tvUsedGb.text = String.format(Locale.US, "%.2f GB", usedGb)

        val percentQuota = (totalBytes.toDouble() / quotaBytes.toDouble() * 100.0).coerceIn(0.0, 100.0)
        tvQuotaUsedPercent.text = String.format(Locale.US, "%.1f%%", percentQuota)
        pbStorageMeter.max = 1000
        pbStorageMeter.progress = (percentQuota * 10).toInt()

        val remainingLimitBytes = maxOf(0L, limitBytes - totalBytes)

        val colorEmerald = Color.parseColor("#10B981")
        val colorAmber = Color.parseColor("#F59E0B")
        val colorRed = Color.parseColor("#EF4444")

        when (status) {
            "exceeded" -> {
                tvStatusBadge.text = "Limit Reached"
                tvStatusBadge.setTextColor(colorRed)
                tvStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#26EF4444"))
                pbStorageMeter.progressTintList = ColorStateList.valueOf(colorRed)
                tvRemainingText.text = "Uploads blocked"
                tvRemainingText.setTextColor(colorRed)
                tvQuotaUsedPercent.setTextColor(colorRed)
            }
            "warning" -> {
                tvStatusBadge.text = "Approaching Limit"
                tvStatusBadge.setTextColor(colorAmber)
                tvStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#26F59E0B"))
                pbStorageMeter.progressTintList = ColorStateList.valueOf(colorAmber)
                tvRemainingText.text = "${formatBytes(remainingLimitBytes)} remaining"
                tvRemainingText.setTextColor(colorAmber)
                tvQuotaUsedPercent.setTextColor(colorAmber)
            }
            else -> {
                tvStatusBadge.text = "Storage Healthy"
                tvStatusBadge.setTextColor(colorEmerald)
                tvStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2610B981"))
                pbStorageMeter.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.cm_primary))
                tvRemainingText.text = "${formatBytes(remainingLimitBytes)} remaining"
                tvRemainingText.setTextColor(ContextCompat.getColor(context, R.color.cm_text_primary))
                tvQuotaUsedPercent.setTextColor(ContextCompat.getColor(context, R.color.cm_primary))
            }
        }

        tvActiveFiles.text = totalFiles.toString()
        tvTotalStored.text = formatBytes(totalBytes)

        // Courses Breakdown
        llCourses.removeAllViews()
        val coursesArr = data.optJSONArray("courses")
        if (coursesArr == null || coursesArr.length() == 0) {
            val emptyTv = TextView(context).apply {
                text = "No course files uploaded yet"
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.cm_text_muted))
                setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            }
            llCourses.addView(emptyTv)
        } else {
            for (i in 0 until coursesArr.length()) {
                val item = coursesArr.getJSONObject(i)
                val code = item.optString("course_code", "GEN")
                val title = item.optString("course_title", "General")
                val count = item.optInt("file_count", 0)
                val bytes = item.optLong("total_bytes", 0L)

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
                }

                val leftCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvCode = TextView(context).apply {
                    text = "$code · $title"
                    textSize = 12.5f
                    setTextColor(ContextCompat.getColor(context, R.color.cm_text_primary))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val tvCount = TextView(context).apply {
                    text = "$count ${if (count == 1) "file" else "files"}"
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(context, R.color.cm_text_secondary))
                }
                leftCol.addView(tvCode)
                leftCol.addView(tvCount)

                val tvSize = TextView(context).apply {
                    text = formatBytes(bytes)
                    textSize = 12.5f
                    setTextColor(ContextCompat.getColor(context, R.color.cm_text_primary))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }

                row.addView(leftCol)
                row.addView(tvSize)
                llCourses.addView(row)

                if (i < coursesArr.length() - 1) {
                    val divider = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(context, 1)
                        ).apply {
                            setMargins(dp(context, 12), 0, dp(context, 12), 0)
                        }
                        setBackgroundColor(ContextCompat.getColor(context, R.color.cm_border))
                    }
                    llCourses.addView(divider)
                }
            }
        }

        // Top Uploaders Breakdown
        llUploaders.removeAllViews()
        val uploadersArr = data.optJSONArray("uploaders")
        if (uploadersArr == null || uploadersArr.length() == 0) {
            val emptyTv = TextView(context).apply {
                text = "No uploaders recorded"
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.cm_text_muted))
                setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            }
            llUploaders.addView(emptyTv)
        } else {
            for (i in 0 until uploadersArr.length()) {
                val item = uploadersArr.getJSONObject(i)
                val name = item.optString("full_name", "Contributor")
                val role = item.optString("role", "member").uppercase()
                val count = item.optInt("file_count", 0)
                val bytes = item.optLong("total_bytes", 0L)

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
                }

                val leftCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvName = TextView(context).apply {
                    text = "$name ($role)"
                    textSize = 12.5f
                    setTextColor(ContextCompat.getColor(context, R.color.cm_text_primary))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val tvCount = TextView(context).apply {
                    text = "$count ${if (count == 1) "file" else "files"}"
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(context, R.color.cm_text_secondary))
                }
                leftCol.addView(tvName)
                leftCol.addView(tvCount)

                val tvSize = TextView(context).apply {
                    text = formatBytes(bytes)
                    textSize = 12.5f
                    setTextColor(ContextCompat.getColor(context, R.color.cm_text_primary))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }

                row.addView(leftCol)
                row.addView(tvSize)
                llUploaders.addView(row)

                if (i < uploadersArr.length() - 1) {
                    val divider = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(context, 1)
                        ).apply {
                            setMargins(dp(context, 12), 0, dp(context, 12), 0)
                        }
                        setBackgroundColor(ContextCompat.getColor(context, R.color.cm_border))
                    }
                    llUploaders.addView(divider)
                }
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val formatted = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(Locale.US, "%.1f %s", formatted, units[digitGroups])
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}

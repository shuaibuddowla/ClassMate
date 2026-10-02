package com.shuaib.classmate.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Dedicated, locally sorted view of every active file in one semester course. */
class ClassMateCourseFilesActivity : AppCompatActivity() {
    private val courseId by lazy { intent.getStringExtra("course_id").orEmpty() }
    private val batchId by lazy { intent.getStringExtra("batch_id").orEmpty() }
    private var search = ""
    private val courseName by lazy { intent.getStringExtra("course_name").orEmpty() }
    private val canEdit by lazy { intent.getStringExtra("role") == "admin" || intent.getBooleanExtra("is_cr", false) }
    private val editLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) { setResult(RESULT_OK); loadFiles() }
    }
    private val canManage by lazy { intent.getBooleanExtra("can_manage", false) }
    private val files = mutableListOf<JSONObject>()
    private var filtered = emptyList<JSONObject>()
    private var category = "All"
    private var sort = "Newest"
    private lateinit var count: TextView
    private lateinit var sortButton: TextView
    private lateinit var filterButton: TextView
    private lateinit var adapter: FileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClassMateAuthApi.attach(applicationContext)
        if (courseId.isBlank() && batchId.isBlank()) { finish(); return }
        val scale = resources.displayMetrics.density
        fun dp(value: Int) = (value * scale).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.cm_background))
            setPadding(dp(16), dp(18), dp(16), 0)
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(dp(16) + bars.left, bars.top, dp(16) + bars.right, bars.bottom); insets
        }
        root.addView(com.google.android.material.appbar.MaterialToolbar(this).apply {
            title = courseName; setTitleTextColor(getColor(R.color.cm_text_primary))
            setNavigationIcon(R.drawable.ic_chevron_left); navigationIcon?.setTint(getColor(R.color.cm_text_primary))
            setNavigationOnClickListener { finish() }
        })
        count = TextView(this).apply {
            text = "Loading files…"
            textSize = 13f
            setTextColor(getColor(R.color.cm_text_disabled))
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(count)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterButton = control("All categories") {
            val choices = arrayOf("All", "Notes", "Slides", "Questions", "Syllabus", "Other")
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("File category").setItems(choices) { _, which ->
                category = choices[which]
                filterButton.text = category
                showFiles()
            }.show()
        }
        sortButton = control("Newest first") {
            val choices = arrayOf("Newest", "Oldest", "Name")
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Sort files").setItems(choices) { _, which ->
                sort = choices[which]
                sortButton.text = when (sort) {
                    "Oldest" -> "Oldest first"; "Name" -> "Name A–Z"; else -> "Newest first"
                }
                showFiles()
            }.show()
        }
        controls.addView(filterButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        controls.addView(sortButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(8)
        })
        root.addView(controls)
        root.addView(android.widget.EditText(this).apply {
            hint = "Search files"; setSingleLine(true); textSize = 14f
            setTextColor(getColor(R.color.cm_text_primary)); setHintTextColor(getColor(R.color.cm_text_secondary))
            setBackgroundResource(R.drawable.bg_library_search_html); setPadding(dp(16), 0, dp(16), 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { search = s.toString(); if (::adapter.isInitialized) showFiles() }
            })
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        adapter = FileAdapter()
        root.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ClassMateCourseFilesActivity)
            adapter = this@ClassMateCourseFilesActivity.adapter
            clipToPadding = false
            setPadding(0, dp(12), 0, dp(24))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadFiles()
    }

    private fun control(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = android.view.Gravity.CENTER
        setTextColor(getColor(R.color.cm_primary))
        setBackgroundResource(R.drawable.bg_library_action_capsule)
        setOnClickListener { onClick() }
    }

    private fun loadFiles() = lifecycleScope.launch {
        runCatching {
            val loaded = mutableListOf<JSONObject>()
            var offset = 0
            do {
                val page = ClassMateAuthApi.rows("file_metadata",
                    "select=id,title,category,size_bytes,created_at,semester_course_id,batch_id&" +
                        (if (courseId.isBlank()) "batch_id=eq.$batchId" else "semester_course_id=eq.$courseId") +
                        "&status=eq.active&order=created_at.desc&limit=500&offset=$offset")
                for (i in 0 until page.length()) loaded.add(page.getJSONObject(i))
                offset += page.length()
            } while (page.length() == 500)
            loaded
        }.onSuccess {
            files.clear()
            files.addAll(it)
            showFiles()
        }.onFailure { error ->
            count.text = "Could not load files"
            Toast.makeText(this@ClassMateCourseFilesActivity, error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showFiles() {
        filtered = files.filter { (category == "All" ||
            it.optString("category").equals(category, true)) && (search.isBlank() || it.optString("title").contains(search, true)) }.let { items ->
            when (sort) {
                "Oldest" -> items.sortedBy { it.optString("created_at") }
                "Name" -> items.sortedBy { it.optString("title").lowercase() }
                else -> items.sortedByDescending { it.optString("created_at") }
            }
        }
        count.text = "${filtered.size} ${if (filtered.size == 1) "file" else "files"}"
        adapter.notifyDataSetChanged()
    }

    private fun openFile(file: JSONObject) = lifecycleScope.launch {
        runCatching { ClassMateAuthApi.signedResource(file.getString("id")) }
            .onSuccess { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            .onFailure { Toast.makeText(this@ClassMateCourseFilesActivity,
                it.message, Toast.LENGTH_LONG).show() }
    }

    private fun deleteFile(file: JSONObject) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Permanently delete ${file.optString("title")}?")
            .setMessage("This removes the protected file, library entry, and linked notice. This cannot be undone.")
            .setPositiveButton("Delete permanently") { _, _ -> lifecycleScope.launch {
                runCatching { ClassMateAuthApi.deleteResource(file.getString("id")) }
                    .onSuccess {
                        ClassMateAcademicCache.removeResourceNotice(this@ClassMateCourseFilesActivity, intent.getStringExtra("profile_id").orEmpty(), batchId, file.getString("id"))
                        files.removeAll { it.optString("id") == file.optString("id") }
                        showFiles()
                        setResult(RESULT_OK)
                    }.onFailure { Toast.makeText(this@ClassMateCourseFilesActivity,
                        it.message, Toast.LENGTH_LONG).show() }
            } }.setNegativeButton("Cancel", null).show()
    }

    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.Holder>() {
        inner class Holder(val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
            val title: TextView = card.findViewWithTag("title")
            val meta: TextView = card.findViewWithTag("meta")
            val delete: TextView = card.findViewWithTag("delete")
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val dp = resources.displayMetrics.density
            val card = MaterialCardView(this@ClassMateCourseFilesActivity).apply {
                radius = 14 * dp
                strokeWidth = dp.toInt()
                strokeColor = getColor(R.color.cm_border_glass)
                setCardBackgroundColor(getColor(R.color.cm_card))
                layoutParams = RecyclerView.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = (8 * dp).toInt() }
            }
            val row = LinearLayout(this@ClassMateCourseFilesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt())
            }
            val labels = LinearLayout(this@ClassMateCourseFilesActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            labels.addView(TextView(this@ClassMateCourseFilesActivity).apply {
                tag = "title"; textSize = 15f
                setTextColor(getColor(R.color.cm_text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            labels.addView(TextView(this@ClassMateCourseFilesActivity).apply {
                tag = "meta"; textSize = 12f
                setTextColor(getColor(R.color.cm_text_disabled))
            })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this@ClassMateCourseFilesActivity).apply {
                tag = "delete"; text = if (canEdit) "Manage" else "Delete"; textSize = 13f
                setTextColor(getColor(R.color.cm_error))
                setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
                visibility = if (canManage) View.VISIBLE else View.GONE
            })
            card.addView(row)
            return Holder(card)
        }
        override fun getItemCount() = filtered.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val file = filtered[position]
            holder.title.text = ClassMateNoticeText.styled(holder.title, file.optString("title"), search)
            holder.meta.text = "${file.optString("category").replaceFirstChar { it.uppercase() }}" +
                " · ${file.optLong("size_bytes") / 1024} KB"
            holder.card.setOnClickListener { openFile(file) }
            holder.delete.setOnClickListener {
                if (canEdit) com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ClassMateCourseFilesActivity)
                    .setTitle("Manage file").setItems(arrayOf("Edit file details", "Delete permanently")) { _, choice ->
                        if (choice == 1) deleteFile(file) else editLauncher.launch(Intent(this@ClassMateCourseFilesActivity, ClassMatePublishActivity::class.java)
                            .putExtra("batch_id", batchId).putExtra("upload", true).putExtra("role", intent.getStringExtra("role"))
                            .putExtra("resource", file.toString()))
                    }.show()
                else deleteFile(file)
            }
        }
    }
}

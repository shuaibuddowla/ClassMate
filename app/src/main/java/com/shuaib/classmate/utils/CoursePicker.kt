package com.shuaib.classmate.utils

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.ListenerRegistration
import com.shuaib.classmate.R
import kotlinx.coroutines.launch
import com.shuaib.classmate.databinding.DialogAddCourseBinding
import com.shuaib.classmate.models.Course
import com.shuaib.classmate.repositories.CourseRepository
import com.shuaib.classmate.repositories.ArchiveLibraryRepository

object CoursePicker {
    private const val ADD_NEW = "+ Add new course"

    fun bind(
        context: Context,
        dropdown: AutoCompleteTextView,
        codeField: EditText? = null,
        batchId: String = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() },
        semesterId: String = AppContextManager.getSemesterId(),
        onCourseSelected: (Course) -> Unit = {},
        onCoursesChanged: (List<Course>) -> Unit = {}
    ): ListenerRegistration = CourseRepository.listen(batchId, semesterId, { courses ->
        onCoursesChanged(courses)
        val canAddCourse = AppContextManager.appContextFlow.value.canManageBatch(batchId)
        val options = courses.map { it.name } + if (canAddCourse) listOf(ADD_NEW) else emptyList()
        dropdown.setAdapter(ArrayAdapter(context, R.layout.item_course_dropdown, options))
        dropdown.dropDownVerticalOffset = (6 * context.resources.displayMetrics.density).toInt()
        dropdown.setDropDownBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_course_dropdown_popup))
        dropdown.setOnItemClickListener { _, _, position, _ ->
            if (canAddCourse && position == courses.size) {
                dropdown.setText("", false)
                showAddCourseDialog(context, batchId, semesterId) { }
            } else if (position in courses.indices) {
                codeField?.setText(courses[position].code)
                onCourseSelected(courses[position])
            }
        }
    }, { Toast.makeText(context, "Could not load courses: ${it.message}", Toast.LENGTH_LONG).show() })

    fun showAddCourseDialog(
        context: Context,
        batchId: String,
        semesterId: String,
        existing: Course? = null,
        onSaved: () -> Unit = {}
    ) {
        val binding = DialogAddCourseBinding.inflate(android.view.LayoutInflater.from(context))
        binding.tvCourseScope.text = "${com.shuaib.classmate.models.Batch.formatName(batchId)}  •  ${SemesterManager.formatDisplay(semesterId)}"
        val v2Catalog = ArchiveLibraryRepository.usesSupabaseCatalog
        val categories = if (v2Catalog) listOf("Theory Course", "Lab Course")
        else listOf("Regular Course", "Lab Course", "Syllabus")
        binding.dropdownCourseType.setAdapter(ArrayAdapter(context, R.layout.item_course_dropdown, categories))
        binding.dropdownCourseType.setText(categories.first(), false)
        binding.dropdownCourseType.setDropDownBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_course_dropdown_popup))
        val pickTeacherAction = {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                try {
                    val arr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            org.json.JSONArray(com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi.rpcText("assignable_teachers", org.json.JSONObject().put("target_batch", batchId.takeIf { it.isNotBlank() } ?: org.json.JSONObject.NULL)))
                        } catch (_: Exception) {
                            try {
                                com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi.rows("teacher_directory", "select=id,full_name,email,designation&order=full_name")
                            } catch (_: Exception) {
                                org.json.JSONArray()
                            }
                        }
                    }
                    val teachers = mutableListOf<org.json.JSONObject>()
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { teachers.add(it) }
                    if (teachers.isEmpty()) {
                        Toast.makeText(context, "No teachers found in directory. You can enter a name manually.", Toast.LENGTH_SHORT).show()
                    } else {
                        showTeacherSearchBottomSheet(context, teachers) { chosen ->
                            binding.etCourseTeacher.setText(chosen.optString("full_name").trim())
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not load teachers: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.layoutCourseTeacher.setEndIconOnClickListener { pickTeacherAction() }
        binding.etCourseTeacher.setOnClickListener { pickTeacherAction() }

        if (existing != null) {
            binding.etCourseName.setText(existing.name)
            binding.etCourseCode.setText(existing.code)
            binding.etCourseTeacher.setText(existing.teacherName)
            binding.dropdownCourseType.setText(if (existing.type == "lab") "Lab Course" else categories.first(), false)
            binding.etCourseName.isEnabled = false
            binding.etCourseCode.isEnabled = false
            binding.dropdownCourseType.isEnabled = false
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) "Create a course" else "Set course teacher")
            .setMessage(if (existing == null) "It will be available everywhere this semester." else "This teacher will appear on the timetable for this course.")
            .setView(binding.root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = binding.etCourseName.text.toString().trim()
                if (name.isBlank()) {
                    binding.etCourseName.error = "Course name is required"
                    return@setOnClickListener
                }
                val code = binding.etCourseCode.text.toString().trim()
                if (v2Catalog && code.isBlank()) {
                    binding.etCourseCode.error = "Course code is required for the Supabase catalogue"
                    return@setOnClickListener
                }
                val teacherName = binding.etCourseTeacher.text.toString().trim()
                if (teacherName.isBlank()) {
                    binding.etCourseTeacher.error = "Teacher name is required"
                    return@setOnClickListener
                }
                val type = when (binding.dropdownCourseType.text.toString()) {
                    "Lab Course" -> "lab"
                    "Syllabus" -> "syllabus"
                    else -> "regular"
                }
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                CourseRepository.add(batchId, semesterId, name, code, teacherName, type, {
                    Toast.makeText(context, if (existing == null) "$name added" else "Teacher saved", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onSaved()
                }, { error ->
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    Toast.makeText(context, "Could not add course: ${error.message}", Toast.LENGTH_LONG).show()
                })
            }
        }
        dialog.show()
    }

    private fun showTeacherSearchBottomSheet(
        context: Context,
        teachers: List<org.json.JSONObject>,
        onSelected: (org.json.JSONObject) -> Unit
    ) {
        val dp = { value: Int -> (value * context.resources.displayMetrics.density).toInt() }
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        val sheetView = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(24))
        }

        // Drag handle pill
        val handle = android.view.View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(3).toFloat()
                setColor(android.graphics.Color.parseColor("#CBD5E1"))
            }
        }
        sheetView.addView(handle, android.widget.LinearLayout.LayoutParams(dp(44), dp(5)).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })

        // Title & Subtitle
        val title = android.widget.TextView(context).apply {
            text = "Choose Teacher from Directory"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.cm_text_primary))
        }
        sheetView.addView(title)

        val sub = android.widget.TextView(context).apply {
            text = "Search by teacher name, designation, or email"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.cm_text_secondary))
            setPadding(0, dp(2), 0, dp(12))
        }
        sheetView.addView(sub)

        // Search Input
        val searchInput = com.google.android.material.textfield.TextInputEditText(context).apply {
            hint = "Search faculty name or initial…"
            textSize = 14f
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val searchLayout = com.google.android.material.textfield.TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            val r = dp(12).toFloat()
            setBoxCornerRadii(r, r, r, r)
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT
            startIconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_search_modern)
            addView(searchInput)
        }
        sheetView.addView(searchLayout, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val resultsScroll = android.widget.ScrollView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, dp(340))
        }
        val resultsContainer = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        resultsScroll.addView(resultsContainer)
        sheetView.addView(resultsScroll)

        fun renderList(query: String) {
            resultsContainer.removeAllViews()
            val q = query.trim().lowercase()
            val filtered = if (q.isBlank()) teachers else teachers.filter { t ->
                val n = t.optString("full_name").lowercase()
                val des = t.optString("designation").lowercase()
                val em = t.optString("email").lowercase()
                n.contains(q) || des.contains(q) || em.contains(q)
            }

            if (filtered.isEmpty()) {
                val emptyTv = android.widget.TextView(context).apply {
                    text = "No teachers found matching \"$query\""
                    gravity = android.view.Gravity.CENTER
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.cm_text_muted))
                    setPadding(dp(16), dp(32), dp(16), dp(32))
                }
                resultsContainer.addView(emptyTv)
            } else {
                filtered.forEach { t ->
                    val card = android.widget.LinearLayout(context).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        isClickable = true
                        isFocusable = true
                        val typedValue = android.util.TypedValue()
                        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                        setBackgroundResource(typedValue.resourceId)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        setOnClickListener {
                            bottomSheetDialog.dismiss()
                            onSelected(t)
                        }
                    }
                    val nameTv = android.widget.TextView(context).apply {
                        text = t.optString("full_name").trim()
                        textSize = 15f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(context, R.color.cm_text_primary))
                    }
                    val des = t.optString("designation").trim()
                    val em = t.optString("email").trim()
                    val subtitleText = listOf(des.takeIf { it.isNotBlank() }, em.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" • ")
                    val subTv = android.widget.TextView(context).apply {
                        text = subtitleText.ifBlank { "Faculty Member" }
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(context, R.color.cm_text_secondary))
                        setPadding(0, dp(2), 0, 0)
                    }
                    card.addView(nameTv)
                    card.addView(subTv)
                    resultsContainer.addView(card, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
                }
            }
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { renderList(s?.toString().orEmpty()) }
        })
        renderList("")

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }
}

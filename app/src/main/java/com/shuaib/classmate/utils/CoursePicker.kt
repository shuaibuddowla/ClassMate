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
}

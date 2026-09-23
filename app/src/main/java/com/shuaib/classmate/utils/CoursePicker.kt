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

object CoursePicker {
    private const val ADD_NEW = "+ Add new course"

    fun bind(
        context: Context,
        dropdown: AutoCompleteTextView,
        codeField: EditText? = null,
        batchId: String = AppContextManager.getManagedBatchId().ifBlank { AppContextManager.getBatchId() },
        semesterId: String = AppContextManager.getSemesterId(),
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
                showAddDialog(context, batchId, semesterId)
            } else if (position in courses.indices) {
                codeField?.setText(courses[position].code)
            }
        }
    }, { Toast.makeText(context, "Could not load courses: ${it.message}", Toast.LENGTH_LONG).show() })

    private fun showAddDialog(context: Context, batchId: String, semesterId: String) {
        val binding = DialogAddCourseBinding.inflate(android.view.LayoutInflater.from(context))
        binding.tvCourseScope.text = "${com.shuaib.classmate.models.Batch.formatName(batchId)}  •  ${SemesterManager.formatDisplay(semesterId)}"
        val categories = listOf("Regular Course", "Lab Course", "Syllabus")
        binding.dropdownCourseType.setAdapter(ArrayAdapter(context, R.layout.item_course_dropdown, categories))
        binding.dropdownCourseType.setText(categories.first(), false)
        binding.dropdownCourseType.setDropDownBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_course_dropdown_popup))

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Create a course")
            .setMessage("It will be available everywhere this semester.")
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
                val type = when (binding.dropdownCourseType.text.toString()) {
                    "Lab Course" -> "lab"
                    "Syllabus" -> "syllabus"
                    else -> "regular"
                }
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                CourseRepository.add(batchId, semesterId, name, binding.etCourseCode.text.toString(), type, {
                    Toast.makeText(context, "$name added", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }, { error ->
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    Toast.makeText(context, "Could not add course: ${error.message}", Toast.LENGTH_LONG).show()
                })
            }
        }
        dialog.show()
    }
}

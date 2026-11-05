package com.shuaib.classmate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.ClassMateCourses
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object ClassMateTeachersDialog {

    data class TeacherItem(
        val name: String,
        val email: String,
        val recordId: String,
        val departmentCode: String,
        val designation: String,
        val courses: MutableList<String>,
        val courseOfferingIds: MutableList<String>
    )

    fun show(
        context: Context,
        scope: CoroutineScope,
        batchId: String,
        batchLabel: String,
        profile: JSONObject,
        onTeacherDataChanged: (() -> Unit)? = null
    ) {
        val role = profile.optString("role")
        val isAdmin = role in setOf("admin", "teacher") || profile.optBoolean("is_server_owner")
        val canDeleteTeacher = role == "admin" || profile.optBoolean("is_server_owner")
        val isCr = profile.optBoolean("is_cr")
        val canManage = isAdmin || isCr

        val dialog = BottomSheetDialog(context, R.style.Theme_ClassMate_BottomSheetDialog)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_teachers_directory, null, false)
        dialog.setContentView(view)

        val tvSubtitle = view.findViewById<TextView>(R.id.tvTeachersSubtitle)
        val btnAddTeacher = view.findViewById<MaterialButton>(R.id.btnAddTeacher)
        val btnClose = view.findViewById<ImageView>(R.id.btnCloseTeachers)
        val etSearch = view.findViewById<EditText>(R.id.etSearchTeachers)
        val rv = view.findViewById<RecyclerView>(R.id.rvTeachers)
        val pb = view.findViewById<ProgressBar>(R.id.pbLoadingTeachers)
        val emptyLayout = view.findViewById<LinearLayout>(R.id.layoutEmptyTeachers)
        val tvEmptyHint = view.findViewById<TextView>(R.id.tvEmptyTeachersHint)

        tvSubtitle.text = batchLabel.ifBlank { "Faculty roster & course assignments" }

        // Role-based visibility for Add Teacher button
        btnAddTeacher.visibility = if (canManage) View.VISIBLE else View.GONE
        if (canManage) {
            tvEmptyHint.text = "No teachers configured yet. Tap '＋ Add' above to add faculty members."
        } else {
            tvEmptyHint.text = "No teachers assigned to this batch's courses yet."
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        rv.layoutManager = LinearLayoutManager(context)

        val fullTeachersList = mutableListOf<TeacherItem>()
        val filteredList = mutableListOf<TeacherItem>()

        val adapter = object : RecyclerView.Adapter<TeacherViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TeacherViewHolder {
                val item = LayoutInflater.from(parent.context).inflate(R.layout.item_teacher_card, parent, false)
                return TeacherViewHolder(item)
            }

            override fun onBindViewHolder(holder: TeacherViewHolder, position: Int) {
                val t = filteredList[position]
                holder.tvName.text = t.name
                val deptLabel = when (t.departmentCode.lowercase()) {
                    "cse" -> "CSE"
                    "phy" -> "Physics"
                    "chem" -> "Chemistry"
                    "acct" -> "Accounting"
                    "eco" -> "Economics"
                    "math" -> "Math"
                    else -> t.departmentCode.uppercase().takeIf { it.isNotBlank() }
                }
                holder.tvCourses.text = listOfNotNull(
                    deptLabel,
                    t.designation.takeIf { it.isNotBlank() },
                    t.courses.joinToString(" · ").takeIf { it.isNotBlank() } ?: "No courses linked"
                ).joinToString(" · ")
                holder.tvEmail.text = t.email.ifBlank { "Email not available" }

                // Initials
                val initials = t.name.split(" ")
                    .filter { it.isNotBlank() && !it.contains(".") }
                    .take(2)
                    .map { it.first().uppercaseChar() }
                    .joinToString("")
                holder.tvInitials.text = if (initials.isNotBlank()) initials else "T"

                // Role regulation: Only Admins and CRs can edit
                holder.btnEdit.visibility = if (canManage) View.VISIBLE else View.GONE
                holder.btnEdit.setOnClickListener {
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    showEditTeacherDialog(context, scope, batchId, t, isAdmin) {
                        dialog.dismiss()
                        show(context, scope, batchId, batchLabel, profile, onTeacherDataChanged)
                        onTeacherDataChanged?.invoke()
                    }
                }
                holder.btnDelete.visibility = if (canDeleteTeacher && t.recordId.isNotBlank()) View.VISIBLE else View.GONE
                holder.btnDelete.setOnClickListener {
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    showDeleteTeacherDialog(context, scope, t) {
                        dialog.dismiss()
                        show(context, scope, batchId, batchLabel, profile, onTeacherDataChanged)
                        onTeacherDataChanged?.invoke()
                    }
                }

                // Email actions
                val hasEmail = t.email.isNotBlank()
                holder.btnSendEmail.isEnabled = hasEmail
                holder.btnCopyEmail.isEnabled = hasEmail

                holder.btnSendEmail.setOnClickListener {
                    if (hasEmail) {
                        try {
                            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${t.email}"))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "No email app found: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                holder.btnCopyEmail.setOnClickListener {
                    if (hasEmail) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Teacher Email", t.email))
                        Toast.makeText(context, "Teacher email copied", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun getItemCount(): Int = filteredList.size
        }
        rv.adapter = adapter

        fun applyFilter(q: String) {
            val query = q.trim().lowercase()
            filteredList.clear()
            if (query.isEmpty()) {
                filteredList.addAll(fullTeachersList)
            } else {
                filteredList.addAll(fullTeachersList.filter {
                    it.name.lowercase().contains(query) ||
                    it.email.lowercase().contains(query) ||
                    it.departmentCode.lowercase().contains(query) ||
                    it.designation.lowercase().contains(query) ||
                    it.courses.any { c -> c.lowercase().contains(query) }
                })
            }
            adapter.notifyDataSetChanged()
            emptyLayout.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (filteredList.isEmpty()) View.GONE else View.VISIBLE
        }

        etSearch.doAfterTextChanged {
            applyFilter(it?.toString().orEmpty())
        }

        // Fetch teachers data
        scope.launch {
            try {
                val coursesJson = withContext(Dispatchers.IO) {
                    try { ClassMateCourses.catalog(batchId) } catch (_: Exception) { JSONArray() }
                }

                val directoryRows = withContext(Dispatchers.IO) {
                    try {
                        val arr = JSONArray(ClassMateAuthApi.rpcText("batch_teachers_directory", JSONObject().put("target_batch", batchId)))
                        val list = mutableListOf<JSONObject>()
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
                        list
                    } catch (_: Exception) {
                        emptyList<JSONObject>()
                    }
                }

                val teachersMap = linkedMapOf<String, TeacherItem>()
                for (teacher in directoryRows) {
                    val id = teacher.optString("id")
                    val name = teacher.optString("full_name").trim()
                    if (name.isBlank()) continue
                    val coursesArr = teacher.optJSONArray("courses")
                    val coursesList = mutableListOf<String>()
                    if (coursesArr != null) {
                        for (j in 0 until coursesArr.length()) {
                            val c = coursesArr.optString(j).trim()
                            if (c.isNotBlank()) coursesList.add(c)
                        }
                    }
                    teachersMap[id] = TeacherItem(name, teacher.optString("email"), id,
                        teacher.optString("department_code"), teacher.optString("designation"), coursesList, mutableListOf())
                }

                for (i in 0 until coursesJson.length()) {
                    val c = coursesJson.optJSONObject(i) ?: continue
                    val teacherName = c.optString("teacher_name").trim()
                    if (teacherName.isBlank() || teacherName.equals("null", true)) continue

                    val recordId = c.optString("teacher_record_id").trim()
                    val offeringId = c.optString("offering_id").trim()
                    val courseCode = c.optString("course_code").trim()
                    val courseTitle = c.optString("course_title").trim()
                    val courseLabel = if (courseCode.isNotBlank()) courseCode else courseTitle

                    val email = teachersMap[recordId]?.email.orEmpty()

                    val key = if (recordId.isNotBlank()) recordId else teacherName.lowercase()
                    val existing = teachersMap[key]
                    if (existing != null) {
                        if (courseLabel.isNotBlank() && !existing.courses.contains(courseLabel)) {
                            existing.courses.add(courseLabel)
                        }
                        if (offeringId.isNotBlank() && !existing.courseOfferingIds.contains(offeringId)) {
                            existing.courseOfferingIds.add(offeringId)
                        }
                    } else {
                        teachersMap[key] = TeacherItem(
                            name = teacherName,
                            email = email,
                            recordId = recordId,
                            departmentCode = "cse",
                            designation = "",
                            courses = if (courseLabel.isNotBlank()) mutableListOf(courseLabel) else mutableListOf(),
                            courseOfferingIds = if (offeringId.isNotBlank()) mutableListOf(offeringId) else mutableListOf()
                        )
                    }
                }

                fullTeachersList.clear()
                fullTeachersList.addAll(teachersMap.values)
                fullTeachersList.sortWith(compareBy<TeacherItem>({ if (it.departmentCode.equals("cse", true)) 0 else 1 }, { it.name.lowercase() }))

                pb.visibility = View.GONE
                applyFilter(etSearch.text?.toString().orEmpty())

            } catch (e: Exception) {
                pb.visibility = View.GONE
                Toast.makeText(context, "Could not load teachers: ${e.message}", Toast.LENGTH_SHORT).show()
                applyFilter("")
            }
        }

        // Add Teacher action (Admin & CR only)
        btnAddTeacher.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showAddTeacherDialog(context, scope, batchId, isAdmin) {
                dialog.dismiss()
                show(context, scope, batchId, batchLabel, profile, onTeacherDataChanged)
                onTeacherDataChanged?.invoke()
            }
        }

        dialog.show()
    }

    private fun showAddTeacherDialog(
        context: Context,
        scope: CoroutineScope,
        batchId: String,
        isAdmin: Boolean,
        onSuccess: () -> Unit
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        val etName = EditText(context).apply {
            hint = "Teacher Full Name"
            textSize = 14f
            setSingleLine(true)
        }
        val etEmail = EditText(context).apply {
            hint = "Email (e.g. name@mbstu.ac.bd)"
            textSize = 14f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        layout.addView(etName)
        layout.addView(etEmail)

        MaterialAlertDialogBuilder(context)
            .setTitle("Add Faculty Member")
            .setMessage("Enter the teacher's name and university email.")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { d, _ ->
                val name = etName.text.toString().trim()
                val email = etEmail.text.toString().trim()
                if (name.length < 2) {
                    Toast.makeText(context, "Please enter a valid teacher name.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                scope.launch {
                    try {
                        val dept = withContext(Dispatchers.IO) {
                            try {
                                ClassMateAuthApi.rows("batches", "select=department_id&id=eq.$batchId")
                                    .optJSONObject(0)?.optString("department_id").orEmpty()
                            } catch (_: Exception) { "" }
                        }

                        if (isAdmin && dept.isNotBlank()) {
                            withContext(Dispatchers.IO) {
                                ClassMateAuthApi.rpc(
                                    "owner_create_teacher",
                                    JSONObject()
                                        .put("target_department", dept)
                                        .put("target_name", name)
                                        .put("target_email", email)
                                )
                            }
                        }
                        Toast.makeText(context, "Teacher added successfully.", Toast.LENGTH_SHORT).show()
                        onSuccess()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to add teacher: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun showEditTeacherDialog(
        context: Context,
        scope: CoroutineScope,
        batchId: String,
        teacher: TeacherItem,
        isAdmin: Boolean,
        onSuccess: () -> Unit
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        val etName = EditText(context).apply {
            hint = "Teacher Full Name"
            setText(teacher.name)
            textSize = 14f
            setSingleLine(true)
        }
        val etEmail = EditText(context).apply {
            hint = "Email (e.g. name@mbstu.ac.bd)"
            setText(teacher.email)
            textSize = 14f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        layout.addView(etName)
        layout.addView(etEmail)

        MaterialAlertDialogBuilder(context)
            .setTitle("Edit Teacher Information")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save Changes") { _, _ ->
                val newName = etName.text.toString().trim()
                val newEmail = etEmail.text.toString().trim()
                if (newName.length < 2) {
                    Toast.makeText(context, "Teacher name is required.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                scope.launch {
                    try {
                        if (teacher.recordId.isNotBlank() && isAdmin) {
                            withContext(Dispatchers.IO) {
                                ClassMateAuthApi.rpc(
                                    "owner_save_teacher",
                                    JSONObject()
                                        .put("target_record", teacher.recordId)
                                        .put("target_name", newName)
                                        .put("target_email", newEmail)
                                )
                            }
                        }
                        Toast.makeText(context, "Teacher updated successfully.", Toast.LENGTH_SHORT).show()
                        onSuccess()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun showDeleteTeacherDialog(
        context: Context,
        scope: CoroutineScope,
        teacher: TeacherItem,
        onSuccess: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Delete ${teacher.name}?")
            .setMessage("This removes the teacher from the directory and clears their course assignments. Students will see those courses as unassigned.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            ClassMateAuthApi.rpc("owner_delete_teacher", JSONObject().put("target_record", teacher.recordId))
                        }
                        if (!result.optBoolean("deleted")) throw IllegalStateException("The server did not confirm deletion")
                        Toast.makeText(context, "${teacher.name} deleted", Toast.LENGTH_SHORT).show()
                        onSuccess()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not delete teacher: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private class TeacherViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvTeacherName)
        val tvCourses: TextView = v.findViewById(R.id.tvTeacherCourses)
        val tvEmail: TextView = v.findViewById(R.id.tvTeacherEmail)
        val tvInitials: TextView = v.findViewById(R.id.tvTeacherInitials)
        val btnEdit: ImageView = v.findViewById(R.id.btnTeacherEdit)
        val btnDelete: ImageView = v.findViewById(R.id.btnTeacherDelete)
        val btnCopyEmail: MaterialButton = v.findViewById(R.id.btnTeacherCopyEmail)
        val btnSendEmail: MaterialButton = v.findViewById(R.id.btnTeacherSendEmail)
    }
}

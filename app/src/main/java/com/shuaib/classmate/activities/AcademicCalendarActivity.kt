package com.shuaib.classmate.activities

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.shuaib.classmate.R
import com.shuaib.classmate.adapters.AcademicCalendarExceptionAdapter
import com.shuaib.classmate.databinding.ActivityAcademicCalendarBinding
import com.shuaib.classmate.databinding.DialogAcademicCalendarExceptionBinding
import com.shuaib.classmate.models.AcademicCalendarException
import com.shuaib.classmate.repositories.AcademicCalendarRepository
import com.shuaib.classmate.utils.ClassReminderWorkCoordinator
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Calendar

class AcademicCalendarActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAcademicCalendarBinding
    private lateinit var adapter: AcademicCalendarExceptionAdapter
    private val repository = AcademicCalendarRepository()
    private var registration: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAcademicCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        adapter = AcademicCalendarExceptionAdapter(
            onClick = { showExceptionDialog(it) },
            onLongClick = { showActionsDialog(it) }
        )
        binding.rvExceptions.layoutManager = LinearLayoutManager(this)
        binding.rvExceptions.adapter = adapter
        binding.fabAddException.setOnClickListener { showExceptionDialog(null) }
        observeExceptions()
    }

    private fun observeExceptions() {
        registration?.remove()
        registration = repository.observeAllExceptions(
            onResult = { exceptions ->
                val sorted = exceptions.sortedWith(
                    compareByDescending<AcademicCalendarException> { it.isActive }
                        .thenBy { it.startDate }
                )
                adapter.submitList(sorted)
                binding.tvEmptyState.isVisible = sorted.isEmpty()
                binding.rvExceptions.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
            },
            onError = {
                Toast.makeText(this, "Failed to load calendar records", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showExceptionDialog(existing: AcademicCalendarException?) {
        val dialogBinding = DialogAcademicCalendarExceptionBinding.inflate(LayoutInflater.from(this))
        val typeLabels = listOf("Vacation", "Holiday", "Classes Suspended")
        dialogBinding.dropdownType.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, typeLabels)
        )

        val typeLabel = when (existing?.type) {
            AcademicCalendarException.TYPE_HOLIDAY -> "Holiday"
            AcademicCalendarException.TYPE_CLASS_SUSPENDED -> "Classes Suspended"
            else -> "Vacation"
        }
        dialogBinding.dropdownType.setText(typeLabel, false)
        dialogBinding.etTitle.setText(existing?.title ?: "")
        dialogBinding.etStartDate.setText(existing?.startDate ?: LocalDate.now().toString())
        dialogBinding.etEndDate.setText(existing?.endDate ?: LocalDate.now().toString())
        dialogBinding.etReason.setText(existing?.reason ?: "")
        dialogBinding.switchHolidayBriefing.isChecked = existing?.showHolidayBriefing ?: false
        dialogBinding.switchActive.isChecked = existing?.isActive ?: true

        dialogBinding.etStartDate.setOnClickListener {
            showDatePicker(dialogBinding.etStartDate.text?.toString().orEmpty()) { date ->
                dialogBinding.etStartDate.setText(date)
            }
        }
        dialogBinding.etEndDate.setOnClickListener {
            showDatePicker(dialogBinding.etEndDate.text?.toString().orEmpty()) { date ->
                dialogBinding.etEndDate.setText(date)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add Class Off Day" else "Edit Class Off Day")
            .setView(dialogBinding.root)
            .setPositiveButton(if (existing == null) "Save" else "Update", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        saveFromDialog(existing, dialogBinding, this)
                    }
                }
                show()
            }
    }

    private fun saveFromDialog(
        existing: AcademicCalendarException?,
        dialogBinding: DialogAcademicCalendarExceptionBinding,
        dialog: AlertDialog
    ) {
        val type = when (dialogBinding.dropdownType.text.toString()) {
            "Holiday" -> AcademicCalendarException.TYPE_HOLIDAY
            "Classes Suspended" -> AcademicCalendarException.TYPE_CLASS_SUSPENDED
            else -> AcademicCalendarException.TYPE_VACATION
        }
        val exception = AcademicCalendarException(
            id = existing?.id.orEmpty(),
            title = dialogBinding.etTitle.text?.toString()?.trim().orEmpty(),
            type = type,
            startDate = dialogBinding.etStartDate.text?.toString()?.trim().orEmpty(),
            endDate = dialogBinding.etEndDate.text?.toString()?.trim().orEmpty(),
            scope = AcademicCalendarException.SCOPE_ALL_CLASSES,
            reason = dialogBinding.etReason.text?.toString()?.trim().orEmpty(),
            isActive = dialogBinding.switchActive.isChecked,
            showHolidayBriefing = dialogBinding.switchHolidayBriefing.isChecked,
            createdBy = existing?.createdBy?.ifBlank { FirebaseAuth.getInstance().currentUser?.uid.orEmpty() }
                ?: FirebaseAuth.getInstance().currentUser?.uid.orEmpty(),
            createdAt = existing?.createdAt
        )

        lifecycleScope.launch {
            val result = repository.saveException(exception)
            if (result.isSuccess) {
                if (exception.isActive && coversToday(exception)) {
                    ClassReminderWorkCoordinator.cancelTodayClassReminders(this@AcademicCalendarActivity)
                } else {
                    ClassReminderWorkCoordinator.refreshTodayClassReminders(this@AcademicCalendarActivity)
                }
                Toast.makeText(this@AcademicCalendarActivity, "Calendar exception saved", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(
                    this@AcademicCalendarActivity,
                    result.exceptionOrNull()?.message ?: "Save failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showActionsDialog(exception: AcademicCalendarException) {
        val activeLabel = if (exception.isActive) "Deactivate" else "Reactivate"
        MaterialAlertDialogBuilder(this)
            .setTitle(exception.title)
            .setItems(arrayOf("Edit", activeLabel, "Delete")) { _, which ->
                when (which) {
                    0 -> showExceptionDialog(exception)
                    1 -> setActive(exception, !exception.isActive)
                    2 -> deleteException(exception)
                }
            }
            .show()
    }

    private fun setActive(exception: AcademicCalendarException, active: Boolean) {
        lifecycleScope.launch {
            val result = repository.setActive(exception.id, active)
            if (result.isSuccess) {
                if (active && coversToday(exception.copy(isActive = true))) {
                    ClassReminderWorkCoordinator.cancelTodayClassReminders(this@AcademicCalendarActivity)
                } else {
                    ClassReminderWorkCoordinator.refreshTodayClassReminders(this@AcademicCalendarActivity)
                }
            } else {
                Toast.makeText(this@AcademicCalendarActivity, "Update failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteException(exception: AcademicCalendarException) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete record?")
            .setMessage("This removes ${exception.title} from the academic calendar.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val result = repository.deleteException(exception.id)
                    if (result.isSuccess) {
                        ClassReminderWorkCoordinator.refreshTodayClassReminders(this@AcademicCalendarActivity)
                    } else {
                        Toast.makeText(this@AcademicCalendarActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDatePicker(initial: String, onDate: (String) -> Unit) {
        val date = try {
            LocalDate.parse(initial)
        } catch (_: Exception) {
            LocalDate.now()
        }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                onDate(LocalDate.of(year, month + 1, dayOfMonth).toString())
            },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth
        ).show()
    }

    private fun coversToday(exception: AcademicCalendarException): Boolean {
        val today = LocalDate.now()
        val start = try { LocalDate.parse(exception.startDate) } catch (_: Exception) { return false }
        val end = try { LocalDate.parse(exception.endDate) } catch (_: Exception) { return false }
        return !today.isBefore(start) && !today.isAfter(end)
    }

    override fun onDestroy() {
        registration?.remove()
        super.onDestroy()
    }
}

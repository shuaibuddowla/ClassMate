package com.shuaib.classmate.notices

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.BottomSheetNoticeReminderBinding
import com.shuaib.classmate.models.Notice
import com.shuaib.classmate.utils.ThemeColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class NoticeReminderBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetNoticeReminderBinding? = null
    private val binding get() = _binding!!

    private val noticeId: String
        get() = requireArguments().getString(ARG_NOTICE_ID).orEmpty()
    private val noticeTitle: String
        get() = requireArguments().getString(ARG_NOTICE_TITLE).orEmpty()
    private val noticePreview: String
        get() = requireArguments().getString(ARG_NOTICE_PREVIEW).orEmpty()
    private val deadlineMillis: Long?
        get() = requireArguments().getLong(ARG_DEADLINE_MILLIS).takeIf { it > 0L }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetNoticeReminderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnClose.setOnClickListener { dismiss() }
        binding.tvReminderSubtitle.text = noticeTitle.ifBlank { "Choose when ClassMate should alert you" }
        NoticeReminderManager.getCurrentReminder(noticeId) { reminderAt ->
            if (_binding == null) return@getCurrentReminder
            if (reminderAt == null) renderReminderOptions(null) else renderExistingReminder(reminderAt)
        }
    }

    private fun renderExistingReminder(reminderAt: Long) {
        binding.tvCurrentReminder.visibility = View.VISIBLE
        binding.tvCurrentReminder.text = "Current reminder: ${formatReminderTime(reminderAt)}"
        binding.optionsContainer.removeAllViews()
        addOption(
            title = "Change reminder",
            subtitle = "Pick a different alert time",
            iconRes = R.drawable.ic_bell_outline,
            selected = false
        ) {
            renderReminderOptions(reminderAt)
        }
        addOption(
            title = "Remove reminder",
            subtitle = "Cancel this notice alert",
            iconRes = R.drawable.ic_close,
            selected = false,
            accentColor = "#FF4D6D"
        ) {
            NoticeReminderManager.remove(requireContext(), noticeId) { success, _ ->
                if (success) dismiss()
            }
        }
    }

    private fun renderReminderOptions(currentReminderAt: Long?) {
        binding.tvCurrentReminder.visibility = if (currentReminderAt != null) View.VISIBLE else View.GONE
        currentReminderAt?.let {
            binding.tvCurrentReminder.text = "Current reminder: ${formatReminderTime(it)}"
        }
        binding.optionsContainer.removeAllViews()
        val options = if (deadlineMillis != null) {
            deadlineOptions(deadlineMillis!!)
        } else {
            noDeadlineOptions()
        }
        options.forEach { option ->
            addOption(
                title = option.title,
                subtitle = option.subtitle,
                iconRes = R.drawable.ic_bell_outline,
                selected = currentReminderAt?.let { abs(it - option.timeMillis) < TimeUnit.MINUTES.toMillis(1) } == true
            ) {
                schedule(option.timeMillis, option.title)
            }
        }
        addOption(
            title = "Custom time",
            subtitle = "Choose a date and time",
            iconRes = R.drawable.ic_bell_outline,
            selected = false
        ) {
            showCustomPicker()
        }
    }

    private fun deadlineOptions(deadline: Long): List<ReminderOption> {
        return listOf(
            ReminderOption("30 minutes before", formatReminderTime(deadline - TimeUnit.MINUTES.toMillis(30)), deadline - TimeUnit.MINUTES.toMillis(30)),
            ReminderOption("1 hour before", formatReminderTime(deadline - TimeUnit.HOURS.toMillis(1)), deadline - TimeUnit.HOURS.toMillis(1)),
            ReminderOption("3 hours before", formatReminderTime(deadline - TimeUnit.HOURS.toMillis(3)), deadline - TimeUnit.HOURS.toMillis(3)),
            ReminderOption("1 day before", formatReminderTime(deadline - TimeUnit.DAYS.toMillis(1)), deadline - TimeUnit.DAYS.toMillis(1)),
            ReminderOption("2 days before", formatReminderTime(deadline - TimeUnit.DAYS.toMillis(2)), deadline - TimeUnit.DAYS.toMillis(2)),
            ReminderOption("Morning of due date, 8:00 AM", formatReminderTime(morningOf(deadline)), morningOf(deadline))
        )
    }

    private fun noDeadlineOptions(): List<ReminderOption> {
        return listOf(
            ReminderOption("Later today", formatReminderTime(laterToday()), laterToday()),
            ReminderOption("Tomorrow morning", formatReminderTime(tomorrowMorning()), tomorrowMorning())
        )
    }

    private fun addOption(
        title: String,
        subtitle: String,
        iconRes: Int,
        selected: Boolean,
        accentColor: String = "#60A5FA",
        onClick: () -> Unit
    ) {
        val color = if (accentColor == "#60A5FA") ThemeColors.primaryLight(requireContext()) else Color.parseColor(accentColor)
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = requireContext().getDrawable(
                if (selected) R.drawable.bg_notice_reminder_option_selected else R.drawable.bg_notice_reminder_option
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { onClick() }
        }
        row.addView(ImageView(requireContext()).apply {
            setImageResource(iconRes)
            setColorFilter(color)
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        row.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                text = title
                setTextColor(ThemeColors.textPrimary(requireContext()))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
            })
            addView(TextView(requireContext()).apply {
                text = subtitle
                setTextColor(ThemeColors.textMuted(requireContext()))
                textSize = 12f
                maxLines = 1
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })
        if (selected) {
            row.addView(ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(ThemeColors.primaryLight(requireContext()))
                rotation = 90f
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        binding.optionsContainer.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        })
    }

    private fun schedule(reminderAt: Long, label: String) {
        NoticeReminderManager.schedule(
            context = requireContext(),
            noticeId = noticeId,
            title = noticeTitle.ifBlank { "Notice reminder" },
            body = noticePreview,
            reminderAt = reminderAt,
            label = label
        ) { success, _ ->
            if (success) dismiss()
        }
    }

    private fun showCustomPicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                TimePickerDialog(
                    requireContext(),
                    { _, hour, minute ->
                        cal.set(year, month, day, hour, minute, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        schedule(cal.timeInMillis, "Custom time")
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    false
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun morningOf(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun laterToday(): Long {
        val now = System.currentTimeMillis()
        val evening = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 18)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return if (evening > now + TimeUnit.MINUTES.toMillis(10)) evening else now + TimeUnit.HOURS.toMillis(2)
    }

    private fun tomorrowMorning(): Long {
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun formatReminderTime(millis: Long): String {
        return SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault()).format(millis)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private data class ReminderOption(
        val title: String,
        val subtitle: String,
        val timeMillis: Long
    )

    companion object {
        const val TAG = "NoticeReminderBottomSheet"
        private const val ARG_NOTICE_ID = "noticeId"
        private const val ARG_NOTICE_TITLE = "noticeTitle"
        private const val ARG_NOTICE_PREVIEW = "noticePreview"
        private const val ARG_DEADLINE_MILLIS = "deadlineMillis"

        fun newInstance(notice: Notice): NoticeReminderBottomSheet {
            return NoticeReminderBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_NOTICE_ID, notice.id)
                    putString(ARG_NOTICE_TITLE, notice.title)
                    putString(ARG_NOTICE_PREVIEW, NoticeUi.preview(notice, 120))
                    putLong(ARG_DEADLINE_MILLIS, notice.deadlineAt?.toDate()?.time ?: 0L)
                }
            }
        }
    }
}

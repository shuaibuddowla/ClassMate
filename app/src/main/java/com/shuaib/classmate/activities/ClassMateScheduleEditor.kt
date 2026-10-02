package com.shuaib.classmate.activities

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.*
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.shuaib.classmate.R
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Shared form presentation; every write remains authorized by Supabase. */
abstract class ClassMateScheduleEditor : AppCompatActivity() {
    internal lateinit var form: ClassMateFormUi
    protected lateinit var save: MaterialButton
    protected lateinit var status: TextView
    protected var busy = false
    protected fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    protected fun page(title: String) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.cm_background)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        root.addView(MaterialToolbar(this).apply {
            this.title = title; setTitleTextColor(getColor(R.color.cm_text_primary)); setNavigationIcon(R.drawable.ic_chevron_left)
            navigationIcon?.setTint(getColor(R.color.cm_text_primary)); setNavigationOnClickListener { if (!busy) finish() }
        })
        form = ClassMateFormUi(this); root.addView(form.scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        save = MaterialButton(this).apply { text = "Save"; isAllCaps = false; cornerRadius = dp(16) }
        root.addView(save, LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(24), dp(8), dp(24), dp(12)) })
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!busy) finish() }
        })
    }
    protected fun timeButton(label: String, time: () -> LocalTime, chosen: (LocalTime) -> Unit): MaterialButton {
        val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false; text = "$label · ${time().format(DateTimeFormatter.ofPattern("hh:mm a"))}"
        }
        button.setOnClickListener {
            if (busy) return@setOnClickListener
            val value = time()
            MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_12H).setHour(value.hour).setMinute(value.minute).setTitleText(label).build().also { picker ->
                picker.addOnPositiveButtonClickListener { chosen(LocalTime.of(picker.hour, picker.minute)); button.text = "$label · ${time().format(DateTimeFormatter.ofPattern("hh:mm a"))}" }
                picker.show(supportFragmentManager, "time")
            }
        }
        return button
    }
    private fun enableFields(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) enableFields(view.getChildAt(i), enabled)
    }
    protected fun perform(action: suspend () -> Unit, success: () -> Unit) {
        if (busy) return
        if (!ClassMateAcademicCache.online(this)) { status.visibility = View.VISIBLE; status.text = "Connect to the internet to save changes."; return }
        busy = true; enableFields(form.panel, false); save.isEnabled = false; status.visibility = View.VISIBLE; status.text = "Saving…"
        lifecycleScope.launch {
            try { action(); setResult(RESULT_OK); success() }
            catch (e: Exception) { status.text = e.message ?: "Could not save. Your changes are still here." }
            finally { busy = false; enableFields(form.panel, true); save.isEnabled = true }
        }
    }
}

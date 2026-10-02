package com.shuaib.classmate.activities

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.shuaib.classmate.R
import org.json.JSONObject

internal object ClassMateWelcomeDialogs {
    fun show(activity: AppCompatActivity, step: Int, profile: JSONObject, batch: JSONObject?, department: JSONObject?, notificationsAllowed: Boolean,
             continueFlow: () -> Unit, enable: () -> Unit, skip: () -> Unit, onProfileUpdated: (JSONObject)->Unit): AlertDialog {
        if(step==2) return ClassMateProfileDetailsDialog.show(activity,profile,true) { updated -> onProfileUpdated(updated); continueFlow() }
        val form = ClassMateFormUi(activity)
        val density = activity.resources.displayMetrics.density
        val surface = MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(24*density).build()).apply {
            fillColor = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.cm_surface))
        }
        form.label("$step of 3").apply { setTextColor(activity.getColor(R.color.cm_primary)); textSize=12f }
        fun field(label: String, value: String?) {
            if (value.isNullOrBlank() || value == "null") return
            val row = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(0,(5*density).toInt(),0,(5*density).toInt()) }
            row.addView(TextView(activity).apply { text=label; textSize=11f; setTextColor(activity.getColor(R.color.cm_text_secondary)) })
            row.addView(TextView(activity).apply { text=value; textSize=16f; setTypeface(null,1); setTextColor(activity.getColor(R.color.cm_text_primary)) })
            form.panel.addView(row)
        }
        val builder = MaterialAlertDialogBuilder(activity).setBackground(surface).setCancelable(false).setView(form.scroll)
        when (step) {
            1 -> {
                builder.setTitle("We found your account")
                field("Name", profile.optString("full_name")); field("University email", profile.optString("email"))
                field("Student ID", profile.optString("student_id"))
                field("Department", department?.optString("name"))
                field("Batch", batch?.let { "Batch ${it.optInt("batch_number")}" })
                field("Session", (if (profile.optString("role") == "student") profile else batch)?.optString("academic_session"))
                field("Role", if(profile.optBoolean("is_cr")) "Class representative" else profile.optString("role").replaceFirstChar { it.uppercase() })
                builder.setPositiveButton("Continue") { _,_ -> continueFlow() }
            }
            else -> {
                builder.setTitle(if(notificationsAllowed) "Notifications are enabled" else "Stay in the loop")
                form.label(if(notificationsAllowed) "You’re ready for your batch’s alerts." else "Allow alerts for new notices and class updates in your batch.").apply { textSize=15f }
                builder.setPositiveButton(if(notificationsAllowed) "Continue" else "Enable notifications") { _,_ -> enable() }
                if(!notificationsAllowed) builder.setNegativeButton("Not now") { _,_ -> skip() }
            }
        }
        return builder.create().also { it.show(); it.window?.decorView?.apply {
            if (android.animation.ValueAnimator.areAnimatorsEnabled() && !(activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled) {
                alpha=0f; translationY=16*density; animate().alpha(1f).translationY(0f).setDuration(200).start()
            }
        } }
    }
}

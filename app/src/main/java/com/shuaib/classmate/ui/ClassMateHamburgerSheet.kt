package com.shuaib.classmate.ui

import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.*
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

internal object ClassMateHamburgerSheet {

    fun show(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        batchId: () -> String,
        batchLabel: () -> String,
        profile: () -> JSONObject
    ) {
        val dialog = BottomSheetDialog(activity, R.style.Theme_ClassMate_BottomSheetDialog)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_classmate_hamburger_drawer, null, false)
        dialog.setContentView(view)

        // Make window edge-to-edge
        dialog.window?.let { window ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            window.navigationBarColor = Color.TRANSPARENT
            window.statusBarColor = Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }

        // Full screen expansion
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<android.widget.FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.isFitToContents = false
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = true
                bottomSheet.layoutParams.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                bottomSheet.setBackgroundResource(R.drawable.bg_bottom_sheet)
                bottomSheet.setPadding(0, 0, 0, 0)
                bottomSheet.fitsSystemWindows = false
                bottomSheet.requestLayout()
            }
        }

        val content = view.findViewById<android.widget.LinearLayout>(R.id.layoutDrawerContent)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val navBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            val statusBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            view.setPadding(0, statusBars.top, 0, 0)
            val d = activity.resources.displayMetrics.density
            content?.setPadding(
                (20 * d).toInt(),
                (12 * d).toInt(),
                (20 * d).toInt(),
                navBars.bottom + (32 * d).toInt()
            )
            insets
        }

        val p = profile()
        val fullName = p.optString("full_name").trim().ifBlank { "ClassMate User" }
        val studentId = p.optString("student_id").trim()
        val role = p.optString("role")
        val isServerOwner = p.optBoolean("is_server_owner")
        val isCr = p.optBoolean("is_cr")
        val isTeacher = role == "teacher"
        val currentBatch = batchLabel().ifBlank { "MBSTU" }
        val avatarUrl = p.optString("avatar_url").takeUnless { it.isBlank() || it.equals("null", true) }

        // Close button
        view.findViewById<ImageView>(R.id.btnDrawerClose)?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }

        // Bind profile header
        val tvName = view.findViewById<TextView>(R.id.tvDrawerUserName)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvDrawerUserSubtitle)
        val tvBadge = view.findViewById<TextView>(R.id.tvDrawerRoleBadge)
        val ivAvatar = view.findViewById<ImageView>(R.id.ivDrawerAvatar)

        tvName?.text = fullName
        tvSubtitle?.text = if (studentId.isNotBlank()) "$studentId · $currentBatch" else currentBatch

        when {
            isServerOwner -> {
                tvBadge?.text = "Admin"
                tvBadge?.setTextColor(Color.parseColor("#EA580C"))
            }
            isTeacher -> {
                tvBadge?.text = "Faculty"
                tvBadge?.setTextColor(Color.parseColor("#2563EB"))
            }
            isCr -> {
                tvBadge?.text = "CR"
                tvBadge?.setTextColor(Color.parseColor("#7C3AED"))
            }
            else -> {
                tvBadge?.text = "Student"
                tvBadge?.setTextColor(Color.parseColor("#16A34A"))
            }
        }

        if (ivAvatar != null) {
            if (!avatarUrl.isNullOrBlank()) {
                Glide.with(activity)
                    .load(avatarUrl)
                    .circleCrop()
                    .placeholder(R.drawable.bg_circle_avatar_placeholder)
                    .into(ivAvatar)
            } else {
                ivAvatar.setImageResource(R.drawable.bg_circle_avatar_placeholder)
            }
        }

        // BIND ACTIONS WITH HAPTIC FEEDBACK

        // 1. MBSTU Bus Transit
        view.findViewById<View>(R.id.menuBusTransit)?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            val intent = Intent(activity, ClassMateTransitActivity::class.java).apply {
                putExtra("EXTRA_BATCH_ID", batchId())
                putExtra("EXTRA_ROLE", role)
            }
            activity.startActivity(intent)
        }

        // 2. Teachers Directory
        view.findViewById<View>(R.id.menuTeachers)?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            ClassMateTeachersDialog.show(
                context = activity,
                scope = scope,
                batchId = batchId(),
                batchLabel = batchLabel(),
                profile = profile()
            )
        }

        // 3. Batchmates
        view.findViewById<View>(R.id.menuBatchmates)?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            val intent = Intent(activity, ClassMateBatchActivity::class.java).apply {
                putExtra("EXTRA_BATCH_ID", batchId())
                putExtra("EXTRA_BATCH_LABEL", batchLabel())
                putExtra("EXTRA_ROLE", role)
            }
            activity.startActivity(intent)
        }

        // 4. Batch Fund
        view.findViewById<View>(R.id.menuBatchFund)?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            val intent = Intent(activity, ClassMateBatchFundActivity::class.java).apply {
                putExtra("batch_id", batchId())
                putExtra("batch_label", batchLabel())
            }
            activity.startActivity(intent)
        }

        // 5. Class Routine Export
        view.findViewById<View>(R.id.menuExportRoutine)?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            ClassMateRoutineExporter.exportRoutine(
                context = activity,
                accountId = p.optString("id"),
                batchId = batchId(),
                batchLabel = batchLabel()
            )
        }

        // 6. Blood Donor Directory
        view.findViewById<View>(R.id.menuBloodDirectory)?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            val intent = Intent(activity, ClassMateBloodActivity::class.java).apply {
                putExtra("batch_id", batchId())
            }
            activity.startActivity(intent)
        }

        // 7. Campus Emergency Contacts
        view.findViewById<View>(R.id.menuEmergencyContacts)?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            ClassMateEmergencyDialog.show(activity)
        }

        // Footer version
        view.findViewById<TextView>(R.id.tvDrawerFooterVersion)?.text =
            "ClassMate v${BuildConfig.VERSION_NAME} · MBSTU Student Companion"

        dialog.show()
    }
}

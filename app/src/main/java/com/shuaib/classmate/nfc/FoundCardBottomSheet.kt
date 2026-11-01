package com.shuaib.classmate.nfc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.ClassMateAdminAttendanceActivity
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.ui.ClassMateHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Native bottom sheet for instant student identity presentation on NFC card tap.
 * Zero-browser, 100% offline-ready, with online hydration for profile picture & live attendance.
 * Handles missing contact details, unassigned cards, and fallback states gracefully.
 */
class FoundCardBottomSheet(
    private val activity: AppCompatActivity,
    private val initialPayload: NfcCardManager.StudentCardPayload,
    private val onDismiss: (() -> Unit)? = null
) {
    private val dialog = BottomSheetDialog(activity, R.style.Theme_ClassMate_BottomSheetDialog)
    private val view: View = LayoutInflater.from(activity).inflate(R.layout.dialog_found_card_sheet, null, false)

    private val ivStudentAvatar: ShapeableImageView = view.findViewById(R.id.ivStudentAvatar)
    private val tvStudentInitials: TextView = view.findViewById(R.id.tvStudentInitials)
    private val tvStudentName: TextView = view.findViewById(R.id.tvStudentName)
    private val tvStudentRoll: TextView = view.findViewById(R.id.tvStudentRoll)
    private val tvStudentDeptBatch: TextView = view.findViewById(R.id.tvStudentDeptBatch)
    private val tvSheetHeaderSubtitle: TextView = view.findViewById(R.id.tvSheetHeaderSubtitle)

    private val tvStatusBadge: TextView = view.findViewById(R.id.tvStatusBadge)
    private val tvBloodBadge: TextView = view.findViewById(R.id.tvBloodBadge)
    private val tvAttendanceBadge: TextView = view.findViewById(R.id.tvAttendanceBadge)
    private val tvCardUidBadge: TextView = view.findViewById(R.id.tvCardUidBadge)

    private val layoutRegisteredStudent: LinearLayout = view.findViewById(R.id.layoutRegisteredStudent)
    private val layoutContactActions: LinearLayout = view.findViewById(R.id.layoutContactActions)
    private val layoutMissingContact: LinearLayout = view.findViewById(R.id.layoutMissingContact)
    private val layoutUnregisteredCard: LinearLayout = view.findViewById(R.id.layoutUnregisteredCard)

    private val btnCallOwner: MaterialButton = view.findViewById(R.id.btnCallOwner)
    private val btnWhatsappOwner: MaterialButton = view.findViewById(R.id.btnWhatsappOwner)
    private val btnCopyRoll: MaterialButton = view.findViewById(R.id.btnCopyRoll)
    private val btnShareCard: MaterialButton = view.findViewById(R.id.btnShareCard)
    private val btnCloseFoundCard: ImageButton = view.findViewById(R.id.btnCloseFoundCard)

    private val tvUnregisteredCardUid: TextView = view.findViewById(R.id.tvUnregisteredCardUid)
    private val tvUnregisteredHint: TextView = view.findViewById(R.id.tvUnregisteredHint)
    private val btnAssignUnregisteredCard: MaterialButton = view.findViewById(R.id.btnAssignUnregisteredCard)
    private val progressLoadingStudent: ProgressBar = view.findViewById(R.id.progressLoadingStudent)

    private var activePayload: NfcCardManager.StudentCardPayload = initialPayload
    private var activePhoneNumber: String = ""
    private var activeRoll: String = ""
    private var activeName: String = ""

    fun show() {
        dialog.setContentView(view)

        // Make background transparent for rounded bottom sheet shape
        (view.parent as? View)?.setBackgroundResource(android.R.color.transparent)

        dialog.behavior.apply {
            isFitToContents = true
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }

        dialog.setOnDismissListener { onDismiss?.invoke() }
        btnCloseFoundCard.setOnClickListener {
            ClassMateHaptics.selection(it)
            dialog.dismiss()
        }

        // 1. Resolve offline payload (from NDEF or local cache)
        resolveOfflineData()

        // 2. Render resolved offline state
        renderOfflineState()

        // 3. Online Hydration (Supabase)
        hydrateOnlineData()

        dialog.show()
    }

    private fun resolveOfflineData() {
        if (!activePayload.hasStudentInfo && activePayload.uid.isNotBlank()) {
            val cached = NfcCardLocalCache.getCard(activity, activePayload.uid)
            if (cached != null && cached.hasStudentInfo) {
                activePayload = cached
            }
        }
    }

    private fun renderOfflineState() {
        val uid = activePayload.uid.uppercase(Locale.US)
        tvCardUidBadge.text = if (uid.isNotBlank()) "UID: ••••${uid.takeLast(4)}" else "NFC Tag"

        if (activePayload.hasStudentInfo) {
            layoutRegisteredStudent.visibility = View.VISIBLE
            layoutUnregisteredCard.visibility = View.GONE
            progressLoadingStudent.visibility = View.GONE

            activeName = activePayload.name.ifBlank { "MBSTU Student" }
            activeRoll = activePayload.roll.ifBlank { activePayload.studentId }
            tvStudentName.text = activeName
            tvStudentRoll.text = activeRoll.ifBlank { "Student Roll Not Set" }

            val deptBatchText = buildString {
                if (activePayload.batch.isNotBlank()) append(activePayload.batch)
                if (activePayload.batch.isNotBlank() && activePayload.dept.isNotBlank()) append(" · ")
                if (activePayload.dept.isNotBlank()) append(activePayload.dept)
            }.ifBlank { "MBSTU Campus Student" }
            tvStudentDeptBatch.text = deptBatchText

            // Initials fallback or cached avatar
            val cachedAvatar = NfcCardLocalCache.getCachedAvatarUrl(activity, uid)
            if (cachedAvatar.isNotBlank() && cachedAvatar.startsWith("http")) {
                Glide.with(activity).load(cachedAvatar).circleCrop().into(ivStudentAvatar)
                ivStudentAvatar.visibility = View.VISIBLE
                tvStudentInitials.visibility = View.GONE
            } else {
                tvStudentInitials.text = getInitials(activeName, activeRoll)
                tvStudentInitials.visibility = View.VISIBLE
                ivStudentAvatar.visibility = View.GONE
            }

            // Blood group handling
            val cleanBlood = activePayload.blood.trim()
            if (cleanBlood.isNotEmpty() && cleanBlood != "null") {
                tvBloodBadge.text = "🩸 $cleanBlood"
                tvBloodBadge.visibility = View.VISIBLE
            } else {
                tvBloodBadge.visibility = View.GONE
            }

            // Cached attendance %
            val cachedPct = NfcCardLocalCache.getCachedAttendancePct(activity, uid)
            if (cachedPct != null && cachedPct > 0.0) {
                tvAttendanceBadge.text = "📊 ${String.format(Locale.US, "%.1f", cachedPct)}% Attendance"
                tvAttendanceBadge.visibility = View.VISIBLE
            } else {
                tvAttendanceBadge.visibility = View.GONE
            }

            tvStatusBadge.text = "✓ Verified Offline Card"

            // Contact actions
            bindContactActions(activePayload.phone)
        } else {
            // Unregistered / blank card or pending online verification
            if (isNetworkAvailable()) {
                // Network is available: show loading spinner while querying Supabase
                layoutRegisteredStudent.visibility = View.GONE
                layoutUnregisteredCard.visibility = View.GONE
                progressLoadingStudent.visibility = View.VISIBLE
                tvSheetHeaderSubtitle.text = "Verifying Smart Card ••••${uid.takeLast(4)}..."
            } else {
                // Offline and not cached
                showUnregisteredState(uid)
            }
        }

        setupButtons()
    }

    private fun bindContactActions(phoneNumber: String) {
        val cleanPhone = phoneNumber.filter { it.isDigit() || it == '+' }.trim()
        activePhoneNumber = cleanPhone

        if (cleanPhone.isNotEmpty() && cleanPhone != "null" && cleanPhone.length >= 7) {
            layoutContactActions.visibility = View.VISIBLE
            layoutMissingContact.visibility = View.GONE

            btnCallOwner.setOnClickListener {
                ClassMateHaptics.selection(it)
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone"))
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(activity, "Cannot dial $cleanPhone", Toast.LENGTH_SHORT).show()
                }
            }

            btnWhatsappOwner.setOnClickListener {
                ClassMateHaptics.selection(it)
                try {
                    val normalized = if (cleanPhone.startsWith("01") && cleanPhone.length == 11) {
                        "88$cleanPhone"
                    } else {
                        cleanPhone.removePrefix("+")
                    }
                    val url = "https://wa.me/$normalized?text=Hello%20$activeName,%20I%20found%20your%20ClassMate%20Smart%20Card%20on%20campus."
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(activity, "WhatsApp not available", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            layoutContactActions.visibility = View.GONE
            layoutMissingContact.visibility = View.VISIBLE
        }
    }

    private fun showUnregisteredState(uid: String) {
        progressLoadingStudent.visibility = View.GONE
        layoutRegisteredStudent.visibility = View.GONE
        layoutUnregisteredCard.visibility = View.VISIBLE
        tvSheetHeaderSubtitle.text = "NFC Tap · Instant Campus Identity"
        tvUnregisteredCardUid.text = if (uid.isNotBlank()) "Card UID: ••••${uid.takeLast(4)}" else "Blank NFC Tag"

        if (ClassMateAuthApi.configured && ClassMateAuthApi.hasSavedSession()) {
            btnAssignUnregisteredCard.visibility = View.VISIBLE
            btnAssignUnregisteredCard.setOnClickListener {
                ClassMateHaptics.selection(it)
                val intent = Intent(activity, ClassMateAdminAttendanceActivity::class.java).apply {
                    putExtra("EXTRA_CARD_UID", uid)
                }
                activity.startActivity(intent)
                dialog.dismiss()
            }
        } else {
            btnAssignUnregisteredCard.visibility = View.GONE
        }
    }

    private fun setupButtons() {
        btnCopyRoll.setOnClickListener {
            ClassMateHaptics.selection(it)
            val roll = activeRoll
            if (roll.isNotBlank()) {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Student Roll", roll))
                Toast.makeText(activity, "Roll $roll copied to clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "No student roll to copy", Toast.LENGTH_SHORT).show()
            }
        }

        btnShareCard.setOnClickListener {
            ClassMateHaptics.selection(it)
            val shareText = buildString {
                appendLine("ClassMate Student Identity Card:")
                if (activeName.isNotBlank()) appendLine("Name: $activeName")
                if (activeRoll.isNotBlank()) appendLine("Roll: $activeRoll")
                if (activePayload.batch.isNotBlank()) appendLine("Batch: ${activePayload.batch}")
                if (activePhoneNumber.isNotBlank()) appendLine("Phone: $activePhoneNumber")
                if (activePayload.uid.isNotBlank()) appendLine("Card UID: ${activePayload.uid}")
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            activity.startActivity(Intent.createChooser(intent, "Share Student Card"))
        }
    }

    private fun hydrateOnlineData() {
        val uid = activePayload.uid.trim()
        if (uid.isEmpty()) return

        activity.lifecycleScope.launch {
            try {
                val params = JSONObject().put("p_card_uid", uid)
                val result = withContext(Dispatchers.IO) {
                    try {
                        ClassMateAuthApi.rpc("lookup_student_by_card", params)
                    } catch (_: Exception) {
                        null
                    }
                }

                progressLoadingStudent.visibility = View.GONE
                tvSheetHeaderSubtitle.text = "NFC Tap · Instant Campus Identity"

                if (result != null && result.optBoolean("found", false)) {
                    val onlineName = result.optString("full_name")
                    val onlineRoll = result.optString("roll")
                    val onlinePhone = result.optString("phone")
                    val onlineBlood = result.optString("blood_group")
                    val onlineAvatar = result.optString("avatar_url")
                    val onlineBatch = result.optString("batch_name")
                    val onlineDept = result.optString("department_name")
                    val attendancePct = if (result.has("attendance_percentage") && !result.isNull("attendance_percentage")) {
                        result.optDouble("attendance_percentage")
                    } else null

                    // Transition to registered view
                    layoutRegisteredStudent.visibility = View.VISIBLE
                    layoutUnregisteredCard.visibility = View.GONE

                    if (onlineName.isNotBlank()) {
                        activeName = onlineName
                        tvStudentName.text = activeName
                    }
                    if (onlineRoll.isNotBlank()) {
                        activeRoll = onlineRoll
                        tvStudentRoll.text = activeRoll
                    }

                    val deptBatch = buildString {
                        if (onlineBatch.isNotBlank()) append(onlineBatch)
                        if (onlineBatch.isNotBlank() && onlineDept.isNotBlank()) append(" · ")
                        if (onlineDept.isNotBlank()) append(onlineDept)
                    }.ifBlank { "MBSTU Student" }
                    tvStudentDeptBatch.text = deptBatch

                    tvStatusBadge.text = "✓ Verified MBSTU Student"

                    // Avatar hydration
                    if (onlineAvatar.isNotBlank() && onlineAvatar != "null" && onlineAvatar.startsWith("http")) {
                        Glide.with(activity)
                            .load(onlineAvatar)
                            .circleCrop()
                            .into(ivStudentAvatar)
                        ivStudentAvatar.visibility = View.VISIBLE
                        tvStudentInitials.visibility = View.GONE
                    }

                    // Blood group
                    if (onlineBlood.isNotBlank() && onlineBlood != "null") {
                        tvBloodBadge.text = "🩸 $onlineBlood"
                        tvBloodBadge.visibility = View.VISIBLE
                    } else {
                        tvBloodBadge.visibility = View.GONE
                    }

                    // Attendance percentage
                    if (attendancePct != null && attendancePct > 0.0) {
                        tvAttendanceBadge.text = "📊 ${String.format(Locale.US, "%.1f", attendancePct)}% Attendance"
                        tvAttendanceBadge.visibility = View.VISIBLE
                    } else {
                        tvAttendanceBadge.visibility = View.GONE
                    }

                    // Update contact actions
                    bindContactActions(onlinePhone)

                    // Persist to local offline cache
                    val updatedPayload = NfcCardManager.StudentCardPayload(
                        uid = uid,
                        studentId = result.optString("student_id"),
                        name = onlineName,
                        roll = onlineRoll,
                        batch = onlineBatch,
                        dept = onlineDept,
                        phone = onlinePhone,
                        blood = onlineBlood
                    )
                    activePayload = updatedPayload
                    NfcCardLocalCache.saveCard(activity, updatedPayload, onlineAvatar, attendancePct)
                } else {
                    // Only show unregistered if offline didn't find anything either
                    if (!activePayload.hasStudentInfo) {
                        showUnregisteredState(uid)
                    }
                }
            } catch (_: Exception) {
                progressLoadingStudent.visibility = View.GONE
                if (!activePayload.hasStudentInfo) {
                    showUnregisteredState(uid)
                }
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun getInitials(name: String, fallback: String): String {
        val parts = name.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
            parts.size == 1 && parts[0].isNotEmpty() -> parts[0].take(2).uppercase(Locale.US)
            fallback.isNotEmpty() -> fallback.take(2).uppercase(Locale.US)
            else -> "ID"
        }
    }
}

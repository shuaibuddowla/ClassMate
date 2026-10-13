package com.shuaib.classmate.activities

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.databinding.ActivityTeacherLiveAttendanceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

class ClassMateTeacherLiveAttendanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTeacherLiveAttendanceBinding
    private var slotId: String = ""
    private var sessionId: String? = null
    private var sessionSecret: String? = null
    private var courseTitle: String = ""
    private var courseCode: String = ""
    private var isSessionClosed = false

    private var rosterList = mutableListOf<RosterMember>()
    private var qrRotationJob: Job? = null
    private var livePollJob: Job? = null
    private val rosterAdapter = RosterAdapter()

    data class RosterMember(
        val studentId: String,
        val fullName: String,
        val rollNumber: String,
        var isPresent: Boolean,
        val markedAt: String?,
        val method: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeacherLiveAttendanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        slotId = intent.getStringExtra("EXTRA_SLOT_ID") ?: ""
        sessionId = intent.getStringExtra("EXTRA_SESSION_ID")
        courseTitle = intent.getStringExtra("EXTRA_COURSE_TITLE") ?: "Class Attendance"
        courseCode = intent.getStringExtra("EXTRA_COURSE_CODE") ?: ""

        binding.tvCourseTitle.text = courseTitle
        binding.tvCourseSubtitle.text = if (courseCode.isNotBlank()) "$courseCode · Live Session" else "Live Session"

        binding.btnBack.setOnClickListener { finish() }

        binding.rvRoster.layoutManager = LinearLayoutManager(this)
        binding.rvRoster.adapter = rosterAdapter

        binding.tabQrCode.setOnClickListener { switchTab(isQr = true) }
        binding.tabLiveRoster.setOnClickListener { switchTab(isQr = false) }
        binding.btnViewList.setOnClickListener { switchTab(isQr = false) }
        binding.cardLiveCounter.setOnClickListener { switchTab(isQr = false) }

        binding.btnCloseAttendance.setOnClickListener {
            closeSession()
        }

        binding.btnExportExcel.setOnClickListener {
            exportToExcel()
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.layoutTopAppBar) { v, insets ->
            val statusInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val basePaddingTop = (12 * resources.displayMetrics.density).toInt()
            val basePaddingBottom = (12 * resources.displayMetrics.density).toInt()
            v.setPadding(v.paddingLeft, basePaddingTop + statusInsets.top, v.paddingRight, basePaddingBottom)
            insets
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.layoutBottomActions) { v, insets ->
            val navInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            val basePaddingTop = (10 * resources.displayMetrics.density).toInt()
            val basePaddingBottom = (14 * resources.displayMetrics.density).toInt()
            v.setPadding(v.paddingLeft, basePaddingTop, v.paddingRight, basePaddingBottom + navInsets.bottom)
            insets
        }

        initializeSession()
    }

    private fun switchTab(isQr: Boolean) {
        val primaryColor = getColor(R.color.cm_primary)
        val mutedColor = getColor(R.color.cm_text_muted)

        if (isQr) {
            binding.tabQrCode.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            binding.tabQrCode.setTextColor(Color.WHITE)
            binding.tabLiveRoster.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            binding.tabLiveRoster.setTextColor(mutedColor)

            binding.scrollQrView.visibility = View.VISIBLE
            binding.layoutSummaryView.visibility = View.GONE
        } else {
            binding.tabQrCode.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            binding.tabQrCode.setTextColor(mutedColor)
            binding.tabLiveRoster.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            binding.tabLiveRoster.setTextColor(Color.WHITE)

            binding.scrollQrView.visibility = View.GONE
            binding.layoutSummaryView.visibility = View.VISIBLE
        }
    }

    private fun initializeSession() {
        lifecycleScope.launch {
            try {
                if (sessionId == null) {
                    val res = withContext(Dispatchers.IO) {
                        ClassMateAuthApi.rpc("start_attendance_session", JSONObject().put("target_slot", slotId))
                    }
                    sessionId = res.getString("session_id")
                    sessionSecret = res.getString("session_secret")
                } else {
                    val summary = withContext(Dispatchers.IO) {
                        ClassMateAuthApi.rpc("get_session_summary", JSONObject().put("target_session", sessionId))
                    }
                    if (summary.optString("status") == "closed") {
                        isSessionClosed = true
                        updateClosedUi()
                    }
                    val rows = withContext(Dispatchers.IO) {
                        ClassMateAuthApi.rows("attendance_sessions", "id=eq.$sessionId")
                    }
                    if (rows.length() > 0) {
                        sessionSecret = rows.getJSONObject(0).getString("session_secret")
                    }
                }

                if (!isSessionClosed && sessionSecret != null) {
                    startQrRotation()
                }

                startLiveCounterPoll()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateTeacherLiveAttendanceActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startQrRotation() {
        qrRotationJob?.cancel()
        qrRotationJob = lifecycleScope.launch {
            val secret = sessionSecret ?: return@launch
            while (isActive && !isSessionClosed) {
                val nowSec = System.currentTimeMillis() / 1000
                val windowIdx = nowSec / 15
                val secondsRemaining = (15 - (nowSec % 15)).toInt()

                val token = hmacSha256(secret, windowIdx.toString())
                val payload = JSONObject().apply {
                    put("s", sessionId)
                    put("w", windowIdx)
                    put("t", token)
                }.toString()

                val qrBitmap = generateQrBitmap(payload, 600, 600)
                binding.ivQrCode.setImageBitmap(qrBitmap)

                binding.tvCountdown.text = "Rotates in ${secondsRemaining}s"
                binding.progressCountdown.progress = secondsRemaining

                delay(1000)
            }
        }
    }

    private fun startLiveCounterPoll() {
        livePollJob?.cancel()
        livePollJob = lifecycleScope.launch {
            while (isActive) {
                val currentSessionId = sessionId
                if (currentSessionId != null) {
                    refreshSummary(currentSessionId)
                }
                delay(2500)
            }
        }
    }

    private suspend fun refreshSummary(sid: String) {
        try {
            val summary = withContext(Dispatchers.IO) {
                ClassMateAuthApi.rpc("get_session_summary", JSONObject().put("target_session", sid))
            }

            val rosterArray = summary.optJSONArray("roster") ?: JSONArray()
            val members = mutableListOf<RosterMember>()
            var presentCount = 0

            for (i in 0 until rosterArray.length()) {
                val obj = rosterArray.getJSONObject(i)
                val isPresent = obj.optString("status") == "present"
                if (isPresent) presentCount++
                members.add(
                    RosterMember(
                        studentId = obj.getString("student_id"),
                        fullName = obj.optString("full_name", "Student"),
                        rollNumber = obj.optString("roll_number", "—"),
                        isPresent = isPresent,
                        markedAt = obj.optString("marked_at").takeIf { it.isNotBlank() },
                        method = obj.optString("method")
                    )
                )
            }

            // Natural roll sorting: CE25001 < CE25002 < ... < CE25032 < ... < CE25060
            members.sortWith { a, b ->
                val rollCmp = compareRolls(a.rollNumber, b.rollNumber)
                if (rollCmp != 0) rollCmp else a.fullName.compareTo(b.fullName, ignoreCase = true)
            }

            rosterList = members
            val totalCount = members.size
            val percent = if (totalCount > 0) (presentCount * 100 / totalCount) else 0

            binding.tvLiveCount.text = "$presentCount / $totalCount present"
            binding.tvLivePercent.text = "$percent% turnout"
            binding.tvSumPresent.text = presentCount.toString()
            binding.tvSumAbsent.text = (totalCount - presentCount).toString()

            rosterAdapter.notifyDataSetChanged()
        } catch (_: Exception) {
            // ignore transient poll error
        }
    }

    private fun closeSession() {
        val sid = sessionId ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ClassMateAuthApi.rpc("close_attendance_session", JSONObject().put("target_session", sid))
                }
                isSessionClosed = true
                qrRotationJob?.cancel()
                updateClosedUi()
                refreshSummary(sid)
                Toast.makeText(this@ClassMateTeacherLiveAttendanceActivity, "Attendance session closed", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateTeacherLiveAttendanceActivity, "Failed to close: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateClosedUi() {
        binding.tvStatusBadge.text = "CLOSED"
        binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_cancelled)
        binding.tvStatusBadge.setTextColor(getColor(R.color.cm_notice_cancel_text))
        binding.btnCloseAttendance.visibility = View.GONE
        binding.btnExportExcel.visibility = View.VISIBLE
        switchTab(isQr = false)
    }

    private fun exportToExcel() {
        val sid = sessionId ?: return
        lifecycleScope.launch {
            try {
                Toast.makeText(this@ClassMateTeacherLiveAttendanceActivity, "Generating Excel sheet...", Toast.LENGTH_SHORT).show()
                val client = OkHttpClient()
                val token = ClassMateAuthApi.accessToken
                val url = "${BuildConfig.CLASSMATE_URL.trimEnd('/')}/functions/v1/export-attendance"

                val body = JSONObject().put("session_id", sid).toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                val bytes = withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("Server returned ${response.code}")
                        response.body?.bytes() ?: throw Exception("Empty response")
                    }
                }

                val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                val fileName = "Attendance_${courseCode.ifBlank { "Class" }}_$dateStr.xlsx"
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
                val file = File(dir, fileName)
                withContext(Dispatchers.IO) {
                    FileOutputStream(file).use { it.write(bytes) }
                }

                Toast.makeText(this@ClassMateTeacherLiveAttendanceActivity, "Downloaded: ${file.name}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateTeacherLiveAttendanceActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleOverride(member: RosterMember) {
        val sid = sessionId ?: return
        lifecycleScope.launch {
            try {
                val newPresent = !member.isPresent
                withContext(Dispatchers.IO) {
                    ClassMateAuthApi.rpc(
                        "manual_override_attendance",
                        JSONObject().apply {
                            put("target_session", sid)
                            put("target_student", member.studentId)
                            put("present", newPresent)
                        }
                    )
                }
                member.isPresent = newPresent
                rosterAdapter.notifyDataSetChanged()
                refreshSummary(sid)
            } catch (e: Exception) {
                Toast.makeText(this@ClassMateTeacherLiveAttendanceActivity, "Override error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hmacSha256(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateQrBitmap(text: String, width: Int, height: Int): Bitmap {
        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        qrRotationJob?.cancel()
        livePollJob?.cancel()
    }

    companion object {
        /**
         * Natural sort comparison for student rolls (e.g. CE25001 < CE25002 < ... < CE25032 < ... < CE25060)
         */
        fun compareRolls(aRoll: String?, bRoll: String?): Int {
            val a = (aRoll ?: "").trim().uppercase()
            val b = (bRoll ?: "").trim().uppercase()
            if (a.isEmpty() && b.isEmpty()) return 0
            if (a.isEmpty()) return 1
            if (b.isEmpty()) return -1

            val regex = Regex("(\\d+)|(\\D+)")
            val aParts = regex.findAll(a).map { it.value }.toList()
            val bParts = regex.findAll(b).map { it.value }.toList()

            val len = minOf(aParts.size, bParts.size)
            for (i in 0 until len) {
                val partA = aParts[i]
                val partB = bParts[i]
                val isNumA = partA.all { it.isDigit() }
                val isNumB = partB.all { it.isDigit() }

                if (isNumA && isNumB) {
                    val numA = partA.toLongOrNull() ?: 0L
                    val numB = partB.toLongOrNull() ?: 0L
                    if (numA != numB) {
                        return numA.compareTo(numB)
                    }
                    if (partA.length != partB.length) {
                        return partA.compareTo(partB)
                    }
                } else if (partA != partB) {
                    return partA.compareTo(partB)
                }
            }
            return a.compareTo(b)
        }
    }

    inner class RosterAdapter : RecyclerView.Adapter<RosterAdapter.ViewHolder>() {
        inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val tvRoll: TextView = view.findViewById(R.id.tvStudentRoll)
            val tvName: TextView = view.findViewById(R.id.tvStudentName)
            val tvTime: TextView = view.findViewById(R.id.tvScanTime)
            val tvStatus: TextView = view.findViewById(R.id.tvRosterStatus)
            val btnOverride: ImageButton = view.findViewById(R.id.btnOverride)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_attendance_roster, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount() = rosterList.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = rosterList[position]
            holder.tvRoll.text = item.rollNumber
            holder.tvName.text = item.fullName
            holder.tvTime.text = item.markedAt?.takeLast(8) ?: "—"

            if (item.isPresent) {
                holder.tvStatus.text = "PRESENT"
                holder.tvStatus.setBackgroundResource(R.drawable.bg_badge_green)
                holder.tvStatus.setTextColor(Color.parseColor("#10B981"))
            } else {
                holder.tvStatus.text = "ABSENT"
                holder.tvStatus.setBackgroundResource(R.drawable.bg_badge_cancelled)
                holder.tvStatus.setTextColor(Color.parseColor("#EF4444"))
            }

            holder.btnOverride.setOnClickListener {
                toggleOverride(item)
            }
        }
    }
}

from pathlib import Path

path = Path('app/src/main/java/com/shuaib/classmate/activities/ClassMateAcademicScreens.kt')
source = path.read_text(encoding='utf-8-sig')
source = source.replace('import android.content.Intent\n', '''import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.shuaib.classmate.services.ClassMateAutoMuteScheduler
import com.shuaib.classmate.services.ShakeToTorchService
''', 1)
start = source.index('    private fun loadNotices(')
end = source.index('    private fun setupLibrary(', start)
notice = '''    private fun loadNotices(root: View): Unit = launch(root) {
        val refresh = root.v<SwipeRefreshLayout>(R.id.swipeRefresh)
        val selectedBatch = batchId()
        val search = root.v<EditText>(R.id.etNoticeSearch).text.toString().trim()
        try {
            val visible = rows(ClassMateAuthApi.rows("notices",
                "select=id,title,body,published_at,semester_course_id,author_id" +
                    "&batch_id=eq.$selectedBatch&order=published_at.desc&limit=100"))
            val engagement = if (visible.isEmpty()) emptyMap() else rows(JSONArray(
                ClassMateAuthApi.rpcText("notice_engagement", JSONObject().put("target_ids",
                    JSONArray(visible.map { it.getString("id") }))))).associateBy { it.getString("notice_id") }
            if (!active(root) || selectedBatch != batchId() ||
                search != root.v<EditText>(R.id.etNoticeSearch).text.toString().trim()) return@launch
            text(root, R.id.tvNoticeSubtitle, "${visible.size} updates · Pull to refresh")
            val filtered = visible.filter { search.isBlank() ||
                it.optString("title").contains(search, true) || it.optString("body").contains(search, true) }
                .sortedWith(compareByDescending<JSONObject> {
                    engagement[it.optString("id")]?.optBoolean("is_pinned") == true
                }.thenByDescending { it.optString("published_at") })
            root.v<View>(R.id.emptyNoticeState).visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            recycler(root, R.id.rvNotices, R.layout.item_notice_modern, filtered) { card, item ->
                val noticeId = item.getString("id")
                val state = engagement[noticeId]
                text(card, R.id.tvTitle, item.optString("title"))
                text(card, R.id.tvPreview, item.optString("body"))
                text(card, R.id.tvSubject, if (item.isNull("semester_course_id")) "Announcement" else "Course")
                text(card, R.id.tvAuthorName, if (item.optString("author_id") == profile().optString("id"))
                    profile().optString("full_name") else "ClassMate")
                text(card, R.id.tvMeta, item.optString("published_at").replace('T', ' ').take(16))
                text(card, R.id.tvLikeCount, state?.optLong("like_count")?.toString() ?: "0")
                text(card, R.id.tvCommentCount, state?.optLong("comment_count")?.toString() ?: "0")
                card.v<android.widget.ImageView>(R.id.ivLikeIcon).setImageResource(
                    if (state?.optBoolean("is_liked") == true) R.drawable.ic_heart_filled
                    else R.drawable.ic_heart_outline)
                card.v<android.widget.ImageView>(R.id.btnPin).setImageResource(
                    if (state?.optBoolean("is_pinned") == true) R.drawable.ic_pin_filled
                    else R.drawable.ic_pin_outline)
                card.v<View>(R.id.btnLike).setOnClickListener {
                    setNoticeReaction(root, noticeId, "liked", state?.optBoolean("is_liked") != true)
                }
                card.v<View>(R.id.btnPin).setOnClickListener {
                    setNoticeReaction(root, noticeId, "pinned", state?.optBoolean("is_pinned") != true)
                }
                card.v<View>(R.id.btnComment).setOnClickListener { showNoticeComments(root, item) }
                listOf(R.id.btnOptions, R.id.btnReminder, R.id.imagePreviewContainer,
                    R.id.fileAttachmentContainer)
                    .forEach { card.findViewById<View>(it)?.visibility = View.GONE }
                card.v<View>(R.id.btnLike).visibility = View.VISIBLE
                card.v<View>(R.id.btnComment).visibility = View.VISIBLE
                card.v<View>(R.id.btnPin).visibility = View.VISIBLE
                card.v<View>(R.id.cardRoot).setOnClickListener {
                    AlertDialog.Builder(activity).setTitle(item.optString("title"))
                        .setMessage(item.optString("body")).setPositiveButton("Close", null).show()
                }
            }
        } finally { refresh.isRefreshing = false }
    }

    private fun setNoticeReaction(root: View, noticeId: String, field: String, enabled: Boolean): Unit =
        launch(root) {
            val profileId = profile().getString("id")
            val current = ClassMateAuthApi.rows("notice_reactions",
                "select=liked,pinned,reminder_at&profile_id=eq.$profileId&notice_id=eq.$noticeId")
                .optJSONObject(0)
            val row = JSONObject().put("profile_id", profileId).put("notice_id", noticeId)
                .put("liked", current?.optBoolean("liked") ?: false)
                .put("pinned", current?.optBoolean("pinned") ?: false)
            current?.optString("reminder_at")?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { row.put("reminder_at", it) }
            row.put(field, enabled)
            ClassMateAuthApi.upsert("notice_reactions", row)
            if (active(root)) loadNotices(root)
        }

    private fun showNoticeComments(root: View, notice: JSONObject): Unit = launch(root) {
        val noticeId = notice.getString("id")
        val comments = rows(ClassMateAuthApi.rows("notice_comments",
            "select=id,body,created_at,author_id&notice_id=eq.$noticeId&order=created_at.asc&limit=100"))
        if (!active(root)) return@launch
        val content = comments.joinToString("\\n\\n") { comment ->
            val author = if (comment.optString("author_id") == profile().optString("id"))
                profile().optString("full_name") else "ClassMate member"
            "$author · ${comment.optString("created_at").take(10)}\\n${comment.optString("body")}" 
        }.ifBlank { "No comments yet" }
        AlertDialog.Builder(activity).setTitle("Comments · ${notice.optString("title")}")
            .setMessage(content).setPositiveButton("Add comment") { _, _ ->
                val field = EditText(activity).apply { hint = "Write a comment" }
                AlertDialog.Builder(activity).setTitle("Add comment").setView(field)
                    .setPositiveButton("Post") { _, _ ->
                        val body = field.text.toString().trim()
                        if (body.isBlank()) return@setPositiveButton
                        launch(root) {
                            ClassMateAuthApi.insert("notice_comments", JSONObject()
                                .put("notice_id", noticeId).put("author_id", profile().getString("id"))
                                .put("body", body))
                            if (active(root)) { loadNotices(root); showNoticeComments(root, notice) }
                        }
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }

'''
source = source[:start] + notice + source[end:]
start = source.index('    private fun setupProfile(')
profile = '''    private fun setupProfile(root: View) {
        val account = profile()
        text(root, R.id.tvProfileName, account.optString("full_name").ifBlank { "ClassMate user" })
        text(root, R.id.tvRoleBadge, if (account.optString("role") == "admin") "ADMIN"
            else account.optString("role").uppercase())
        root.v<TextView>(R.id.tvRoleBadge).setBackgroundResource(R.drawable.bg_role_badge)
        text(root, R.id.tvUserSubInfo, listOf(account.optString("student_id").takeUnless { it == "null" },
            account.optString("email")).filterNotNull().joinToString(" · "))
        listOf(R.id.cardSeeFriends, R.id.savedResourcesTagSection, R.id.adminSection,
            R.id.fabEditPhoto, R.id.cardAiSettings, R.id.layoutAddWidget)
            .forEach { root.v<View>(it).visibility = View.GONE }
        root.v<View>(R.id.layoutDeleteOfflineCache).setOnClickListener {
            AlertDialog.Builder(activity).setTitle("Clear offline cache")
                .setMessage("Remove temporary downloads from this device?")
                .setPositiveButton("Clear") { _, _ ->
                    activity.cacheDir.listFiles()?.forEach { file -> file.deleteRecursively() }
                    Toast.makeText(activity, "Offline cache cleared", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Cancel", null).show()
        }
        val photo = GoogleSignIn.getLastSignedInAccount(activity)?.photoUrl
        if (photo != null) com.bumptech.glide.Glide.with(activity).load(photo)
            .placeholder(R.drawable.ic_default_avatar).into(root.v(R.id.ivProfile))
        val prefs = AppPreferences(activity)
        root.v<SwitchCompat>(R.id.switchDarkMode).apply {
            isChecked = prefs.isDarkMode()
            setOnCheckedChangeListener { _, checked ->
                prefs.setDarkMode(checked)
                AppCompatDelegate.setDefaultNightMode(if (checked)
                    AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
        root.v<SwitchCompat>(R.id.switchNotifications).apply {
            isChecked = prefs.isNotificationsEnabled()
            setOnCheckedChangeListener { _, checked ->
                prefs.setNotificationsEnabled(checked)
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                    scope.launch { runCatching {
                        if (checked) ClassMateAuthApi.registerDeviceToken(token)
                        else ClassMateAuthApi.unregisterDeviceToken(token)
                    } }
                }
            }
        }
        root.v<SwitchCompat>(R.id.switchAutoMute).apply {
            isChecked = prefs.isAutoMuteEnabled()
            setOnCheckedChangeListener { _, checked ->
                prefs.setAutoMuteEnabled(checked)
                if (checked) scope.launch { runCatching { ClassMateAutoMuteScheduler.schedule(activity, batchId()) }
                    .onFailure { Toast.makeText(activity, it.message ?: "Auto-mute setup failed", Toast.LENGTH_LONG).show() } }
                else ClassMateAutoMuteScheduler.cancel(activity)
            }
        }
        root.v<SwitchCompat>(R.id.switchShakeToTorch).apply {
            isChecked = prefs.isShakeToTorchEnabled()
            setOnCheckedChangeListener { _, checked ->
                if (checked && ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                    isChecked = false
                    ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), 4011)
                    return@setOnCheckedChangeListener
                }
                prefs.setShakeToTorchEnabled(checked)
                if (checked) ShakeToTorchService.start(activity) else ShakeToTorchService.stop(activity)
            }
        }
        root.v<View>(R.id.btnLogout).setOnClickListener { onSignOut() }
        root.v<View>(R.id.cardPersonalInfo).setOnClickListener { showProfileDetails(root) }
    }

    private fun showProfileDetails(root: View): Unit = launch(root) {
        val account = profile()
        val departmentId = account.optString("department_id")
        val department = if (departmentId.isBlank() || departmentId == "null") "Global"
            else ClassMateAuthApi.rows("departments", "select=name&id=eq.$departmentId")
                .optJSONObject(0)?.optString("name") ?: "Unknown"
        val assignedBatch = account.optString("batch_id")
        val batch = if (assignedBatch.isBlank() || assignedBatch == "null") "None"
            else ClassMateAuthApi.rows("batches",
                "select=batch_number,academic_session&id=eq.$assignedBatch")
                .optJSONObject(0)?.let {
                    "${it.optInt("batch_number")} · session ${it.optInt("academic_session")}" } ?: "Unknown"
        val semesters = if (batchId().isBlank()) JSONArray() else ClassMateAuthApi.rows("semesters",
            "select=semester_number&batch_id=eq.${batchId()}&status=eq.active&limit=1")
        if (!root.isAttachedToWindow) return@launch
        val details = listOf(
            "Email: ${account.optString("email")}",
            "Role: ${account.optString("role")}",
            "Department: $department",
            "Assigned batch: $batch",
            "Current semester: ${semesters.optJSONObject(0)?.optInt("semester_number") ?: "None"}",
            "CR: ${if (account.optBoolean("is_cr")) "Yes" else "No"}",
            "Verification: ${account.optString("verification_status")}",
        ).joinToString("\\n")
        AlertDialog.Builder(activity).setTitle("Profile details").setMessage(details)
            .setPositiveButton("Close", null)
            .apply {
                if (account.optString("role") in setOf("admin", "teacher"))
                    setNeutralButton("Switch batch") { _, _ -> onSwitchBatch() }
            }.show()
    }
}
'''
source = source[:start] + profile
path.write_text(source, encoding='utf-8')

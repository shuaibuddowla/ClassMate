from pathlib import Path

path = Path('app/src/main/java/com/shuaib/classmate/activities/ClassMateAcademicScreensSupabase.kt')
source = path.read_text(encoding='utf-8-sig')
source = source.replace('import com.shuaib.classmate.services.ClassMateAutoMuteScheduler\n',
    'import com.shuaib.classmate.services.ClassMateAutoMuteScheduler\n'
    'import com.shuaib.classmate.services.ClassMateNoticeReminderScheduler\n'
    'import java.time.Instant\nimport java.time.ZonedDateTime\n', 1)
old = '''                listOf(R.id.btnOptions, R.id.btnReminder, R.id.imagePreviewContainer,
                    R.id.fileAttachmentContainer)
                    .forEach { card.findViewById<View>(it)?.visibility = View.GONE }
                card.v<View>(R.id.btnLike).visibility = View.VISIBLE
                card.v<View>(R.id.btnComment).visibility = View.VISIBLE
                card.v<View>(R.id.btnPin).visibility = View.VISIBLE'''
new = '''                listOf(R.id.btnOptions, R.id.imagePreviewContainer, R.id.fileAttachmentContainer)
                    .forEach { card.findViewById<View>(it)?.visibility = View.GONE }
                card.v<View>(R.id.btnLike).visibility = View.VISIBLE
                card.v<View>(R.id.btnComment).visibility = View.VISIBLE
                card.v<View>(R.id.btnPin).visibility = View.VISIBLE
                card.v<View>(R.id.btnReminder).visibility = View.VISIBLE
                card.v<View>(R.id.btnReminder).alpha = if (state?.isNull("reminder_at") == false) 1f else 0.6f
                card.v<View>(R.id.btnReminder).setOnClickListener { showReminderOptions(root, item) }'''
assert old in source
source = source.replace(old, new, 1)
marker = '    private fun setupLibrary(root: View) {'
assert marker in source
addition = '''    private fun showReminderOptions(root: View, notice: JSONObject) {
        val options = arrayOf("In one hour", "Tomorrow at 9 AM", "Clear reminder")
        AlertDialog.Builder(activity).setTitle("Remind me about this notice")
            .setItems(options) { _, choice ->
                val at = when (choice) {
                    0 -> Instant.now().plusSeconds(3600)
                    1 -> ZonedDateTime.now().plusDays(1).withHour(9).withMinute(0)
                        .withSecond(0).withNano(0).toInstant()
                    else -> null
                }
                launch(root) {
                    val profileId = profile().getString("id")
                    val noticeId = notice.getString("id")
                    val current = ClassMateAuthApi.rows("notice_reactions",
                        "select=liked,pinned&profile_id=eq.$profileId&notice_id=eq.$noticeId")
                        .optJSONObject(0)
                    val row = JSONObject().put("profile_id", profileId).put("notice_id", noticeId)
                        .put("liked", current?.optBoolean("liked") ?: false)
                        .put("pinned", current?.optBoolean("pinned") ?: false)
                        .put("reminder_at", at?.toString() ?: JSONObject.NULL)
                    ClassMateAuthApi.upsert("notice_reactions", row)
                    ClassMateNoticeReminderScheduler.schedule(activity, noticeId,
                        notice.optString("title"), at)
                    if (active(root)) {
                        Toast.makeText(activity, if (at == null) "Reminder cleared" else "Reminder set",
                            Toast.LENGTH_SHORT).show()
                        loadNotices(root)
                    }
                }
            }.show()
    }

'''
source = source.replace(marker, addition + marker, 1)
path.write_text(source, encoding='utf-8')

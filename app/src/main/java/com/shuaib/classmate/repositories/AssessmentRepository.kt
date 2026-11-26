package com.shuaib.classmate.repositories

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.models.AssessmentItem
import com.shuaib.classmate.models.AssessmentType
import com.shuaib.classmate.utils.AppContextManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class AssessmentRepository private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("classmate_assessments_store", Context.MODE_PRIVATE)
    private val _assessmentsFlow = MutableStateFlow<List<AssessmentItem>>(emptyList())
    val assessmentsFlow: StateFlow<List<AssessmentItem>> = _assessmentsFlow.asStateFlow()

    @Volatile
    var activeBatchId: String = ""
        private set

    init {
        loadAssessments()
    }

    fun setActiveBatch(batchId: String) {
        if (batchId.isNotBlank()) {
            activeBatchId = batchId
        }
    }

    fun loadAssessments(batchId: String = currentBatchId()) {
        val resolvedBatch = batchId.ifBlank { currentBatchId() }
        if (resolvedBatch.isNotBlank()) activeBatchId = resolvedBatch

        val realJson = prefs.getString("assessments_$resolvedBatch", null)
        val realList = if (!realJson.isNullOrBlank()) {
            parseList(realJson)
        } else {
            emptyList()
        }

        if (realList.isNotEmpty()) {
            _assessmentsFlow.value = realList.sortedBy { it.getDeadlineDateTime() }
            com.shuaib.classmate.services.ClassMateAssessmentReminderScheduler.rescheduleAll(context, realList)
        } else {
            _assessmentsFlow.value = emptyList()
        }
    }

    private fun isUuid(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return runCatching { java.util.UUID.fromString(value.trim()) }.isSuccess
    }

    private fun JSONObject.optClean(key: String, default: String = ""): String {
        if (isNull(key)) return default
        val v = optString(key, default).trim()
        return if (v.equals("null", ignoreCase = true)) default else v
    }

    suspend fun refreshFromRemote(batchId: String = currentBatchId()): Unit = withContext(Dispatchers.IO) {
        val resolvedBatch = batchId.ifBlank { currentBatchId() }
        if (resolvedBatch.isBlank() || !isUuid(resolvedBatch)) return@withContext
        activeBatchId = resolvedBatch

        val remoteList = runCatching {
            val activeSemId = runCatching {
                ClassMateAuthApi.rows("semesters", "select=id&batch_id=eq.$resolvedBatch&status=eq.active&limit=1")
                    .optJSONObject(0)?.optString("id").orEmpty()
            }.getOrDefault("")
            val query = if (activeSemId.isNotBlank()) {
                "select=*&batch_id=eq.$resolvedBatch&semester_id=eq.$activeSemId&order=deadline_date.asc,deadline_time.asc"
            } else {
                "select=*&batch_id=eq.$resolvedBatch&order=deadline_date.asc,deadline_time.asc"
            }
            val rows = ClassMateAuthApi.rows("assessments", query)
            val list = mutableListOf<AssessmentItem>()
            for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                list.add(
                    AssessmentItem(
                        id = o.optClean("id"),
                        batchId = o.optClean("batch_id", resolvedBatch),
                        courseId = o.optClean("course_id"),
                        courseCode = o.optClean("course_code"),
                        courseTitle = o.optClean("course_title"),
                        type = o.optClean("type", AssessmentType.CLASS_TEST.code),
                        title = o.optClean("title"),
                        deadlineDate = o.optClean("deadline_date"),
                        deadlineTime = o.optClean("deadline_time"),
                        room = o.optClean("room"),
                        syllabus = o.optClean("syllabus"),
                        noticeId = o.optClean("notice_id"),
                        postedBy = o.optClean("posted_by"),
                        createdAt = o.optLong("created_at", System.currentTimeMillis()),
                        isMock = false
                    )
                )
            }
            list
        }.getOrNull()

        if (remoteList != null && remoteList.isNotEmpty()) {
            saveStoredRealAssessments(resolvedBatch, remoteList)
            _assessmentsFlow.value = remoteList.sortedBy { it.getDeadlineDateTime() }
        } else if (remoteList != null && remoteList.isEmpty()) {
            saveStoredRealAssessments(resolvedBatch, emptyList())
            _assessmentsFlow.value = emptyList()
        } else {
            val localReal = getStoredRealAssessments(resolvedBatch)
            if (localReal.isNotEmpty()) {
                _assessmentsFlow.value = localReal.sortedBy { it.getDeadlineDateTime() }
                // Re-sync local items to Supabase if remote returned empty
                localReal.forEach { item ->
                    if (isUuid(resolvedBatch)) {
                        runCatching {
                            val payload = JSONObject().apply {
                                put("id", item.id)
                                put("batch_id", resolvedBatch)
                                if (isUuid(item.courseId)) {
                                    put("course_id", item.courseId)
                                } else {
                                    put("course_id", JSONObject.NULL)
                                }
                                put("course_code", item.courseCode)
                                put("course_title", item.courseTitle)
                                put("type", if (item.type == "ct") "class_test" else item.type)
                                put("title", item.title)
                                put("deadline_date", item.deadlineDate)
                                put("deadline_time", item.deadlineTime)
                                put("room", item.room)
                                put("syllabus", item.syllabus)
                                if (isUuid(item.noticeId)) put("notice_id", item.noticeId) else put("notice_id", JSONObject.NULL)
                                put("posted_by", item.postedBy)
                                put("created_at", item.createdAt)
                            }
                            ClassMateAuthApi.upsert("assessments", payload)
                        }
                    }
                }
            } else {
                _assessmentsFlow.value = emptyList()
            }
        }
    }

    suspend fun saveAssessment(item: AssessmentItem, targetBatchId: String = ""): Result<AssessmentItem> = withContext(Dispatchers.IO) {
        runCatching {
            val batchId = targetBatchId.ifBlank { item.batchId.ifBlank { currentBatchId() } }
            if (batchId.isNotBlank() && isUuid(batchId)) activeBatchId = batchId
            val id = item.id.ifBlank { "assessment_${System.currentTimeMillis()}" }
            val realItem = item.copy(id = id, batchId = batchId, isMock = false)

            // 1. Update local cache (real list only)
            val currentReal = getStoredRealAssessments(batchId).filterNot { it.id == id }.toMutableList()
            currentReal.add(realItem)
            saveStoredRealAssessments(batchId, currentReal)

            // Update UI flow immediately
            _assessmentsFlow.value = currentReal.sortedBy { it.getDeadlineDateTime() }

            // 2. Sync to Supabase before returning so follow-up notice linking never races
            if (isUuid(batchId)) {
                runCatching {
                    val payload = JSONObject().apply {
                        put("id", id)
                        put("batch_id", batchId)
                        if (isUuid(realItem.courseId)) {
                            put("course_id", realItem.courseId)
                        } else {
                            put("course_id", JSONObject.NULL)
                        }
                        put("course_code", realItem.courseCode)
                        put("course_title", realItem.courseTitle)
                        put("type", if (realItem.type == "ct") "class_test" else realItem.type)
                        put("title", realItem.title)
                        put("deadline_date", realItem.deadlineDate)
                        put("deadline_time", realItem.deadlineTime)
                        put("room", realItem.room)
                        put("syllabus", realItem.syllabus)
                        if (isUuid(realItem.noticeId)) put("notice_id", realItem.noticeId) else put("notice_id", JSONObject.NULL)
                        put("posted_by", realItem.postedBy)
                        put("created_at", realItem.createdAt)
                    }
                    ClassMateAuthApi.upsert("assessments", payload)
                }.onFailure { Log.e(TAG, "Supabase assessment sync omitted: ${it.message}", it) }
            }

            // 3. Sync to Firestore in background if configured
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val db = FirebaseFirestore.getInstance()
                    val firestoreMap = hashMapOf<String, Any>(
                        "id" to id,
                        "batchId" to batchId,
                        "courseId" to realItem.courseId,
                        "courseCode" to realItem.courseCode,
                        "courseTitle" to realItem.courseTitle,
                        "type" to realItem.type,
                        "title" to realItem.title,
                        "deadlineDate" to realItem.deadlineDate,
                        "deadlineTime" to realItem.deadlineTime,
                        "room" to realItem.room,
                        "syllabus" to realItem.syllabus,
                        "noticeId" to realItem.noticeId,
                        "postedBy" to realItem.postedBy,
                        "createdAt" to realItem.createdAt
                    )
                    db.collection("batches").document(batchId)
                        .collection("assessments").document(id)
                        .set(firestoreMap)
                }.onFailure { Log.d(TAG, "Firestore assessment sync omitted: ${it.message}") }
            }

            realItem
        }
    }

    suspend fun updateAssessmentNoticeId(assessmentId: String, noticeId: String, targetBatchId: String = ""): Unit = withContext(Dispatchers.IO) {
        val batchId = targetBatchId.ifBlank { currentBatchId() }
        if (assessmentId.isBlank() || noticeId.isBlank() || !isUuid(noticeId)) return@withContext
        val currentReal = getStoredRealAssessments(batchId).toMutableList()
        val index = currentReal.indexOfFirst { it.id == assessmentId }
        if (index != -1) {
            val updated = currentReal[index].copy(noticeId = noticeId)
            currentReal[index] = updated
            saveStoredRealAssessments(batchId, currentReal)
            _assessmentsFlow.value = currentReal.sortedBy { it.getDeadlineDateTime() }
        }
        if (isUuid(batchId)) {
            runCatching {
                val payload = JSONObject().apply {
                    put("id", assessmentId)
                    put("notice_id", noticeId)
                }
                ClassMateAuthApi.upsert("assessments", payload)
            }
        }
    }

    suspend fun deleteAssessment(assessmentId: String, targetBatchId: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val batchId = targetBatchId.ifBlank { currentBatchId() }
            val currentReal = getStoredRealAssessments(batchId).filterNot { it.id == assessmentId }
            com.shuaib.classmate.services.ClassMateAssessmentReminderScheduler.cancelForAssessment(context, assessmentId)
            saveStoredRealAssessments(batchId, currentReal)

            if (currentReal.isEmpty()) {
                _assessmentsFlow.value = emptyList()
            } else {
                _assessmentsFlow.value = currentReal.sortedBy { it.getDeadlineDateTime() }
            }

            // Remote deletes with batch isolation in background
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                if (isUuid(batchId)) {
                    runCatching {
                        ClassMateAuthApi.delete("assessments", "id=eq.$assessmentId&batch_id=eq.$batchId")
                    }
                }
                runCatching {
                    FirebaseFirestore.getInstance().collection("batches").document(batchId)
                        .collection("assessments").document(assessmentId)
                        .delete()
                }
            }
            Unit
        }
    }

    fun getAssessmentById(id: String): AssessmentItem? {
        return _assessmentsFlow.value.firstOrNull { it.id == id }
    }

    suspend fun fetchAssessmentById(id: String, targetBatchId: String = ""): AssessmentItem? = withContext(Dispatchers.IO) {
        val cleanId = id.trim()
        if (cleanId.isBlank()) return@withContext null
        val local = getAssessmentById(cleanId)
        if (local != null) return@withContext local

        runCatching {
            val rows = ClassMateAuthApi.rows("assessments", "select=*&id=eq.$cleanId&limit=1")
            if (rows.length() > 0) {
                val o = rows.getJSONObject(0)
                val batch = targetBatchId.ifBlank { o.optClean("batch_id", currentBatchId()) }
                val item = AssessmentItem(
                    id = o.optClean("id"),
                    batchId = o.optClean("batch_id", batch),
                    courseId = o.optClean("course_id"),
                    courseCode = o.optClean("course_code"),
                    courseTitle = o.optClean("course_title"),
                    type = o.optClean("type", AssessmentType.CLASS_TEST.code),
                    title = o.optClean("title"),
                    deadlineDate = o.optClean("deadline_date"),
                    deadlineTime = o.optClean("deadline_time"),
                    room = o.optClean("room"),
                    syllabus = o.optClean("syllabus"),
                    noticeId = o.optClean("notice_id"),
                    postedBy = o.optClean("posted_by"),
                    createdAt = o.optLong("created_at", System.currentTimeMillis()),
                    isMock = false
                )
                val current = _assessmentsFlow.value
                if (current.none { it.id == item.id }) {
                    _assessmentsFlow.value = (current + item).sortedBy { it.getDeadlineDateTime() }
                }
                item
            } else null
        }.getOrNull()
    }

    private fun getStoredRealAssessments(batchId: String): List<AssessmentItem> {
        val json = prefs.getString("assessments_$batchId", null) ?: return emptyList()
        return parseList(json)
    }

    private fun saveStoredRealAssessments(batchId: String, items: List<AssessmentItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("batchId", item.batchId)
                put("courseId", item.courseId)
                put("courseCode", item.courseCode)
                put("courseTitle", item.courseTitle)
                put("type", item.type)
                put("title", item.title)
                put("deadlineDate", item.deadlineDate)
                put("deadlineTime", item.deadlineTime)
                put("room", item.room)
                put("syllabus", item.syllabus)
                put("noticeId", item.noticeId)
                put("postedBy", item.postedBy)
                put("createdAt", item.createdAt)
            })
        }
        prefs.edit().putString("assessments_$batchId", array.toString()).apply()
        com.shuaib.classmate.services.ClassMateAssessmentReminderScheduler.rescheduleAll(context, items)
    }

    private fun parseList(jsonStr: String): List<AssessmentItem> {
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<AssessmentItem>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                list.add(
                    AssessmentItem(
                        id = o.optString("id"),
                        batchId = o.optString("batchId"),
                        courseId = o.optString("courseId"),
                        courseCode = o.optString("courseCode"),
                        courseTitle = o.optString("courseTitle"),
                        type = o.optString("type", AssessmentType.CLASS_TEST.code),
                        title = o.optString("title"),
                        deadlineDate = o.optString("deadlineDate"),
                        deadlineTime = o.optString("deadlineTime"),
                        room = o.optString("room"),
                        syllabus = o.optString("syllabus"),
                        noticeId = o.optString("noticeId"),
                        postedBy = o.optString("postedBy"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        isMock = false
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun currentBatchId(): String {
        return AppContextManager.getManagedBatchId().ifBlank {
            AppContextManager.getBatchId().ifBlank { "default_batch" }
        }
    }

    /**
     * Testing / Staging Preview Data:
     * Appears ONLY until the user posts the first real assessment.
     * Allows immediate testing of the Hero elevated gradient card, countdown, chips, and bottom sheets!
     */
    private fun generateMockAssessments(batchId: String): List<AssessmentItem> {
        val today = LocalDate.now()
        return listOf(
            AssessmentItem(
                id = "mock_assessment_1",
                batchId = batchId,
                courseId = "mock_c1",
                courseCode = "CSE-3101",
                courseTitle = "Database Management Systems",
                type = AssessmentType.CLASS_TEST.code,
                title = "CT-2: Relational Algebra, SQL & Normalization",
                deadlineDate = today.plusDays(2).toString(),
                deadlineTime = "10:30 AM",
                room = "402",
                syllabus = "• Chapter 3: Relational Data Model\n• Chapter 4: Relational Algebra Queries\n• Chapter 5: Advanced SQL (JOINs, Aggregates, Subqueries)\n• Normal Forms: 1NF, 2NF, 3NF, BCNF\n\n📌 Note: Scientific calculators allowed. Bring 2B pencil for ER diagram drawing.",
                postedBy = "Course CR",
                isMock = true
            ),
            AssessmentItem(
                id = "mock_assessment_2",
                batchId = batchId,
                courseId = "mock_c2",
                courseCode = "CSE-3103",
                courseTitle = "Software Engineering",
                type = AssessmentType.ASSIGNMENT.code,
                title = "SRS Document Submission (Phase 1)",
                deadlineDate = today.plusDays(4).toString(),
                deadlineTime = "11:59 PM",
                room = "Google Classroom",
                syllabus = "Submit the IEEE 830 compliant Software Requirement Specification (SRS) for your semester project.\n\nRequired sections:\n1. Introduction & Project Scope\n2. User Personas & Use Case Diagrams\n3. Functional & Non-functional Requirements\n4. Activity & Sequence Diagrams\n\nSubmit as a single PDF. Late submissions will receive 10% penalty per day.",
                postedBy = "Dr. Teacher",
                isMock = true
            ),
            AssessmentItem(
                id = "mock_assessment_3",
                batchId = batchId,
                courseId = "mock_c3",
                courseCode = "CSE-3105",
                courseTitle = "Microprocessor & Interfacing",
                type = AssessmentType.QUIZ.code,
                title = "Pop Quiz: 8086 Architecture & Register Set",
                deadlineDate = today.plusDays(6).toString(),
                deadlineTime = "11:15 AM",
                room = "305",
                syllabus = "15-minute quick MCQ & short answer quiz on 8086 registers, segmented memory concept, and basic addressing modes.",
                postedBy = "Faculty Member",
                isMock = true
            ),
            AssessmentItem(
                id = "mock_assessment_4",
                batchId = batchId,
                courseId = "mock_c4",
                courseCode = "CSE-3102",
                courseTitle = "DBMS Sessional Lab",
                type = AssessmentType.LAB_REPORT.code,
                title = "Lab Report 04: Complex PL/SQL Triggers",
                deadlineDate = today.plusDays(8).toString(),
                deadlineTime = "02:00 PM",
                room = "Software Lab 2",
                syllabus = "Complete experimentation write-up on PL/SQL Row-level triggers, BEFORE/AFTER constraints, and audit logging table.",
                postedBy = "Lab Instructor",
                isMock = true
            ),
            AssessmentItem(
                id = "mock_assessment_5",
                batchId = batchId,
                courseId = "mock_c5",
                courseCode = "CSE-3107",
                courseTitle = "Technical Communication",
                type = AssessmentType.PRESENTATION.code,
                title = "Group Slide Presentation: Modern Cloud Architectures",
                deadlineDate = today.plusDays(10).toString(),
                deadlineTime = "09:00 AM",
                room = "Seminar Room 1",
                syllabus = "8-minute team presentation followed by 2 minutes Q&A. Dress code: Formal/Business Casual. Prepare 10-12 slides.",
                postedBy = "Course Teacher",
                isMock = true
            ),
            // One past assessment to test "Past / Completed" archive
            AssessmentItem(
                id = "mock_assessment_past_1",
                batchId = batchId,
                courseId = "mock_c1",
                courseCode = "CSE-3101",
                courseTitle = "Database Management Systems",
                type = AssessmentType.CLASS_TEST.code,
                title = "CT-1: ER Modeling & Database Architecture",
                deadlineDate = today.minusDays(5).toString(),
                deadlineTime = "10:30 AM",
                room = "402",
                syllabus = "Chapters 1 & 2: Entity-Relationship Diagrams, Weak Entities, Cardinality Ratios.",
                postedBy = "Course CR",
                isMock = true
            )
        )
    }

    companion object {
        private const val TAG = "AssessmentRepository"

        @Volatile
        private var instance: AssessmentRepository? = null

        fun getInstance(context: Context): AssessmentRepository {
            return instance ?: synchronized(this) {
                instance ?: AssessmentRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}

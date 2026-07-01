package com.shuaib.classmate.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.shuaib.classmate.databinding.FragmentChatBinding
import com.shuaib.classmate.chat.model.AiMessage
import com.shuaib.classmate.ai.AiChatMessageInput
import com.shuaib.classmate.services.AIService
import com.shuaib.classmate.data.local.ClassMateDatabase
import com.shuaib.classmate.data.local.NoticeEntity
import com.shuaib.classmate.data.local.TimetableEntity
import com.shuaib.classmate.notices.NoticeTextFormatter
import com.shuaib.classmate.utils.applyClickAnimation
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.models.User
import com.shuaib.classmate.repositories.TimetableRepository
import com.google.firebase.firestore.Source
import java.util.Locale
import java.util.UUID

class ChatFragment : Fragment(), TextToSpeech.OnInitListener {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private var adapter: AiChatAdapter? = null
    private var tts: TextToSpeech? = null
    private var chatHistoryListener: com.google.firebase.firestore.ListenerRegistration? = null

    private var cachedNotices: List<NoticeEntity> = emptyList()
    private var cachedTodayPeriods: List<TimetableEntity> = emptyList()
    private var isAiGenerating = false


    private val speechRecognizerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                binding.etMessage.setText(spokenText)
                binding.etMessage.setSelection(spokenText.length)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottomPadding = maxOf(imeInsets.bottom, systemBarInsets.bottom)
            binding.root.setPadding(0, 0, 0, bottomPadding)
            insets
        }

        // Initialize TTS
        tts = TextToSpeech(requireContext(), this)

        // Initialize Chat List
        adapter = AiChatAdapter(
            onCopy = { msg -> copyToClipboard(msg.text) },
            onShare = { msg -> shareText(msg.text) },
            onSpeak = { msg -> toggleSpeak(msg) }
        )


        binding.rvAiChat.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = this@ChatFragment.adapter
            setHasFixedSize(true)
        }

        // Keyboard & Scroll Optimization: Scroll to end on keyboard open/resize
        binding.rvAiChat.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                val count = adapter?.itemCount ?: 0
                if (count > 0) {
                    binding.rvAiChat.post {
                        if (_binding != null) {
                            binding.rvAiChat.smoothScrollToPosition(count - 1)
                        }
                    }
                }
            }
        }

        // Keyboard focus scroll helper
        binding.etMessage.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.rvAiChat.postDelayed({
                    if (_binding != null) {
                        val count = adapter?.itemCount ?: 0
                        if (count > 0) {
                            binding.rvAiChat.smoothScrollToPosition(count - 1)
                        }
                    }
                }, 200)
            }
        }

        // Send button micro-interaction setup
        binding.btnSend.isEnabled = false
        binding.btnSend.alpha = 0.5f
        binding.etMessage.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val isNotEmpty = !s.isNullOrBlank()
                binding.btnSend.isEnabled = isNotEmpty
                binding.btnSend.animate()
                    .alpha(if (isNotEmpty) 1.0f else 0.5f)
                    .setDuration(150)
                    .start()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Click Listeners
        binding.btnBack.applyClickAnimation {
            (activity as? com.shuaib.classmate.activities.MainActivity)?.showMainTab(com.shuaib.classmate.R.id.nav_timetable)
        }

        binding.btnSend.applyClickAnimation {
            sendMessage()
        }

        binding.btnVoiceInput.applyClickAnimation {
            startVoiceInput()
        }

        binding.btnClearChat.applyClickAnimation {
            showClearHistoryDialog()
        }

        // Suggestions click listeners and animations
        val suggestions = listOf(
            binding.chipClasses to "What classes do I have today?",
            binding.chipNotices to "Please summarize the recent notices.",
            binding.chipResources to "What PDF study notes or resources are available?",
            binding.chipCancellations to "Are there any class cancellations today?",
            binding.cardClasses to "What classes do I have today?",
            binding.cardNotices to "Please summarize the recent notices.",
            binding.cardResources to "What PDF study notes or resources are available?",
            binding.cardCancellations to "Are there any class cancellations today?"
        )

        suggestions.forEach { (view, prompt) ->
            view.applyClickAnimation {
                if (!isAiGenerating) {
                    sendPrompt(prompt)
                }
            }
        }


        // Start dynamic database collections
        observeLocalDatabase()

        // Load chat history
        loadChatHistory()
    }

    private fun observeLocalDatabase() {
        // Observe notices
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ClassMateDatabase.getInstance(requireContext()).noticeDao()
                    .observeNotices(20)
                    .collect { list ->
                        cachedNotices = list
                    }
            }
        }

        // Observe today's periods
        val todayName = java.text.SimpleDateFormat("EEEE", Locale.US).format(java.util.Date()).lowercase()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ClassMateDatabase.getInstance(requireContext()).timetableDao()
                    .observePeriods(todayName)
                    .collect { list ->
                        cachedTodayPeriods = list
                    }
            }
        }
    }

    private fun loadChatHistory() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            updateEmptyStateVisibility(true)
            return
        }

        chatHistoryListener?.remove()
        chatHistoryListener = FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .collection("ai_chat_history")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (_binding == null) return@addSnapshotListener
                if (error != null) {
                    Log.e("ChatFragment", "Failed to load chat history: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val messages = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(AiMessage::class.java)
                    }
                    adapter?.submitList(messages) {
                        if (_binding != null && messages.isNotEmpty()) {
                            binding.rvAiChat.smoothScrollToPosition(messages.size - 1)
                        }
                    }
                    updateEmptyStateVisibility(messages.isEmpty())
                }
            }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isBlank()) return

        binding.etMessage.setText("")
        executeAiWorkflow(text)
    }

    private fun sendPrompt(promptText: String) {
        executeAiWorkflow(promptText)
    }

    private fun executeAiWorkflow(query: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (isAiGenerating) return

        // Lock inputs and disable interactive views to prevent concurrent requests
        isAiGenerating = true
        binding.btnSend.isEnabled = false
        binding.btnSend.alpha = 0.3f
        binding.etMessage.isEnabled = false
        binding.btnVoiceInput.isEnabled = false
        binding.btnVoiceInput.alpha = 0.3f

        // 0. Extract stable history from adapter BEFORE saving user message to Firestore
        val history = adapter?.currentList?.map { msg ->
            val role = if (msg.sender == "user") "user" else "assistant"
            AiChatMessageInput(role, msg.text)
        } ?: emptyList()

        // 1. Add User message
        val userMsg = AiMessage(
            id = UUID.randomUUID().toString(),
            text = query,
            sender = "user",
            timestamp = System.currentTimeMillis()
        )
        saveMessageToFirestore(userMsg)

        // 2. Show typing indicator and scroll down
        binding.layoutTypingIndicator.isVisible = true
        binding.rvAiChat.postDelayed({
            if (_binding != null) {
                val count = adapter?.itemCount ?: 0
                if (count > 0) {
                    binding.rvAiChat.smoothScrollToPosition(count - 1)
                }
            }
        }, 100)

        // 3. Generate response with robust safety try-catch block
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val systemPrompt = buildSystemContext()
                val response = AIService.chatWithAi(history + AiChatMessageInput("user", query), systemPrompt)

                if (_binding == null) return@launch

                val finalResponse = response ?: "Sorry, I couldn't reach the server. Please check your connection and try again."
                
                // Extract classmate actions
                val actionPattern = Regex("<classmate_action>([\\s\\S]*?)</classmate_action>")
                val match = actionPattern.find(finalResponse)
                if (match != null) {
                    val actionJson = match.groupValues[1].trim()
                    processClassmateAction(actionJson)
                }
                
                val cleanResponse = finalResponse.replace(actionPattern, "").trim()
                val displayText = if (cleanResponse.isBlank()) "Action executed successfully!" else cleanResponse

                val aiMsg = AiMessage(
                    id = UUID.randomUUID().toString(),
                    text = displayText,
                    sender = "ai",
                    timestamp = System.currentTimeMillis()
                )
                saveMessageToFirestore(aiMsg)
            } catch (e: Exception) {
                Log.e("ChatFragment", "Error during AI generation: ${e.message}", e)
                if (_binding != null) {
                    val errorMsg = AiMessage(
                        id = UUID.randomUUID().toString(),
                        text = "Sorry, an unexpected error occurred. Please check your connection and try again.",
                        sender = "ai",
                        timestamp = System.currentTimeMillis()
                    )
                    saveMessageToFirestore(errorMsg)
                }
            } finally {
                if (_binding != null) {
                    binding.layoutTypingIndicator.isVisible = false
                    isAiGenerating = false
                    
                    // Re-enable inputs
                    binding.etMessage.isEnabled = true
                    binding.btnVoiceInput.isEnabled = true
                    binding.btnVoiceInput.alpha = 1.0f
                    
                    val isNotEmpty = !binding.etMessage.text.isNullOrBlank()
                    binding.btnSend.isEnabled = isNotEmpty
                    binding.btnSend.animate()
                        .alpha(if (isNotEmpty) 1.0f else 0.5f)
                        .setDuration(150)
                        .start()
                }
            }
        }
    }


    private fun saveMessageToFirestore(message: AiMessage) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .collection("ai_chat_history")
            .document(message.id)
            .set(message)
            .addOnFailureListener { e ->
                Log.e("ChatFragment", "Failed to save chat message: ${e.message}")
            }
    }

    private suspend fun buildSystemContext(): String {
        val appContext = requireContext().applicationContext
        return withContext(Dispatchers.IO) {
            val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date())
            val dayOfWeek = java.text.SimpleDateFormat("EEEE", Locale.US).format(java.util.Date())
            val sb = StringBuilder()
            
            // --- Persona & Identity ---
            sb.append("You are ClassMate AI, the elite personal academic assistant for MBSTU CSE-22 batch students.\n")
            sb.append("Your mission is to deliver masterclass responses that are warm, authoritative, scholarly, and extremely organized. You speak like a senior academic representative or Class Representative (CR).\n\n")
            
            sb.append("Current local date and time: $now (Day of week: $dayOfWeek)\n\n")

            // --- Notice Cancellation & Timetable Connection ---
            sb.append("--- IMPORTANT SYSTEM WIRING: NOTICE CANCELLATIONS & TIMETABLE ---\n")
            sb.append("Understand how class cancellation notices are wired to the class timetable database:\n")
            sb.append("1. A cancelled class announcement notice is published with the property `isCancel` set to true.\n")
            sb.append("2. When a class notice cancellation occurs, the corresponding timetable period document (in the `/timetable/{day}/periods/{periodId}` collection) is updated. It sets `isCancelled` to true and sets the `cancelDate` field to the date of cancellation (formatted as `yyyy-MM-dd`).\n")
            sb.append("3. The live timetable checks these fields and dynamically marks the class as 'Class has been cancelled' for that date.\n")
            sb.append("4. As ClassMate AI, you must link these two pieces of information. When a user asks about cancellations, cross-reference both recent cancellations in the notices feed and the live timetable cancellation status. You can cancel classes yourself by outputting a structured action block.\n\n")

            // --- Weekly Class Schedule ---
            sb.append("--- WEEKLY CLASS SCHEDULE ---\n")
            val timetableDao = ClassMateDatabase.getInstance(appContext).timetableDao()
            val days = listOf("saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday")
            days.forEach { day ->
                val periods = timetableDao.getPeriodsSync(day)
                val dayName = day.replaceFirstChar { it.uppercase() }
                if (periods.isEmpty()) {
                    sb.append("$dayName: No classes scheduled.\n")
                } else {
                    sb.append("$dayName:\n")
                    periods.forEach { entity ->
                        val status = when {
                            entity.cancelDate.isNotBlank() -> "CANCELLED on ${entity.cancelDate}"
                            entity.substituteDate.isNotBlank() -> "SUBSTITUTE on ${entity.substituteDate} (taught by ${entity.substituteTeacher})"
                            else -> "Active"
                        }
                        sb.append("  - ID: ${entity.id} | ${entity.subject} | Time: ${entity.startTime} -> ${entity.endTime} | Teacher: ${entity.teacher} | Status: $status\n")
                    }
                }
            }
            sb.append("\n")

            // --- Academic Calendar Exceptions & Holidays ---
            sb.append("--- ACADEMIC CALENDAR, HOLIDAYS & SUSPENSIONS ---\n")
            try {
                val exceptionsSnapshot = FirebaseFirestore.getInstance()
                    .collection("academic_calendar_exceptions")
                    .whereEqualTo("isActive", true)
                    .get()
                    .await()
                val exceptions = exceptionsSnapshot.documents
                if (exceptions.isEmpty()) {
                    sb.append("No active academic holidays or suspension events scheduled.\n")
                } else {
                    exceptions.forEach { doc ->
                        val title = doc.getString("title") ?: "Event"
                        val type = doc.getString("type") ?: "N/A"
                        val start = doc.getString("startDate") ?: ""
                        val end = doc.getString("endDate") ?: ""
                        val reason = doc.getString("reason") ?: ""
                        sb.append("- Event: $title | Type: $type | Dates: $start to $end | Details: $reason\n")
                    }
                }
            } catch (e: Exception) {
                sb.append("Academic calendar exceptions are currently unavailable (Failed to load: ${e.message})\n")
            }
            sb.append("\n")

            // --- Recent Announcements & Notices ---
            sb.append("--- RECENT ANNOUNCEMENTS & NOTICES ---\n")
            val notices = cachedNotices
            if (notices.isEmpty()) {
                sb.append("No recent notices found.\n")
            } else {
                notices.take(15).forEach { entity ->
                    val typePrefix = if (entity.isCancel) "[CLASS CANCELLATION] " else ""
                    sb.append("- Title: $typePrefix${entity.title}\n  Body: ${entity.body}\n  Posted: ${java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(entity.timestampMillis))}\n")
                }
            }
            sb.append("\n")

            // --- Available PDF Study Resources ---
            sb.append("--- AVAILABLE PDF STUDY RESOURCES & FILES ---\n")
            try {
                val snapshot = FirebaseFirestore.getInstance()
                    .collection("library_files")
                    .whereEqualTo("isDeleted", false)
                    .get()
                    .await()
                val files = snapshot.documents
                if (files.isEmpty()) {
                    sb.append("No study resources or PDF files are currently uploaded in the library.\n")
                } else {
                    files.forEach { doc ->
                        val title = doc.getString("title") ?: "N/A"
                        val subject = doc.getString("subject") ?: "N/A"
                        val code = doc.getString("courseCode") ?: ""
                        val desc = doc.getString("description") ?: ""
                        val url = doc.getString("downloadUrl") ?: ""
                        sb.append("- Title: $title | Course: $subject ($code) | Description: $desc | Download Link: $url\n")
                    }
                }
            } catch (e: Exception) {
                sb.append("Study resources are currently unavailable (Failed to load: ${e.message})\n")
            }
            sb.append("\n")

            // --- Question Bank Papers ---
            sb.append("--- QUESTION BANK PAPERS ---\n")
            try {
                val qbSnapshot = FirebaseFirestore.getInstance()
                    .collection("question_bank")
                    .whereEqualTo("isDeleted", false)
                    .get()
                    .await()
                val qb = qbSnapshot.documents
                if (qb.isEmpty()) {
                    sb.append("No question papers currently uploaded in the question bank.\n")
                } else {
                    qb.forEach { doc ->
                        val title = doc.getString("title") ?: "N/A"
                        val code = doc.getString("courseCode") ?: ""
                        val subject = doc.getString("subject") ?: ""
                        val examType = doc.getString("examType") ?: "Exam"
                        val semester = doc.getString("semester") ?: ""
                        val year = doc.getString("year") ?: ""
                        val url = doc.getString("downloadUrl") ?: doc.getString("driveUrl") ?: ""
                        sb.append("- Title: $title | Course: $subject ($code) | Type: $examType | Sem: $semester | Year: $year | Download Link: $url\n")
                    }
                }
            } catch (e: Exception) {
                sb.append("Question bank papers are currently unavailable (Failed to load: ${e.message})\n")
            }
            sb.append("\n")

            // --- Active Class Polls ---
            sb.append("--- ACTIVE CLASS POLLS ---\n")
            try {
                val pollsSnapshot = FirebaseFirestore.getInstance()
                    .collection("polls")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(10)
                    .get()
                    .await()
                val polls = pollsSnapshot.documents
                if (polls.isEmpty()) {
                    sb.append("No active polls or voting events found.\n")
                } else {
                    polls.forEach { doc ->
                        val question = doc.getString("question") ?: "N/A"
                        val options = doc.get("options") as? List<*>
                        val active = doc.getBoolean("isActive") ?: true
                        val expires = doc.getTimestamp("expiresAt")?.toDate()?.toString() ?: "N/A"
                        sb.append("- Question: $question | Options: ${options?.joinToString(", ")} | Status: ${if (active) "Active (Expires: $expires)" else "Closed"}\n")
                    }
                }
            } catch (e: Exception) {
                sb.append("Class polls are currently unavailable (Failed to load: ${e.message})\n")
            }
            sb.append("\n")

            // --- Published Results & Seat Plans ---
            sb.append("--- ACADEMIC RESULTS & SEAT PLANS ---\n")
            try {
                val resultsSnapshot = FirebaseFirestore.getInstance()
                    .collection("results")
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(5)
                    .get()
                    .await()
                val results = resultsSnapshot.documents
                if (results.isNotEmpty()) {
                    sb.append("Results:\n")
                    results.forEach { doc ->
                        val title = doc.getString("title") ?: "Result"
                        val subject = doc.getString("subject") ?: ""
                        val code = doc.getString("courseCode") ?: ""
                        val url = doc.getString("downloadUrl") ?: doc.getString("driveUrl") ?: ""
                        sb.append("  - Result sheet: $title | Subject: $subject ($code) | Download Link: $url\n")
                    }
                }
            } catch (e: Exception) {
                sb.append("Academic results are currently unavailable.\n")
            }
            try {
                val plansSnapshot = FirebaseFirestore.getInstance()
                    .collection("seatplans")
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(5)
                    .get()
                    .await()
                if (plansSnapshot.documents.isNotEmpty()) {
                    sb.append("Seat Plans:\n")
                    plansSnapshot.documents.forEach { doc ->
                        val title = doc.getString("title") ?: "Seat Plan"
                        val date = doc.getString("examDate") ?: "N/A"
                        val url = doc.getString("downloadUrl") ?: doc.getString("driveUrl") ?: ""
                        sb.append("  - Exam room mapping: $title | Date: $date | Download Link: $url\n")
                    }
                }
            } catch (e: Exception) {
                sb.append("Seat plans are currently unavailable.\n")
            }
            sb.append("\n")

            // --- Class Action Tools ---
            sb.append("--- CLASS ACTION TOOLS ---\n")
            sb.append("You can perform actions on the class schedule database or notices (reschedule, cancel, substitute, add, delete, post notice) by outputting a structured action block in your response.\n")
            sb.append("To execute an action, you MUST output a single block wrapped in `<classmate_action>` and `</classmate_action>` tags containing a JSON object.\n")
            sb.append("Use the exact ID of the class period from the weekly schedule.\n\n")
            sb.append("Available actions:\n")
            sb.append("1. CANCEL_CLASS (temporarily cancel a class for a specific date):\n")
            sb.append("{\n  \"action\": \"CANCEL_CLASS\",\n  \"day\": \"saturday\",\n  \"id\": \"period_id\",\n  \"date\": \"yyyy-MM-dd\"\n}\n\n")
            sb.append("2. SUBSTITUTE_CLASS (temporarily replace the teacher for a class on a specific date):\n")
            sb.append("{\n  \"action\": \"SUBSTITUTE_CLASS\",\n  \"day\": \"saturday\",\n  \"id\": \"period_id\",\n  \"substituteTeacher\": \"Name of substitute teacher\",\n  \"date\": \"yyyy-MM-dd\"\n}\n\n")
            sb.append("3. RESCHEDULE_CLASS (reschedule time for a class):\n")
            sb.append("{\n  \"action\": \"RESCHEDULE_CLASS\",\n  \"day\": \"saturday\",\n  \"id\": \"period_id\",\n  \"startTime\": \"HH:mm\",\n  \"endTime\": \"HH:mm\"\n}\n\n")
            sb.append("4. ADD_CLASS (add a new class slot to a day):\n")
            sb.append("{\n  \"action\": \"ADD_CLASS\",\n  \"day\": \"saturday\",\n  \"subject\": \"Subject Name\",\n  \"teacher\": \"Teacher Name\",\n  \"startTime\": \"HH:mm\",\n  \"endTime\": \"HH:mm\"\n}\n\n")
            sb.append("5. DELETE_CLASS (completely delete a class period):\n")
            sb.append("{\n  \"action\": \"DELETE_CLASS\",\n  \"day\": \"saturday\",\n  \"id\": \"period_id\"\n}\n\n")
            sb.append("6. POST_NOTICE (post a new announcement notice to the batch, this also sends push alerts):\n")
            sb.append("{\n  \"action\": \"POST_NOTICE\",\n  \"title\": \"Title of the notice\",\n  \"body\": \"Detailed content of the notice\",\n  \"priority\": \"normal\" or \"urgent\"\n}\n\n")
            sb.append("Important:\n")
            sb.append("- Only output the action block if the user explicitly asks to cancel, substitute, reschedule, add, delete, or post a notice.\n")
            sb.append("- Always output a friendly, natural text response explaining the result of the action in addition to the action block.\n\n")

            // --- Instructions for Masterclass Outputs ---
            sb.append("Instructions for Masterclass Output:\n")
            sb.append("1. Structure your output beautifully. Use bold section titles, bullet points, and blockquotes. DO NOT use Markdown tables (as they are not supported by the client view). Avoid raw walls of text.\n")
            sb.append("2. If presenting data (notices, schedule, study materials, polls), display it in clean bulleted lists, bold key-value layouts, or numbered lists instead of tables.\n")
            sb.append("3. For any listed resource (PDF, Question Paper, Result, Seat Plan) that contains a Download Link, ALWAYS create a clickable link using: `[Click to Download](url)`. Make sure to extract the exact url from the database info.\n")
            sb.append("4. Answer clearly, concisely, and supportively. Keep responses under 300 words unless detail is requested.\n")
            sb.append("5. When referencing class schedule cancellations, remember they are linked to the timetable documents. Provide status updates connecting both the notice feed cancellation announcement and the timetable period state.\n")
            sb.toString()
        }
    }

    private fun processClassmateAction(actionJson: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                if (doc.exists()) {
                    val user = doc.toObject(User::class.java)
                    val json = org.json.JSONObject(actionJson)
                    val action = json.optString("action")
                    
                    val hasPermission = when (action) {
                        "POST_NOTICE" -> user?.canPostNotices() ?: false || user?.isAdmin() ?: false
                        else -> user?.canEditTimetable() ?: false || user?.isAdmin() ?: false
                    }
                    
                    if (hasPermission) {
                        executeDatabaseAction(actionJson)
                    } else {
                        val messageType = if (action == "POST_NOTICE") "post notices" else "modify schedules"
                        Toast.makeText(requireContext(), "Permission Denied: Only batch admins can $messageType.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("ChatFragment", "Failed to check admin status for action: ${e.message}")
            }
    }

    private fun executeDatabaseAction(actionJson: String) {
        try {
            val json = org.json.JSONObject(actionJson)
            val action = json.optString("action")
            val db = FirebaseFirestore.getInstance()
            
            if (action == "POST_NOTICE") {
                val title = json.optString("title")
                val body = json.optString("body")
                val priority = json.optString("priority", "normal")
                if (title.isBlank() || body.isBlank()) return
                
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
                db.collection("users").document(uid).get()
                    .addOnSuccessListener { userDoc ->
                        if (_binding == null) return@addOnSuccessListener
                        val adminName = userDoc.getString("fullName") ?: userDoc.getString("name") ?: "Admin"
                        
                        val noticeData = hashMapOf(
                            "title" to title,
                            "body" to body,
                            "content" to body,
                            "type" to "notice",
                            "priority" to priority.lowercase(),
                            "subject" to "General",
                            "isCancel" to false,
                            "isSub" to false,
                            "isAssignment" to false,
                            "isClassTest" to false,
                            "isResource" to false,
                            "attachmentType" to "none",
                            "attachmentUrl" to "",
                            "attachmentName" to "",
                            "attachments" to emptyList<Map<String, Any>>(),
                            "postedBy" to adminName,
                            "createdBy" to uid,
                            "createdByName" to adminName,
                            "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                            "isPinned" to false,
                            "isDeleted" to false,
                            "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                        )
                        
                        db.collection("notices")
                            .add(noticeData)
                            .addOnSuccessListener { docRef ->
                                if (_binding == null) return@addOnSuccessListener
                                com.shuaib.classmate.utils.WidgetUpdater.refresh(requireContext())
                                com.shuaib.classmate.utils.NotificationSender.sendNoticeAlert(
                                    title = title,
                                    body = body,
                                    noticeId = docRef.id,
                                    onSuccess = {
                                        if (_binding != null) {
                                            Toast.makeText(requireContext(), "✅ Notice successfully posted!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onFailure = {
                                        if (_binding != null) {
                                            Toast.makeText(requireContext(), "✅ Notice posted (Notification failed).", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                            .addOnFailureListener { e ->
                                Log.e("ChatFragment", "Failed to save AI notice: ${e.message}")
                            }
                    }
                return
            }

            val day = json.optString("day").lowercase()
            if (day.isBlank()) return
            val collection = db.collection("timetable").document(day).collection("periods")
            
            when (action) {
                "CANCEL_CLASS" -> {
                    val id = json.optString("id")
                    val date = json.optString("date")
                    if (id.isBlank() || date.isBlank()) return
                    
                    val docRef = collection.document(id)
                    docRef.get().addOnSuccessListener { doc ->
                        if (_binding == null) return@addOnSuccessListener
                        if (doc.exists()) {
                            val period = doc.toObject(Period::class.java)?.copy(id = doc.id) ?: return@addOnSuccessListener
                            val updated = period.copy(
                                isCancelled = true,
                                cancelDate = date
                            )
                            docRef.set(updated).addOnSuccessListener {
                                syncAndRefreshWidget(day)
                            }
                        }
                    }
                }
                "SUBSTITUTE_CLASS" -> {
                    val id = json.optString("id")
                    val substituteTeacher = json.optString("substituteTeacher")
                    val date = json.optString("date")
                    if (id.isBlank() || substituteTeacher.isBlank() || date.isBlank()) return
                    
                    val docRef = collection.document(id)
                    docRef.get().addOnSuccessListener { doc ->
                        if (_binding == null) return@addOnSuccessListener
                        if (doc.exists()) {
                            val period = doc.toObject(Period::class.java)?.copy(id = doc.id) ?: return@addOnSuccessListener
                            val updated = period.copy(
                                isSubstitute = true,
                                substituteTeacher = substituteTeacher,
                                substituteDate = date
                            )
                            docRef.set(updated).addOnSuccessListener {
                                syncAndRefreshWidget(day)
                            }
                        }
                    }
                }
                "RESCHEDULE_CLASS" -> {
                    val id = json.optString("id")
                    val startTime = json.optString("startTime")
                    val endTime = json.optString("endTime")
                    if (id.isBlank() || startTime.isBlank() || endTime.isBlank()) return
                    
                    val docRef = collection.document(id)
                    docRef.get().addOnSuccessListener { doc ->
                        if (_binding == null) return@addOnSuccessListener
                        if (doc.exists()) {
                            val period = doc.toObject(Period::class.java)?.copy(id = doc.id) ?: return@addOnSuccessListener
                            val updated = period.copy(
                                startTime = startTime,
                                endTime = endTime
                            )
                            docRef.set(updated).addOnSuccessListener {
                                syncAndRefreshWidget(day)
                            }
                        }
                    }
                }
                "ADD_CLASS" -> {
                    val subject = json.optString("subject")
                    val teacher = json.optString("teacher")
                    val startTime = json.optString("startTime")
                    val endTime = json.optString("endTime")
                    if (subject.isBlank() || teacher.isBlank() || startTime.isBlank() || endTime.isBlank()) return
                    
                    val newDocRef = collection.document()
                    val newPeriod = Period(
                        id = newDocRef.id,
                        subject = subject,
                        teacher = teacher,
                        startTime = startTime,
                        endTime = endTime
                    )
                    newDocRef.set(newPeriod).addOnSuccessListener {
                        syncAndRefreshWidget(day)
                    }
                }
                "DELETE_CLASS" -> {
                    val id = json.optString("id")
                    if (id.isBlank()) return
                    
                    collection.document(id).delete().addOnSuccessListener {
                        syncAndRefreshWidget(day)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatFragment", "Failed to parse or execute AI classmate action: ${e.message}")
        }
    }

    private fun syncAndRefreshWidget(day: String) {
        val context = activity ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                TimetableRepository.getInstance(context.applicationContext)
                    .syncDayFromFirestore(day, Source.DEFAULT)
                
                launch(Dispatchers.Main) {
                    if (_binding != null) {
                        com.shuaib.classmate.utils.WidgetUpdater.refresh(context, syncTodayTimetable = false)
                        Toast.makeText(context, "Class schedule successfully updated!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatFragment", "Failed to sync day after class action: ${e.message}")
            }
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to ClassMate AI...")
        }
        runCatching {
            speechRecognizerLauncher.launch(intent)
        }.onFailure {
            Toast.makeText(requireContext(), "Speech recognition is not supported on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showClearHistoryDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear Chat History")
            .setMessage("Are you sure you want to delete all chat history with ClassMate AI?")
            .setPositiveButton("Clear") { _, _ -> clearChatHistory() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearChatHistory() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()
        val collection = db.collection("users").document(uid).collection("ai_chat_history")

        collection.get().addOnSuccessListener { snapshot ->
            if (snapshot.isEmpty) return@addOnSuccessListener
            val batch = db.batch()
            snapshot.documents.forEach { doc ->
                batch.delete(doc.reference)
            }
            batch.commit().addOnSuccessListener {
                adapter?.submitList(emptyList())
                updateEmptyStateVisibility(true)
                Toast.makeText(requireContext(), "Chat history cleared!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ClassMate AI Response", NoticeTextFormatter.stripMarkdown(text))
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, NoticeTextFormatter.stripMarkdown(text))
        }
        startActivity(Intent.createChooser(shareIntent, "Share response via"))
    }

    private fun toggleSpeak(message: AiMessage) {
        if (adapter?.speakingMessageId == message.id) {
            tts?.stop()
            adapter?.setSpeakingMessageId(null)
        } else {
            speak(message)
        }
    }

    private fun speak(message: AiMessage) {
        val cleanText = NoticeTextFormatter.stripMarkdown(message.text)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, message.id)
        }
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, message.id)
    }

    private fun updateEmptyStateVisibility(isEmpty: Boolean) {
        if (_binding == null) return
        binding.layoutEmptyState.isVisible = isEmpty
        binding.scrollSuggestions.isVisible = !isEmpty
    }


    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    activity?.runOnUiThread {
                        if (_binding != null) {
                            adapter?.setSpeakingMessageId(utteranceId)
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    activity?.runOnUiThread {
                        if (_binding != null && adapter?.speakingMessageId == utteranceId) {
                            adapter?.setSpeakingMessageId(null)
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    activity?.runOnUiThread {
                        if (_binding != null && adapter?.speakingMessageId == utteranceId) {
                            adapter?.setSpeakingMessageId(null)
                        }
                    }
                }
            })
        }
    }


    override fun onDestroyView() {
        chatHistoryListener?.remove()
        chatHistoryListener = null
        super.onDestroyView()
        tts?.stop()
        tts?.shutdown()
        tts = null
        _binding = null
    }
}

package com.shuaib.classmate.activities

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ActivityCreatePollBinding
import com.shuaib.classmate.utils.NotificationSender
import com.shuaib.classmate.utils.ThemeColors
import java.util.Date

class CreatePollActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreatePollBinding
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private var options = mutableListOf("", "")
    private var currentUserName = ""
    private var pollId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreatePollBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        binding.btnBack.setOnClickListener { finish() }

        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                currentUserName = doc.getString("name") ?: "Admin"
            }

        // Check if editing
        pollId = intent.getStringExtra("POLL_ID")
        val existingQuestion = intent.getStringExtra("QUESTION")
        val existingOptions = intent.getStringArrayListExtra("OPTIONS")
        val existingAllowMultiple = intent.getBooleanExtra("ALLOW_MULTIPLE", false)

        if (pollId != null) {
            binding.tvTitle.text = "Edit Poll"
            binding.btnPublish.text = "Update Poll"
            binding.etQuestion.setText(existingQuestion)
            binding.switchMultipleAnswers.isChecked = existingAllowMultiple
            if (existingOptions != null) {
                options = existingOptions.toMutableList()
            }
        }

        renderOptions()

        binding.btnAddOption.setOnClickListener {
            if (options.size < 12) {
                options.add("")
                renderOptions()
            } else {
                Toast.makeText(this, "Maximum 12 options allowed", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPublish.setOnClickListener {
            publishPoll()
        }
    }

    private fun renderOptions() {
        binding.optionsContainer.removeAllViews()
        options.forEachIndexed { index, value ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 14) }
            }

            val field = TextInputLayout(this).apply {
                hint = "Option ${index + 1}"
                boxStrokeColor = ThemeColors.primary(this@CreatePollActivity)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { weight = 1f }
            }
            val editText = TextInputEditText(this).apply {
                setTextColor(ThemeColors.textPrimary(this@CreatePollActivity))
                setText(value)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        options[index] = s.toString()
                    }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
            }
            field.addView(editText)
            row.addView(field)

            val removeButton = ImageButton(this).apply {
                val buttonSize = (44 * resources.displayMetrics.density).toInt()
                setImageResource(R.drawable.ic_close)
                setColorFilter(ThemeColors.textMuted(this@CreatePollActivity))
                background = getDrawable(android.R.drawable.list_selector_background)
                contentDescription = "Remove option"
                isVisible = options.size > 2
                layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                    setMargins(8, 0, 0, 0)
                }
                setOnClickListener {
                    if (options.size > 2) {
                        options.removeAt(index)
                        renderOptions()
                    }
                }
            }
            row.addView(removeButton)
            binding.optionsContainer.addView(row)
        }
        binding.btnAddOption.isVisible = options.size < 12
    }

    private fun publishPoll() {
        val question = binding.etQuestion.text.toString().trim()

        if (question.isEmpty()) {
            binding.etQuestion.error = "Question required"
            return
        }

        val validOptions = options.filter { it.isNotBlank() }
        if (validOptions.size < 2) {
            Toast.makeText(this, "At least 2 options required", Toast.LENGTH_SHORT).show()
            return
        }

        val hours = when (binding.rgExpiry.checkedRadioButtonId) {
            R.id.rb1Hour -> 1
            R.id.rb6Hours -> 6
            R.id.rb24Hours -> 24
            R.id.rb48Hours -> 48
            else -> 24
        }

        val expiresAt = Timestamp(Date(System.currentTimeMillis() + hours * 3600000L))

        binding.progressBar.isVisible = true
        binding.btnPublish.isEnabled = false

        val pollData = hashMapOf(
            "question" to question,
            "options" to validOptions,
            "createdBy" to currentUserName,
            "expiresAt" to expiresAt,
            "isActive" to true,
            "allowMultipleAnswers" to binding.switchMultipleAnswers.isChecked
        )

        val task = if (pollId != null) {
            db.collection("polls").document(pollId!!).update(pollData as Map<String, Any>)
        } else {
            pollData["createdAt"] = FieldValue.serverTimestamp()
            pollData["votes"] = emptyMap<String, String>()
            db.collection("polls").add(pollData)
        }

        task.addOnSuccessListener {
            if (pollId == null) {
                NotificationSender.sendPollAlert(question = question)
            }
            binding.progressBar.isVisible = false
            Toast.makeText(this, if (pollId != null) "Poll updated!" else "Poll published!", Toast.LENGTH_SHORT).show()
            finish()
        }.addOnFailureListener { e ->
            binding.progressBar.isVisible = false
            binding.btnPublish.isEnabled = true
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

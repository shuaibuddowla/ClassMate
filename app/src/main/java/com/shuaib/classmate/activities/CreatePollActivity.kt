package com.shuaib.classmate.activities

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.databinding.ActivityCreatePollBinding
import com.shuaib.classmate.utils.AppContextManager
import com.shuaib.classmate.utils.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CreatePollActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreatePollBinding
    private var options = mutableListOf("", "")
    private var pollId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreatePollBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

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
            if (options.size < 10) {
                options.add("")
                renderOptions()
            } else {
                Toast.makeText(this, "Maximum 10 options allowed", Toast.LENGTH_SHORT).show()
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
        binding.btnAddOption.isVisible = options.size < 10
    }

    private fun publishPoll() {
        val question = binding.etQuestion.text.toString().trim()

        if (question.length < 2) {
            binding.etQuestion.error = "Question required (at least 2 characters)"
            return
        }

        val validOptions = options.map { it.trim() }.filter { it.isNotBlank() }
        if (validOptions.size < 2) {
            Toast.makeText(this, "At least 2 options required", Toast.LENGTH_SHORT).show()
            return
        }
        if (validOptions.size > 10) {
            Toast.makeText(this, "Maximum 10 options allowed", Toast.LENGTH_SHORT).show()
            return
        }

        val hours = when (binding.rgExpiry.checkedRadioButtonId) {
            R.id.rb1Hour -> 1
            R.id.rb6Hours -> 6
            R.id.rb24Hours -> 24
            R.id.rb48Hours -> 48
            else -> 24
        }

        val targetBatchId = AppContextManager.getManagedBatchId()
            .ifBlank { AppContextManager.getBatchId() }

        if (targetBatchId.isBlank()) {
            Toast.makeText(this, "Batch not selected", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.isVisible = true
        binding.btnPublish.isEnabled = false

        val params = JSONObject().apply {
            put("target_batch", targetBatchId)
            put("poll_question", question)
            put("poll_options", JSONArray(validOptions))
            put("poll_description", "")
            put("is_multiple_choice", binding.switchMultipleAnswers.isChecked)
            put("is_anonymous", false)
            put("duration_hours", hours)
        }

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                withContext(Dispatchers.IO) {
                    ClassMateAuthApi.rpc("publish_batch_poll", params)
                }
                binding.progressBar.isVisible = false
                Toast.makeText(this@CreatePollActivity, "Poll published successfully!", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                binding.progressBar.isVisible = false
                binding.btnPublish.isEnabled = true
                Toast.makeText(this@CreatePollActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

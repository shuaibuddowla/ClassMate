package com.shuaib.classmate.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shuaib.classmate.databinding.ActivityAiSettingsBinding
import com.shuaib.classmate.utils.AppContextManager
import com.shuaib.classmate.utils.AppPreferences

class AiSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiSettingsBinding

    private val geminiModels = listOf(
        ModelOption("gemini-3.5-flash", "Gemini 3.5 Flash", "Fast & accurate (Recommended)", "Recommended"),
        ModelOption("gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite", "Ultra-low latency", "Ultra-Fast"),
        ModelOption("gemini-3.8-flash", "Gemini 3.8 Flash", "Advanced reasoning", "Deep Reasoning"),
        ModelOption("gemini-2.5-flash", "Gemini 2.5 Flash", "Standard baseline", "Stable"),
        ModelOption("gemini-2.5-pro", "Gemini 2.5 Pro", "Complex task reasoning", "Pro")
    )

    private data class ModelOption(
        val id: String,
        val displayName: String,
        val subtitle: String,
        val badgeText: String
    ) {
        override fun toString(): String = "$displayName - $subtitle"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupAiSettings()
        setupWorkspaceButton()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupAiSettings() {
        val prefs = AppPreferences(this)
        var isInitialized = false

        val geminiAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            geminiModels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerGeminiModel.adapter = geminiAdapter

        val currentGeminiModel = prefs.getGeminiModel()
        val geminiIndex = geminiModels.indexOfFirst { it.id == currentGeminiModel }.coerceAtLeast(0)
        binding.spinnerGeminiModel.setSelection(geminiIndex)
        binding.tvModelSpeedBadge.text = geminiModels[geminiIndex].badgeText

        binding.spinnerGeminiModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedOption = geminiModels[position]
                binding.tvModelSpeedBadge.text = selectedOption.badgeText
                if (!isInitialized) return
                runCatching {
                    prefs.setGeminiModel(selectedOption.id)
                    Toast.makeText(
                        this@AiSettingsActivity,
                        "Model updated: ${selectedOption.displayName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { e ->
                    Toast.makeText(
                        this@AiSettingsActivity,
                        "Failed to update model: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.root.post {
            isInitialized = true
        }
    }

    private fun setupWorkspaceButton() {
        binding.btnOpenAiWorkspace.setOnClickListener {
            val batchId = AppContextManager.getManagedBatchId().ifBlank {
                AppContextManager.getBatchId()
            }.ifBlank {
                com.shuaib.classmate.activities.ClassMateAcademicCache.home(this)?.optString("batch").orEmpty()
                    .ifBlank { com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi.notificationIdentity()?.optString("batch_id").orEmpty() }
            }

            if (batchId.isBlank()) {
                Toast.makeText(this, "Please select or join a batch to open ClassMate AI Assistant.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, ClassMateAiActivity::class.java).apply {
                putExtra("batch_id", batchId)
            }
            startActivity(intent)
        }
    }
}

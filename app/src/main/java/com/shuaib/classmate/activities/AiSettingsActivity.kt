package com.shuaib.classmate.activities

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shuaib.classmate.databinding.ActivityAiSettingsBinding
import com.shuaib.classmate.utils.AppPreferences

class AiSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupAiSettings()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupAiSettings() {
        val prefs = AppPreferences(this)
        var isInitialized = false

        // 1. Primary Provider Spinner
        val providers = arrayOf("Gemini", "Groq")
        val providerAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            providers
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerPrimaryProvider.adapter = providerAdapter
        val isGemini = prefs.isGeminiPrimary()
        binding.spinnerPrimaryProvider.setSelection(if (isGemini) 0 else 1)

        binding.spinnerPrimaryProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isInitialized) return
                runCatching {
                    prefs.setGeminiPrimary(position == 0)
                    Toast.makeText(
                        this@AiSettingsActivity,
                        "Primary provider set to: ${if (position == 0) "Gemini" else "Groq"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { e ->
                    Toast.makeText(
                        this@AiSettingsActivity,
                        "Failed to update provider: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 2. Gemini Model Spinner (including gemini-3.5-flash)
        val geminiModels = arrayOf("gemini-2.5-flash", "gemini-3.5-flash", "gemini-2.5-pro", "gemini-1.5-flash", "gemini-1.5-pro")
        val geminiAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            geminiModels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerGeminiModel.adapter = geminiAdapter
        val currentGeminiModel = prefs.getGeminiModel()
        val geminiIndex = geminiModels.indexOf(currentGeminiModel).coerceAtLeast(0)
        binding.spinnerGeminiModel.setSelection(geminiIndex)

        binding.spinnerGeminiModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isInitialized) return
                val selected = geminiModels[position]
                runCatching {
                    prefs.setGeminiModel(selected)
                    Toast.makeText(
                        this@AiSettingsActivity,
                        "Gemini model updated: $selected",
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

        // 3. Groq Model Spinner
        val groqModels = arrayOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "mixtral-8x7b-32768", "gemma2-9b-it")
        val groqAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            groqModels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerGroqModel.adapter = groqAdapter
        val currentGroqModel = prefs.getGroqModel()
        val groqIndex = groqModels.indexOf(currentGroqModel).coerceAtLeast(0)
        binding.spinnerGroqModel.setSelection(groqIndex)

        binding.spinnerGroqModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isInitialized) return
                val selected = groqModels[position]
                runCatching {
                    prefs.setGroqModel(selected)
                    Toast.makeText(
                        this@AiSettingsActivity,
                        "Groq model updated: $selected",
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

        // Setup flag to prevent Toast on initial load
        binding.root.post {
            isInitialized = true
        }
    }
}

package com.shuaib.classmate.activities

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ActivityForgotPasswordBinding
import com.shuaib.classmate.utils.AuthDebug
import com.shuaib.classmate.utils.AuthErrorMapper

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var cooldownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthDebug.logRuntimeConfig(this, auth, "ForgotPasswordActivity", getString(R.string.default_web_client_id))

        binding.etEmail.requestFocus()
        binding.btnBack.setOnClickListener { finish() }
        binding.tvBackToSignIn.setOnClickListener { finish() }
        binding.btnSendReset.setOnClickListener { sendResetLink() }

        binding.etEmail.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                sendResetLink()
                true
            } else {
                false
            }
        }

        // Open Gmail app (falls back to Gmail web if app not installed)
        binding.btnOpenGmail.setOnClickListener { openGmail() }
    }

    override fun onDestroy() {
        cooldownTimer?.cancel()
        super.onDestroy()
    }

    private fun sendResetLink() {
        val email = binding.etEmail.text.toString().trim()
        Log.d("AuthTrace", "1. Forgot password clicked")
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Log.d("AuthTrace", "2. Email validation failed")
            binding.tilEmail.error = "Enter a valid email"
            return
        }
        Log.d("AuthTrace", "2. Email validation passed")
        binding.tilEmail.error = null
        setLoading(true)
        Log.d("AuthTrace", "3. sendPasswordResetEmail started")
        AuthDebug.d("Forgot password reset start email=${AuthDebug.maskEmail(email)}")

        auth.sendPasswordResetEmail(email)
            .addOnSuccessListener {
                Log.d("AuthTrace", "4. Success: Reset email sent")
                AuthDebug.d("Forgot password reset email sent email=${AuthDebug.maskEmail(email)}")
                setLoading(false)
                showEmailSentSuccess(email)
                startCooldown()
            }
            .addOnFailureListener {
                Log.d("AuthTrace", "4. Failure: ${it.message}")
                Log.d("AuthTrace", "5. Exact exception: $it")
                AuthDebug.logAuthFailure("forgot_password_reset", it)
                setLoading(false)
                Snackbar.make(binding.root, AuthErrorMapper.forgotPasswordMessage(it), Snackbar.LENGTH_LONG).show()
            }
    }

    /**
     * Reveals the success/instruction panel with the sent-to email address
     * and the spam tip + Open Gmail button.
     */
    private fun showEmailSentSuccess(email: String) {
        binding.tvEmailSentTo.text =
            "A password reset link was sent to\n$email\n\nPlease open it and follow the instructions to reset your password."
        binding.cardEmailSent.isVisible = true
        // Scroll to show the card
        binding.cardEmailSent.post {
            (binding.root as? android.widget.ScrollView)?.smoothScrollTo(0, binding.cardEmailSent.top)
        }
    }

    private fun openGmail() {
        // Try launching Gmail app directly; fall back to a chooser so the
        // user can pick whichever email client they prefer.
        val gmailPackage = "com.google.android.gm"
        val gmailIntent = packageManager.getLaunchIntentForPackage(gmailPackage)
        if (gmailIntent != null) {
            startActivity(gmailIntent)
        } else {
            // Gmail not installed – open a generic email chooser
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_EMAIL)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                "Open Email App"
            )
            runCatching { startActivity(chooser) }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSendReset.isEnabled = !loading
        binding.btnSendReset.text = if (loading) "" else "Send Reset Link"
    }

    private fun startCooldown() {
        binding.btnSendReset.isEnabled = false
        cooldownTimer?.cancel()
        cooldownTimer = object : CountDownTimer(60_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                binding.btnSendReset.text = "Resend in ${millisUntilFinished / 1000}s"
            }

            override fun onFinish() {
                binding.btnSendReset.text = "Send Reset Link"
                binding.btnSendReset.isEnabled = true
            }
        }.start()
    }
}

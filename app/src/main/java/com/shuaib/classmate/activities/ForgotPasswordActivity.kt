package com.shuaib.classmate.activities

import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
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
                Snackbar.make(binding.root, "Reset link sent! Check your email.", Snackbar.LENGTH_LONG).show()
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

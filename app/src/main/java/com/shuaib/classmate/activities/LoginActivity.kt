package com.shuaib.classmate.activities

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.onesignal.OneSignal
import com.shuaib.classmate.R
import com.shuaib.classmate.chat.ChatRepository
import com.shuaib.classmate.databinding.ActivityLoginBinding
import com.shuaib.classmate.models.User
import com.shuaib.classmate.utils.AnimUtils
import com.shuaib.classmate.utils.AuthDebug
import com.shuaib.classmate.utils.AuthErrorMapper
import com.shuaib.classmate.utils.applyClickAnimation

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private var logoPulseAnimator: AnimatorSet? = null
    private var authInProgress = false

    private val googleSignInLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("AuthTrace", "Google account picker result code: ${result.resultCode}")
        AuthDebug.d("Google signin account picker returned resultCode=${result.resultCode} dataPresent=${result.data != null}")
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            Log.d("AuthTrace", "idToken present: ${!idToken.isNullOrBlank()}")
            AuthDebug.d("Google signin credential result type=${account.javaClass.name} idTokenPresent=${!idToken.isNullOrBlank()}")
            if (idToken.isNullOrBlank()) {
                Log.e("AuthTrace", "NULL_ID_TOKEN: Google account returned but idToken is null. " +
                    "This usually means the Web OAuth client ID is wrong or the OAuth consent screen is misconfigured. " +
                    "resultCode=${result.resultCode} email=${account.email?.take(3)}***")
                AuthDebug.e("Google signin token null. status=${result.resultCode}")
                setLoading(false)
                showError("Google Sign-In returned no token. Check OAuth configuration. [NULL_TOKEN]")
                shakeGoogleButton()
                return@registerForActivityResult
            }
            AuthDebug.d("Google signin account selected email=${AuthDebug.maskEmail(account.email.orEmpty())}")
            signInWithGoogleToken(idToken)
        } catch (e: ApiException) {
            val statusName = com.google.android.gms.common.api.CommonStatusCodes.getStatusCodeString(e.statusCode)
            Log.e("AuthTrace", "GoogleSignIn ApiException: statusCode=${e.statusCode} statusName=$statusName message=${e.message}")
            AuthDebug.e("Google signin account picker failed status=${e.statusCode} ($statusName)", e)
            setLoading(false)
            if (AuthErrorMapper.isGoogleSignInCancelled(e.statusCode)) {
                return@registerForActivityResult
            }
            showError(AuthErrorMapper.googleSignInPickerMessage(e.statusCode))
            shakeGoogleButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        AuthDebug.logRuntimeConfig(this, auth, "LoginActivity", getString(R.string.default_web_client_id))

        setupAnimations()

        // Google sign-in
        binding.btnGoogleSignIn.applyClickAnimation {
            signInWithGoogle()
        }

        // Email sign-in
        binding.btnEmailSignIn.setOnClickListener {
            signInWithEmail()
        }

        // Forgot password
        binding.tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }

        // Register link
        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        // Keyboard done action on password field triggers sign-in
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                signInWithEmail()
                true
            } else false
        }
    }

    private fun setupAnimations() {
        listOf(binding.ivLogo, binding.tvTitle, binding.tvSubtitle, binding.cardFeatures, binding.cardEmailSignIn, binding.layoutOrDivider, binding.cardGoogleSignIn, binding.tvSecureNotice)
            .forEachIndexed { index, view ->
                view.translationY = 60f
                view.alpha = 0f
                view.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setStartDelay(index * 80L)
                    .setDuration(450)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        startLogoBreathing()
    }

    private fun startLogoBreathing() {
        if (AnimUtils.isReduceMotionEnabled(this)) return
        val scaleX = ObjectAnimator.ofFloat(binding.ivLogo, View.SCALE_X, 1f, 1.04f, 1f).apply {
            duration = 2600
            startDelay = 700
            repeatCount = ValueAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(binding.ivLogo, View.SCALE_Y, 1f, 1.04f, 1f).apply {
            duration = 2600
            startDelay = 700
            repeatCount = ValueAnimator.INFINITE
        }
        logoPulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }
    }

    // ═══════════════════════════════════════════════════
    // EMAIL SIGN-IN
    // ═══════════════════════════════════════════════════

    private fun signInWithEmail() {
        if (authInProgress) return

        binding.tilEmail.error = null
        binding.tilPassword.error = null

        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (!validateEmailForm(email, password)) return

        Log.d("AuthTrace", "Email sign-in started")
        AuthDebug.d("Email signin start email=${AuthDebug.maskEmail(email)}")
        setLoading(true)

        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val user = result.user ?: return@addOnSuccessListener
                Log.d("AuthTrace", "Email sign-in success, uid: ${user.uid}")
                AuthDebug.d("Email signin success uid=${user.uid}")
                normalizeUserProfile(user.uid, user)
            }
            .addOnFailureListener { e ->
                Log.d("AuthTrace", "Email sign-in failure: ${e.message}")
                AuthDebug.logAuthFailure("email_signin", e)
                setLoading(false)
                showError(AuthErrorMapper.loginMessage(e))
                shakeEmailCard()
            }
    }

    private fun validateEmailForm(email: String, password: String): Boolean {
        var valid = true
        if (email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter a valid email"
            valid = false
        }
        if (password.isBlank()) {
            binding.tilPassword.error = "Enter your password"
            valid = false
        } else if (password.length < 6) {
            binding.tilPassword.error = "Password must be at least 6 characters"
            valid = false
        }
        if (!valid) shakeEmailCard()
        return valid
    }

    // ═══════════════════════════════════════════════════
    // GOOGLE SIGN-IN
    // ═══════════════════════════════════════════════════

    private fun signInWithGoogle() {
        if (authInProgress) return
        Log.d("AuthTrace", "Google sign-in button clicked")
        val clientId = getString(R.string.default_web_client_id)
        AuthDebug.d("Google signin flow started webClientIdConfigured=${clientId.isNotBlank() && !clientId.startsWith("TODO_")} summary=${AuthDebug.clientIdSummary(clientId)}")

        val configError = googleConfigError(clientId)
        if (configError != null) {
            AuthDebug.e("Google signin config invalid: $configError")
            showError(configError)
            return
        }

        setLoading(true)
        val googleSignInClient = GoogleSignIn.getClient(
            this,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(clientId)
                .requestEmail()
                .build()
        )
        googleSignInClient.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    private fun signInWithGoogleToken(idToken: String) {
        Log.d("AuthTrace", "Firebase credential exchange started")
        AuthDebug.d("Google signin Firebase credential exchange start")
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(firebaseCredential)
            .addOnSuccessListener { result ->
                val user = result.user ?: return@addOnSuccessListener
                Log.d("AuthTrace", "Firebase credential success, uid: ${user.uid}")
                AuthDebug.d("Google signin Firebase auth success uid=${user.uid} isNewUser=${result.additionalUserInfo?.isNewUser == true}")
                normalizeUserProfile(user.uid, user)
            }
            .addOnFailureListener {
                Log.d("AuthTrace", "Firebase credential failure: ${it.message}")
                AuthDebug.logAuthFailure("google_signin_firebase", it)
                setLoading(false)
                showError(AuthErrorMapper.googleMessage(it))
                shakeGoogleButton()
            }
    }

    // ═══════════════════════════════════════════════════
    // SHARED POST-AUTH FLOW
    // ═══════════════════════════════════════════════════

    private fun normalizeUserProfile(uid: String, firebaseUser: FirebaseUser) {
        Log.d("AuthTrace", "users/{uid} profile normalization started")
        val userRef = firestore.collection("users").document(uid)
        val displayName = firebaseUser.displayName?.takeIf { it.isNotBlank() }
            ?: firebaseUser.email?.substringBefore("@")
            ?: "User"
        val photoUrl = firebaseUser.photoUrl?.toString() ?: ""

        userRef.get().addOnSuccessListener { doc ->
            if (!doc.exists()) {
                val authProvider = if (firebaseUser.providerData.any { it.providerId == "google.com" }) "google" else "email"
                val profile = hashMapOf<String, Any>(
                    "uid" to uid,
                    "name" to displayName,
                    "fullName" to displayName,
                    "email" to (firebaseUser.email ?: ""),
                    "studentId" to "",
                    "department" to "CSE",
                    "photoUrl" to photoUrl,
                    "role" to "student",
                    "permissions" to User.DEFAULT_PERMISSIONS,
                    "favoriteSubjects" to emptyList<String>(),
                    "favoritePdfIds" to emptyList<String>(),
                    "authProvider" to authProvider,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                userRef.set(profile)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d("FirestoreDebug", "Created new user profile: users/$uid")
                        } else {
                            task.exception?.let { logFirestoreFailure(it) }
                        }
                        ChatRepository.init(this@LoginActivity, uid, displayName, photoUrl)
                        identifyUserAndNavigate(uid, "student", "")
                    }
                return@addOnSuccessListener
            }

            val existingData = doc.data ?: emptyMap<String, Any>()
            val role = existingData["role"] as? String ?: "student"
            val batchId = existingData["batchId"] as? String ?: ""
            val profileUpdate = hashMapOf<String, Any>()

            if (displayName.isNotBlank() && (existingData["name"] as? String).isNullOrBlank()) {
                profileUpdate["name"] = displayName
                profileUpdate["fullName"] = displayName
            }
            if (photoUrl.isNotBlank() && (existingData["photoUrl"] as? String).isNullOrBlank()) {
                profileUpdate["photoUrl"] = photoUrl
            }
            if (!existingData.containsKey("department") || (existingData["department"] as? String).isNullOrBlank()) {
                profileUpdate["department"] = "CSE"
            }
            profileUpdate["updatedAt"] = FieldValue.serverTimestamp()

            val existingPerms = doc.get("permissions") as? Map<*, *> ?: emptyMap<String, Boolean>()
            val missingPerms = User.DEFAULT_PERMISSIONS.filter { !existingPerms.containsKey(it.key) }

            fun finishNormalization() {
                ChatRepository.init(this@LoginActivity, uid, displayName, photoUrl)
                if (missingPerms.isNotEmpty()) {
                    val payload = mapOf("permissions" to missingPerms)
                    userRef.set(payload, SetOptions.merge())
                        .addOnCompleteListener {
                            identifyUserAndNavigate(uid, role, batchId)
                        }
                } else {
                    identifyUserAndNavigate(uid, role, batchId)
                }
            }

            if (profileUpdate.isNotEmpty()) {
                userRef.set(profileUpdate, SetOptions.merge())
                    .addOnCompleteListener {
                        finishNormalization()
                    }
            } else {
                finishNormalization()
            }
        }.addOnFailureListener {
            Log.d("AuthTrace", "profile fetch failure: ${it.message}")
            ChatRepository.init(this@LoginActivity, uid, displayName, photoUrl)
            navigateToMain()
        }
    }

    private fun logFirestoreFailure(e: Exception) {
        Log.e("FirestoreDebug", "FULL ERROR: ${e.message}")
        Log.e("FirestoreDebug", "ERROR CLASS: ${e.javaClass.name}")
        Log.e("FirestoreDebug", "CAUSE: ${e.cause?.message}")
    }

    private fun identifyUserAndNavigate(uid: String, role: String, batchId: String) {
        OneSignal.login(uid)
        OneSignal.User.addTag("role", role)
        OneSignal.User.addTag("uid", uid)
        if (batchId.isNotBlank()) {
            OneSignal.User.addTag("batchId", batchId)
            OneSignal.User.addTag("batch_$batchId", "true")
        }
        try {
            OneSignal.User.pushSubscription.optIn()
        } catch (_: Exception) {}

        if (batchId.isBlank()) {
            setLoading(false)
            startActivity(Intent(this, BatchSelectionActivity::class.java))
            finish()
        } else {
            setLoading(true)
            com.shuaib.classmate.utils.AppContextManager.resolveAndInitialize(uid, batchId) { resolvedBatch, resolvedSem ->
                setLoading(false)
                navigateToMain()
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // UI HELPERS
    // ═══════════════════════════════════════════════════

    private fun setLoading(loading: Boolean) {
        authInProgress = loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.layoutButtonContent.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        binding.btnGoogleSignIn.isEnabled = !loading
        binding.btnEmailSignIn.isEnabled = !loading
    }

    private fun shakeGoogleButton() {
        val animation = TranslateAnimation(-14f, 14f, 0f, 0f).apply {
            duration = 55
            repeatCount = 5
            repeatMode = Animation.REVERSE
        }
        binding.cardGoogleSignIn.startAnimation(animation)
    }

    private fun shakeEmailCard() {
        val animation = TranslateAnimation(-14f, 14f, 0f, 0f).apply {
            duration = 55
            repeatCount = 5
            repeatMode = Animation.REVERSE
        }
        binding.cardEmailSignIn.startAnimation(animation)
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun googleConfigError(clientId: String): String? {
        return when {
            FirebaseApp.getApps(this).isEmpty() -> "Firebase is not initialized."
            clientId.isBlank() || clientId.startsWith("TODO_") -> "Google Sign-In configuration missing."
            resources.getIdentifier("google_app_id", "string", packageName) == 0 -> "Google Services configuration missing."
            else -> null
        }
    }

    private fun navigateToMain() {
        Log.d("AuthTrace", "Navigation to MainActivity started")
        Toast.makeText(this, "Signed in successfully", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        finishAffinity()
    }

    override fun onDestroy() {
        logoPulseAnimator?.cancel()
        super.onDestroy()
    }
}

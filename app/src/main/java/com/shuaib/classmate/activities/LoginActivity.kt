package com.shuaib.classmate.activities

import android.content.Intent
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.onesignal.OneSignal
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ActivityLoginBinding
import com.shuaib.classmate.models.User
import com.shuaib.classmate.utils.AnimUtils
import com.shuaib.classmate.utils.AuthDebug
import com.shuaib.classmate.utils.AuthErrorMapper
import com.shuaib.classmate.utils.StudentIdUtils
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
        Log.d("AuthTrace", "3. Google account picker result code: ${result.resultCode}")
        AuthDebug.d("Google signin account picker returned resultCode=${result.resultCode} dataPresent=${result.data != null}")
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            Log.d("AuthTrace", "4. idToken present: ${!idToken.isNullOrBlank()}")
            AuthDebug.d("Google signin credential result type=${account.javaClass.name} idTokenPresent=${!idToken.isNullOrBlank()}")
            if (idToken.isNullOrBlank()) {
                AuthDebug.e("Google signin token null. status=${result.resultCode}")
                setLoading(false)
                showError("This sign-in option is temporarily unavailable.")
                shakeForm()
                return@registerForActivityResult
            }
            AuthDebug.d("Google signin account selected email=${AuthDebug.maskEmail(account.email.orEmpty())}")
            signInWithGoogleToken(idToken)
        } catch (e: ApiException) {
            Log.d("AuthTrace", "4. idToken present: false (error: ${e.statusCode})")
            AuthDebug.e("Google signin account picker failed status=${e.statusCode}", e)
            setLoading(false)
            if (AuthErrorMapper.isGoogleSignInCancelled(e.statusCode)) {
                return@registerForActivityResult
            }
            showError(AuthErrorMapper.googleSignInPickerMessage(e.statusCode))
            shakeForm()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        AuthDebug.logRuntimeConfig(this, auth, "LoginActivity", getString(R.string.default_web_client_id))
        setupAnimations()
        setupKeyboardActions()

        binding.etEmail.requestFocus()
        binding.btnLogin.applyClickAnimation { loginUser() }
        binding.tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    private fun setupKeyboardActions() {
        binding.etEmail.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.etPassword.requestFocus()
                true
            } else {
                false
            }
        }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                loginUser()
                true
            } else {
                false
            }
        }
    }

    private fun setupAnimations() {
        listOf(binding.ivLogo, binding.tvTitle, binding.cardLogin, binding.tvRegister)
            .forEachIndexed { index, view ->
                view.translationY = 80f
                view.alpha = 0f
                view.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setStartDelay(index * 90L)
                    .setDuration(450)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        startLogoBreathing()
    }

    private fun startLogoBreathing() {
        if (AnimUtils.isReduceMotionEnabled(this)) return
        val scaleX = ObjectAnimator.ofFloat(binding.ivLogo, View.SCALE_X, 1f, 1.035f, 1f).apply {
            duration = 2600
            startDelay = 700
            repeatCount = ValueAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(binding.ivLogo, View.SCALE_Y, 1f, 1.035f, 1f).apply {
            duration = 2600
            startDelay = 700
            repeatCount = ValueAnimator.INFINITE
        }
        logoPulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }
    }

    private fun loginUser() {
        if (authInProgress) return
        val identifier = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        Log.d("AuthTrace", "1. Login button clicked")
        if (!validateIdentifier(identifier) or !validatePassword(password)) {
            shakeForm()
            return
        }

        setLoading(true)
        if (isStudentId(identifier)) {
            AuthDebug.d("Email login start via studentId=${StudentIdUtils.normalize(identifier)}")
            signInWithStudentId(identifier, password)
        } else {
            AuthDebug.d("Email login start email=${AuthDebug.maskEmail(identifier)}")
            signInWithEmail(identifier, password)
        }
    }

    private fun signInWithStudentId(studentId: String, password: String) {
        val normalizedStudentId = StudentIdUtils.normalize(studentId)
        AuthDebug.d("Student ID login lookup start studentId=$normalizedStudentId")
        firestore.collection("student_id_lookup")
            .document(normalizedStudentId)
            .get()
            .addOnSuccessListener { snapshot ->
                val email = snapshot.getString("email").orEmpty()
                AuthDebug.d("Student ID login lookup success exists=${snapshot.exists()} emailPresent=${email.isNotBlank()}")
                if (email.isBlank()) {
                    setLoading(false)
                    binding.tilEmail.error = "No account found for $normalizedStudentId"
                    shakeForm()
                    return@addOnSuccessListener
                }
                signInWithEmail(email, password)
            }
            .addOnFailureListener {
                AuthDebug.logFirestoreFailure("student_id_login_lookup", it as Exception)
                setLoading(false)
                showError(it.message ?: "Could not find student ID")
                shakeForm()
            }
    }

    private fun signInWithEmail(email: String, password: String) {
        Log.d("AuthTrace", "2. FirebaseAuth signIn started")
        AuthDebug.d("Email credential exchange start email=${AuthDebug.maskEmail(email)}")
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                Log.d("AuthTrace", "3. FirebaseAuth signIn success")
                Log.d("AuthTrace", "4. currentUser uid: ${auth.currentUser?.uid.orEmpty()}")
                AuthDebug.d("Email login success uid=${auth.currentUser?.uid.orEmpty()}")
                auth.currentUser?.uid?.let { uid ->
                    normalizeUserProfile(uid)
                }
            }
            .addOnFailureListener {
                Log.d("AuthTrace", "3. FirebaseAuth signIn failure: ${it.message}")
                AuthDebug.logAuthFailure("email_login", it)
                setLoading(false)
                showError(AuthErrorMapper.loginMessage(it))
                shakeForm()
            }
    }

    private fun normalizeUserProfile(uid: String) {
        Log.d("AuthTrace", "5. users/{uid} profile normalization started")
        val userRef = firestore.collection("users").document(uid)
        val firebaseUser = auth.currentUser

        userRef.get().addOnSuccessListener { doc ->
            if (!doc.exists()) {
                val fullName = firebaseUser?.displayName
                    ?: firebaseUser?.email?.substringBefore("@")
                    ?: "User"
                val provider = if (firebaseUser?.providerData?.any { it.providerId == "google.com" } == true) "google" else "email"
                val profile = hashMapOf<String, Any>(
                    "uid" to uid,
                    "name" to fullName,
                    "fullName" to fullName,
                    "email" to (firebaseUser?.email ?: ""),
                    "studentId" to "",
                    "photoUrl" to (firebaseUser?.photoUrl?.toString() ?: ""),
                    "role" to "student",
                    "permissions" to User.DEFAULT_PERMISSIONS,
                    "authProvider" to provider,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                logFirestoreWrite("users/$uid", uid, profile)
                userRef.set(profile)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d("FirestoreDebug", "Write success: users/$uid")
                        } else {
                            task.exception?.let { logFirestoreFailure(it) }
                        }
                        identifyUserAndNavigate(uid, "student")
                    }
                return@addOnSuccessListener
            }

            val existingData = doc.data ?: emptyMap<String, Any>()
            val isGoogleUser = firebaseUser?.providerData?.any { it.providerId == "google.com" } == true
            val role = existingData["role"] as? String ?: "student"
            val profileUpdate = hashMapOf<String, Any>()
            if (isGoogleUser) {
                profileUpdate["name"] = firebaseUser?.displayName ?: ""
                profileUpdate["fullName"] = firebaseUser?.displayName ?: ""
                profileUpdate["email"] = firebaseUser?.email ?: ""
                profileUpdate["photoUrl"] = firebaseUser?.photoUrl?.toString() ?: ""
                profileUpdate["updatedAt"] = FieldValue.serverTimestamp()
            }

            val existingPerms = doc.get("permissions") as? Map<*, *> ?: emptyMap<String, Boolean>()
            val missingPerms = User.DEFAULT_PERMISSIONS.filter { !existingPerms.containsKey(it.key) }

            fun finishNormalization() {
                if (missingPerms.isNotEmpty()) {
                    val payload = mapOf("permissions" to missingPerms)
                    logFirestoreWrite("users/$uid", uid, payload)
                    userRef.set(payload, SetOptions.merge())
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                Log.d("FirestoreDebug", "Write success: users/$uid")
                            } else {
                                task.exception?.let { logFirestoreFailure(it) }
                            }
                            identifyUserAndNavigate(uid, role)
                        }
                } else {
                    identifyUserAndNavigate(uid, role)
                }
            }

            if (profileUpdate.isNotEmpty()) {
                logFirestoreWrite("users/$uid", uid, profileUpdate)
                userRef.set(profileUpdate, SetOptions.merge())
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d("FirestoreDebug", "Write success: users/$uid")
                        } else {
                            task.exception?.let { logFirestoreFailure(it) }
                        }
                        finishNormalization()
                    }
            } else {
                finishNormalization()
            }
        }.addOnFailureListener {
            Log.d("AuthTrace", "6. profile fetch failure: ${it.message}")
            navigateToMain()
        }
    }

    private fun logFirestoreWrite(path: String, uid: String, payload: Map<*, *>) {
        Log.d("FirestoreDebug", "About to write to: $path")
        Log.d("FirestoreDebug", "Auth uid: ${FirebaseAuth.getInstance().currentUser?.uid}")
        Log.d("FirestoreDebug", "Target uid: $uid")
        Log.d("FirestoreDebug", "Payload keys: ${payload.keys}")
        Log.d("FirestoreDebug", "Role value: ${payload["role"]}")
        Log.d("FirestoreDebug", "Permissions value: ${payload["permissions"]}")
    }

    private fun logFirestoreFailure(e: Exception) {
        Log.e("FirestoreDebug", "FULL ERROR: ${e.message}")
        Log.e("FirestoreDebug", "ERROR CLASS: ${e.javaClass.name}")
        Log.e("FirestoreDebug", "CAUSE: ${e.cause?.message}")
    }

    private fun identifyUserAndNavigate(uid: String, role: String) {
        ensureRoleDefaults(uid)
        OneSignal.login(uid)
        OneSignal.User.addTag("role", role)
        OneSignal.User.addTag("uid", uid)
        navigateToMain()
    }

    private fun ensureRoleDefaults(uid: String) {
        val userRef = FirebaseFirestore.getInstance().document("users/$uid")
        userRef.get()
            .addOnSuccessListener { doc ->
                if (!doc.exists() || !doc.contains("role")) {
                    val roleDefaults = hashMapOf<String, Any>(
                        "role" to "student",
                        "permissions" to User.DEFAULT_PERMISSIONS,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    userRef.set(roleDefaults, SetOptions.merge())
                        .addOnFailureListener { e ->
                            Log.e("AuthDebug", "Role defaults merge failed: ${e.message}", e)
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e("AuthDebug", "Role defaults check failed: ${e.message}", e)
            }
    }

    private fun signInWithGoogle() {
        if (authInProgress) return
        Log.d("AuthTrace", "1. Google button clicked")
        val clientId = getString(R.string.default_web_client_id)
        AuthDebug.d("Google signin flow started webClientIdConfigured=${clientId.isNotBlank() && !clientId.startsWith("TODO_")} summary=${AuthDebug.clientIdSummary(clientId)}")
        val configError = googleConfigError(clientId)
        if (configError != null) {
            AuthDebug.e("Google signin config invalid: $configError")
            showError(configError)
            return
        }

        setLoading(true)
        Log.d("AuthTrace", "2. Account picker started")
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
        Log.d("AuthTrace", "5. Firebase credential exchange started")
        AuthDebug.d("Google signin Firebase credential exchange start")
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(firebaseCredential)
            .addOnSuccessListener { result ->
                val user = result.user ?: return@addOnSuccessListener
                Log.d("AuthTrace", "6. Firebase credential success")
                Log.d("AuthTrace", "7. uid: ${user.uid}")
                Log.d("AuthTrace", "8. isNewUser: ${result.additionalUserInfo?.isNewUser == true}")
                AuthDebug.d("Google signin Firebase auth success uid=${user.uid} isNewUser=${result.additionalUserInfo?.isNewUser == true}")
                
                // Always normalize Google users to ensure they have permissions/role structure
                normalizeUserProfile(user.uid)
            }
            .addOnFailureListener {
                Log.d("AuthTrace", "6. Firebase credential failure: ${it.message}")
                AuthDebug.logAuthFailure("google_signin_firebase", it)
                setLoading(false)
                showError(AuthErrorMapper.googleMessage(it))
                shakeForm()
            }
    }

    private fun validateIdentifier(identifier: String): Boolean {
        val value = identifier.trim()
        return if (value.isBlank() || (!Patterns.EMAIL_ADDRESS.matcher(value).matches() && !isStudentId(value))) {
            binding.tilEmail.error = "Enter a valid email or student ID (example: CE25045)"
            false
        } else {
            binding.tilEmail.error = null
            true
        }
    }

    private fun validatePassword(password: String): Boolean {
        return if (password.isBlank()) {
            binding.tilPassword.error = "Enter your password"
            false
        } else {
            binding.tilPassword.error = null
            true
        }
    }

    private fun setLoading(loading: Boolean) {
        authInProgress = loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.btnGoogle.isEnabled = !loading
        binding.tvForgotPassword.isEnabled = !loading
        binding.btnLogin.text = if (loading) "" else "Sign In"
    }

    private fun shakeForm() {
        val animation = TranslateAnimation(-14f, 14f, 0f, 0f).apply {
            duration = 55
            repeatCount = 5
            repeatMode = Animation.REVERSE
        }
        binding.cardLogin.startAnimation(animation)
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun isStudentId(value: String): Boolean = StudentIdUtils.isValid(value)

    private fun googleConfigError(clientId: String): String? {
        return when {
            FirebaseApp.getApps(this).isEmpty() -> "Firebase is not initialized."
            clientId.isBlank() || clientId.startsWith("TODO_") -> "This sign-in option is temporarily unavailable."
            resources.getIdentifier("google_app_id", "string", packageName) == 0 -> "This sign-in option is temporarily unavailable."
            else -> null
        }
    }

    private fun navigateToMain() {
        Log.d("AuthTrace", "11. navigation started")
        Toast.makeText(this, "Signed in", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        finishAffinity()
    }

    override fun onDestroy() {
        logoPulseAnimator?.cancel()
        super.onDestroy()
    }
}

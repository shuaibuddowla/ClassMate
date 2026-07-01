package com.shuaib.classmate.activities

import android.content.Intent
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
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.onesignal.OneSignal
import com.shuaib.classmate.R
import com.shuaib.classmate.chat.ChatRepository
import com.shuaib.classmate.databinding.ActivityRegisterBinding
import com.shuaib.classmate.models.User
import com.shuaib.classmate.utils.AuthDebug
import com.shuaib.classmate.utils.AuthErrorMapper
import com.shuaib.classmate.utils.StudentIdUtils
import com.shuaib.classmate.utils.applyClickAnimation

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private var authInProgress = false
    private var currentStep = 0

    private val googleSignInLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("AuthTrace", "3. Google account picker result code: ${result.resultCode}")
        AuthDebug.d("Google signup account picker returned resultCode=${result.resultCode} dataPresent=${result.data != null}")
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            Log.d("AuthTrace", "4. idToken present: ${!idToken.isNullOrBlank()}")
            AuthDebug.d("Google signup credential result type=${account.javaClass.name} idTokenPresent=${!idToken.isNullOrBlank()}")
            if (idToken.isNullOrBlank()) {
                AuthDebug.e("Google signup token null. status=${result.resultCode}")
                setLoading(false)
                showError("This sign-up option is temporarily unavailable.")
                shakeForm()
                return@registerForActivityResult
            }
            AuthDebug.d("Google signup account selected email=${AuthDebug.maskEmail(account.email.orEmpty())}")
            signInWithGoogleToken(idToken)
        } catch (e: ApiException) {
            Log.d("AuthTrace", "4. idToken present: false (error: ${e.statusCode})")
            AuthDebug.e("Google signup account picker failed status=${e.statusCode}", e)
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
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        AuthDebug.logRuntimeConfig(this, auth, "RegisterActivity", getString(R.string.default_web_client_id))

        setupAnimations()
        setupPasswordStrength()
        setupKeyboardActions()
        setupStudentIdFormatting()
        showStep(0, focus = false)

        binding.btnRegister.applyClickAnimation { handlePrimaryAction() }
        binding.tvLogin.setOnClickListener {
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
    }

    private fun setupAnimations() {
        listOf(binding.cardRegister, binding.tvLogin)
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
    }

    private fun setupKeyboardActions() {
        binding.etName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.etStudentId.requestFocus()
                true
            } else {
                false
            }
        }
        binding.etStudentId.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handlePrimaryAction()
                true
            } else {
                false
            }
        }
        binding.etEmail.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.etPassword.requestFocus()
                true
            } else {
                false
            }
        }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.etConfirmPassword.requestFocus()
                true
            } else {
                false
            }
        }
        binding.etConfirmPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                registerUser()
                true
            } else {
                false
            }
        }
        listOf(
            binding.etName,
            binding.etStudentId,
            binding.etEmail,
            binding.etPassword,
            binding.etConfirmPassword
        ).forEach { field ->
            field.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    keepFieldVisible(view)
                }
            }
        }
    }

    private fun setupPasswordStrength() {
        binding.etPassword.doAfterTextChanged { updatePasswordStrength(it?.toString().orEmpty()) }
    }

    private fun setupStudentIdFormatting() {
        binding.etStudentId.filters = arrayOf(StudentIdUtils.lengthFilter)
    }

    private fun handlePrimaryAction() {
        if (authInProgress) return
        when (currentStep) {
            0 -> if (validateProfileStep()) showStep(1)
            else -> registerUser()
        }
    }

    private fun showStep(step: Int, focus: Boolean = true) {
        currentStep = step
        binding.stepProfile.visibility = if (step == 0) View.VISIBLE else View.GONE
        binding.stepAccount.visibility = if (step == 1) View.VISIBLE else View.GONE

        binding.stepDotOne.setBackgroundResource(if (step >= 0) R.drawable.bg_step_active else R.drawable.bg_step_inactive)
        binding.stepDotTwo.setBackgroundResource(if (step >= 1) R.drawable.bg_step_active else R.drawable.bg_step_inactive)

        when (step) {
            0 -> {
                binding.tvStepTitle.text = "Tell us who you are"
                binding.tvStepSubtitle.text = "Start with your name and student ID."
                binding.btnRegister.text = "Next"
                if (focus) binding.etName.requestFocus()
            }
            else -> {
                binding.tvStepTitle.text = "Set your login"
                binding.tvStepSubtitle.text = "Use your email and enter your password twice."
                binding.btnRegister.text = "Create account"
                if (focus) binding.etEmail.requestFocus()
            }
        }
        binding.scrollContent.post {
            binding.scrollContent.smoothScrollTo(0, binding.tvStepTitle.top)
        }
    }

    private fun keepFieldVisible(view: View) {
        binding.scrollContent.postDelayed({
            binding.scrollContent.smoothScrollTo(0, (view.bottom - 160).coerceAtLeast(0))
        }, 120L)
    }

    private fun registerUser() {
        if (authInProgress) return
        val name = binding.etName.text.toString().trim()
        val studentId = StudentIdUtils.normalize(binding.etStudentId.text.toString())
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirmPassword = binding.etConfirmPassword.text.toString()

        Log.d("AuthTrace", "1. Signup button clicked")
        if (!validateForm(name, studentId, email, password, confirmPassword)) {
            Log.d("AuthTrace", "2. Input validation failed")
            shakeForm()
            return
        }
        Log.d("AuthTrace", "2. Input validation passed")

        AuthDebug.d("Email signup start email=${AuthDebug.maskEmail(email)} studentId=$studentId")
        setLoading(true)
        createEmailAccount(name, studentId, email, password)
    }

    private fun createEmailAccount(name: String, studentId: String, email: String, password: String) {
        Log.d("AuthTrace", "3. FirebaseAuth createUser started")
        AuthDebug.d("Email signup Firebase createUser start email=${AuthDebug.maskEmail(email)}")
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val firebaseUser = result.user ?: return@addOnSuccessListener
                Log.d("AuthTrace", "4. FirebaseAuth createUser success")
                Log.d("AuthTrace", "5. uid created: ${firebaseUser.uid}")
                AuthDebug.d("Email signup Firebase user created=true uid=${firebaseUser.uid}")
                val profileUpdates = UserProfileChangeRequest.Builder()
                    .setDisplayName(name)
                    .build()

                firebaseUser.updateProfile(profileUpdates).addOnCompleteListener {
                    ChatRepository.init(this@RegisterActivity, firebaseUser.uid, name, firebaseUser.photoUrl?.toString() ?: "")
                    saveUserToFirestore(firebaseUser.uid, name, studentId, email, "")
                }
            }
            .addOnFailureListener {
                Log.d("AuthTrace", "4. FirebaseAuth createUser failure: ${it.message}")
                AuthDebug.logAuthFailure("email_signup_create_user", it)
                setLoading(false)
                showError(AuthErrorMapper.signupMessage(it))
                shakeForm()
            }
    }

    private fun signInWithGoogle() {
        if (authInProgress) return
        Log.d("AuthTrace", "1. Google button clicked")
        val clientId = getString(R.string.default_web_client_id)
        AuthDebug.d("Google signup flow started webClientIdConfigured=${clientId.isNotBlank() && !clientId.startsWith("TODO_")} summary=${AuthDebug.clientIdSummary(clientId)}")
        val configError = googleConfigError(clientId)
        if (configError != null) {
            AuthDebug.e("Google signup config invalid: $configError")
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
        AuthDebug.d("Google signup Firebase credential exchange start")
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(firebaseCredential)
            .addOnSuccessListener { result ->
                val user = result.user ?: return@addOnSuccessListener
                Log.d("AuthTrace", "6. Firebase credential success")
                Log.d("AuthTrace", "7. uid: ${user.uid}")
                Log.d("AuthTrace", "8. isNewUser: ${result.additionalUserInfo?.isNewUser == true}")
                AuthDebug.d("Google signup Firebase auth success uid=${user.uid} isNewUser=${result.additionalUserInfo?.isNewUser == true}")
                saveGoogleUserToFirestore(
                    uid = user.uid,
                    name = user.displayName.orEmpty(),
                    email = user.email.orEmpty(),
                    photoUrl = user.photoUrl?.toString().orEmpty(),
                    isNewUser = result.additionalUserInfo?.isNewUser == true
                )
            }
            .addOnFailureListener {
                Log.d("AuthTrace", "6. Firebase credential failure: ${it.message}")
                AuthDebug.logAuthFailure("google_signup_firebase", it)
                setLoading(false)
                showError(AuthErrorMapper.googleMessage(it))
                shakeForm()
            }
    }

    private fun saveGoogleUserToFirestore(uid: String, name: String, email: String, photoUrl: String, isNewUser: Boolean) {
        val userRef = firestore.document("users/$uid")
        val finalName = name.ifBlank { email.substringBefore("@").ifBlank { "User" } }
        Log.d("AuthTrace", "9. users/{uid} profile create/update started")
        
        fun createGoogleProfile() {
            val userMap = hashMapOf<String, Any>(
                "uid" to uid,
                "name" to finalName,
                "fullName" to finalName,
                "email" to email,
                "studentId" to "",
                "department" to "CSE",
                "photoUrl" to photoUrl,
                "role" to "student",
                "permissions" to User.DEFAULT_PERMISSIONS,
                "favoriteSubjects" to emptyList<String>(),
                "favoritePdfIds" to emptyList<String>(),
                "authProvider" to "google",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            AuthDebug.d("Firestore Google profile write start newUser=true uid=$uid")
            logFirestoreWrite("users/$uid", uid, userMap)
            userRef.set(userMap, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d("FirestoreDebug", "Write success: users/$uid")
                    Log.d("AuthTrace", "10. Profile create/update success")
                    AuthDebug.d("Firestore Google profile save success for new user uid=$uid")
                    finishAuthFlow(uid)
                }
                .addOnFailureListener { e ->
                    logFirestoreFailure(e)
                    Log.d("AuthTrace", "10. Profile create/update failure: ${e.message}")
                    AuthDebug.logFirestoreFailure("google_signup_new_profile_write", e)
                    setLoading(false)
                    showError(e.message ?: "Error saving profile")
                    shakeForm()
                }
        }

        fun updateGoogleProfile() {
            val updates = hashMapOf<String, Any>(
                "name" to finalName,
                "fullName" to finalName,
                "email" to email,
                "photoUrl" to photoUrl,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            AuthDebug.d("Firestore Google profile write start newUser=false uid=$uid")
            logFirestoreWrite("users/$uid", uid, updates)
            userRef.set(updates, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d("FirestoreDebug", "Write success: users/$uid")
                    Log.d("AuthTrace", "10. Profile update success")
                    AuthDebug.d("Firestore Google profile update success for existing user uid=$uid")
                    addMissingPermissions(uid) {
                        finishAuthFlow(uid)
                    }
                }
                .addOnFailureListener { e ->
                    logFirestoreFailure(e)
                    Log.d("AuthTrace", "10. Profile update failure: ${e.message}")
                    AuthDebug.logFirestoreFailure("google_signup_existing_profile_write", e)
                    setLoading(false)
                    showError(e.message ?: "Error saving profile")
                    shakeForm()
                }
        }

        if (isNewUser) {
            createGoogleProfile()
            return
        }

        userRef.get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    updateGoogleProfile()
                } else {
                    createGoogleProfile()
                }
            }
            .addOnFailureListener { e ->
                Log.d("AuthTrace", "10. Profile fetch failure: ${e.message}")
                AuthDebug.logFirestoreFailure("google_signup_profile_fetch", e)
                setLoading(false)
                showError(e.message ?: "Error loading profile")
                shakeForm()
            }
    }

    private fun addMissingPermissions(uid: String, onComplete: () -> Unit) {
        val userRef = FirebaseFirestore.getInstance().document("users/$uid")
        userRef.get()
            .addOnSuccessListener { doc ->
                val existingPerms = doc.get("permissions") as? Map<*, *> ?: emptyMap<String, Boolean>()
                val missingPerms = User.DEFAULT_PERMISSIONS.filter { !existingPerms.containsKey(it.key) }
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
                            onComplete()
                        }
                } else {
                    onComplete()
                }
            }
            .addOnFailureListener {
                onComplete()
            }
    }

    private fun saveUserToFirestore(uid: String, name: String, studentId: String, email: String, photoUrl: String) {
        val userMap = hashMapOf<String, Any>(
            "uid" to uid,
            "name" to name,
            "fullName" to name,
            "email" to email,
            "studentId" to studentId,
            "department" to "CSE",
            "photoUrl" to photoUrl,
            "role" to "student",
            "permissions" to User.DEFAULT_PERMISSIONS,
            "favoriteSubjects" to emptyList<String>(),
            "favoritePdfIds" to emptyList<String>(),
            "authProvider" to "email",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        val userRef = FirebaseFirestore.getInstance().document("users/$uid")

        Log.d("AuthTrace", "6. Firestore users/{uid} write started")
        logFirestoreWrite("users/$uid", uid, userMap)
        userRef.set(userMap, SetOptions.merge())
            .addOnSuccessListener {
                Log.d("FirestoreDebug", "Write success: users/$uid")
                Log.d("AuthTrace", "7. Firestore users/{uid} write success")

                if (studentId.isNotBlank() && StudentIdUtils.isValid(studentId)) {
                    val lookupRef = firestore.collection("student_id_lookup").document(studentId)
                    val lookupMap = hashMapOf<String, Any>(
                        "uid" to uid,
                        "email" to email
                    )
                    logFirestoreWrite("student_id_lookup/$studentId", uid, lookupMap)
                    lookupRef.set(lookupMap)
                        .addOnSuccessListener {
                            Log.d("FirestoreDebug", "Write success: student_id_lookup/$studentId")
                        }
                        .addOnFailureListener { e ->
                            Log.e("FirestoreDebug", "Lookup write failed (non-fatal): ${e.message}")
                            logFirestoreFailure(e)
                        }
                }

                finishAuthFlow(uid)
            }
            .addOnFailureListener { e ->
                Log.e("FirestoreDebug", "User profile write failed: ${e.message}")
                logFirestoreFailure(e)
                Log.d("AuthTrace", "7. Firestore users/{uid} write failure: ${e.message}")
                AuthDebug.logFirestoreFailure("email_signup_profile_write", e)
                AuthDebug.e("Email signup Firestore profile failed; deleting orphan auth user.")
                auth.currentUser?.delete()
                setLoading(false)
                showError(e.message ?: "Error saving profile")
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

    private fun finishAuthFlow(uid: String) {
        Log.d("AuthTrace", "8. Navigation started")
        ensureRoleDefaults(uid)
        identifyUserInOneSignal(uid)
        setLoading(false)
        
        startActivity(Intent(this, CompleteProfileActivity::class.java))
        finishAffinity()
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

    private fun validateForm(name: String, studentId: String, email: String, password: String, confirmPassword: String): Boolean {
        var valid = true
        binding.tilName.error = null
        binding.tilStudentId.error = null
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tilConfirmPassword.error = null

        if (name.length < 2) {
            binding.tilName.error = "Name must be at least 2 characters"
            valid = false
        }
        if (!StudentIdUtils.isValid(studentId)) {
            binding.tilStudentId.error = "Enter valid student ID (example: CE25045)"
            valid = false
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter a valid email"
            valid = false
        }
        if (password.length < 6) {
            binding.tilPassword.error = "Password must be at least 6 characters"
            valid = false
        }
        if (password != confirmPassword) {
            binding.tilConfirmPassword.error = "Passwords do not match"
            valid = false
        }
        return valid
    }

    private fun validateProfileStep(): Boolean {
        var valid = true
        binding.tilName.error = null
        binding.tilStudentId.error = null

        val name = binding.etName.text.toString().trim()
        val studentId = StudentIdUtils.normalize(binding.etStudentId.text.toString())
        if (name.length < 2) {
            binding.tilName.error = "Name must be at least 2 characters"
            valid = false
        }
        if (!StudentIdUtils.isValid(studentId)) {
            binding.tilStudentId.error = "Enter valid student ID (example: CE25045)"
            valid = false
        }
        if (!valid) shakeForm()
        return valid
    }

    private fun updatePasswordStrength(password: String) {
        val (label, drawable, widthDp) = when {
            password.isBlank() -> Triple("Password strength", R.drawable.bg_password_strength, 0)
            password.length >= 8 && password.any { !it.isLetterOrDigit() } -> Triple("Strong", R.drawable.bg_password_strength_strong, 180)
            password.length >= 6 && password.any { it.isDigit() } -> Triple("Medium", R.drawable.bg_password_strength_medium, 120)
            else -> Triple("Weak", R.drawable.bg_password_strength_weak, 70)
        }
        binding.tvPasswordStrength.text = label
        binding.viewPasswordStrength.setBackgroundResource(drawable)
        binding.viewPasswordStrength.updateLayoutParams {
            width = (widthDp * resources.displayMetrics.density).toInt()
        }
    }

    private fun setLoading(loading: Boolean) {
        authInProgress = loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnRegister.isEnabled = !loading
        binding.tvLogin.isEnabled = !loading
        binding.btnRegister.text = when {
            loading -> ""
            currentStep == 0 -> "Next"
            else -> "Create account"
        }
    }

    private fun shakeForm() {
        val animation = TranslateAnimation(-14f, 14f, 0f, 0f).apply {
            duration = 55
            repeatCount = 5
            repeatMode = Animation.REVERSE
        }
        binding.cardRegister.startAnimation(animation)
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun googleConfigError(clientId: String): String? {
        return when {
            FirebaseApp.getApps(this).isEmpty() -> "Firebase is not initialized."
            clientId.isBlank() || clientId.startsWith("TODO_") -> "This sign-up option is temporarily unavailable."
            resources.getIdentifier("google_app_id", "string", packageName) == 0 -> "This sign-up option is temporarily unavailable."
            else -> null
        }
    }

    private fun departmentFromStudentId(studentId: String): String {
        return studentId.takeWhile { it.isLetter() }.ifBlank { "CSE" }
    }

    private fun identifyUserInOneSignal(uid: String) {
        OneSignal.login(uid)
        OneSignal.User.addTag("role", "student")
        OneSignal.User.addTag("uid", uid)
        try {
            OneSignal.User.pushSubscription.optIn()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

package com.shuaib.classmate.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.shuaib.classmate.models.User
import com.shuaib.classmate.utils.AppPreferences

@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var hasNavigated = false
    private var isReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Keep system splash screen visible until routing is determined
        splashScreen.setKeepOnScreenCondition { !isReady }

        checkUserStatus()
    }

    private fun checkUserStatus() {
        val auth = FirebaseAuth.getInstance()
        val prefs = AppPreferences(this)
        val currentUser = auth.currentUser

        if (!prefs.isOnboardingComplete()) {
            navigate(OnboardingActivity::class.java)
        } else if (currentUser != null) {
            fetchProfileAndRoute(currentUser.uid)
        } else {
            navigate(LoginActivity::class.java)
        }
    }

    private fun fetchProfileAndRoute(uid: String) {
        val userRef = FirebaseFirestore.getInstance().collection("users").document(uid)

        val timeoutRunnable = Runnable {
            if (!hasNavigated) {
                navigate(MainActivity::class.java)
            }
        }
        handler.postDelayed(timeoutRunnable, 3000)

        userRef.get(Source.CACHE)
            .addOnSuccessListener { cached ->
                if (cached.exists()) {
                    handler.removeCallbacks(timeoutRunnable)
                    routeFromProfile(cached)
                } else {
                    fetchFromServer(userRef, timeoutRunnable)
                }
            }
            .addOnFailureListener {
                fetchFromServer(userRef, timeoutRunnable)
            }
    }

    private fun fetchFromServer(userRef: com.google.firebase.firestore.DocumentReference, timeoutRunnable: Runnable) {
        userRef.get(Source.SERVER)
            .addOnSuccessListener { doc ->
                handler.removeCallbacks(timeoutRunnable)
                if (doc.exists()) {
                    routeFromProfile(doc)
                } else {
                    navigate(MainActivity::class.java)
                }
            }
            .addOnFailureListener {
                handler.removeCallbacks(timeoutRunnable)
                navigate(MainActivity::class.java)
            }
    }

    private fun routeFromProfile(document: DocumentSnapshot) {
        try {
            navigate(MainActivity::class.java)
        } catch (e: Exception) {
            android.util.Log.e("SplashActivity", "Error parsing profile: ${e.message}")
            navigate(MainActivity::class.java)
        }
    }

    private fun navigate(destination: Class<*>) {
        if (hasNavigated || isFinishing) return
        hasNavigated = true
        isReady = true // Release system splash screen
        startActivity(Intent(this, destination))
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

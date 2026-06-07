package com.shuaib.classmate.attendance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.services.BleScannerService

object AttendanceScanManager {

    private const val TAG = "AttendanceScanManager"

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    fun startScanning(context: Context) {
        if (!hasScanPermission(context) || !hasFineLocationPermission(context)) {
            Log.d(TAG, "Scanner not started: missing BLE scan or fine location permission")
            return
        }

        val appContext = context.applicationContext
        val intent = BleScannerService.startIntent(appContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(appContext, intent)
        } else {
            appContext.startService(intent)
        }
    }

    fun stopScanning(context: Context) {
        context.applicationContext.stopService(BleScannerService.stopIntent(context.applicationContext))
    }

    fun checkActiveSessionAndStart(context: Context) {
        Log.d(TAG, "Checking for active session...")
        val uid = auth.currentUser?.uid
        if (uid == null || !hasScanPermission(context) || !hasFineLocationPermission(context)) {
            Log.d(TAG, "No active session check: uid missing or required permission missing")
            stopScanning(context)
            return
        }

        db.collection("users").document(uid).get()
            .addOnSuccessListener { userDoc ->
                val role = userDoc.getString("role") ?: "student"
                if (role != "student") {
                    Log.d(TAG, "Current user role is $role, scanner not started")
                    stopScanning(context)
                    return@addOnSuccessListener
                }

                findActiveSessionForClass(
                    onFound = { sessionId ->
                        Log.d(TAG, "Active session found: $sessionId")
                        db.collection("attendance_records")
                            .document("${sessionId}__$uid")
                            .get()
                            .addOnSuccessListener { recordDoc ->
                                if (recordDoc.exists() && recordDoc.getString("status") == "present") {
                                    Log.d(TAG, "Student already present for $sessionId, scanner not started")
                                    stopScanning(context)
                                } else {
                                    Log.d(TAG, "Starting scanner for active session: $sessionId")
                                    startScanning(context)
                                }
                            }
                            .addOnFailureListener {
                                Log.d(TAG, "Could not check existing record, starting scanner for $sessionId")
                                startScanning(context)
                            }
                    },
                    onMissing = {
                        Log.d(TAG, "No active session, scanner not started")
                        stopScanning(context)
                    }
                )
            }
            .addOnFailureListener {
                Log.d(TAG, "User role lookup failed, scanner not started: ${it.message}")
                stopScanning(context)
            }
    }

    private fun findActiveSessionForClass(
        onFound: (String) -> Unit,
        onMissing: () -> Unit
    ) {
        db.collection("attendance_sessions")
            .whereEqualTo("status", "active")
            .get()
            .addOnSuccessListener { snapshot ->
                val activeSession = snapshot.documents.firstOrNull { doc ->
                    val classId = doc.getString("classId")
                    classId.isNullOrBlank() || classId == BleAdvertiser.CLASS_ID
                }

                if (activeSession == null) {
                    onMissing()
                } else {
                    Log.d(
                        TAG,
                        "Matching active session: ${activeSession.id}, subject=${activeSession.getString("subject")}"
                    )
                    onFound(activeSession.id)
                }
            }
            .addOnFailureListener {
                Log.d(TAG, "Active session lookup failed: ${it.message}")
                onMissing()
            }
    }

    private fun hasScanPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasFineLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}

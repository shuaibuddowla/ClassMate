package com.shuaib.classmate.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.nio.ByteBuffer
import java.util.UUID

class BleScanner(
    private val context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    interface Callback {
        fun onMarkedPresent(sessionId: String)
        fun onScanFailed(message: String)
    }

    data class AttendanceBeacon(
        val sessionId: String,
        val classId: String,
        val timestamp: Long
    )

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val sessionsBeingChecked = mutableSetOf<String>()
    private val sessionsMarkedPresent = mutableSetOf<String>()

    @SuppressLint("MissingPermission")
    fun start(callback: Callback) {
        Log.d(TAG, "startScan() called")
        if (!hasScanPermission()) {
            Log.d(TAG, "startScan() blocked: Bluetooth scan permission missing")
            callback.onScanFailed("Bluetooth scan permission is missing.")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.d(TAG, "startScan() blocked: Bluetooth adapter unavailable or disabled")
            callback.onScanFailed("Bluetooth is turned off.")
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.d(TAG, "startScan() blocked: bluetoothLeScanner is null")
            callback.onScanFailed("This device does not support BLE scanning.")
            return
        }

        stop()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result, callback)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handleScanResult(it, callback) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.d(TAG, "onScanFailed: $errorCode")
                callback.onScanFailed("BLE scan failed: $errorCode")
            }
        }

        scanner?.startScan(emptyList(), settings, scanCallback)
        Log.d(TAG, "startScan() submitted with empty filter list")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!hasScanPermission()) return
        scanCallback?.let { callback ->
            Log.d(TAG, "stopScan() called")
            scanner?.stopScan(callback)
        }
        scanCallback = null
    }

    private fun handleScanResult(result: ScanResult, callback: Callback) {
        Log.d(TAG, "onScanResult: ${result.device.address} RSSI=${result.rssi}")
        Log.d(TAG, "Payload bytes: ${result.scanRecord?.bytes?.toHex()}")

        val rssiValid = result.rssi >= MIN_RSSI
        Log.d(TAG, "RSSI check: ${result.rssi} >= $MIN_RSSI = $rssiValid")
        if (!rssiValid) return

        val beacon = parseBeacon(result) ?: return
        val timestampValid = isFresh(beacon.timestamp)
        Log.d(TAG, "timestamp valid: ${beacon.timestamp} = $timestampValid")
        if (!timestampValid) return

        if (beacon.sessionId in sessionsMarkedPresent || beacon.sessionId in sessionsBeingChecked) {
            Log.d(TAG, "Session already marked or being checked: ${beacon.sessionId}")
            return
        }

        val uid = auth.currentUser?.uid ?: return
        sessionsBeingChecked.add(beacon.sessionId)

        validateAndMarkPresent(
            beacon = beacon,
            studentUid = uid,
            callback = callback
        )
    }

    private fun parseBeacon(result: ScanResult): AttendanceBeacon? {
        val scanRecord = result.scanRecord ?: return null
        val serviceData = scanRecord.getServiceData(BleConstants.SERVICE_UUID)
            ?: run {
                Log.d(TAG, "No service data for our UUID")
                return null
            }
        Log.d(TAG, "✅ Found ClassMate beacon!")

        if (serviceData.size < PAYLOAD_BYTE_COUNT) {
            Log.d(TAG, "Attendance payload too short: ${serviceData.size} bytes")
            return null
        }

        return try {
            val sessionIdBytes = serviceData.copyOfRange(0, UUID_BYTE_COUNT)
            val timeBytes = serviceData.copyOfRange(UUID_BYTE_COUNT, PAYLOAD_BYTE_COUNT)
            val uuidBuffer = ByteBuffer.wrap(sessionIdBytes)
            val sessionId = UUID(uuidBuffer.long, uuidBuffer.long).toString()
            val timestamp = ByteBuffer.wrap(timeBytes).int.toLong()

            Log.d(TAG, "Parsed sessionId: $sessionId")
            Log.d(TAG, "Parsed timestamp: $timestamp")
            Log.d(TAG, "sessionId: $sessionId")
            Log.d(TAG, "timestamp age: ${System.currentTimeMillis() / 1000 - timestamp}s")

            AttendanceBeacon(
                sessionId = sessionId,
                classId = "",
                timestamp = timestamp
            )
        } catch (e: Exception) {
            Log.w(TAG, "Invalid attendance beacon: ${e.message}")
            null
        }
    }

    private fun validateAndMarkPresent(
        beacon: AttendanceBeacon,
        studentUid: String,
        callback: Callback
    ) {
        val recordId = "${beacon.sessionId}__$studentUid"
        val sessionRef = db.collection("attendance_sessions").document(beacon.sessionId)
        val recordRef = db.collection("attendance_records").document(recordId)

        sessionRef.get()
            .addOnSuccessListener { sessionDoc ->
                val status = sessionDoc.getString("status")
                val sessionClassId = sessionDoc.getString("classId") ?: BleAdvertiser.CLASS_ID
                val classIdMatches = sessionClassId == BleAdvertiser.CLASS_ID
                Log.d(TAG, "classId match: $sessionClassId == ${BleAdvertiser.CLASS_ID} = $classIdMatches")
                if (!sessionDoc.exists() || status != "active" || sessionClassId != BleAdvertiser.CLASS_ID) {
                    Log.d(TAG, "Firestore active session check failed: exists=${sessionDoc.exists()} status=$status classId=$sessionClassId")
                    sessionsBeingChecked.remove(beacon.sessionId)
                    return@addOnSuccessListener
                }

                recordRef.get()
                    .addOnSuccessListener { recordDoc ->
                        if (recordDoc.exists() && recordDoc.getString("status") == "present") {
                            Log.d(TAG, "Student already present for session ${beacon.sessionId}")
                            sessionsBeingChecked.remove(beacon.sessionId)
                            sessionsMarkedPresent.add(beacon.sessionId)
                            callback.onMarkedPresent(beacon.sessionId)
                            return@addOnSuccessListener
                        }

                        db.collection("users").document(studentUid).get()
                            .addOnSuccessListener { userDoc ->
                                val studentName = userDoc.getString("name")
                                    ?: auth.currentUser?.displayName
                                    ?: "Student"

                                Log.d(TAG, "Writing present to Firestore...")
                                recordRef.set(
                                    mapOf(
                                        "sessionId" to beacon.sessionId,
                                        "studentUid" to studentUid,
                                        "studentName" to studentName,
                                        "status" to "present",
                                        "detectedAt" to Timestamp.now(),
                                        "source" to "auto"
                                    )
                                )
                                    .addOnSuccessListener {
                                        Log.d(TAG, "Present write completed for session ${beacon.sessionId}")
                                        sessionsBeingChecked.remove(beacon.sessionId)
                                        sessionsMarkedPresent.add(beacon.sessionId)
                                        callback.onMarkedPresent(beacon.sessionId)
                                    }
                                    .addOnFailureListener {
                                        sessionsBeingChecked.remove(beacon.sessionId)
                                        Log.w(TAG, "Attendance write skipped: ${it.message}")
                                    }
                            }
                            .addOnFailureListener {
                                sessionsBeingChecked.remove(beacon.sessionId)
                                Log.w(TAG, "Student profile lookup skipped: ${it.message}")
                            }
                    }
                    .addOnFailureListener {
                        sessionsBeingChecked.remove(beacon.sessionId)
                        Log.w(TAG, "Attendance record lookup skipped: ${it.message}")
                    }
            }
            .addOnFailureListener {
                sessionsBeingChecked.remove(beacon.sessionId)
                Log.w(TAG, "Attendance validation skipped: ${it.message}")
            }
    }

    private fun isFresh(timestamp: Long): Boolean {
        val diff = System.currentTimeMillis() - (timestamp * 1000L)
        return diff in -60000L..MAX_SESSION_AGE_MS
    }

    private fun hasScanPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "BleScanner"
        private const val MIN_RSSI = -80
        private const val MAX_SESSION_AGE_MS = 10 * 60 * 1000L
        private const val UUID_BYTE_COUNT = 16
        private const val TIMESTAMP_BYTE_COUNT = 4
        private const val PAYLOAD_BYTE_COUNT = UUID_BYTE_COUNT + TIMESTAMP_BYTE_COUNT
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = " ") { byte ->
    "%02X".format(byte)
}

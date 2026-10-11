package com.shuaib.classmate.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.shuaib.classmate.R
import com.shuaib.classmate.data.local.ClassMateDatabase
import com.shuaib.classmate.data.local.PendingAttendanceScanEntity
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.databinding.ActivityStudentAttendanceScannerBinding
import com.shuaib.classmate.workers.AttendanceSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

class ClassMateStudentAttendanceScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStudentAttendanceScannerBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var isScanned = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to scan attendance", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentAttendanceScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val courseTitle = intent.getStringExtra("EXTRA_COURSE_TITLE") ?: "Scan Attendance"
        binding.tvCourseTitle.text = courseTitle

        binding.btnBack.setOnClickListener { finish() }
        binding.btnDone.setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && !isScanned) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val scanner = BarcodeScanning.getClient()

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        if (barcode.valueType == Barcode.TYPE_TEXT || barcode.valueType == Barcode.TYPE_UNKNOWN) {
                            val raw = barcode.rawValue
                            if (!raw.isNullOrBlank() && !isScanned) {
                                handleBarcodeDetected(raw)
                                break
                            }
                        }
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun handleBarcodeDetected(raw: String) {
        try {
            var sessionId = ""
            var timeWindow: Long = 0
            var qrToken = ""

            if (raw.startsWith("{")) {
                val json = JSONObject(raw)
                sessionId = json.getString("s")
                timeWindow = json.getLong("w")
                qrToken = json.getString("t")
            } else if (raw.startsWith("cm:")) {
                val parts = raw.split(":")
                sessionId = parts[1]
                timeWindow = parts[2].toLong()
                qrToken = parts[3]
            }

            if (sessionId.isBlank() || qrToken.isBlank()) return

            isScanned = true
            runOnUiThread {
                submitAttendance(sessionId, timeWindow, qrToken)
            }
        } catch (_: Exception) {
            // Not a valid attendance QR format
        }
    }

    private fun submitAttendance(sessionId: String, timeWindow: Long, qrToken: String) {
        val isOnline = isNetworkAvailable(this)

        lifecycleScope.launch {
            if (isOnline) {
                try {
                    val params = JSONObject().apply {
                        put("target_session", sessionId)
                        put("qr_token", qrToken)
                        put("time_window", timeWindow)
                    }
                    val result = withContext(Dispatchers.IO) {
                        ClassMateAuthApi.rpc("mark_attendance", params)
                    }

                    showSuccessUi(
                        title = "Marked Present",
                        message = "Your attendance has been recorded successfully.",
                        isOffline = false
                    )
                } catch (e: Exception) {
                    val msg = e.message.orEmpty()
                    if (msg.contains("Network") || msg.contains("timeout") || msg.contains("IOException")) {
                        // Fallback to offline queue
                        saveOfflineScan(sessionId, timeWindow, qrToken)
                    } else {
                        showErrorUi(msg.ifBlank { "Invalid or expired QR code" })
                    }
                }
            } else {
                saveOfflineScan(sessionId, timeWindow, qrToken)
            }
        }
    }

    private suspend fun saveOfflineScan(sessionId: String, timeWindow: Long, qrToken: String) {
        withContext(Dispatchers.IO) {
            val db = ClassMateDatabase.getInstance(applicationContext)
            db.pendingAttendanceDao().insert(
                PendingAttendanceScanEntity(
                    sessionId = sessionId,
                    timeWindow = timeWindow,
                    qrToken = qrToken,
                    scannedAtMillis = System.currentTimeMillis()
                )
            )
            AttendanceSyncWorker.enqueue(applicationContext)
        }

        showSuccessUi(
            title = "Saved Offline",
            message = "📡 Saved locally. Attendance will sync automatically as soon as internet is restored.",
            isOffline = true
        )
    }

    private fun showSuccessUi(title: String, message: String, isOffline: Boolean) {
        binding.cardResult.visibility = View.VISIBLE
        binding.tvResultTitle.text = title
        binding.tvResultMessage.text = message

        if (isOffline) {
            binding.ivResultIcon.setImageResource(R.drawable.ic_clock)
            binding.ivResultIcon.setColorFilter(Color.parseColor("#F59E0B"))
            binding.tvResultTitle.setTextColor(Color.parseColor("#F59E0B"))
        } else {
            binding.ivResultIcon.setImageResource(R.drawable.ic_check)
            binding.ivResultIcon.setColorFilter(Color.parseColor("#10B981"))
            binding.tvResultTitle.setTextColor(Color.parseColor("#10B981"))
        }
    }

    private fun showErrorUi(error: String) {
        binding.cardResult.visibility = View.VISIBLE
        binding.ivResultIcon.setImageResource(R.drawable.ic_close)
        binding.ivResultIcon.setColorFilter(Color.parseColor("#EF4444"))
        binding.tvResultTitle.text = "Scan Failed"
        binding.tvResultTitle.setTextColor(Color.parseColor("#EF4444"))
        binding.tvResultMessage.text = error
        binding.btnDone.text = "Scan Again"
        binding.btnDone.setOnClickListener {
            binding.cardResult.visibility = View.GONE
            isScanned = false
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

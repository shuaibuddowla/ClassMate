package com.shuaib.classmate.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.UUID

class BleAdvertiser(private val context: Context) {

    data class Payload(
        val sessionId: String,
        val classId: String,
        val timestamp: Long
    )

    interface Callback {
        fun onStarted()
        fun onFailed(message: String)
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var legacyCallback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun start(payload: Payload, callback: Callback) {
        Log.d(TAG, "startAdvertising() called")
        if (!hasAdvertisePermission()) {
            Log.d(TAG, "startAdvertising() blocked: Bluetooth advertise permission is missing")
            callback.onFailed("Bluetooth advertise permission is missing.")
            return
        }

        val adapter = bluetoothAdapter
        Log.d(TAG, "BluetoothAdapter enabled: ${adapter?.isEnabled == true}")
        if (adapter == null || !adapter.isEnabled) {
            callback.onFailed("Bluetooth is turned off.")
            return
        }
        Log.d(TAG, "isMultipleAdvertisementSupported: ${adapter.isMultipleAdvertisementSupported}")

        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.d(TAG, "startAdvertising() blocked: bluetoothLeAdvertiser is null")
            callback.onFailed("This device does not support BLE advertising.")
            return
        }

        stop()

        val originalName = randomizeAdapterName(adapter, payload.sessionId)
        val advertiseData = buildAdvertiseData(payload)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        legacyCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "onStartSuccess: advertiser running")
                restoreAdapterName(adapter, originalName)
                callback.onStarted()
            }

            override fun onStartFailure(errorCode: Int) {
                Log.d(TAG, "onStartFailure errorCode: $errorCode")
                restoreAdapterName(adapter, originalName)
                callback.onFailed("BLE advertising failed: ${advertiseErrorMessage(errorCode)}")
            }
        }

        advertiser.startAdvertising(settings, advertiseData, legacyCallback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!hasAdvertisePermission()) return

        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return

        legacyCallback?.let {
            advertiser.stopAdvertising(it)
        }
        legacyCallback = null
    }

    private fun buildAdvertiseData(payload: Payload): AdvertiseData {
        val payloadBytes = buildPayloadBytes(payload.sessionId)
        Log.d(TAG, "Payload bytes: ${payloadBytes.toHex()}")

        return AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(BleConstants.SERVICE_UUID, payloadBytes)
            .build()
    }

    private fun buildPayloadBytes(sessionId: String): ByteArray {
        val uuid = UUID.fromString(sessionId)
        val uuidBytes = ByteBuffer.allocate(UUID_BYTE_COUNT)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        val timeBytes = ByteBuffer.allocate(TIMESTAMP_BYTE_COUNT)
            .putInt((System.currentTimeMillis() / 1000).toInt())
            .array()

        return uuidBytes + timeBytes
    }

    @SuppressLint("MissingPermission")
    private fun randomizeAdapterName(adapter: BluetoothAdapter, sessionId: String): String? {
        return try {
            val originalName = adapter.name
            val randomizedName = "CM-${sessionId.take(8)}"
            adapter.name = randomizedName
            Log.d(TAG, "Bluetooth adapter name randomized: $randomizedName")
            originalName
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not randomize adapter name: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Could not randomize adapter name: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun restoreAdapterName(adapter: BluetoothAdapter, originalName: String?) {
        if (originalName.isNullOrBlank()) return

        try {
            adapter.name = originalName
            Log.d(TAG, "Bluetooth adapter name restored")
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not restore adapter name: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore adapter name: ${e.message}")
        }
    }

    private fun advertiseErrorMessage(errorCode: Int): String {
        val label = when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "ADVERTISE_FAILED_DATA_TOO_LARGE"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ADVERTISE_FAILED_ALREADY_STARTED"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "ADVERTISE_FAILED_INTERNAL_ERROR"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "ADVERTISE_FAILED_FEATURE_UNSUPPORTED"
            else -> "UNKNOWN"
        }
        return "$label ($errorCode)"
    }

    private fun hasAdvertisePermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "BleAdvertiser"
        private const val UUID_BYTE_COUNT = 16
        private const val TIMESTAMP_BYTE_COUNT = 4
        const val CLASS_ID = "CSE-2021-A"
        val ATTENDANCE_SERVICE_UUID: UUID =
            BleConstants.SERVICE_UUID.uuid
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = " ") { byte ->
    "%02X".format(byte)
}

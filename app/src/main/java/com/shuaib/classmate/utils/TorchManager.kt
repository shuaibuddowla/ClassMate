package com.shuaib.classmate.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

object TorchManager {
    
    private var isTorchOn = false
    private var cameraId: String? = null
    
    fun toggle(context: Context): Boolean {
        isTorchOn = !isTorchOn
        setTorch(context, isTorchOn)
        return isTorchOn
    }
    
    fun setTorch(context: Context, enabled: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (cameraId == null) {
                cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            }
            val id = cameraId ?: return
            cameraManager.setTorchMode(id, enabled)
            isTorchOn = enabled
        } catch (e: Exception) {
            Log.e("TorchManager", "Failed to toggle torch: ${e.message}")
            isTorchOn = false
        }
    }
    
    fun isOn(): Boolean = isTorchOn
    
    fun turnOff(context: Context) {
        if (isTorchOn) {
            setTorch(context, false)
        }
    }
}

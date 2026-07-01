package com.shuaib.classmate.utils

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {
    
    private var lastShakeTime = 0L
    private var lastX = 0f
    private var lastY = 0f  
    private var lastZ = 0f
    private var isFirstUpdate = true
    
    companion object {
        private const val SHAKE_THRESHOLD = 12.0f  // Force threshold
        private const val SHAKE_COOLDOWN_MS = 1500L  // Prevent rapid toggling
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        
        if (isFirstUpdate) {
            lastX = x
            lastY = y
            lastZ = z
            isFirstUpdate = false
            return
        }
        
        val deltaX = x - lastX
        val deltaY = y - lastY
        val deltaZ = z - lastZ
        
        lastX = x
        lastY = y
        lastZ = z
        
        val acceleration = sqrt((deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toDouble()).toFloat()
        
        if (acceleration > SHAKE_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > SHAKE_COOLDOWN_MS) {
                lastShakeTime = now
                onShake()
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    fun reset() {
        isFirstUpdate = true
        lastShakeTime = 0L
    }
}

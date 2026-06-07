package com.shuaib.classmate.utils

import android.util.Base64

/**
 * A simple utility to obfuscate sensitive strings.
 * This is NOT military-grade encryption, but it stops simple string-searches 
 * and basic decompilation from revealing your keys.
 */
object Obfuscator {
    
    // A secret key used for the XOR operation. 
    // Changing this will change how strings are obfuscated.
    private const val MASK = 0xAF.toByte()

    /**
     * Obfuscates a plain string into a Base64 encoded XORed string.
     * You use this once to generate your "garbage" string.
     */
    fun obfuscate(input: String): String {
        val bytes = input.toByteArray()
        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor MASK.toInt()).toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Reverses the obfuscation to get the original string back.
     */
    fun deobfuscate(input: String): String {
        return try {
            val bytes = Base64.decode(input, Base64.NO_WRAP)
            for (i in bytes.indices) {
                bytes[i] = (bytes[i].toInt() xor MASK.toInt()).toByte()
            }
            String(bytes)
        } catch (e: Exception) {
            ""
        }
    }
}

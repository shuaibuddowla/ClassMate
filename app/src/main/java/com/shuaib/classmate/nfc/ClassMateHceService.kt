package com.shuaib.classmate.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.shuaib.classmate.activities.ClassMateAcademicCache
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Host-based Card Emulation (HCE) service for ClassMate Attendance.
 * Emulates an ISO/IEC 7816-4 smart card with AID: F0010203040506.
 * Responds to reader queries with a secure, timestamped, HMAC-signed student token.
 */
class ClassMateHceService : HostApduService() {

    companion object {
        private const val TAG = "ClassMateHce"

        // AID: F0 01 02 03 04 05 06
        private val AID_BYTES = byteArrayOf(
            0xF0.toByte(), 0x01.toByte(), 0x02.toByte(),
            0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte()
        )

        private val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00.toByte())

        private fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        fun generateHmacToken(profileId: String, studentRoll: String, timestamp: Long): String {
            val message = "$studentRoll:$profileId:$timestamp"
            val mac = Mac.getInstance("HmacSHA256")
            val key = SecretKeySpec(profileId.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
            mac.init(key)
            val hmacBytes = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
            val hmacHex = bytesToHex(hmacBytes).take(32) // First 16 bytes hex (32 chars) for compact APDU
            return "NFC:$studentRoll:$profileId:$timestamp:$hmacHex"
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            return STATUS_FAILED
        }

        val cla = commandApdu[0].toInt() and 0xFF
        val ins = commandApdu[1].toInt() and 0xFF
        val p1 = commandApdu[2].toInt() and 0xFF

        // Check if SELECT by Name (CLA=0x00, INS=0xA4, P1=0x04)
        if (cla == 0x00 && ins == 0xA4 && p1 == 0x04) {
            val lc = if (commandApdu.size > 4) commandApdu[4].toInt() and 0xFF else 0
            if (commandApdu.size >= 5 + lc) {
                val aid = commandApdu.copyOfRange(5, 5 + lc)
                if (aid.contentEquals(AID_BYTES)) {
                    Log.d(TAG, "SELECT AID matched! Generating secure attendance token...")
                    return buildAttendanceResponse()
                }
            }
        }

        return STATUS_FAILED
    }

    private fun buildAttendanceResponse(): ByteArray {
        return try {
            val homeData = ClassMateAcademicCache.home(applicationContext)
            val profile = homeData?.optJSONObject("profile")

            val profileId = profile?.optString("id")?.takeIf { it.isNotBlank() }
            val studentRoll = profile?.optString("student_id")?.takeIf { it.isNotBlank() }

            if (profileId == null || studentRoll == null) {
                Log.w(TAG, "No logged in student profile found for NFC response.")
                return "NFC:UNREGISTERED:NONE:0:0".toByteArray(StandardCharsets.UTF_8) + STATUS_SUCCESS
            }

            val timestamp = System.currentTimeMillis() / 1000
            val token = generateHmacToken(profileId, studentRoll, timestamp)
            Log.d(TAG, "Generated NFC Token: $token")

            token.toByteArray(StandardCharsets.UTF_8) + STATUS_SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "Error generating NFC response", e)
            STATUS_FAILED
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "NFC Session Deactivated. Reason: $reason")
    }
}

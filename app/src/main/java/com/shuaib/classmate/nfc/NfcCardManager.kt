package com.shuaib.classmate.nfc

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Robust NFC reading and writing engine for ClassMate MIFARE Classic 1K & standard NFC tags.
 * Supports NDEF URI encoding with zero-browser deep-linking + offline payload caching.
 */
object NfcCardManager {

    const val SCHEME = "classmate"
    const val HOST = "card"
    const val WEB_HOST = "classmatebd.vercel.app"
    const val AAR_PACKAGE = "com.shuaib.classmate"

    data class StudentCardPayload(
        val uid: String,
        val studentId: String = "",
        val name: String = "",
        val roll: String = "",
        val batch: String = "",
        val dept: String = "",
        val phone: String = "",
        val blood: String = ""
    ) {
        val hasContactInfo: Boolean get() = phone.isNotBlank() && phone != "null"
        val hasStudentInfo: Boolean get() = name.isNotBlank() || roll.isNotBlank()
    }

    /**
     * Converts a raw byte array (from Tag.id or NFC Intent) to a clean uppercase Hex UID string.
     * e.g. [0x04, 0xA1, 0xB2, 0xC3] -> "04A1B2C3"
     */
    fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02X", b))
        }
        return sb.toString()
    }

    /**
     * Extracts physical card UID from either the intent extras or Tag object.
     */
    fun extractUid(intent: Intent): String {
        // 1. Check direct EXTRA_ID
        val idBytes = intent.getByteArrayExtra(NfcAdapter.EXTRA_ID)
        if (idBytes != null && idBytes.isNotEmpty()) {
            return bytesToHex(idBytes)
        }

        // 2. Check EXTRA_TAG parcelable
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            return bytesToHex(tag.id)
        }

        // 3. Fallback: check query parameter from URI if dispatched via deep link
        val uri = intent.data
        if (uri != null) {
            val qUid = uri.getQueryParameter("uid")?.trim()
            if (!qUid.isNullOrEmpty()) return qUid.uppercase(Locale.US)
        }

        return ""
    }

    /**
     * Parses the card payload from an NFC Intent or Deep Link URI.
     * Decodes offline student information stored on the card.
     */
    fun parseCardPayload(intent: Intent): StudentCardPayload {
        val uid = extractUid(intent)

        // 1. Try URI data from Intent (e.g. classmate://card?...)
        val intentUri = intent.data
        if (intentUri != null) {
            val fromUri = parseFromUri(intentUri, uid)
            if (fromUri.hasStudentInfo) return fromUri
        }

        // 2. Try raw NDEF messages attached to the NFC intent
        val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        if (rawMessages != null) {
            for (raw in rawMessages) {
                val msg = raw as? NdefMessage ?: continue
                for (rec in msg.records) {
                    val uri = rec.toUri()
                    if (uri != null) {
                        val parsed = parseFromUri(uri, uid)
                        if (parsed.hasStudentInfo) return parsed
                    }
                }
            }
        }

        // 3. Return payload with UID only (unregistered or blank card)
        return StudentCardPayload(uid = uid)
    }

    private fun parseFromUri(uri: Uri, fallbackUid: String): StudentCardPayload {
        val uidParam = uri.getQueryParameter("uid")?.trim().orEmpty()
        val uid = if (uidParam.isNotEmpty()) uidParam.uppercase(Locale.US) else fallbackUid

        return StudentCardPayload(
            uid = uid,
            studentId = uri.getQueryParameter("id")?.trim().orEmpty(),
            name = uri.getQueryParameter("name")?.trim().orEmpty(),
            roll = uri.getQueryParameter("roll")?.trim().orEmpty(),
            batch = uri.getQueryParameter("batch")?.trim().orEmpty(),
            dept = uri.getQueryParameter("dept")?.trim().orEmpty(),
            phone = uri.getQueryParameter("phone")?.trim().orEmpty(),
            blood = uri.getQueryParameter("blood")?.trim().orEmpty()
        )
    }

    /**
     * Creates an NdefMessage containing:
     * Record 1: Deep link URI (`classmate://card?uid=...&name=...`) for 0-latency native pop.
     * Record 2: Android Application Record (`com.shuaib.classmate`) so the OS routes cleanly.
     */
    fun createNdefMessage(payload: StudentCardPayload): NdefMessage {
        val builder = Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendQueryParameter("uid", payload.uid)
            .appendQueryParameter("roll", payload.roll)
            .appendQueryParameter("name", payload.name)

        if (payload.studentId.isNotBlank()) builder.appendQueryParameter("id", payload.studentId)
        if (payload.batch.isNotBlank()) builder.appendQueryParameter("batch", payload.batch)
        if (payload.dept.isNotBlank()) builder.appendQueryParameter("dept", payload.dept)
        if (payload.phone.isNotBlank()) builder.appendQueryParameter("phone", payload.phone)
        if (payload.blood.isNotBlank()) builder.appendQueryParameter("blood", payload.blood)

        val uriRecord = NdefRecord.createUri(builder.build())
        val aarRecord = NdefRecord.createApplicationRecord(AAR_PACKAGE)

        return NdefMessage(arrayOf(uriRecord, aarRecord))
    }

    /**
     * Writes student data directly onto the physical MIFARE / NFC card.
     * Works with both formatted NDEF tags and blank unformatted tags (via NdefFormatable).
     */
    fun writeStudentCard(tag: Tag, payload: StudentCardPayload): Result<Unit> {
        val message = createNdefMessage(payload)
        val size = message.toByteArray().size

        // 1. Attempt standard NDEF write
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return try {
                ndef.connect()
                if (!ndef.isWritable) {
                    Result.failure(IllegalStateException("Card is marked read-only"))
                } else if (ndef.maxSize < size) {
                    Result.failure(IllegalStateException("Card memory too small: ${ndef.maxSize} bytes available, $size required"))
                } else {
                    ndef.writeNdefMessage(message)
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                try { ndef.close() } catch (_: Exception) {}
            }
        }

        // 2. Attempt formatting with NDEF (for blank MIFARE Classic 1K cards)
        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            return try {
                formatable.connect()
                formatable.format(message)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                try { formatable.close() } catch (_: Exception) {}
            }
        }

        return Result.failure(UnsupportedOperationException("Card does not support standard NDEF formatting"))
    }
}

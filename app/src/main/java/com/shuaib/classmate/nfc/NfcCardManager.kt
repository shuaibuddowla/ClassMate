package com.shuaib.classmate.nfc

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Robust NFC reading and writing engine for ClassMate MIFARE Classic 1K & standard NFC tags.
 * Supports NDEF URI encoding with zero-browser deep-linking + raw MIFARE Classic sector writing.
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
        val idBytes = intent.getByteArrayExtra(NfcAdapter.EXTRA_ID)
        if (idBytes != null && idBytes.isNotEmpty()) {
            return bytesToHex(idBytes)
        }

        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            return bytesToHex(tag.id)
        }

        val uri = intent.data
        if (uri != null) {
            val qUid = uri.getQueryParameter("uid")?.trim()
            if (!qUid.isNullOrEmpty()) return qUid.uppercase(Locale.US)
        }

        return ""
    }

    /**
     * Parses the card payload from an NFC Intent or Deep Link URI.
     * Decodes offline student information stored on the card (NDEF or MIFARE Classic sectors).
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

        // 3. Try reading directly from MIFARE Classic blocks (Sector 1 & 2)
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            val fromMifare = readFromMifareClassic(tag, uid)
            if (fromMifare != null && fromMifare.hasStudentInfo) {
                return fromMifare
            }
        }

        // 4. Return payload with UID only (unregistered or blank card)
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
     * Reads student details directly from MIFARE Classic 1K data blocks.
     */
    fun readFromMifareClassic(tag: Tag, uid: String): StudentCardPayload? {
        val mifare = MifareClassic.get(tag) ?: return null
        return try {
            mifare.connect()
            val key = MifareClassic.KEY_DEFAULT
            val auth1 = mifare.authenticateSectorWithKeyA(1, key) || mifare.authenticateSectorWithKeyB(1, key)
            if (auth1) {
                val rollBytes = mifare.readBlock(4)
                val nameBytes = mifare.readBlock(5)
                val phoneBytes = mifare.readBlock(6)

                val roll = String(rollBytes, StandardCharsets.UTF_8).trim('\u0000', ' ')
                val name = String(nameBytes, StandardCharsets.UTF_8).trim('\u0000', ' ')
                val phone = String(phoneBytes, StandardCharsets.UTF_8).trim('\u0000', ' ')

                var batch = ""
                var blood = ""
                val auth2 = mifare.authenticateSectorWithKeyA(2, key) || mifare.authenticateSectorWithKeyB(2, key)
                if (auth2) {
                    batch = String(mifare.readBlock(8), StandardCharsets.UTF_8).trim('\u0000', ' ')
                    blood = String(mifare.readBlock(9), StandardCharsets.UTF_8).trim('\u0000', ' ')
                }

                if (roll.isNotBlank() || name.isNotBlank()) {
                    StudentCardPayload(
                        uid = uid,
                        name = name,
                        roll = roll,
                        phone = phone,
                        batch = batch,
                        blood = blood
                    )
                } else null
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try { mifare.close() } catch (_: Exception) {}
        }
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
     * Tries 3 methods:
     * 1. Standard NDEF write
     * 2. NDEF formatting via NdefFormatable
     * 3. Direct MIFARE Classic 1K sector write (Sectors 1 & 2)
     */
    fun writeStudentCard(tag: Tag, payload: StudentCardPayload): Result<Unit> {
        val message = createNdefMessage(payload)
        val size = message.toByteArray().size

        // Method 1: Standard NDEF write
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (ndef.isWritable && ndef.maxSize >= size) {
                    ndef.writeNdefMessage(message)
                    return Result.success(Unit)
                }
            } catch (_: Exception) {
            } finally {
                try { ndef.close() } catch (_: Exception) {}
            }
        }

        // Method 2: NDEF Formatable
        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            try {
                formatable.connect()
                formatable.format(message)
                return Result.success(Unit)
            } catch (_: Exception) {
            } finally {
                try { formatable.close() } catch (_: Exception) {}
            }
        }

        // Method 3: Direct MIFARE Classic 1K block writing (Sector 1 & 2)
        val mifare = MifareClassic.get(tag)
        if (mifare != null) {
            try {
                mifare.connect()
                val key = MifareClassic.KEY_DEFAULT
                if (mifare.authenticateSectorWithKeyA(1, key) || mifare.authenticateSectorWithKeyB(1, key)) {
                    // Block 4: roll (padded to 16 bytes)
                    val rollBytes = payload.roll.toByteArray(StandardCharsets.UTF_8).copyOf(16)
                    mifare.writeBlock(4, rollBytes)

                    // Block 5: name (padded to 16 bytes)
                    val nameBytes = payload.name.toByteArray(StandardCharsets.UTF_8).copyOf(16)
                    mifare.writeBlock(5, nameBytes)

                    // Block 6: phone (padded to 16 bytes)
                    val phoneBytes = payload.phone.toByteArray(StandardCharsets.UTF_8).copyOf(16)
                    mifare.writeBlock(6, phoneBytes)

                    // Sector 2: batch & blood
                    if (mifare.authenticateSectorWithKeyA(2, key) || mifare.authenticateSectorWithKeyB(2, key)) {
                        val batchBytes = payload.batch.toByteArray(StandardCharsets.UTF_8).copyOf(16)
                        mifare.writeBlock(8, batchBytes)

                        val bloodBytes = payload.blood.toByteArray(StandardCharsets.UTF_8).copyOf(16)
                        mifare.writeBlock(9, bloodBytes)
                    }

                    return Result.success(Unit)
                }
            } catch (e: Exception) {
                return Result.failure(e)
            } finally {
                try { mifare.close() } catch (_: Exception) {}
            }
        }

        return Result.failure(UnsupportedOperationException("Card does not support standard NFC or MIFARE Classic writing"))
    }
}

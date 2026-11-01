package com.shuaib.classmate.nfc

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Trampoline / Dispatcher activity for NFC card discovery and deep links.
 * When a card is tapped:
 * - If offline: shows nothing and exits immediately (as per user requirement).
 * - If online: opens the native FoundCardBottomSheet and loads the live student profile from Supabase.
 */
class NfcCardDispatcherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }

        // 1. Skip offline: do nothing when offline
        if (!isOnline()) {
            finish()
            return
        }

        // 2. Extract card UID from NDEF Web URL (/c/<uid>), Intent extras, or Tag
        val uid = NfcCardManager.extractUid(intent)
        if (uid.isBlank()) {
            finish()
            return
        }

        val payload = NfcCardManager.StudentCardPayload(uid = uid)
        FoundCardBottomSheet(this, payload) {
            finish()
        }.show()
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

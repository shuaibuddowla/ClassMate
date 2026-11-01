package com.shuaib.classmate.nfc

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Trampoline / Dispatcher activity for NFC card discovery and deep links.
 * Immediately opens the native FoundCardBottomSheet over the current screen without opening a browser.
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

        val payload = NfcCardManager.parseCardPayload(intent)
        FoundCardBottomSheet(this, payload) {
            finish()
        }.show()
    }
}

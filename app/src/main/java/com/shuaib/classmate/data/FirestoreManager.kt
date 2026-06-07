package com.shuaib.classmate.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings

object FirestoreManager {
    private var _db: FirebaseFirestore? = null
    val db: FirebaseFirestore
        get() {
            if (_db == null) {
                _db = FirebaseFirestore.getInstance()
            }
            return _db!!
        }

    fun enableOfflinePersistence() {
        try {
            val firestore = FirebaseFirestore.getInstance()
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                .build()
            firestore.firestoreSettings = settings
            _db = firestore
        } catch (e: Exception) {
            android.util.Log.w("FirestoreManager", "Firestore settings already set or failed: ${e.message}")
        }
    }
}

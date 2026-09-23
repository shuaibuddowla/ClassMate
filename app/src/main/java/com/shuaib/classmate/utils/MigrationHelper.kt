package com.shuaib.classmate.utils

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.shuaib.classmate.models.Batch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object MigrationHelper {
    private const val TAG = "MigrationHelper"
    private val scope = CoroutineScope(Dispatchers.IO)

    fun runInitialSeedAndMigration(db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
        scope.launch {
            try {
                seedPredefinedBatches(db)
                migrateLegacyCse22Data(db)
            } catch (e: Exception) {
                Log.e(TAG, "Error in migration / seed: ${e.message}", e)
            }
        }
    }

    private suspend fun seedPredefinedBatches(db: FirebaseFirestore) {
        val batchWrites = db.batch()
        for (batch in Batch.PREDEFINED_BATCHES) {
            val docRef = db.collection("batches").document(batch.id)
            val snapshot = docRef.get().await()
            if (!snapshot.exists()) {
                val data = mapOf(
                    "id" to batch.id,
                    "name" to batch.name,
                    "activeSemesterId" to batch.activeSemesterId,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "createdBy" to "system"
                )
                batchWrites.set(docRef, data, SetOptions.merge())
            }
        }
        batchWrites.commit().await()
        Log.d(TAG, "Predefined batches verified.")
    }

    private suspend fun migrateLegacyCse22Data(db: FirebaseFirestore) {
        val targetBatchRef = db.collection("batches").document("cse22")

        // 1. Migrate notices
        val existingBatchNotices = targetBatchRef.collection("notices").limit(1).get().await()
        if (existingBatchNotices.isEmpty) {
            val rootNotices = db.collection("notices").limit(100).get().await()
            if (!rootNotices.isEmpty) {
                Log.d(TAG, "Migrating ${rootNotices.size()} legacy notices into cse22...")
                val noticeBatch = db.batch()
                rootNotices.documents.forEach { doc ->
                    val data = doc.data?.toMutableMap() ?: mutableMapOf()
                    data["batchId"] = "cse22"
                    val destDoc = targetBatchRef.collection("notices").document(doc.id)
                    noticeBatch.set(destDoc, data, SetOptions.merge())
                }
                noticeBatch.commit().await()
            }
        }

        // 2. Migrate timetable (2nd semester)
        val days = listOf("saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday")
        val sem2TimetableRef = targetBatchRef.collection("semesters").document("2nd").collection("timetable")
        for (day in days) {
            val destPeriods = sem2TimetableRef.document(day).collection("periods").limit(1).get().await()
            if (destPeriods.isEmpty) {
                val legacyPeriods = db.collection("timetable").document(day).collection("periods").get().await()
                if (!legacyPeriods.isEmpty) {
                    val timetableBatch = db.batch()
                    legacyPeriods.documents.forEach { doc ->
                        val data = doc.data?.toMutableMap() ?: mutableMapOf()
                        val destPeriodDoc = sem2TimetableRef.document(day).collection("periods").document(doc.id)
                        timetableBatch.set(destPeriodDoc, data, SetOptions.merge())
                    }
                    timetableBatch.commit().await()
                }
            }
        }

        // 3. Migrate library files (2nd semester)
        val sem2LibraryRef = targetBatchRef.collection("semesters").document("2nd").collection("library")
        val existingLibrary = sem2LibraryRef.limit(1).get().await()
        if (existingLibrary.isEmpty) {
            val legacyFiles = db.collection("library_files").limit(200).get().await()
            if (!legacyFiles.isEmpty) {
                Log.d(TAG, "Migrating ${legacyFiles.size()} legacy library files into cse22...")
                val libraryBatch = db.batch()
                legacyFiles.documents.forEach { doc ->
                    val data = doc.data?.toMutableMap() ?: mutableMapOf()
                    data["batchId"] = "cse22"
                    data["semester"] = "2nd"
                    val destDoc = sem2LibraryRef.document(doc.id)
                    libraryBatch.set(destDoc, data, SetOptions.merge())
                }
                libraryBatch.commit().await()
            }
        }

        Log.d(TAG, "Legacy cse22 data migration check completed.")
    }
}

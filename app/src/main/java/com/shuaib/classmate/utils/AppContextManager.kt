package com.shuaib.classmate.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.onesignal.OneSignal
import com.shuaib.classmate.data.local.ClassMateDatabase
import com.shuaib.classmate.models.Batch
import com.shuaib.classmate.repositories.NoticeRepository
import com.shuaib.classmate.repositories.TimetableRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AppContextState(
    val uid: String = "",
    val batchId: String = "",
    val semesterId: String = "",
    val role: String = "student",
    val adminBatchIds: List<String> = emptyList(),
    val managedBatchId: String = ""
) {
    fun isGlobalSuperAdmin(): Boolean = role == "global_super_admin" || role == "global_superadmin"

    fun isSuperAdmin(): Boolean = isGlobalSuperAdmin() || role == "superadmin" || role == "super_admin"

    fun isAdmin(): Boolean = isSuperAdmin() || role == "admin"

    fun canManageBatch(targetBatch: String): Boolean {
        if (isGlobalSuperAdmin()) return true
        if (role == "superadmin" || role == "super_admin" || role == "admin") {
            return adminBatchIds.contains(targetBatch.lowercase())
        }
        return false
    }
}

object AppContextManager {
    private const val TAG = "AppContextManager"
    private const val PREFS_NAME = "classmate_app_context"
    private const val KEY_UID = "ctx_uid"
    private const val KEY_BATCH_ID = "ctx_batch_id"
    private const val KEY_SEMESTER_ID = "ctx_semester_id"
    private const val KEY_ROLE = "ctx_role"
    private const val KEY_ADMIN_BATCHES = "ctx_admin_batches"
    private const val KEY_MANAGED_BATCH_ID = "ctx_managed_batch_id"

    const val DEFAULT_BATCH = "cse22"
    const val DEFAULT_SEMESTER = "3rd"

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private var userDocListener: ListenerRegistration? = null
    private var batchDocListener: ListenerRegistration? = null
    private var lastTaggedBatchId: String = ""

    private val _stateFlow = MutableStateFlow(AppContextState())
    val appContextFlow: StateFlow<AppContextState> = _stateFlow.asStateFlow()

    private val _activeBatchFlow = MutableStateFlow("")
    val activeBatchFlow: StateFlow<String> = _activeBatchFlow.asStateFlow()

    private val _activeSemesterFlow = MutableStateFlow("")
    val activeSemesterFlow: StateFlow<String> = _activeSemesterFlow.asStateFlow()

    private val _managedBatchFlow = MutableStateFlow("")
    val managedBatchFlow: StateFlow<String> = _managedBatchFlow.asStateFlow()

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val savedUid = prefs?.getString(KEY_UID, "") ?: ""
        val savedBatch = prefs?.getString(KEY_BATCH_ID, "") ?: ""
        val savedSem = prefs?.getString(KEY_SEMESTER_ID, "") ?: ""
        val savedRole = prefs?.getString(KEY_ROLE, "student") ?: "student"
        val savedAdminBatches = prefs?.getStringSet(KEY_ADMIN_BATCHES, emptySet())?.toList() ?: emptyList()
        val savedManagedBatch = prefs?.getString(KEY_MANAGED_BATCH_ID, savedBatch) ?: savedBatch

        val restored = AppContextState(
            uid = savedUid,
            batchId = normalizeBatch(savedBatch),
            semesterId = savedSem.takeIf { it.isNotBlank() }?.let { SemesterManager.normalizeSemester(it) } ?: "",
            role = savedRole,
            adminBatchIds = savedAdminBatches.map { normalizeBatch(it) },
            managedBatchId = normalizeBatch(savedManagedBatch)
        )
        updateState(restored)

        val currentAuthUid = FirebaseAuth.getInstance().currentUser?.uid
        if (!currentAuthUid.isNullOrBlank()) {
            attachUser(currentAuthUid)
        }
    }

    fun attachUser(uid: String) {
        if (uid.isBlank()) return
        Log.d(TAG, "Attaching user context for $uid")

        val current = _stateFlow.value
        if (current.uid != uid) {
            updateState(current.copy(uid = uid))
            prefs?.edit()?.putString(KEY_UID, uid)?.apply()
        }

        startUserListener(uid)
    }

    fun detachUser() {
        userDocListener?.remove()
        batchDocListener?.remove()
        userDocListener = null
        batchDocListener = null
        prefs?.edit()?.clear()?.apply()
        updateState(AppContextState())
    }

    fun getUid(): String = _stateFlow.value.uid

    fun getBatchId(): String = _stateFlow.value.batchId

    fun getActiveSemester(): String = _stateFlow.value.semesterId

    fun getSemesterId(): String = _stateFlow.value.semesterId

    fun getRole(): String = _stateFlow.value.role

    fun getAdminBatchIds(): List<String> = _stateFlow.value.adminBatchIds

    fun getManagedBatchId(): String = _stateFlow.value.managedBatchId

    fun setManagedBatchId(batchId: String) {
        val norm = normalizeBatch(batchId)
        val current = _stateFlow.value
        if (norm.isBlank() || !current.canManageBatch(norm)) {
            Log.w(TAG, "Rejected unmanaged batch selection: $norm")
            return
        }
        if (current.managedBatchId != norm) {
            updateState(current.copy(managedBatchId = norm))
            prefs?.edit()?.putString(KEY_MANAGED_BATCH_ID, norm)?.apply()
        }
    }

    fun setSemesterId(semesterId: String) {
        val norm = SemesterManager.normalizeSemester(semesterId)
        val current = _stateFlow.value
        if (current.semesterId != norm) {
            updateState(current.copy(semesterId = norm))
            prefs?.edit()?.putString(KEY_SEMESTER_ID, norm)?.apply()
        }
    }

    fun setBatchId(batchId: String) {
        val norm = normalizeBatch(batchId)
        val current = _stateFlow.value
        if (current.batchId != norm) {
            updateState(current.copy(batchId = norm, managedBatchId = norm))
            prefs?.edit()?.putString(KEY_BATCH_ID, norm)?.putString(KEY_MANAGED_BATCH_ID, norm)?.apply()
            updatePushNotificationBatchTag(norm)
            startBatchListener(norm)
        }
    }

    fun resolveAndInitialize(
        uid: String,
        batchId: String,
        onResolved: (resolvedBatchId: String, resolvedSemesterId: String) -> Unit
    ) {
        val normBatch = normalizeBatch(batchId)
        val db = FirebaseFirestore.getInstance()
        val batchRef = db.collection("batches").document(normBatch)

        batchRef.get()
            .addOnSuccessListener { doc ->
                val sem = if (doc.exists()) {
                    SemesterManager.normalizeSemester(
                        doc.getString("activeSemesterId") ?: doc.getString("activeSemester") ?: DEFAULT_SEMESTER
                    )
                } else {
                    Log.e(TAG, "Selected batch does not exist: $normBatch")
                    return@addOnSuccessListener
                }

                attachUser(uid)
                setBatchId(normBatch)
                setSemesterId(sem)
                onResolved(normBatch, sem)
            }
            .addOnFailureListener {
                Log.e(TAG, "Unable to resolve batch $normBatch", it)
            }
    }

    fun normalizeBatch(raw: String?): String {
        val clean = raw?.trim()?.lowercase() ?: ""
        return when {
            clean.startsWith("cse-") -> clean.replace("-", "")
            clean.startsWith("cse_") -> clean.replace("_", "")
            clean.startsWith("cse ") -> clean.replace(" ", "")
            clean.isNotBlank() -> clean
            else -> ""
        }
    }

    private fun startUserListener(uid: String) {
        userDocListener?.remove()
        val db = FirebaseFirestore.getInstance()
        userDocListener = db.collection("users").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Error listening to users/$uid: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val batch = normalizeBatch(snapshot.getString("batchId"))
                    if (batch.isBlank()) return@addSnapshotListener
                    val role = snapshot.getString("role") ?: "student"
                    val adminBatches = (snapshot.get("adminBatchIds") as? List<*>)
                        ?.mapNotNull { it?.toString()?.let { b -> normalizeBatch(b) } }
                        .orEmpty()

                    val current = _stateFlow.value
                    val managed = if (current.managedBatchId.isNotBlank() && (adminBatches.contains(current.managedBatchId) || role == "global_super_admin" || role == "global_superadmin")) {
                        current.managedBatchId
                    } else {
                        batch
                    }

                    val updated = current.copy(
                        uid = uid,
                        batchId = batch,
                        role = role,
                        adminBatchIds = adminBatches,
                        managedBatchId = managed
                    )
                    updateState(updated)
                    persistState(updated)

                    updatePushNotificationBatchTag(batch)
                    startBatchListener(batch)
                }
            }
    }

    private fun startBatchListener(batchId: String) {
        val normBatch = normalizeBatch(batchId)
        batchDocListener?.remove()
        val db = FirebaseFirestore.getInstance()
        batchDocListener = db.collection("batches").document(normBatch)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Error listening to batches/$normBatch: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val activeSem = SemesterManager.normalizeSemester(
                        snapshot.getString("activeSemesterId") ?: snapshot.getString("activeSemester") ?: DEFAULT_SEMESTER
                    )
                    handleSemesterChange(activeSem)
                }
            }
    }

    private fun handleSemesterChange(newSemester: String) {
        val current = _stateFlow.value
        if (newSemester != current.semesterId) {
            Log.i(TAG, "Batch ${current.batchId} semester switched to $newSemester")
            val updated = current.copy(semesterId = newSemester)
            updateState(updated)
            prefs?.edit()?.putString(KEY_SEMESTER_ID, newSemester)?.apply()

            val ctx = appContext ?: return
            scope.launch {
                try {
                    val db = ClassMateDatabase.getInstance(ctx)
                    db.timetableDao().clearSemester(current.batchId, current.semesterId)
                    TimetableRepository.getInstance(ctx).syncAllFromFirestore()
                    WidgetUpdater.refresh(ctx)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling semester sync: ${e.message}", e)
                }
            }
        }
    }

    fun updateBatchActiveSemester(
        batchId: String,
        newSemester: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val normBatch = normalizeBatch(batchId)
        val normSem = SemesterManager.normalizeSemester(newSemester)
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

        val data = mapOf(
            "activeSemesterId" to normSem,
            "updatedAt" to FieldValue.serverTimestamp(),
            "updatedBy" to uid
        )

        FirebaseFirestore.getInstance().collection("batches").document(normBatch)
            .set(data, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                if (normBatch == _stateFlow.value.batchId) {
                    handleSemesterChange(normSem)
                }
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed updating batch active semester: ${e.message}", e)
                onFailure(e)
            }
    }

    private fun updatePushNotificationBatchTag(batchId: String) {
        try {
            if (lastTaggedBatchId.isNotBlank() && lastTaggedBatchId != batchId) {
                OneSignal.User.removeTag("batch_$lastTaggedBatchId")
            }
            OneSignal.User.addTag("batchId", batchId)
            OneSignal.User.addTag("batch_$batchId", "true")
            lastTaggedBatchId = batchId
        } catch (e: Exception) {
            Log.w(TAG, "Error setting OneSignal batch tags: ${e.message}")
        }
    }

    private fun updateState(newState: AppContextState) {
        _stateFlow.value = newState
        _activeBatchFlow.value = newState.batchId
        _activeSemesterFlow.value = newState.semesterId
        _managedBatchFlow.value = newState.managedBatchId
    }

    private fun persistState(state: AppContextState) {
        prefs?.edit()
            ?.putString(KEY_UID, state.uid)
            ?.putString(KEY_BATCH_ID, state.batchId)
            ?.putString(KEY_SEMESTER_ID, state.semesterId)
            ?.putString(KEY_ROLE, state.role)
            ?.putStringSet(KEY_ADMIN_BATCHES, state.adminBatchIds.toSet())
            ?.putString(KEY_MANAGED_BATCH_ID, state.managedBatchId)
            ?.apply()
    }
}

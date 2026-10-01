package com.shuaib.classmate.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.shuaib.classmate.models.Notice
import com.shuaib.classmate.repositories.NoticeRepository
import com.shuaib.classmate.utils.AppContextManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class NoticeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NoticeRepository.getInstance(application)
    private var realtimeListener: ListenerRegistration? = null
    private val _showOlder = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val _historyLimit = kotlinx.coroutines.flow.MutableStateFlow(80)
    private var olderBefore = NoticeRepository.currentWeekStart()
    private val _isLoadingOlder = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder

    val notices: StateFlow<List<Notice>> = combine(
        AppContextManager.activeBatchFlow, _showOlder, _historyLimit
    ) { batch, showOlder, historyLimit -> Triple(batch, showOlder, historyLimit) }
    .flatMapLatest { (batch, showOlder, historyLimit) ->
        if (showOlder) repository.observeNotices(batch, historyLimit) else repository.observeCurrentWeekNotices(batch)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isRefreshing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    init {
        viewModelScope.launch {
            AppContextManager.activeBatchFlow.collect { batch ->
                startRealtimeSync(batch)
                runCatching { repository.syncFromSupabase(batch) }
                    .onFailure { android.util.Log.w("NoticeViewModel", "V2 notice sync failed", it) }
                runCatching { repository.syncFromFirestore(batch, Source.DEFAULT) }
            }
        }
    }

    private fun startRealtimeSync(batchId: String = AppContextManager.getBatchId()) {
        realtimeListener?.remove()
        realtimeListener = repository.startRealtimeSync(viewModelScope, batchId)
    }

    fun refresh() {
        val batch = AppContextManager.getBatchId()
        repository.enqueueNetworkSync()
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                runCatching { repository.syncFromSupabase(batch) }
                    .onFailure { android.util.Log.w("NoticeViewModel", "V2 notice refresh failed", it) }
                repository.syncFromFirestore(batch, Source.SERVER)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadOlderNotices() {
        if (_isLoadingOlder.value) return
        val batch = AppContextManager.getBatchId()
        viewModelScope.launch {
            _isLoadingOlder.value = true
            try {
                val oldest = repository.syncOlderNotices(batch, olderBefore)
                if (oldest != null && oldest.isBefore(olderBefore)) olderBefore = oldest
                _historyLimit.value += 40
                _showOlder.value = true
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }

    override fun onCleared() {
        realtimeListener?.remove()
        super.onCleared()
    }
}

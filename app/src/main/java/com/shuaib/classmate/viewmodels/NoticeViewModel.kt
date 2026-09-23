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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class NoticeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NoticeRepository.getInstance(application)
    private var realtimeListener: ListenerRegistration? = null

    val notices: StateFlow<List<Notice>> = AppContextManager.activeBatchFlow
        .flatMapLatest { batch -> repository.observeNotices(batch) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isRefreshing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    init {
        viewModelScope.launch {
            AppContextManager.activeBatchFlow.collect { batch ->
                startRealtimeSync(batch)
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
                repository.syncFromFirestore(batch, Source.SERVER)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    override fun onCleared() {
        realtimeListener?.remove()
        super.onCleared()
    }
}

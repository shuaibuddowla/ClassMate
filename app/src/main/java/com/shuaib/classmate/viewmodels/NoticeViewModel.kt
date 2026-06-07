package com.shuaib.classmate.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.shuaib.classmate.models.Notice
import com.shuaib.classmate.repositories.NoticeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NoticeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NoticeRepository.getInstance(application)
    private var realtimeListener: ListenerRegistration? = null

    val notices: StateFlow<List<Notice>> = repository.observeNotices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isRefreshing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    init {
        viewModelScope.launch {
            runCatching { repository.syncFromFirestore(com.google.firebase.firestore.Source.CACHE) }
        }
        startRealtimeSync()
    }

    private fun startRealtimeSync() {
        realtimeListener?.remove()
        realtimeListener = repository.startRealtimeSync(viewModelScope)
    }

    fun refresh() {
        // We still keep refresh for manual triggers, but realtime handles the rest
        repository.enqueueNetworkSync()
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repository.syncFromFirestore(com.google.firebase.firestore.Source.SERVER)
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

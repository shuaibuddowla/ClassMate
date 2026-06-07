package com.shuaib.classmate.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.Source
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.repositories.TimetableRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class TimetableViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TimetableRepository.getInstance(application)

    fun observePeriods(day: String): Flow<List<Period>> = repository.observePeriods(day)

    fun refreshDay(day: String) {
        repository.enqueueNetworkSync()
        viewModelScope.launch {
            runCatching { repository.syncDayFromFirestore(day, Source.CACHE) }
            runCatching { repository.syncDayFromFirestore(day, Source.SERVER) }
        }
    }

    fun refreshAll() {
        repository.enqueueNetworkSync()
        viewModelScope.launch {
            runCatching { repository.syncAllFromFirestore(Source.CACHE) }
            runCatching { repository.syncAllFromFirestore(Source.SERVER) }
        }
    }
}

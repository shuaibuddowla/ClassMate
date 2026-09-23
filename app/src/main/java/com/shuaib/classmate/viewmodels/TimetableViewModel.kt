package com.shuaib.classmate.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.Source
import com.shuaib.classmate.models.Period
import com.shuaib.classmate.repositories.TimetableRepository
import com.shuaib.classmate.utils.AppContextManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class TimetableViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TimetableRepository.getInstance(application)

    fun observePeriods(day: String): Flow<List<Period>> {
        return AppContextManager.activeBatchFlow.flatMapLatest { batch ->
            AppContextManager.activeSemesterFlow.flatMapLatest { sem ->
                repository.observePeriods(day, sem, batch)
            }
        }
    }

    fun refreshDay(
        day: String,
        semester: String = AppContextManager.getSemesterId(),
        batch: String = AppContextManager.getBatchId()
    ) {
        repository.enqueueNetworkSync()
        viewModelScope.launch {
            runCatching { repository.syncDayFromFirestore(day, semester, batch, Source.CACHE) }
            runCatching { repository.syncDayFromFirestore(day, semester, batch, Source.SERVER) }
        }
    }

    fun refreshAll(
        semester: String = AppContextManager.getSemesterId(),
        batch: String = AppContextManager.getBatchId()
    ) {
        repository.enqueueNetworkSync()
        viewModelScope.launch {
            runCatching { repository.syncAllFromFirestore(semester, batch, Source.CACHE) }
            runCatching { repository.syncAllFromFirestore(semester, batch, Source.SERVER) }
        }
    }
}

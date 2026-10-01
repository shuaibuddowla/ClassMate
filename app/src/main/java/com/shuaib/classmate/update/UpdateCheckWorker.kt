package com.shuaib.classmate.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        var lastProgress = -1
        UpdateCoordinator.run(applicationContext, progress = { percent ->
            if (percent in 1..99 && percent / 10 > lastProgress / 10) {
                lastProgress = percent
                UpdateNotifications.show(applicationContext, "Downloading ClassMate update",
                    "$percent%", progress = percent)
            }
        })
        Result.success()
    } catch (error: PermanentUpdateException) {
        Log.e("ClassMateUpdate", "Permanent update failure", error)
        UpdateNotifications.show(applicationContext, "ClassMate update rejected",
            "The release could not be verified")
        Result.success()
    } catch (error: IOException) {
        Log.w("ClassMateUpdate", "Transient update failure", error)
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    } catch (error: Exception) {
        Log.e("ClassMateUpdate", "Update check failure", error)
        Result.failure()
    }
}

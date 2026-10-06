package com.shuaib.classmate.activities

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import com.shuaib.classmate.ui.GlassBottomNavView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import org.json.JSONObject

/** Counts are account-scoped on the server; contacts are never cached here. */
class ClassMateUnreadActivity(private val activity: AppCompatActivity,
    private val batch: () -> String, private val user: () -> String,
    private val friendsVisible: () -> Boolean) {
    private var refreshing = false
    private var refreshAgain = false
    private var popupLoading = false
    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { changes.collect { refresh() } }
                while (true) { refresh(); delay(30_000) }
            }
        }
    }
    fun refresh() {
        if (batch().isBlank() || user().isBlank()) return
        if (refreshing) { refreshAgain=true; return }
        val requestedBatch=batch(); val requestedUser=user(); refreshing=true
        activity.lifecycleScope.launch {
            try {
                val counts=ClassMateAuthApi.rpc("unread_activity",JSONObject().put("target_batch",requestedBatch))
                if(requestedBatch!=batch() || requestedUser!=user()) return@launch
                val nav=activity.findViewById<GlassBottomNavView>(R.id.classmate_home_nav) ?: return@launch
                for ((tab, count) in listOf(R.id.nav_notices to counts.optInt("notices"), R.id.nav_friends to counts.optInt("blood_requests"))) {
                    if(count==0) nav.removeBadge(tab) else nav.getOrCreateBadge(tab).apply {
                        number=count; maxCharacterCount=3; isVisible=true
                        backgroundColor=0xFFD94B55.toInt(); badgeTextColor=android.graphics.Color.WHITE
                    }
                }
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
              catch(_:Exception) { /* Keep the last count during connection failures. */ }
            finally { refreshing=false; if(refreshAgain) {refreshAgain=false;refresh()} }
        }
    }
    fun showUnseenRequest() {
        if(popupLoading) return
        popupLoading=true; val requestedUser=user(); val requestedBatch=batch()
        activity.lifecycleScope.launch {
            try {
                val requests=JSONArray(ClassMateAuthApi.rpcText("unread_blood_requests",JSONObject()))
                val id=requests.optJSONObject(0)?.optString("id") ?: return@launch
                val d=ClassMateAuthApi.rpc("blood_request_details",JSONObject().put("target_request",id))
                if(user()!=requestedUser || batch()!=requestedBatch || !friendsVisible() || activity.isFinishing || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                val needed=runCatching { java.time.OffsetDateTime.parse(d.optString("needed_by")).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM, hh:mm a")) }.getOrDefault(d.optString("needed_by"))
                val person=d.optString("patient_name").takeUnless { it=="null" || it.isBlank() } ?: d.optString("requester_name")
                MaterialAlertDialogBuilder(activity).setTitle("${d.optString("blood_group")} blood needed")
                    .setBackground(ClassMateFeatureUi.surface(activity))
                    .setMessage("For: $person\nRequested by: ${d.optString("requester_name")}\nHospital: ${d.optString("hospital")}\n${d.optInt("units")} unit(s) · Needed by $needed\nAttendant: ${d.optString("attendant_phone")}\n\nCan you help, or find someone who can?")
                    .setNegativeButton("Close",null).setNeutralButton("Call attendant") { _,_->
                        val phone=d.optString("attendant_phone")
                        if(phone.matches(Regex("\\+[1-9][0-9]{7,14}"))) activity.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:$phone")))
                    }.setPositiveButton("View request") { _,_->activity.startActivity(Intent(activity,ClassMateBloodActivity::class.java).putExtra("request_id",id).putExtra("batch_id",requestedBatch)) }.show()
                ClassMateAuthApi.rpcText("mark_blood_request_read",JSONObject().put("target_request",id));refresh()
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
              catch(_:Exception) { /* Retry on the next Friends visit. */ }
            finally { popupLoading=false }
        }
    }
    companion object {
        private val changes=MutableSharedFlow<Unit>(extraBufferCapacity=1)
        fun changed() { changes.tryEmit(Unit) }
    }
}

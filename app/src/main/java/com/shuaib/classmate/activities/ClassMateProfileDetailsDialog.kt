package com.shuaib.classmate.activities

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

internal object ClassMateProfileReminder {
    private fun prefs(context: Context)=context.getSharedPreferences("classmate_profile_reminders_${BuildConfig.CLASSMATE_ENV}",Context.MODE_PRIVATE)
    fun later(context: Context,id: String): String {
        val next=System.currentTimeMillis()+TimeUnit.HOURS.toMillis(24)
        prefs(context).edit().putLong(id,next).apply()
        return Instant.ofEpochMilli(next).toString()
    }
    fun clear(context: Context,id: String) { prefs(context).edit().remove(id).apply() }
    fun due(context: Context,profile: JSONObject): Boolean {
        if(!profile.isNull("profile_completed_at") && profile.optString("profile_completed_at").isNotBlank()) return false
        val server=runCatching { Instant.parse(profile.optString("profile_remind_after")).toEpochMilli() }.getOrDefault(0L)
        return System.currentTimeMillis()>=maxOf(server,prefs(context).getLong(profile.optString("id"),0L))
    }
}

internal object ClassMateProfileDetailsDialog {
    fun show(activity: AppCompatActivity,profile: JSONObject,onboarding: Boolean,onDone: (JSONObject)->Unit): AlertDialog {
        val form=ClassMateFormUi(activity)
        if(onboarding) form.label("2 of 3")
        form.label("Your details are shared with people in your batch.")
        fun value(key: String)=profile.optString(key).takeUnless { it.isBlank() || it=="null" }.orEmpty()
        val mobile=form.field("Mobile number").apply { inputType=InputType.TYPE_CLASS_PHONE; setText(value("mobile_number")); filters=arrayOf(InputFilter.LengthFilter(24)) }
        val town=form.field("Home town").apply { setText(value("home_town")); filters=arrayOf(InputFilter.LengthFilter(100)) }
        val groups=listOf("A+","A−","B+","B−","AB+","AB−","O+","O−","Don’t know")
        val codes=listOf("A+","A-","B+","B-","AB+","AB-","O+","O-","Unknown")
        val bloodBox=com.google.android.material.textfield.TextInputLayout(activity,null,com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle).apply {
            hint="Blood group"; boxBackgroundMode=com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            val radius=12*activity.resources.displayMetrics.density
            setBoxCornerRadii(radius,radius,radius,radius)
            layoutParams=android.widget.LinearLayout.LayoutParams(-1,-2).apply { topMargin=(12*activity.resources.displayMetrics.density).toInt() }
        }
        val blood=com.google.android.material.textfield.MaterialAutoCompleteTextView(bloodBox.context).apply {
            inputType=InputType.TYPE_NULL
            setAdapter(android.widget.ArrayAdapter(context,android.R.layout.simple_dropdown_item_1line,groups))
            setTextColor(activity.getColor(R.color.cm_text_primary))
            codes.indexOf(value("blood_group")).takeIf { it>=0 }?.let { setText(groups[it],false) }
        }
        bloodBox.addView(blood); form.panel.addView(bloodBox)
        val status=form.status()
        val shape=MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(24*activity.resources.displayMetrics.density).build()).apply {
            fillColor=android.content.res.ColorStateList.valueOf(activity.getColor(R.color.cm_surface))
        }
        val dialog=MaterialAlertDialogBuilder(activity).setBackground(shape)
            .setTitle(if(onboarding) "Complete your profile" else "Edit your information")
            .setView(form.scroll).setCancelable(!onboarding)
            .setPositiveButton(if(onboarding) "Continue" else "Save changes",null)
            .setNegativeButton(if(onboarding) "Remind later" else "Cancel",null).create()
        dialog.show()
        var busy=false
        fun saving(active: Boolean) {
            busy=active; listOf(mobile,town,blood).forEach { it.isEnabled=!active }
            dialog.getButton(-1).isEnabled=!active; dialog.getButton(-2).isEnabled=!active
            dialog.setCancelable(!onboarding && !active)
        }
        dialog.getButton(-1).setOnClickListener {
            if(busy) return@setOnClickListener
            if(mobile.text.isNullOrBlank()) { mobile.error="Enter your mobile number"; return@setOnClickListener }
            if(town.text.toString().trim().length<2) { town.error="Enter your home town"; return@setOnClickListener }
            val groupIndex=groups.indexOf(blood.text.toString())
            if(groupIndex<0) { bloodBox.error="Choose a blood group, or Don’t know."; return@setOnClickListener }
            bloodBox.error=null
            saving(true); status.visibility=View.VISIBLE; status.text="Saving your profile…"
            activity.lifecycleScope.launch {
                try {
                    val saved=ClassMateAuthApi.rpc("save_profile_details",JSONObject().put("target_mobile",mobile.text.toString())
                        .put("target_town",town.text.toString()).put("target_blood",codes[groupIndex])
                        .put("target_residence",value("current_residence")))
                    ClassMateProfileReminder.clear(activity,saved.getString("id"))
                    dialog.dismiss(); onDone(saved)
                } catch(e: Exception) { status.text=e.message?.replace(Regex("^\\d{3}:\\s*"),"") ?: "Could not save. Your information is still here."; saving(false) }
            }
        }
        dialog.getButton(-2).setOnClickListener {
            if(busy) return@setOnClickListener
            if(!onboarding) { dialog.dismiss(); return@setOnClickListener }
            val fallback=JSONObject(profile.toString()).put("profile_remind_after",ClassMateProfileReminder.later(activity,profile.optString("id")))
            // Local postponement works even if the connection drops during onboarding.
            dialog.dismiss(); onDone(fallback)
            activity.lifecycleScope.launch { runCatching { ClassMateAuthApi.rpc("defer_profile_completion",JSONObject()) } }
        }
        return dialog
    }
}

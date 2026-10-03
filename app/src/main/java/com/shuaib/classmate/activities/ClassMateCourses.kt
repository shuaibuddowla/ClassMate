package com.shuaib.classmate.activities

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import org.json.JSONArray
import org.json.JSONObject

internal object ClassMateCourses {
    suspend fun catalog(batch: String): JSONArray = JSONArray(ClassMateAuthApi.rpcText(
        "batch_course_catalog", JSONObject().put("target_batch",batch)))

    fun intent(activity: AppCompatActivity,batch: String,label: String,role: String,profile: String,
               mode: String="courses") = Intent(activity,ClassMateConfigurationActivity::class.java)
        .putExtra("batch_id",batch).putExtra("batch_label",label).putExtra("role",role)
        .putExtra("profile_id",profile).putExtra("mode",mode)

    fun prompt(activity: AppCompatActivity,start: ()->Unit) {
        MaterialAlertDialogBuilder(activity).setTitle("Set up your batch courses")
            .setMessage("Add course codes, names and teachers to get your timetable and library ready.")
            .setNegativeButton("Later",null).setPositiveButton("Start configuration") { _,_ -> start() }.show()
    }
}

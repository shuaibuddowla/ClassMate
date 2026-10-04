package com.shuaib.classmate.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

internal object ClassMateFeatureUi {
    fun dp(context: Context, n: Int) = (n * context.resources.displayMetrics.density).toInt()
    fun surface(context: Context) = MaterialShapeDrawable(ShapeAppearanceModel.builder()
        .setAllCornerSizes(dp(context,24).toFloat()).build()).apply {
        fillColor=android.content.res.ColorStateList.valueOf(context.getColor(R.color.cm_surface))
    }
    fun button(context: Context, label: String, primary: Boolean = false, action: () -> Unit) =
        MaterialButton(context,null,com.google.android.material.R.attr.materialButtonStyle).apply {
            text=label; isAllCaps=false; minHeight=dp(context,48); cornerRadius=dp(context,14)
            if(!primary) { backgroundTintList=android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT); strokeWidth=0; setTextColor(context.getColor(R.color.cm_primary)); elevation=0f }
            setOnClickListener { action() }
        }
    fun developer(activity: AppCompatActivity) {
        val form=ClassMateFormUi(activity)
        val heading=form.label("Behind ClassMate").apply { textSize=22f; setTypeface(null,1); setTextColor(activity.getColor(R.color.cm_text_primary)) }
        val details=form.label("Loading developer profile…")
        for((label,url) in listOf("Facebook" to "https://facebook.com/shuaibuddowla", "GitHub" to "https://github.com/shuaibuddowla", "Portfolio" to "https://shuaibuddowla.github.io")) {
            form.panel.addView(button(activity,label) { runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } })
        }
        val dialog=MaterialAlertDialogBuilder(activity).setTitle("About developer").setBackground(surface(activity)).setView(form.scroll).setPositiveButton("Close",null).show()
        fun load() { activity.lifecycleScope.launch {
            try {
                val p=ClassMateAuthApi.rpc("developer_profile",org.json.JSONObject())
                if(!dialog.isShowing) return@launch
                heading.text=p.optString("full_name","ClassMate developer")
                details.text=listOf(p.optString("department"), p.optString("student_id"), "Batch ${p.optString("batch_number")}", "Session ${ClassMateAcademicSession.format(p.optString("academic_session"))}").filter { it.isNotBlank() && it!="null" }.joinToString("\n")
                p.optString("avatar_url").takeIf { it.startsWith("https://") }?.let {
                    val photo=ImageView(activity).apply { contentDescription="Developer profile photo" }
                    form.panel.addView(photo,0,LinearLayout.LayoutParams(dp(activity,72),dp(activity,72)))
                    com.bumptech.glide.Glide.with(activity).load(it).circleCrop().into(photo)
                }
            } catch(e:Exception) { if(dialog.isShowing) { details.text="Could not load profile. Tap to retry."; details.setOnClickListener { load() } } }
        } }
        load()
    }
}

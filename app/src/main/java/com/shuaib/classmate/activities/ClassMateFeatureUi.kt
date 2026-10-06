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
        val hero=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL; gravity=android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(activity,20),dp(activity,28),dp(activity,20),dp(activity,24))
            background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(activity.getColor(R.color.cm_primary_soft),activity.getColor(R.color.cm_surface))).apply { cornerRadius=dp(activity,28).toFloat() }
        }
        form.panel.addView(hero,LinearLayout.LayoutParams(-1,-2))
        hero.addView(TextView(activity).apply { text="DESIGNED & BUILT BY"; textSize=10f; letterSpacing=.18f; setTypeface(null,1); setTextColor(activity.getColor(R.color.cm_primary)) })
        val photo=ImageView(activity).apply { contentDescription="Developer profile photo"; setImageResource(R.drawable.ic_default_avatar) }
        hero.addView(photo,LinearLayout.LayoutParams(dp(activity,76),dp(activity,76)).apply { topMargin=dp(activity,20); bottomMargin=dp(activity,16) })
        val heading=TextView(activity).apply { text="ClassMate developer"; gravity=android.view.Gravity.CENTER; textSize=22f; setTypeface(null,1); setTextColor(activity.getColor(R.color.cm_text_primary)) }
        hero.addView(heading)
        val details=TextView(activity).apply { text="Loading developer profile…"; gravity=android.view.Gravity.CENTER; textSize=13f; setPadding(0,dp(activity,10),0,0); setTextColor(activity.getColor(R.color.cm_text_secondary)) }
        hero.addView(details)
        hero.addView(TextView(activity).apply { text="A student-built space for a more connected campus."; gravity=android.view.Gravity.CENTER; textSize=13f; setPadding(0,dp(activity,14),0,0); setTextColor(activity.getColor(R.color.cm_text_secondary)) })
        for((label,url) in listOf("Explore my work" to "https://shuaibuddowla.github.io", "Find me on GitHub" to "https://github.com/shuaibuddowla")) {
            val row=LinearLayout(activity).apply {
                orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(activity,18),dp(activity,18),dp(activity,18),dp(activity,18))
                background=android.graphics.drawable.GradientDrawable().apply { setColor(activity.getColor(R.color.cm_surface)); cornerRadius=dp(activity,18).toFloat(); setStroke(dp(activity,1),activity.getColor(R.color.cm_border)) }
                isClickable=true; isFocusable=true; contentDescription="Open $label"
                setOnClickListener { runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } }
            }
            val labels=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
            labels.addView(TextView(activity).apply { text=label; textSize=16f; setTypeface(null,1); setTextColor(activity.getColor(R.color.cm_text_primary)) })
            labels.addView(TextView(activity).apply { text=Uri.parse(url).host+Uri.parse(url).path.orEmpty(); textSize=12f; setTextColor(activity.getColor(R.color.cm_text_secondary)) })
            row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(ImageView(activity).apply { setImageResource(R.drawable.ic_chevron_right); imageTintList=android.content.res.ColorStateList.valueOf(activity.getColor(R.color.cm_primary)) },LinearLayout.LayoutParams(dp(activity,20),dp(activity,20)))
            form.panel.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(activity,12) })
        }
        val dialog=com.google.android.material.bottomsheet.BottomSheetDialog(activity)
        form.panel.setPadding(dp(activity,20),dp(activity,20),dp(activity,20),dp(activity,20))
        form.panel.addView(button(activity,"Done") { dialog.dismiss() },LinearLayout.LayoutParams(-1,dp(activity,48)).apply { topMargin=dp(activity,8) })
        dialog.setContentView(form.scroll)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
                background=surface(activity)
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(this).apply {
                    state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed=true
                }
            }
        }
        dialog.show()
        fun load() { activity.lifecycleScope.launch {
            try {
                val p=ClassMateAuthApi.rpc("developer_profile",org.json.JSONObject())
                if(!dialog.isShowing) return@launch
                heading.text=p.optString("full_name","ClassMate developer")
                fun value(key:String)=p.optString(key).takeUnless {it.isBlank() || it=="null"}
                details.text=listOfNotNull(value("department"),value("student_id"),value("batch_number")?.let {"Batch $it"},value("academic_session")?.let {"Session ${ClassMateAcademicSession.format(it)}"}).joinToString(" · ")
                p.optString("avatar_url").takeIf { it.startsWith("https://") }?.let {
                    com.bumptech.glide.Glide.with(activity).load(it).circleCrop().into(photo)
                }
            } catch(e:Exception) { if(dialog.isShowing) { details.text="Could not load profile. Tap to retry."; details.setOnClickListener { load() } } }
        } }
        load()
    }
}

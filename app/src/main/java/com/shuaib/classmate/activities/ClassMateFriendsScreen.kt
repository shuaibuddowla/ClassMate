package com.shuaib.classmate.activities

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal class ClassMateFriendsScreen(private val activity: AppCompatActivity,private val scope: CoroutineScope,
    private val currentBatch: ()->String,private val onCall: (String)->Unit,private val teacher: ()->Boolean = {false}) {
    private var cachedBatch=""
    private var cachedRoot: LinearLayout?=null
    private val pages=linkedMapOf<String,List<JSONObject>>()
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun text(value: String,size: Float=14f,primary: Boolean=false)=TextView(activity).apply {
        text=value; textSize=size; setTextColor(activity.getColor(if(primary) R.color.cm_text_primary else R.color.cm_text_secondary))
    }

    fun render(host: LinearLayout,batchLabel: String) {
        val batch=currentBatch()
        if(cachedBatch!=batch) { pages.clear(); cachedRoot=null; cachedBatch=batch }
        cachedRoot?.let { root ->
            (root.parent as? ViewGroup)?.removeView(root)
            host.addView(root,LinearLayout.LayoutParams(-1,-1))
            return
        }
        val root=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(activity.getColor(R.color.cm_background)) }
        cachedRoot=root
        host.addView(root,LinearLayout.LayoutParams(-1,-1))
        val header=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(4)) }
        val titleRow=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        titleRow.addView(text(if(teacher()) "Students" else "Friends",24f,true).apply { setTypeface(null,1) },LinearLayout.LayoutParams(0,-2,1f))
        titleRow.addView(ClassMateFeatureUi.button(activity,"Blood requests") { activity.startActivity(Intent(activity,ClassMateBloodActivity::class.java).putExtra("batch_id",batch)) }.apply { textSize=13f; minHeight=dp(48); icon=androidx.core.content.ContextCompat.getDrawable(activity,R.drawable.ic_blood_drop); iconTint=android.content.res.ColorStateList.valueOf(0xFFD94B55.toInt()); iconSize=dp(20); setTextColor(0xFFD94B55.toInt()); strokeWidth=dp(1); strokeColor=android.content.res.ColorStateList.valueOf(0x66D94B55); cornerRadius=dp(16); backgroundTintList=android.content.res.ColorStateList.valueOf(activity.getColor(R.color.cm_surface)) })
        header.addView(titleRow)
        header.addView(text(batchLabel.ifBlank { "People in your batch" },11f).apply { setPadding(0,dp(2),0,dp(8)); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
        val searchBox=TextInputLayout(activity).apply {
            isHintEnabled=false; boxBackgroundMode=TextInputLayout.BOX_BACKGROUND_FILLED
            boxBackgroundColor=activity.getColor(R.color.cm_surface)
            boxStrokeWidth=0; boxStrokeWidthFocused=0
            setBoxCornerRadii(dp(14).toFloat(),dp(14).toFloat(),dp(14).toFloat(),dp(14).toFloat())
            setStartIconDrawable(R.drawable.ic_search_modern); endIconMode=TextInputLayout.END_ICON_CLEAR_TEXT
        }
        val input=TextInputEditText(searchBox.context).apply {
            setSingleLine(true); setTextColor(activity.getColor(R.color.cm_text_primary))
            hint=if(teacher()) "Search students by name or ID" else "Search name or student ID"; textSize=13f; minHeight=dp(48); setPadding(dp(12),dp(8),dp(12),dp(8))
            filters=arrayOf(android.text.InputFilter.LengthFilter(100))
            inputType=android.text.InputType.TYPE_CLASS_TEXT
        }
        searchBox.addView(input,LinearLayout.LayoutParams(-1,dp(48))); header.addView(searchBox)
        val status=text("Loading your batch…",12f).apply { setPadding(0,dp(10),0,dp(4)); accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        header.addView(status)
        val retry=MaterialButton(activity,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text="Try again"; visibility=View.GONE }
        header.addView(retry); root.addView(header)
        val list=RecyclerView(activity).apply {
            layoutManager=LinearLayoutManager(activity); clipToPadding=false; setPadding(dp(12),0,dp(12),dp(108)); itemAnimator=null
        }
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        var detailsDialog: AlertDialog?=null
        var detailJob: Job?=null
        val adapter=FriendsAdapter { member ->
            if(!root.isAttachedToWindow || currentBatch()!=batch || detailJob?.isActive==true) return@FriendsAdapter
            detailsDialog?.dismiss()
            detailsDialog=MaterialAlertDialogBuilder(activity).setTitle(member.optString("full_name")).setMessage("Loading profile…").setNegativeButton("Cancel",null).show()
            val loading=detailsDialog!!
            detailJob=scope.launch {
                try {
                    val data=ClassMateAuthApi.rpc("batch_friend_details",JSONObject().put("target_batch",batch).put("target_profile",member.getString("profile_id")))
                    if(!root.isAttachedToWindow || currentBatch()!=batch || !loading.isShowing) return@launch
                    loading.dismiss(); detailsDialog=showDetails(data)
                } catch(e: Exception) {
                    if(loading.isShowing) { loading.dismiss(); Toast.makeText(activity,"Could not open this profile. Check your connection or refresh Friends.",Toast.LENGTH_LONG).show() }
                }
            }
        }
        list.adapter=adapter
        var generation=0
        var query=""
        var offset=0
        var hasMore=true
        var loading=false
        var loadJob: Job?=null
        fun load(reset: Boolean,wait: Boolean=false) {
            if(!reset && (loading || !hasMore)) return
            if(reset) { generation++; loadJob?.cancel(); query=input.text.toString().trim(); offset=0; hasMore=true; adapter.submitList(pages[query].orEmpty()) }
            val request=generation
            val requestedOffset=offset
            loading=true; retry.visibility=View.GONE; status.text=if(pages[query].isNullOrEmpty()) "Loading your batch…" else "${pages[query]!!.size} people · refreshing"
            loadJob=scope.launch {
                try {
                    if(wait) delay(250)
                    val result=JSONArray(ClassMateAuthApi.rpcText("batch_friends",JSONObject().put("target_batch",batch).put("query_text",query).put("result_offset",requestedOffset)))
                    if(!root.isAttachedToWindow || currentBatch()!=batch || request!=generation) return@launch
                    val page=(0 until result.length()).map { result.getJSONObject(it) }.filter { it.optString("role") in setOf("student", "admin") }
                    val merged=(if(reset) page else adapter.currentList+page).distinctBy { it.optString("profile_id") }
                    offset=requestedOffset+result.length(); hasMore=result.length()==100
                    adapter.submitList(merged)
                    pages[query]=merged
                    while(pages.size>8) pages.remove(pages.keys.first())
                    status.text=if(merged.isEmpty()) (if(query.isBlank()) "No members have joined this batch yet." else "No matching people.") else "${merged.size}${if(hasMore) "+" else ""} ${if(merged.size==1) "person" else "people"}"
                } catch(e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) {
                    if(request==generation && root.isAttachedToWindow) { status.text="Could not load Friends. Check your connection."; retry.visibility=View.VISIBLE }
                } finally { if(request==generation) loading=false }
            }
        }
        input.doAfterTextChanged { load(true,true) }
        retry.setOnClickListener { load(offset==0) }
        list.addOnScrollListener(object: RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView,dx: Int,dy: Int) {
                if(dy>0 && (view.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()>=adapter.itemCount-8) load(false)
            }
        })
        root.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { load(true) }
            override fun onViewDetachedFromWindow(view: View) { loadJob?.cancel(); detailJob?.cancel(); detailsDialog?.dismiss() }
        })
        if(root.isAttachedToWindow) load(true)
    }

    private inner class FriendsAdapter(val click: (JSONObject)->Unit): ListAdapter<JSONObject,FriendHolder>(object: DiffUtil.ItemCallback<JSONObject>() {
        override fun areItemsTheSame(old: JSONObject,new: JSONObject)=old.optString("profile_id")==new.optString("profile_id")
        override fun areContentsTheSame(old: JSONObject,new: JSONObject)=old.toString()==new.toString()
    }) {
        override fun onCreateViewHolder(parent: ViewGroup,type: Int): FriendHolder {
            val card=MaterialCardView(activity).apply {
                radius=dp(16).toFloat(); cardElevation=0f; setCardBackgroundColor(activity.getColor(R.color.cm_surface))
                layoutParams=RecyclerView.LayoutParams(-1,-2).apply { bottomMargin=dp(8) }
                isClickable=true; isFocusable=true
            }
            val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(12)) }
            val avatar=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            row.addView(avatar,LinearLayout.LayoutParams(dp(48),dp(48)))
            val labels=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),0,dp(8),0) }
            val name=text("",16f,true).apply { setTypeface(null,1) }
            val badge=text("Admin",11f,true).apply {
                setPadding(dp(8),dp(3),dp(8),dp(3)); setTextColor(activity.getColor(R.color.cm_primary))
                background=android.graphics.drawable.GradientDrawable().apply { setColor(activity.getColor(R.color.cm_primary_soft)); cornerRadius=dp(8).toFloat() }
            }
            val id=text("",12f).apply { setPadding(0,dp(4),0,0) }
            labels.addView(name); labels.addView(badge,LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(4) }); labels.addView(id); row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(ImageView(activity).apply { setImageResource(R.drawable.ic_chevron_right); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(18),dp(18)))
            card.addView(row)
            return FriendHolder(card,avatar,name,id,badge)
        }
        override fun onBindViewHolder(holder: FriendHolder,position: Int) {
            val member=getItem(position)
            holder.badge.visibility=if(member.optString("role")=="admin") View.VISIBLE else View.GONE
            holder.name.text=member.optString("full_name").ifBlank { "ClassMate member" }
            holder.studentId.text=when(member.optString("role")) { "admin" -> member.optString("student_id").takeUnless {it=="null"}.orEmpty(); else -> listOf(member.optString("student_id").takeUnless { it=="null" || it.isBlank() },if(member.optBoolean("is_cr")) "Class representative" else null).filterNotNull().joinToString(" · ") }
            holder.studentId.setTextColor(activity.getColor(if(member.optString("role")=="admin") R.color.cm_primary else R.color.cm_text_secondary))
            Glide.with(activity).load(member.optString("avatar_url").takeUnless { it=="null" || it.isBlank() }).circleCrop().placeholder(R.drawable.ic_default_avatar).error(R.drawable.ic_default_avatar).into(holder.avatar)
            holder.itemView.setOnClickListener { if(member.optString("role")=="admin") ClassMateFeatureUi.developer(activity) else click(member) }
            holder.itemView.contentDescription="${holder.name.text}, ${holder.studentId.text}. Open profile"
        }
    }
    private class FriendHolder(view: View,val avatar: ImageView,val name: TextView,val studentId: TextView,val badge: TextView): RecyclerView.ViewHolder(view)

    private fun showDetails(member: JSONObject): AlertDialog {
        val panel=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(4),dp(18),dp(8)) }
        val scroll=android.widget.ScrollView(activity).apply { isFillViewport=true; addView(panel) }

        val header=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(4),0,dp(12)) }
        val avatar=ImageView(activity).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        header.addView(avatar,LinearLayout.LayoutParams(dp(48),dp(48)))
        Glide.with(activity).load(member.optString("avatar_url").takeUnless { it=="null" || it.isBlank() }).circleCrop().placeholder(R.drawable.ic_default_avatar).error(R.drawable.ic_default_avatar).into(avatar)

        val titles=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),0,0,0) }
        titles.addView(text(member.optString("full_name").ifBlank { "ClassMate member" },16f,true).apply { setTypeface(null,1) })
        val sub=when(member.optString("role")) {
            "admin" -> member.optString("student_id").takeUnless { it=="null" }.orEmpty()
            else -> listOf(member.optString("student_id").takeUnless { it=="null" || it.isBlank() },if(member.optBoolean("is_cr")) "Class representative" else null).filterNotNull().joinToString(" · ")
        }
        if(sub.isNotBlank()) titles.addView(text(sub,12f).apply { setPadding(0,dp(2),0,0) })
        header.addView(titles,LinearLayout.LayoutParams(0,-2,1f))
        panel.addView(header)

        val rows=listOf(
            "Student ID" to "student_id",
            "Mobile number" to "mobile_number",
            "Home town" to "home_town",
            "Blood group" to "blood_group",
            "Current mess / flat" to "current_residence"
        )
        rows.forEachIndexed { i,(label,key) ->
            val itemVal=member.optString(key).takeUnless { it.isBlank() || it=="null" } ?: "Not provided"
            val row=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(7),0,dp(7)) }
            row.addView(text(label,12f).apply { setTextColor(activity.getColor(R.color.cm_text_secondary)) },LinearLayout.LayoutParams(0,-2,0.42f))
            row.addView(text(itemVal,13f,true).apply { gravity=Gravity.END; textAlignment=View.TEXT_ALIGNMENT_VIEW_END },LinearLayout.LayoutParams(0,-2,0.58f))
            panel.addView(row)
            if(i<rows.size-1) {
                panel.addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.cm_border)); alpha=0.5f },LinearLayout.LayoutParams(-1,dp(1)))
            }
        }

        val phone=member.optString("mobile_number").takeIf { it.matches(Regex("\\+[1-9][0-9]{7,14}")) }
        val actions=LinearLayout(activity).apply { gravity=Gravity.CENTER; setPadding(0,dp(12),0,0) }
        fun action(label: String,icon: Int,callback: ()->Unit) {
            actions.addView(MaterialButton(activity,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text=label; textSize=13f; setIconResource(icon); iconSize=dp(18); cornerRadius=dp(12); isEnabled=phone!=null
                setOnClickListener { callback() }
            },LinearLayout.LayoutParams(0,dp(44),1f).apply { marginStart=dp(4); marginEnd=dp(4) })
        }
        action("Call",R.drawable.ic_phone) { phone?.let(onCall) }
        action("WhatsApp",R.drawable.ic_whatsapp) { phone?.let {
            try {
                val intent=Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/${it.removePrefix("+")}"))
                val installed=listOf("com.whatsapp","com.whatsapp.w4b").firstOrNull { packageName ->
                    runCatching { activity.packageManager.getPackageInfo(packageName,0) }.isSuccess
                }
                installed?.let { packageName -> intent.setPackage(packageName) }
                activity.startActivity(intent)
            }
            catch (_: android.content.ActivityNotFoundException) { Toast.makeText(activity,"No app can open WhatsApp",Toast.LENGTH_SHORT).show() }
        } }
        panel.addView(actions)

        val surface=MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(dp(22).toFloat()).build()).apply { fillColor=android.content.res.ColorStateList.valueOf(activity.getColor(R.color.cm_surface)) }
        val dialog=MaterialAlertDialogBuilder(activity).setBackground(surface).setTitle("Profile").setView(scroll).setPositiveButton("Done",null).create()
        dialog.show()

        val metrics=activity.resources.displayMetrics
        val dialogWidth=minOf((metrics.widthPixels * 0.94f).toInt(),dp(520))
        dialog.window?.let { win ->
            win.setLayout(dialogWidth,ViewGroup.LayoutParams.WRAP_CONTENT)
            win.setBackgroundDrawable(surface)
        }
        return dialog
    }
}

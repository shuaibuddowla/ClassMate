package com.shuaib.classmate.activities

import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/** The same paginated, permission-checked comment/reply APIs as the web client. */
internal object ClassMateComments {
    fun show(activity: AppCompatActivity, notice: JSONObject, changed: () -> Unit,
             parent: JSONObject? = null) {
        val form=ClassMateFormUi(activity)
        parent?.let { form.label("Replying to ${it.optString("author_name")}\n${it.optString("body")}") }
        val list=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        form.panel.addView(list)
        val status=form.status()
        val loadMore=ClassMateFeatureUi.button(activity,"Older comments") {}
        form.panel.addView(loadMore); loadMore.visibility=View.GONE
        val input=form.field(if(parent==null) "Write a comment" else "Write a reply",true)
        val send=ClassMateFeatureUi.button(activity,"Post",true) {}
        form.panel.addView(send)
        val dialog=MaterialAlertDialogBuilder(activity).setTitle(if(parent==null) "Comments" else "Replies")
            .setBackground(ClassMateFeatureUi.surface(activity)).setView(form.scroll).setNegativeButton("Close",null).show()
        dialog.window?.setLayout(-1,(activity.resources.displayMetrics.heightPixels*0.85f).toInt())
        var cursor:JSONObject?=null
        var busy=false
        var request=UUID.randomUUID().toString()
        var attemptedBody:String?=null
        fun error(e:Exception) { status.visibility=View.VISIBLE; status.text=e.message ?: "Connection failed. Try again." }
        lateinit var load:(Boolean)->Unit
        fun action(reload:Boolean=false,block:suspend ()->Unit) {
            if(busy) return
            busy=true; send.isEnabled=false; loadMore.isEnabled=false; input.isEnabled=false
            activity.lifecycleScope.launch {
                var successful=false
                try { block(); successful=true } catch(e:Exception) { if(dialog.isShowing) error(e) }
                finally { busy=false; send.isEnabled=true; loadMore.isEnabled=true; input.isEnabled=true; if(successful && reload && dialog.isShowing) load(true) }
            }
        }
        fun edit(comment:JSONObject) {
            val editForm=ClassMateFormUi(activity)
            val field=editForm.field("Comment",true).apply { setText(comment.optString("body")) }
            val editStatus=editForm.status()
            val editor=MaterialAlertDialogBuilder(activity).setTitle("Edit comment").setBackground(ClassMateFeatureUi.surface(activity)).setView(editForm.scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).show()
            editor.getButton(-1).setOnClickListener {
                val body=field.text.toString().trim()
                if(body.length !in 1..2000) { field.error="Use 1–2,000 characters"; return@setOnClickListener }
                editor.getButton(-1).isEnabled=false
                activity.lifecycleScope.launch {
                    try {
                        ClassMateAuthApi.rpcText("save_notice_comment",JSONObject().put("target_notice",notice.getString("id")).put("target_body",body).put("target_id",comment.getString("id")))
                        editor.dismiss(); changed(); if(dialog.isShowing) load(true)
                    } catch(e:Exception) { editStatus.visibility=View.VISIBLE; editStatus.text=e.message; editor.getButton(-1).isEnabled=true }
                }
            }
        }
        load={reset ->
            if(!busy && dialog.isShowing) {
                busy=true; send.isEnabled=false; status.visibility=View.VISIBLE; status.text="Loading…"
                activity.lifecycleScope.launch {
                    try {
                        val c=if(reset) null else cursor
                        val items=JSONArray(ClassMateAuthApi.rpcText("comment_page",JSONObject().put("target_notice",notice.getString("id"))
                            .put("target_parent",parent?.getString("id") ?: JSONObject.NULL).put("before_time",c?.optString("created_at") ?: JSONObject.NULL).put("before_id",c?.optString("id") ?: JSONObject.NULL)))
                        if(!dialog.isShowing) return@launch
                        if(reset) list.removeAllViews()
                        if(items.length()==0 && reset) list.addView(TextView(activity).apply { text="Be the first to comment."; setTextColor(activity.getColor(R.color.cm_text_secondary)) })
                        for(i in 0 until items.length()) {
                            val item=items.getJSONObject(i)
                            val row=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(0,ClassMateFeatureUi.dp(activity,12),0,ClassMateFeatureUi.dp(activity,12)) }
                            val author=LinearLayout(activity).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
                            val photo=ImageView(activity).apply { contentDescription="${item.optString("author_name")} profile photo"; setImageResource(R.drawable.ic_default_avatar) }
                            author.addView(photo,LinearLayout.LayoutParams(ClassMateFeatureUi.dp(activity,32),ClassMateFeatureUi.dp(activity,32)))
                            item.optString("avatar_url").takeIf { it.startsWith("https://") }?.let { com.bumptech.glide.Glide.with(activity).load(it).circleCrop().into(photo) }
                            author.addView(TextView(activity).apply { text="  ${item.optString("author_name")}\n  ${item.optString("created_at").take(10)}"; textSize=12f; setTextColor(activity.getColor(R.color.cm_text_secondary)) })
                            row.addView(author)
                            row.addView(TextView(activity).apply { text=item.optString("body"); textSize=15f; setTextIsSelectable(true); setPadding(0,8,0,0); setTextColor(activity.getColor(R.color.cm_text_primary)) })
                            val actions=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
                            val like=ClassMateFeatureUi.button(activity,"${if(item.optBoolean("liked_by_me")) "Liked" else "Like"} · ${item.optInt("like_count")}") {}
                            like.setOnClickListener { action {
                                ClassMateAuthApi.rpcText("set_comment_like",JSONObject().put("target_id",item.getString("id")).put("target_liked",!item.optBoolean("liked_by_me")))
                                item.put("liked_by_me",!item.optBoolean("liked_by_me")); item.put("like_count",item.optInt("like_count")+if(item.optBoolean("liked_by_me")) 1 else -1)
                                like.text="${if(item.optBoolean("liked_by_me")) "Liked" else "Like"} · ${item.optInt("like_count")}" }
                            }
                            actions.addView(like)
                            if(parent==null) actions.addView(ClassMateFeatureUi.button(activity,"Reply · ${item.optInt("reply_count")}") { if(!busy) show(activity,notice,{ changed(); if(dialog.isShowing) load(true) },item) })
                            if(item.optBoolean("can_edit") || item.optBoolean("can_delete")) {
                                val options=ImageButton(activity).apply { setImageResource(R.drawable.ic_more_vert); contentDescription="Manage comment"; background=null; imageTintList=android.content.res.ColorStateList.valueOf(activity.getColor(R.color.cm_text_secondary)) }
                                actions.addView(options,LinearLayout.LayoutParams(ClassMateFeatureUi.dp(activity,48),ClassMateFeatureUi.dp(activity,48)))
                                options.setOnClickListener { if(!busy) {
                                    val menu=PopupMenu(activity,options)
                                    if(item.optBoolean("can_edit")) menu.menu.add("Edit")
                                    if(item.optBoolean("can_delete")) menu.menu.add("Delete")
                                    menu.setOnMenuItemClickListener { choice ->
                                        if(choice.title=="Edit") edit(item)
                                        else MaterialAlertDialogBuilder(activity).setTitle("Delete comment?").setMessage("Its replies will also be removed.").setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ -> action {
                                            ClassMateAuthApi.rpcText("delete_notice_comment",JSONObject().put("target_id",item.getString("id"))); row.visibility=View.GONE; changed()
                                        } }.show()
                                        true
                                    }; menu.show()
                                } }
                            }
                            row.addView(actions); list.addView(row)
                        }
                        cursor=items.optJSONObject(items.length()-1)
                        loadMore.visibility=if(items.length()==50) View.VISIBLE else View.GONE
                        status.visibility=View.GONE
                    } catch(e:Exception) { if(dialog.isShowing) { error(e); status.setOnClickListener { load(reset) } } }
                    finally { busy=false; send.isEnabled=true }
                }
            }
        }
        loadMore.setOnClickListener { load(false) }
        send.setOnClickListener {
            val body=input.text.toString().trim()
            if(body.length !in 1..2000) { input.error="Use 1–2,000 characters"; return@setOnClickListener }
            if(attemptedBody!=body) { request=UUID.randomUUID().toString(); attemptedBody=body }
            action(reload=true) {
                ClassMateAuthApi.rpcText("save_notice_comment",JSONObject().put("target_notice",notice.getString("id")).put("target_body",body)
                    .put("target_parent",parent?.getString("id") ?: JSONObject.NULL).put("target_request",request))
                if(dialog.isShowing) input.setText(""); attemptedBody=null; changed()
            }
        }
        load(true)
    }
}

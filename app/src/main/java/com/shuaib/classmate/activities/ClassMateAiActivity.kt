package com.shuaib.classmate.activities

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.*
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A native workspace for the existing server agent. No client-side AI key/tools. */
class ClassMateAiActivity : AppCompatActivity() {
    private val batch by lazy { intent.getStringExtra("batch_id").orEmpty() }
    private var conversation:String?=null
    private var request:String?=null
    private var requestText=""
    private var busy=false
    private lateinit var form:ClassMateFormUi
    private lateinit var transcript:LinearLayout
    private lateinit var input:com.google.android.material.textfield.TextInputEditText
    private lateinit var status:TextView
    private lateinit var send:MaterialButton
    private var messages=emptyList<JSONObject>()
    private var canWrite=false
    private var changesApplied=false
    override fun onCreate(state:Bundle?) {
        super.onCreate(state); ClassMateAuthApi.attach(applicationContext)
        conversation=state?.getString("conversation"); request=state?.getString("request"); requestText=state?.getString("request_text").orEmpty()
        changesApplied=state?.getBoolean("changes_applied") ?: false
        if(changesApplied) setResult(RESULT_OK)
        if(batch.isBlank()) { finish(); return }
        WindowCompat.setDecorFitsSystemWindows(window,false)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.cm_background)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v,ins -> val bars=ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()); v.setPadding(bars.left,bars.top,bars.right,bars.bottom); ins }
        val toolbar=MaterialToolbar(this).apply {
            title="ClassMate AI"; setTitleTextColor(getColor(R.color.cm_text_primary)); setNavigationIcon(R.drawable.ic_chevron_left)
            navigationIcon?.setTint(getColor(R.color.cm_text_primary)); setNavigationOnClickListener { if(!busy) finish() }
        }
        toolbar.menu.add("Chats").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener { if(!busy && canWrite) history(); true }; root.addView(toolbar)
        form=ClassMateFormUi(this)
        val fresh=ClassMateFeatureUi.button(this,"New chat") { if(!busy) { conversation=null; request=null; requestText=""; messages=emptyList(); input.setText(""); render() } }
        form.panel.addView(fresh)
        transcript=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; form.panel.addView(transcript)
        status=form.status(); root.addView(form.scroll,LinearLayout.LayoutParams(-1,0,1f))
        val compose=ClassMateFormUi(this); input=compose.field("Ask about your batch or describe a change",true).apply {
            minLines=1; maxLines=4; filters=arrayOf(android.text.InputFilter.LengthFilter(8000)); setText(state?.getString("draft").orEmpty())
        }
        send=ClassMateFeatureUi.button(this,"Send",true) { sendMessage() }.apply { icon=getDrawable(R.drawable.ic_send); isEnabled=false }
        compose.panel.addView(send)
        (compose.panel.parent as? android.view.ViewGroup)?.removeView(compose.panel)
        root.addView(compose.panel); setContentView(root)
        onBackPressedDispatcher.addCallback(this,object:androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(!busy) finish() else Toast.makeText(this@ClassMateAiActivity,"Please wait for the current request",Toast.LENGTH_SHORT).show() }
        })
        operation {
            canWrite=ClassMateAuthApi.rpcText("ai_can_write",JSONObject().put("target_batch",batch)).trim()=="true"
            if(!canWrite) { finish(); return@operation }
            if(conversation!=null) loadMessages() else render()
        }
    }
    override fun onSaveInstanceState(out:Bundle) {
        super.onSaveInstanceState(out); out.putString("conversation",conversation); out.putString("request",request); out.putString("request_text",requestText)
        if(::input.isInitialized) out.putString("draft",input.text.toString())
        out.putBoolean("changes_applied",changesApplied)
    }
    private fun operation(block:suspend ()->Unit) {
        if(busy) return; busy=true; send.isEnabled=false; input.isEnabled=false; status.visibility=View.VISIBLE; status.text="Connecting…"
        lifecycleScope.launch {
            try { block(); status.visibility=View.GONE }
            catch(e:Exception) { status.visibility=View.VISIBLE; status.text=(e.message ?: "Connection failed")+"\nTap to retry."; status.setOnClickListener { operation(block) } }
            finally { busy=false; send.isEnabled=canWrite; input.isEnabled=true }
        }
    }
    private fun sendMessage() {
        val text=input.text.toString().trim(); if(text.isBlank() || busy || !canWrite) return
        if(requestText!=text) { request=UUID.randomUUID().toString(); requestText=text }
        request=request ?: UUID.randomUUID().toString()
        operation {
            if(conversation==null) {
                conversation=UUID.randomUUID().toString()
            }
            ClassMateAuthApi.rpcText("manage_ai_conversation",JSONObject().put("target_id",conversation).put("target_batch",batch).put("operation","create"))
            ClassMateAuthApi.ai(JSONObject().put("mode","agent").put("request_id",request).put("conversation_id",conversation).put("batch_id",batch).put("text",text))
            input.setText(""); request=null; requestText=""
            loadMessages()
            // Keep the submitted prompt and the beginning of its answer visible.
            val anchor=messages.lastOrNull { it.optString("role")=="user" }?.optLong("id")
            transcript.findViewWithTag<View>(anchor)?.let { view -> form.scroll.post { form.scroll.smoothScrollTo(0,transcript.top+view.top) } }
        }
    }
    private suspend fun loadMessages(before:Long?=null) {
        val page=JSONArray(ClassMateAuthApi.rpcText("ai_message_page",JSONObject().put("target_conversation",conversation).put("before_id",before ?: JSONObject.NULL)))
        val list=(0 until page.length()).map { page.getJSONObject(it) }
        messages=if(before==null) list else (list+messages).distinctBy { it.optLong("id") }
        render(page.length()==50)
    }
    private fun render(more:Boolean=false) {
        transcript.removeAllViews()
        if(more && messages.isNotEmpty()) transcript.addView(ClassMateFeatureUi.button(this,"Older messages") { operation { loadMessages(messages.first().optLong("id")) } })
        if(messages.isEmpty()) transcript.addView(TextView(this).apply { text="Your batch, connected. Ask about schedules, notices or files, or describe what you want to change."; textSize=16f; setTextColor(getColor(R.color.cm_text_secondary)); setPadding(0,24,0,24) })
        for(m in messages) {
            val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,16,0,16); tag=m.optLong("id") }
            box.addView(TextView(this).apply { text=if(m.optString("role")=="user") "You" else "ClassMate AI"; textSize=12f; setTextColor(getColor(R.color.cm_primary)) })
            box.addView(TextView(this).apply { text=m.optString("content"); textSize=16f; setTextIsSelectable(true); setTextColor(getColor(R.color.cm_text_primary)) })
            if(m.optString("role")=="assistant") {
                val plan=m.optJSONObject("plan"); val actions=plan?.optJSONArray("actions")
                if(actions!=null && actions.length()>0) {
                    val preview=ClassMateAiPreview.describe(actions)
                    box.addView(TextView(this).apply { text=preview; textSize=14f; setTextColor(getColor(R.color.cm_text_secondary)) })
                    if(m.optString("status")!="complete") box.addView(ClassMateFeatureUi.button(this,"Review & apply changes") {
                        if(!busy) MaterialAlertDialogBuilder(this).setTitle("Apply these changes?").setMessage(preview).setNegativeButton("Cancel",null)
                            .setPositiveButton("Apply") { _,_ -> operation {
                                ClassMateAuthApi.ai(JSONObject().put("mode","execute").put("request_id",m.getString("request_id")))
                                changesApplied=true; setResult(RESULT_OK); ClassMateAcademicCache.invalidateSchedules(this@ClassMateAiActivity,intent.getStringExtra("profile_id").orEmpty(),batch); loadMessages()
                            } }.show()
                    }) else box.addView(TextView(this).apply { text="Changes applied"; setTextColor(getColor(R.color.cm_primary)) })
                }
            }
            transcript.addView(box)
        }
    }
    private fun history() {
        val f=ClassMateFormUi(this)
        val state=f.status(); val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; f.panel.addView(list)
        val d=MaterialAlertDialogBuilder(this).setTitle("Your chats").setBackground(ClassMateFeatureUi.surface(this)).setView(f.scroll).setNegativeButton("Close",null).show()
        var cursor:JSONObject?=null; var loading=false; var olderButton:View?=null
        lateinit var page:(Boolean)->Unit
        fun manage(c:JSONObject,op:String,title:String="New chat") { lifecycleScope.launch {
            try { ClassMateAuthApi.rpcText("manage_ai_conversation",JSONObject().put("target_id",c.getString("id")).put("target_batch",batch).put("operation",op).put("target_title",title))
                if(op=="delete" && conversation==c.getString("id")) { conversation=null; messages=emptyList(); render() }; page(true)
            } catch(e:Exception) { state.visibility=View.VISIBLE; state.text=e.message }
        } }
        page={reset -> if(!loading) { loading=true; state.visibility=View.VISIBLE; state.text="Loading chats…"; lifecycleScope.launch {
            try {
                val c=if(reset) null else cursor
                val rows=JSONArray(ClassMateAuthApi.rpcText("ai_conversation_page",JSONObject().put("target_batch",batch).put("before_at",c?.optString("updated_at") ?: JSONObject.NULL).put("before_uuid",c?.optString("id") ?: JSONObject.NULL)))
                if(!d.isShowing) return@launch
                olderButton?.let { list.removeView(it) }; olderButton=null
                if(reset) list.removeAllViews()
                for(i in 0 until rows.length()) { val chat=rows.getJSONObject(i)
                    list.addView(ClassMateFeatureUi.button(this@ClassMateAiActivity,chat.optString("title")) {
                        d.dismiss(); conversation=chat.getString("id"); request=null; input.setText(""); operation { loadMessages() }
                    })
                    list.addView(ClassMateFeatureUi.button(this@ClassMateAiActivity,"Rename") {
                        val rename=ClassMateFormUi(this@ClassMateAiActivity); val field=rename.field("Chat title").apply { setText(chat.optString("title")) }
                        MaterialAlertDialogBuilder(this@ClassMateAiActivity).setTitle("Rename chat").setView(rename.scroll).setNegativeButton("Cancel",null).setPositiveButton("Save") { _,_ -> if(field.text.toString().trim().isNotBlank()) manage(chat,"rename",field.text.toString().trim()) }.show()
                    })
                    list.addView(ClassMateFeatureUi.button(this@ClassMateAiActivity,"Delete chat") {
                        MaterialAlertDialogBuilder(this@ClassMateAiActivity).setTitle("Delete chat and its messages?").setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ -> manage(chat,"delete") }.show()
                    })
                }
                cursor=rows.optJSONObject(rows.length()-1); state.visibility=View.GONE
                if(rows.length()==0 && reset) state.apply { text="No saved chats yet."; visibility=View.VISIBLE }
                if(rows.length()==30) {
                    olderButton=ClassMateFeatureUi.button(this@ClassMateAiActivity,"Older chats") { page(false) }
                    list.addView(olderButton)
                }
            } catch(e:Exception) { state.text="${e.message}\nTap to retry"; state.setOnClickListener { page(reset) } }
            finally { loading=false }
        } } }
        page(true)
    }
}

internal object ClassMateAiPreview {
    fun describe(actions:JSONArray):String = (0 until actions.length()).joinToString("\n\n") { i ->
        val a=actions.getJSONObject(i); val args=a.optJSONObject("args") ?: JSONObject()
        val publicFields=args.keys().asSequence().filter { !it.endsWith("_id") && it !in setOf("target_batch","target_course","target_semester_course","target_profile","target_resource","target_offering","target_teacher_record","target_record","target_department") }.map { "$it: ${args.opt(it)}" }.joinToString("\n")
        "${i+1}. ${a.optString("name").removePrefix("ai_").replace('_',' ')}\n${a.optString("summary")}\n$publicFields"+
            if(a.optString("name").contains("bus")) "\nBus changes apply university-wide." else ""
    }
}

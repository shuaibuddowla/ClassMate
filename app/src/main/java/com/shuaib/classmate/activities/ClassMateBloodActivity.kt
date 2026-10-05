package com.shuaib.classmate.activities

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.*
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/** Emergency coordination is online-only; donor contacts never enter an offline cache. */
class ClassMateBloodActivity: AppCompatActivity() {
 private lateinit var form:ClassMateFormUi
 private lateinit var list:LinearLayout
 private lateinit var status:TextView
 private var busy=false
 private var offset=0
 private var batch=""
 private val groups=listOf("A+","A-","B+","B-","AB+","AB-","O+","O-")
 override fun onCreate(state:Bundle?) {
  super.onCreate(state);ClassMateAuthApi.attach(applicationContext)
  batch=intent.getStringExtra("batch_id").orEmpty()
  WindowCompat.setDecorFitsSystemWindows(window,false)
  val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(getColor(R.color.cm_background))}
  ViewCompat.setOnApplyWindowInsetsListener(root){v,ins->val bars=ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);ins}
  val toolbar=MaterialToolbar(this).apply {title="Blood requests";setTitleTextColor(getColor(R.color.cm_text_primary));setNavigationIcon(R.drawable.ic_chevron_left);navigationIcon?.setTint(getColor(R.color.cm_text_primary));setNavigationOnClickListener{finish()}}
  root.addView(toolbar);form=ClassMateFormUi(this)
  form.label("Verified help, when every minute matters.")
  form.panel.addView(ClassMateFeatureUi.button(this,"Donor settings"){preferences()})
  form.panel.addView(ClassMateFeatureUi.button(this,"Request blood",true){create()})
  form.label("Red-cell matching only. Hospital screening and cross-matching are required. Do not delay emergency care.")
  status=form.status();list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};form.panel.addView(list)
  form.panel.addView(ClassMateFeatureUi.button(this,"Refresh"){load()})
  root.addView(form.scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
  operation {if(!ClassMateAuthApi.restoreSession()){finish();return@operation};if(batch.isBlank())batch=ClassMateAuthApi.initializeProfile().optString("batch_id")
   if(intent.getBooleanExtra("volunteer_now",false)){intent.removeExtra("volunteer_now");val target=intent.getStringExtra("request_id")?:throw IllegalArgumentException("Request unavailable");ClassMateAuthApi.rpcText("respond_blood_request",JSONObject().put("target_request",target).put("target_response","interested"));Toast.makeText(this@ClassMateBloodActivity,"You volunteered. Call the attendant to arrange screening.",Toast.LENGTH_LONG).show()};fetch()}
 }
 private fun operation(work:suspend ()->Unit){if(busy)return;busy=true;status.visibility=android.view.View.VISIBLE;status.text="Loading…";lifecycleScope.launch{try{work();status.visibility=android.view.View.GONE}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){status.text=e.message?:"Could not complete this action. Try again.";Toast.makeText(this@ClassMateBloodActivity,status.text,Toast.LENGTH_LONG).show()}finally{busy=false}}}
 private suspend fun fetch(reset:Boolean=true){
  if(reset){offset=0;list.removeAllViews()}
  val page=JSONArray(ClassMateAuthApi.rpcText("blood_request_feed",JSONObject().put("result_offset",offset)))
  for(i in 0 until page.length()){
   val r=page.getJSONObject(i)
   val pad=ClassMateFeatureUi.dp(this,16)
   val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=ClassMateFeatureUi.surface(this@ClassMateBloodActivity);setPadding(pad,pad,pad,pad)}
   card.addView(TextView(this).apply {setTextColor(getColor(R.color.cm_text_primary));textSize=16f;text="${r.optString("blood_group")}  ·  ${r.optString("hospital")}\n${r.optInt("units")} unit(s) · ${r.optString("status")}\nNeeded by ${date(r.optString("needed_by"))}\n${r.optInt("volunteers")} volunteers"})
   card.addView(ClassMateFeatureUi.button(this,"View request"){details(r)})
   list.addView(card,LinearLayout.LayoutParams(-1,-2).apply{topMargin=ClassMateFeatureUi.dp(this@ClassMateBloodActivity,12)})
  }
  offset+=page.length()
  if(page.length()==20)list.addView(ClassMateFeatureUi.button(this,"More requests"){operation{list.removeViewAt(list.childCount-1);fetch(false)}})
  if(reset&&page.length()==0)list.addView(TextView(this).apply{text="No requests right now.";setTextColor(getColor(R.color.cm_text_secondary))})
  val target=intent.getStringExtra("request_id")
  if(reset&&target!=null){intent.removeExtra("request_id");val d=ClassMateAuthApi.rpc("blood_request_details",JSONObject().put("target_request",target));showDetails(d,d)}
 }
 private fun load(){operation{fetch()}}
 private fun date(value:String)=runCatching{java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM, hh:mm a"))}.getOrDefault(value)
 private fun dial(phone:String){if(phone.matches(Regex("\\+[1-9][0-9]{7,14}")))runCatching{startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:$phone")))}}
 private fun preferences(){operation{
  val p=ClassMateAuthApi.rpc("blood_preferences",JSONObject());val f=ClassMateFormUi(this)
  f.label("Join voluntarily. Volunteering shares your name and mobile with the organizer and verifier. Recorded donations pause matching alerts for 120 days; hospital screening is still required. Also enable Push notifications in Profile.")
  f.label("Last donation: ${p.optString("last_donation").takeUnless{it=="null"||it.isBlank()}?:"Not recorded"}")
  val enabled=CheckBox(this).apply{text="Receive matching blood alerts";isChecked=p.optBoolean("opted_in");setTextColor(getColor(R.color.cm_text_primary))};f.panel.addView(enabled)
  val donated=f.field("Record donation date (optional, YYYY-MM-DD)")
  val dialog=MaterialAlertDialogBuilder(this).setTitle("Donor settings").setView(f.scroll).setBackground(ClassMateFeatureUi.surface(this)).setNegativeButton("Cancel",null).create()
  f.panel.addView(ClassMateFeatureUi.button(this,"Save preferences",true){operation{val d=donated.text.toString().trim();if(d.isNotEmpty())java.time.LocalDate.parse(d);ClassMateAuthApi.rpc("save_blood_preferences",JSONObject().put("target_enabled",enabled.isChecked).put("donation_date",if(d.isBlank())JSONObject.NULL else d));dialog.dismiss();fetch()}});dialog.show()
 }}
 private fun create(){
  if(batch.isBlank()||batch=="null"){Toast.makeText(this,"Select your batch in Profile first",Toast.LENGTH_LONG).show();return}
  val f=ClassMateFormUi(this);f.label("Confirm details with the attendant. Your admin or batch CR verifies this request before alerts are sent.")
  val group=f.choice("Blood group needed",groups);val hospital=f.field("Hospital / patient location")
  val units=f.field("Units (pints)").apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText("1")}
  val deadline=f.field("Needed by").apply{isFocusable=false;setText("Choose date and time")};var deadlineText:String?=null
  deadline.setOnClickListener{val c=java.util.Calendar.getInstance();android.app.DatePickerDialog(this,{_,y,m,d->android.app.TimePickerDialog(this,{_,h,min->deadlineText=java.time.LocalDateTime.of(y,m+1,d,h,min).atZone(java.time.ZoneId.of("Asia/Dhaka")).toOffsetDateTime().toString();deadline.setText("$d/${m+1}/$y  %02d:%02d".format(h,min))},c.get(java.util.Calendar.HOUR_OF_DAY),c.get(java.util.Calendar.MINUTE),false).show()},c.get(java.util.Calendar.YEAR),c.get(java.util.Calendar.MONTH),c.get(java.util.Calendar.DAY_OF_MONTH)).show()}
  val phone=f.field("Attendant phone (01XXXXXXXXX)").apply{inputType=android.text.InputType.TYPE_CLASS_PHONE}
  val audience=f.choice("Reach",listOf("University-wide","My batch"));val compatible=CheckBox(this).apply{text="Hospital accepts compatible red-cell donors";setTextColor(getColor(R.color.cm_text_primary))};f.panel.addView(compatible)
  val key=UUID.randomUUID().toString();val dialog=MaterialAlertDialogBuilder(this).setTitle("Request blood").setView(f.scroll).setBackground(ClassMateFeatureUi.surface(this)).setNegativeButton("Cancel",null).create()
  f.panel.addView(ClassMateFeatureUi.button(this,"Submit for verification",true){operation{
   var n=phone.text.toString().replace(Regex("[\\s()-]"),"");if(n.matches(Regex("01[0-9]{9}")))n="+88$n"
   require(deadlineText!=null){"Choose a deadline"}
   ClassMateAuthApi.rpcText("create_blood_request",JSONObject().put("request_key",key).put("target_batch",batch).put("target_group",groups[group.selectedItemPosition]).put("target_hospital",hospital.text.toString()).put("target_units",units.text.toString().toInt()).put("target_deadline",deadlineText).put("target_phone",n).put("target_audience",if(audience.selectedItemPosition==0)"university" else "batch").put("allow_compatible",compatible.isChecked));dialog.dismiss();fetch()
  }});dialog.show()
 }
 private fun details(r:JSONObject){operation{showDetails(r,ClassMateAuthApi.rpc("blood_request_details",JSONObject().put("target_request",r.getString("id"))))}}
 private fun showDetails(r:JSONObject,d:JSONObject){
  val f=ClassMateFormUi(this);f.label("${r.optString("hospital")}\n${r.optInt("units")} unit(s) · ${date(r.optString("needed_by"))}\n${r.optString("status")}")
  f.label("Hospital screening and cross-matching are required. Volunteering shares your name and mobile with the organizer and verifier.")
  val dialog=MaterialAlertDialogBuilder(this).setTitle("${r.optString("blood_group")} blood request").setBackground(ClassMateFeatureUi.surface(this)).setView(f.scroll).setNegativeButton("Close",null).create()
  f.panel.addView(ClassMateFeatureUi.button(this,"Call attendant"){dial(d.optString("attendant_phone"))})
  fun action(label:String,rpc:String,args:JSONObject){f.panel.addView(ClassMateFeatureUi.button(this,label,true){
   val apply={operation{ClassMateAuthApi.rpcText(rpc,args);dialog.dismiss();fetch()}}
   if(rpc=="set_blood_request_status")MaterialAlertDialogBuilder(this).setTitle(label).setBackground(ClassMateFeatureUi.surface(this)).setMessage(if(args.optString("target_status")=="open")"Confirm you called the attendant and verified all request details. Matching donors will receive an alert." else "This closes the request and stops pending blood alerts.").setNegativeButton("Keep request",null).setPositiveButton("Confirm"){_,_->apply()}.show() else apply()
  })}
  if(r.optBoolean("can_donate")&&r.optString("my_response")!="interested")action("I can donate","respond_blood_request",JSONObject().put("target_request",r.getString("id")).put("target_response","interested"))
  if(r.optString("my_response")=="interested")action("Withdraw response","respond_blood_request",JSONObject().put("target_request",r.getString("id")).put("target_response","withdrawn"))
  if(r.optBoolean("can_verify")&&r.optString("status")=="pending"){
   f.label("Call the attendant and verify all details before approving.")
   action("Verify & send matching alerts","set_blood_request_status",JSONObject().put("target_request",r.getString("id")).put("target_status","open"))
   action("Reject request","set_blood_request_status",JSONObject().put("target_request",r.getString("id")).put("target_status","rejected"))
  }
  if(d.optBoolean("can_manage")&&r.optString("status") in listOf("open","pending")){
   action("Mark fulfilled","set_blood_request_status",JSONObject().put("target_request",r.getString("id")).put("target_status","fulfilled"))
   action("Cancel request","set_blood_request_status",JSONObject().put("target_request",r.getString("id")).put("target_status","cancelled"))
   val v=d.optJSONArray("volunteers")?:JSONArray();for(i in 0 until v.length()){val p=v.getJSONObject(i);f.panel.addView(ClassMateFeatureUi.button(this,"Call ${p.optString("full_name")}"){dial(p.optString("mobile_number"))})}
  };dialog.show()
 }
}

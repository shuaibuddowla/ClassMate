package com.shuaib.classmate.activities

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

internal object ClassMateHealthDialog {
    fun show(activity: AppCompatActivity,scope: CoroutineScope) {
        val form=ClassMateFormUi(activity)
        val status=form.label("Loading system health…")
        val panel=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        form.panel.addView(panel)
        val dialog=MaterialAlertDialogBuilder(activity).setTitle("System health").setView(form.scroll)
            .setPositiveButton("Close",null).setNeutralButton("Refresh",null).create()
        fun bytes(value: Long)=String.format(Locale.getDefault(),"%.1f MiB",value/1048576.0)
        fun row(label: String,value: String,warning: Boolean=false) {
            panel.addView(TextView(activity).apply {
                text="$label\n$value"; textSize=14f
                val d=activity.resources.displayMetrics.density
                setPadding(0,(8*d).toInt(),0,(8*d).toInt())
                setTextColor(activity.getColor(if(warning) R.color.cm_error else R.color.cm_text_primary))
            })
        }
        var loading=false
        fun load() {
            if(loading) return
            loading=true;status.visibility=View.VISIBLE;status.text="Refreshing…"
            dialog.getButton(-3)?.isEnabled=false
            scope.launch {
                try {
                    val health=ClassMateAuthApi.rpc("system_health",JSONObject())
                    if(!dialog.isShowing || activity.isDestroyed) return@launch
                    panel.removeAllViews();status.text="Current server snapshot · ${health.optString("observed_at").substringBefore('.') }"
                    row("Pending deliveries","${health.optLong("pending_jobs")} devices · ${health.optLong("unprepared_events")} events awaiting preparation")
                    val age=health.optLong("oldest_pending_notice_seconds")
                    row("Oldest pending academic alert","${age/60} minutes",age>300)
                    row("FCM accepted","${health.optLong("accepted_sends")} sends · acceptance does not guarantee phone display")
                    row("Failed deliveries",health.optLong("failed_jobs").toString(),health.optLong("failed_jobs")>0)
                    val states=health.optJSONObject("client_states") ?: JSONObject()
                    row("Phone reports",if(states.length()==0) "No client reports yet" else states.keys().asSequence().joinToString("\n") { "${it.replace('_',' ')}: ${states.optLong(it)}" })
                    val hosting=health.optJSONObject("hosting")
                    val db=health.optLong("database_bytes")
                    val limit=hosting?.optLong("database_limit_bytes") ?: 0
                    row("Database",bytes(db)+(if(limit>0) " / ${bytes(limit)}" else " · allowance unavailable"),limit>0 && db.toDouble()/limit>.8)
                    if(hosting==null) row("Hosting","No provider snapshot available. Run the hosting diagnostic.")
                    else {
                        val observed=hosting.optString("observed_at")
                        val stale=runCatching { Instant.parse(observed).isBefore(Instant.now().minusSeconds(86400)) }.getOrDefault(true)
                        row("Hosting","${hosting.optString("plan").replaceFirstChar { it.uppercase() }} · ${hosting.optString("compute_size").takeUnless { it=="null" } ?: "Compute unavailable"}\n${if(stale) "Stale snapshot" else "Verified snapshot"}: $observed",stale)
                        val details=hosting.optJSONObject("details") ?: JSONObject()
                        fun percent(key: String)=if(details.isNull(key)) "Unavailable" else String.format(Locale.getDefault(),"%.0f%%",details.optDouble(key)*100)
                        row("Compute usage","Memory: ${percent("memory_fraction")} · CPU: ${percent("cpu_fraction")}\n${details.optString("metrics_status","Metrics freshness unavailable")}",details.optString("metrics_status")!="Fresh provider metrics")
                        val egressLimit=hosting.optLong("egress_limit_bytes")
                        val usage=hosting.optLong("egress_bytes")
                        row("Bandwidth source",details.optString("egress_status","Provider usage unavailable")+"\n"+details.optString("egress_observed_at",""),details.optString("egress_status").startsWith("Stale"))
                        row("Organization bandwidth",if(hosting.isNull("egress_bytes")) "Usage unavailable · check provider Usage dashboard" else bytes(usage)+if(egressLimit>0) " / ${bytes(egressLimit)}" else "",!hosting.isNull("egress_bytes") && egressLimit>0 && usage.toDouble()/egressLimit>.8)
                    }
                    val tables=health.optJSONArray("largest_tables")
                    if(tables!=null) row("Largest tables",(0 until tables.length()).joinToString("\n") { val t=tables.getJSONObject(it);"${t.optString("name")}: ${bytes(t.optLong("bytes"))}" })
                    val failures=health.optJSONArray("failed_events")
                    if(failures!=null) for(i in 0 until failures.length()) {
                        val event=failures.getJSONObject(i)
                        val reasons=event.optJSONArray("reasons")
                        if(reasons!=null && reasons.length()>0) row("Failure details",(0 until reasons.length()).joinToString("\n") { val reason=reasons.getJSONObject(it);"${reason.optString("code")}: ${reason.optLong("devices")} devices" })
                        panel.addView(MaterialButton(activity,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                            text="Retry ${event.optLong("devices")} failed ${event.optString("kind").replace('_',' ')} deliveries"
                            setOnClickListener {
                                MaterialAlertDialogBuilder(activity).setTitle("Retry failed deliveries?").setMessage("Only failed devices will be retried. Accepted deliveries stay recorded.")
                                    .setNegativeButton("Cancel",null).setPositiveButton("Retry") { _,_ ->
                                        isEnabled=false
                                        scope.launch {
                                            try { ClassMateAuthApi.rpcText("retry_failed_notifications",JSONObject().put("target_event",event.getString("id")));load() }
                                            catch (_: Exception) { status.text="Retry failed. Refresh and try again.";isEnabled=true }
                                        }
                                    }.show()
                            }
                        })
                    }
                } catch (_: Exception) { if(dialog.isShowing) status.text="System health is unavailable. Owner access and a connection are required." }
                finally { loading=false;if(dialog.isShowing) dialog.getButton(-3)?.isEnabled=true }
            }
        }
        dialog.show();dialog.getButton(-3).setOnClickListener { load() };load()
    }
}

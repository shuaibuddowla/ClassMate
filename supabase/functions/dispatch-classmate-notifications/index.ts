import { createClient } from "npm:@supabase/supabase-js@2.57.0";
import { GoogleAuth } from "npm:google-auth-library@9.15.1";
import { classify, retryDelay } from "./policy.ts";
type Job={event_id:string;profile_id:string;token_hash:string;lease_id:string;kind:string;record_id:string};
const url=Deno.env.get("SUPABASE_URL") ?? "";
const secret=Deno.env.get("CLASSMATE_DISPATCH_SECRET") ?? "";
function authorized(supplied:string|null):boolean {
  if(!supplied || !secret || supplied.length!==secret.length) return false;
  let diff=0; for(let i=0;i<secret.length;i++) diff|=secret.charCodeAt(i)^supplied.charCodeAt(i); return diff===0;
}
Deno.serve(async request=>{
  if(request.method!=="POST" || !authorized(request.headers.get("x-dispatch-secret"))) return new Response("Forbidden",{status:403});
  const started=Date.now(),deadline=started+70_000;
  const db=createClient(url,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",{auth:{persistSession:false},global:{fetch:(input,init)=>fetch(input,{...init,signal:AbortSignal.timeout(Math.max(1,Math.min(10_000,deadline-Date.now())))})}}).schema("classmate");
  let sent=0,accepted=0,failed=0,cancelled=0;
  try {
    const project=new URL(url).hostname.split(".")[0];
    const mock=project==="qpryxvunhebwljlwmggk" && Deno.env.get("CLASSMATE_FCM_MOCK")==="true";
    let bearer:string|null=null,account:{project_id?:string}={};
    if(!mock) {
      account=JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") ?? "{}");
      bearer=(await new GoogleAuth({credentials:account,scopes:["https://www.googleapis.com/auth/firebase.messaging"]}).getAccessToken()) ?? null;
      if(!bearer || !account.project_id) throw new Error("FCM unavailable");
    }
    const prepared=await db.rpc("prepare_notification_jobs",{event_limit:5});
    if(prepared.error) throw new Error("Preparation failed");
    const finish=async(job:Job,outcome:string,error:string|null=null,delay=60)=>{
      const result=await db.rpc("finish_notification_job",{target_event:job.event_id,target_hash:job.token_hash,target_lease:job.lease_id,outcome,error_code:error,retry_seconds:delay});
      if(result.error) throw new Error("Acknowledgement unavailable");
    };
    while(Date.now()<deadline-20_000 && sent<1000) {
      // New academic events can overtake a release between device chunks.
      if(sent>0) {
        const next=await db.rpc("prepare_notification_jobs",{event_limit:5});
        if(next.error) throw new Error("Preparation failed");
        if(Date.now()>=deadline-20_000) break;
      }
      const claimed=await db.rpc("claim_notification_jobs",{claim_size:100});
      if(claimed.error) throw new Error("Claim failed");
      const jobs=(claimed.data ?? []) as Job[];
      if(!jobs.length) break;
      const sentBefore=sent;
      let skippedReal=false;
      let position=0;
      await Promise.all(Array.from({length:8},async()=>{
        for(;;) {
          const job=jobs[position++]; if(!job) return;
          if(Date.now()>deadline-17_000 || sent>=1000) { await finish(job,"release"); continue; }
          let failures=0;
          try {
            const context=await db.rpc("notification_job_context",{target_event:job.event_id,target_hash:job.token_hash,target_lease:job.lease_id});
            if(context.error) throw new Error("Context unavailable");
            const content=context.data;
            failures=content?.failures ?? 0;
            if(!content?.fcm_token) { await finish(job,"cancelled"); cancelled++; continue; }
            if(mock && !content.fcm_token.startsWith("classmate-load:")) { skippedReal=true;await finish(job,"release"); continue; }
            sent++;
            const release=job.kind==="app_update";
            let status=200,code:string|undefined,retryAfter:string|null=null;
            if(mock) await new Promise(resolve=>setTimeout(resolve,100)); // Healthy provider latency for staging benchmarks.
            if(!mock) {
              const response=await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(account.project_id!)}/messages:send`,{
                method:"POST",signal:AbortSignal.timeout(15_000),headers:{Authorization:`Bearer ${bearer}`,"Content-Type":"application/json"},
                body:JSON.stringify({message:{token:content.fcm_token,android:{priority:"HIGH",ttl:"86400s"},data:{
                  kind:job.kind,record_id:job.record_id,batch_id:content.batch_id ?? "",event_id:job.event_id,token_hash:job.token_hash,
                  recipient_id:job.profile_id,project_ref:project,version_code:String(content.version_code ?? ""),version_name:String(content.version_name ?? ""),
                  title:release?`ClassMate ${content.version_name} is available`:content.title,
                  body:release?"Tap to check and install the latest update.":content.body
                }}})
              });
              status=response.status; retryAfter=response.headers.get("retry-after");
              if(!response.ok) { const error=await response.json().catch(()=>({})); code=error.error?.details?.find((d:{errorCode?:string})=>d.errorCode)?.errorCode; }
            } else if(content.fcm_token.includes(":invalid:")) { status=404;code="UNREGISTERED"; }
            else if(content.fcm_token.includes(":throttle:")) { status=429;retryAfter="120"; }
            if(status>=200 && status<300) { await finish(job,"accepted"); accepted++; }
            else { const outcome=classify(status,code); await finish(job,outcome,code ?? `FCM_${status}`,retryDelay(content.failures ?? 0,retryAfter)); failed++; }
          } catch {
            await finish(job,"retry","TRANSPORT_OR_ACK_FAILURE",retryDelay(failures,null)).catch(()=>{}); failed++;
          }
        }
      }));
      if(skippedReal && sent===sentBefore) break;
    }
    const completed=await db.rpc("complete_notification_events");
    if(completed.error) throw new Error("Completion unavailable");
    console.log(JSON.stringify({accepted,failed,cancelled,sent,duration_ms:Date.now()-started,mock}));
    return Response.json({inspected:prepared.data,delivered:completed.data,accepted,failed,cancelled,sent,completed:completed.data,duration_ms:Date.now()-started,mock});
  } catch { return new Response("Notification service temporarily unavailable",{status:503}); }
});

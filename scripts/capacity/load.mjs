// Run only after fixtures.py seed. The private config and results stay in ignored build/.
import fs from 'node:fs';
const config=JSON.parse(fs.readFileSync('build/capacity-private.json','utf8'));
if(config.ref!=='qpryxvunhebwljlwmggk') throw Error('Load tests are restricted to staging');
const base=`https://${config.ref}.supabase.co/rest/v1/`;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const report={started_at:new Date().toISOString(),stages:[],stop_reason:null};
const stages=process.argv.includes('--smoke')?[[25,30]]:[[25,120],[100,120],[250,120],[500,900]];
let stopped=false;
function summary(values) {
  const sorted=values.map(v=>v.ms).sort((a,b)=>a-b),percent=p=>sorted[Math.min(sorted.length-1,Math.floor(sorted.length*p))] ?? null;
  return {requests:values.length,errors:values.filter(v=>!v.ok).length,bytes:values.reduce((n,v)=>n+v.bytes,0),p95_ms:percent(.95),p99_ms:percent(.99),error_rate:values.length?values.filter(v=>!v.ok).length/values.length:0};
}
for(const [count,duration] of stages) {
  if(stopped) break;
  const start=Date.now(),end=start+duration*1000,values=[],cold=[],errors=[];
  const guard=setInterval(()=>{
    const recent=values.filter(v=>v.at>=Date.now()-60_000);
    if(Date.now()-start>=60_000 && recent.length && summary(recent).error_rate>.05) { stopped=true;report.stop_reason='Unexpected API errors exceeded 5% for a minute'; }
    try {
      const resource=JSON.parse(fs.readFileSync('build/capacity-resource.json','utf8'));
      if(Date.now()-Date.parse(resource.observed_at)>=120_000) {stopped=true;report.stop_reason='Resource diagnostics became stale';}
      if(Date.now()-Date.parse(resource.observed_at)<120_000 && resource.maximum_fraction>=.85) {stopped=true;report.stop_reason='Verified resource usage reached 85%';}
    } catch {}
  },1000);
  async function request(user,path,method='GET',body=null,first=false) {
    const before=performance.now();let ok=false,bytes=0,status=0,failure=null;
    try {
      const response=await fetch(base+path,{method,signal:AbortSignal.timeout(15_000),headers:{apikey:config.anon,Authorization:'Bearer '+user.token,'Accept-Profile':'classmate','Content-Profile':'classmate','Content-Type':'application/json',Prefer:'return=minimal,resolution=merge-duplicates'},body:body===null?undefined:JSON.stringify(body)});
      const data=await response.arrayBuffer();bytes=data.byteLength;status=response.status;ok=response.ok;
    } catch(error) {status=0;failure={type:error.name,code:error.cause?.code ?? null};}
    const value={ms:performance.now()-before,bytes,ok,at:Date.now(),operation:path.split('?')[0]};
    (first?cold:values).push(value);if(!ok && errors.length<15) errors.push({operation:value.operation,status,...(failure ?? {})});
  }
  await Promise.all(config.users.slice(0,count).map(async(user,index)=>{
    let iteration=0;
    await request(user,`notices?select=id,title,body,published_at&batch_id=eq.${user.batch}&order=published_at.desc&limit=100`,'GET',null,true);
    await sleep(index/count*10_000);
    while(!stopped && Date.now()<end) {
      switch(iteration++%7) {
        case 0: await request(user,`routine_slots?select=id,semester_course_id,day_of_week,start_time,end_time,room,type&semester_course_id=eq.${user.offering}&order=start_time`);break;
        case 1: await request(user,`notices?select=id,title,body,published_at&batch_id=eq.${user.batch}&order=published_at.desc&limit=100`);await request(user,'rpc/notice_feed_details','POST',{target_ids:Array.from({length:50},(_,i)=>user.notice.slice(0,-12)+String(Number(user.notice.slice(-12))+i).padStart(12,'0'))});break;
        case 2: await request(user,'rpc/mark_notices_read','POST',{target_ids:Array.from({length:10},(_,i)=>user.notice.slice(0,-12)+String(Number(user.notice.slice(-12))+i).padStart(12,'0'))});break;
        case 3: await request(user,'notice_reactions?on_conflict=notice_id,profile_id','POST',{notice_id:user.notice,profile_id:user.id,liked:true});break;
        case 4: await request(user,'rpc/batch_friends','POST',{target_batch:user.batch,query_text:'Load',result_offset:0});break;
        case 5: await request(user,'rpc/notice_readers_page','POST',{target_notice:user.notice,page_size:50});break;
        case 6: await request(user,'rpc/timetable_details','POST',{target_batch:user.batch,target_date:new Date().toISOString().slice(0,10),target_course_ids:[user.offering]});break;
      }
      await sleep(10_000);
    }
  }));
  clearInterval(guard);
  const result={concurrent_users:count,duration_seconds:(Date.now()-start)/1000,cold:summary(cold),...summary(values),operations:Object.fromEntries([...new Set(values.map(v=>v.operation))].map(op=>[op,summary(values.filter(v=>v.operation===op))])),sample_errors:errors};
  result.every_operation_passed=Object.values(result.operations).every(op=>op.error_rate<.01 && op.p95_ms<1000 && op.p99_ms<3000);
  result.passed=result.error_rate<.01 && result.p95_ms<1000 && result.p99_ms<3000 && !stopped;
  report.stages.push(result);fs.writeFileSync('build/capacity-load-results.json',JSON.stringify(report,null,2));console.log(JSON.stringify(result));
}
report.finished_at=new Date().toISOString();fs.writeFileSync('build/capacity-load-results.json',JSON.stringify(report,null,2));
if(stopped) console.log('Load test stopped:',report.stop_reason);


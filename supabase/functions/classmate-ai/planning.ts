import { tools } from './tools.ts';
export type Action = {name:string; args:Record<string,unknown>;summary?:string};
export type Plan = {message:string; actions:Action[]; lookup?:string; lookup_batch?:string;owner_only?:boolean};
export const uuid = (v:unknown):v is string => typeof v==='string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
export function availableTools(owner:boolean,write:boolean,mode:string) {
 return tools.filter(t=>write && (owner || t.cr) && (mode!=='compose' || ['post_notice','ai_post_cancellation_notice'].includes(t.name)));
}
export function validatePlan(raw:unknown,owner:boolean,write:boolean,mode:string):Plan {
 if(!raw || typeof raw!=='object')throw new Error('AI returned an invalid response. Try again.');
 const p=raw as Plan;
 if(typeof p.message!=='string'||p.message.length>12000||!Array.isArray(p.actions)||p.actions.length>40)throw new Error('AI returned an invalid action plan.');
 if(p.lookup!==undefined&&(typeof p.lookup!=='string'||p.lookup.length>150))throw new Error('Invalid lookup.');
 if(p.lookup_batch!==undefined&&(!owner||!uuid(p.lookup_batch)))throw new Error('Batch lookup denied.');
 const allowed=availableTools(owner,write,mode);
 for(const a of p.actions){
  const tool=allowed.find(t=>t.name===a.name);
  if(!tool||!a.args||typeof a.args!=='object'||Array.isArray(a.args))throw new Error('AI proposed an unavailable action. Nothing was changed.');
  for(const key of Object.keys(a.args))if(!tool.arguments.some(x=>x.name===key))throw new Error('AI proposed an unknown field.');
  for(const field of tool.arguments){
   const v=a.args[field.name];
   if(v===undefined){if(!field.optional)throw new Error(`AI needs ${field.name.replaceAll('_',' ')} before continuing.`);continue;}
   if(v===null)continue; // Nullable creation IDs remain validated by the existing SQL RPC.
   if(field.type==='uuid'&&!uuid(v))throw new Error('AI could not resolve an existing record. Please be more specific.');
   if(field.type==='boolean'&&typeof v!=='boolean')throw new Error('Invalid AI boolean.');
   if(['numeric','smallint'].includes(field.type)&&(typeof v!=='number'||!Number.isFinite(v)))throw new Error('Invalid AI number.');
   if(field.type==='text'&&(typeof v!=='string'||v.length>10000))throw new Error('Invalid AI text.');
   if(field.type==='date'&&(typeof v!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(v)))throw new Error('Invalid AI date.');
   if(field.type==='time without time zone'&&(typeof v!=='string'||!/^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?$/.test(v)))throw new Error('Invalid departure time.');
  }
 }
 if(mode==='compose'&&p.actions.length>1)throw new Error('Compose can post only one notice at a time.');
 return {message:p.message,actions:p.actions,...(p.lookup?{lookup:p.lookup}:{}),...(p.lookup_batch?{lookup_batch:p.lookup_batch}:{})};
}
export function preserveSilent(plan:Plan,text:string){
 if(!/^\s*\/silent\b/i.test(text))return plan;
 for(const a of plan.actions){
  if(a.name==='post_notice')a.args.notice_title='/silent '+String(a.args.notice_title||'').replace(/^\/silent\s*/i,'');
  if(a.name==='ai_post_cancellation_notice')a.args.target_silent=true;
 }
 return plan;
}
export function retryDelay(value:string|null,now=Date.now()){
 const seconds=Number(value);
 if(value&&Number.isFinite(seconds))return Math.max(1000,seconds*1000);
 const stamp=value?Date.parse(value):NaN;
 return Number.isFinite(stamp)?Math.max(1000,stamp-now):60_000;
}
export function validateTranslation(source:{title:string;body:string},translated:unknown):{title:string;body:string}{
 if(!translated||typeof translated!=='object')throw new Error('Translation was incomplete. Try again.');
 const t=translated as {title:string;body:string};
 if(typeof t.title!=='string'||!t.title.trim()||typeof t.body!=='string'||t.title.length>2000||t.body.length>20000)throw new Error('Translation was incomplete. Try again.');
 const links=(text:string)=>(text.match(/https?:\/\/[^\s<>"']+/g)||[]).map(u=>u.replace(/[.,!?;:)\]।]+$/,'')).sort();
 if(JSON.stringify(links(source.title+'\n'+source.body))!==JSON.stringify(links(t.title+'\n'+t.body)))throw new Error('Translation changed a link. Please retry.');
 return {title:t.title,body:t.body};
}
export function describePlan(plan:Plan,contexts:unknown[]):Plan {
 const records:Record<string,unknown>[]=[];
 const walk=(value:unknown)=>{if(Array.isArray(value)){for(const row of value){if(row&&typeof row==='object'&&!Array.isArray(row))records.push(row as Record<string,unknown>);walk(row);}}else if(value&&typeof value==='object'){for(const v of Object.values(value))if(v&&typeof v==='object')walk(v);}};
 contexts.forEach(walk);
 const label=(id:unknown):string=>{
  if(!uuid(id))return '';
  const row=records.find(r=>r.id===id||r.offering_id===id)||records.find(r=>r.course_id===id);
  if(!row)return String(id); // Never hide the target if it could not be resolved.
  if(row.departure_time)return `${row.departure_time} campus / ${row.city_departure_time} city (${row.schedule_kind})`;
  if(row.semester_course_id&&row.start_time)return `${label(row.semester_course_id)} · day ${row.day_of_week} · ${row.start_time}–${row.end_time}`;
  return String(row.course_title||row.title||row.full_name||row.name||row.teacher_name||row.email||row.course_code||(row.batch_number?`Batch ${row.batch_number}`:id));
 };
 return {...plan,actions:plan.actions.map(a=>{
  const names=['target_id','target_course','target_semester_course','target_profile','target_resource','target_offering','target_record','target_teacher_record','target_department','target_semester','from_id','to_id'];
  const targets=names.map(k=>label(a.args[k])).filter(Boolean);
  const summary=a.name==='save_student_bus_schedule'?`${a.args.target_active===false?'Remove':'Save'} shared bus: ${a.args.target_campus_departure} campus / ${a.args.target_city_departure} city · ${a.args.target_kind}`:targets.join(' → ')||String(a.args.notice_title||a.args.target_title||a.args.target_name||a.name.replaceAll('_',' '));
  return {...a,summary};
 })};
}

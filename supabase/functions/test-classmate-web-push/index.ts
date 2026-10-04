import {createClient} from 'npm:@supabase/supabase-js@2.57.0';
import {GoogleAuth} from 'npm:google-auth-library@9.15.1';
import {withWebCors} from '../_shared/web-cors.ts';
import {deliveryPayload} from '../dispatch-classmate-notifications/payload.ts';
Deno.serve(request=>withWebCors(request,async request=>{
 if(request.method!=='POST')return new Response('Method not allowed',{status:405});
 const authorization=request.headers.get('authorization')||'';
 const url=Deno.env.get('SUPABASE_URL')||'';
 const caller=createClient(url,Deno.env.get('SUPABASE_ANON_KEY')||'',{global:{headers:{Authorization:authorization}},auth:{persistSession:false}});
 const {data:user,error}=await caller.auth.getUser(authorization.replace(/^Bearer /,''));
 if(error||!user.user)return new Response('Unauthorized',{status:401});
 const body=await request.json().catch(()=>({}));
 if(typeof body.token!=='string')return new Response('Browser registration required',{status:400});
 const claim=await caller.schema('classmate').rpc('claim_browser_push_test',{target_token:body.token});
 if(claim.error||claim.data!==true)return new Response('Register this browser or wait one minute before testing again.',{status:429});
 try{
  const credentials=JSON.parse(Deno.env.get('FCM_SERVICE_ACCOUNT_JSON')||'{}');
  const bearer=await new GoogleAuth({credentials,scopes:['https://www.googleapis.com/auth/firebase.messaging']}).getAccessToken();
  const result=await fetch(`https://fcm.googleapis.com/v1/projects/${credentials.project_id}/messages:send`,{method:'POST',signal:AbortSignal.timeout(15000),headers:{Authorization:`Bearer ${bearer}`,'Content-Type':'application/json'},body:JSON.stringify(deliveryPayload(body.token,{event_id:crypto.randomUUID(),recipient_id:user.user.id,project_ref:new URL(url).hostname.split('.')[0],record_id:'',batch_id:'',kind:'web_test',title:'ClassMate Web is connected',body:'Notifications are working for this browser.'},'web'))});
  if(!result.ok)return new Response('The push provider rejected this browser registration.',{status:502});
  return Response.json({accepted:true});
 }catch{return new Response('Push provider temporarily unavailable',{status:503});}
}));

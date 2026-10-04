import { retryDelay } from './planning.ts';
type Key={key:string;group:string};
type Cooldown={read:(group:string)=>Promise<number>;write:(group:string,until:number)=>Promise<void>};
export class ProviderError extends Error {constructor(message:string,public status=503){super(message);}}
export async function generateJson(keys:Key[],model:string,system:string,input:string,schema:unknown,cooldown:Cooldown,fetcher:typeof fetch=fetch,budget=60_000){
 if(!keys.length)throw new ProviderError('ClassMate AI is not configured yet. Add the Gemini keys in Supabase secrets.');
 const shuffled=keys.slice();const offset=crypto.getRandomValues(new Uint32Array(1))[0]%keys.length;
 const ordered=shuffled.slice(offset).concat(shuffled.slice(0,offset));
 const deadline=Date.now()+budget;let transient=false;
 for(const credential of ordered){
  if(Date.now()>deadline-2000)break;
  if(await cooldown.read(credential.group)>Date.now())continue;
  let response:Response;
  try{response=await fetcher(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,{
   method:'POST',headers:{'Content-Type':'application/json','x-goog-api-key':credential.key},signal:AbortSignal.timeout(Math.min(25000,deadline-Date.now())),
   body:JSON.stringify({systemInstruction:{parts:[{text:system}]},contents:[{role:'user',parts:[{text:input}]}],
    generationConfig:{temperature:0.2,maxOutputTokens:8000,responseMimeType:'application/json',responseJsonSchema:schema}})});
  }catch{transient=true;continue;}
  if(response.status===429){await cooldown.write(credential.group,Date.now()+retryDelay(response.headers.get('Retry-After')));transient=true;continue;}
  if(response.status>=500){transient=true;continue;}
  if([401,403].includes(response.status))continue; // Alternate configured credentials may still be usable.
  if(!response.ok)throw new ProviderError('The configured AI model rejected the request. Check its model name and availability.',502);
  const data=await response.json();const text=(data.candidates?.[0]?.content?.parts||[]).map((p:{text?:string})=>p.text||'').join('');
  try{return JSON.parse(text);}catch{throw new ProviderError('AI could not finish this response. Try a shorter request.',502);}
 }
 throw new ProviderError(transient?'AI is temporarily busy. Please retry shortly.':'AI is unavailable. Check the configured keys or wait for the project quota to reset.',429);
}

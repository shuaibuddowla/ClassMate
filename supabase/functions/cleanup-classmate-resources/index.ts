import { createClient } from "npm:@supabase/supabase-js@2.57.0";
import { S3Client,DeleteObjectCommand } from "npm:@aws-sdk/client-s3@3.893.0";
Deno.serve(async request=>{
  const secret=Deno.env.get("CLASSMATE_DISPATCH_SECRET") ?? "",supplied=request.headers.get("x-dispatch-secret") ?? "";
  let diff=secret.length^supplied.length; for(let i=0;i<secret.length;i++) diff|=secret.charCodeAt(i)^(supplied.charCodeAt(i)||0);
  if(request.method!=="POST" || !secret || diff) return new Response("Forbidden",{status:403});
  const db=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!).schema("classmate");
  const s3=new S3Client({region:"auto",endpoint:`https://${Deno.env.get("R2_ACCOUNT_ID")}.r2.cloudflarestorage.com`,credentials:{accessKeyId:Deno.env.get("R2_ACCESS_KEY_ID")!,secretAccessKey:Deno.env.get("R2_SECRET_ACCESS_KEY")!}});
  const jobs=await db.from("resource_cleanup_jobs").select("object_key,attempts").lte("not_before",new Date().toISOString()).order("created_at").limit(20);
  if(jobs.error) return new Response("Cleanup unavailable",{status:503});
  let removed=0;
  const deadline=Date.now()+60_000;
  for(const job of jobs.data ?? []) {
    if(Date.now()>deadline-12_000) break;
    try {
      await s3.send(new DeleteObjectCommand({Bucket:Deno.env.get("R2_BUCKET"),Key:job.object_key}),{abortSignal:AbortSignal.timeout(10_000)});
      const result=await db.from("resource_cleanup_jobs").delete().eq("object_key",job.object_key); if(!result.error) removed++;
    } catch { await db.from("resource_cleanup_jobs").update({attempts:job.attempts+1,last_error:"Protected object removal pending"}).eq("object_key",job.object_key); }
  }
  return Response.json({removed});
});

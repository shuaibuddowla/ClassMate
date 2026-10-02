import { createClient } from "npm:@supabase/supabase-js@2.57.0";
const base = "https://github.com/shuaibuddowla/ClassMate/releases/download/";
Deno.serve(async request => {
  const secret = Deno.env.get("CLASSMATE_DISPATCH_SECRET") ?? "";
  const supplied = request.headers.get("x-dispatch-secret") ?? "";
  let difference = secret.length ^ supplied.length;
  for (let i=0;i<secret.length;i++) difference |= secret.charCodeAt(i) ^ (supplied.charCodeAt(i) || 0);
  if (request.method !== "POST" || !secret || difference) return new Response("Forbidden",{status:403});
  try {
    const { version_name, version_code } = await request.json();
    if (typeof version_name !== "string" || !/^\d+\.\d+\.\d+$/.test(version_name) || !Number.isSafeInteger(version_code) || version_code<1) return new Response("Invalid release",{status:400});
    // Only published, real GitHub assets can create the global broadcast. Never trust an arbitrary caller URL.
    const response = await fetch(`${base}v${version_name}/update.json?check=${Date.now()}`);
    if (!response.ok) return new Response("Published metadata unavailable; retry",{status:503});
    const metadata = await response.json();
    if (metadata.versionCode !== version_code || metadata.versionName !== version_name ||
        metadata.apkUrl !== `${base}v${version_name}/classmate-${version_name}.apk` ||
        !/^[a-f0-9]{64}$/i.test(metadata.sha256) || !Number.isSafeInteger(metadata.apkSize) || metadata.apkSize<1)
      return new Response("Release metadata mismatch",{status:409});
    const apk = await fetch(metadata.apkUrl,{method:"HEAD"});
    if (!apk.ok || Number(apk.headers.get("content-length")) !== metadata.apkSize) return new Response("Release asset unavailable",{status:503});
    const db = createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,{auth:{persistSession:false}}).schema("classmate");
    const result = await db.rpc("enqueue_app_release",{target_code:version_code,target_name:version_name});
    if (result.error) return new Response("Could not queue release",{status:503});
    return Response.json({queued:true,event_id:result.data});
  } catch { return new Response("Release notification unavailable; retry",{status:503}); }
});

import { createClient } from "npm:@supabase/supabase-js@2.57.0";
import { S3Client, GetObjectCommand } from "npm:@aws-sdk/client-s3@3.893.0";
import { getSignedUrl } from "npm:@aws-sdk/s3-request-presigner@3.893.0";

Deno.serve(async (request) => {
  if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });
  const authorization = request.headers.get("authorization") ?? "";
  if (!authorization.startsWith("Bearer ")) return new Response("Unauthorized", { status: 401 });
  const { resource_id } = await request.json().catch(() => ({}));
  if (typeof resource_id !== "string" || !/^[0-9a-f-]{36}$/i.test(resource_id)) {
    return new Response("Invalid resource ID", { status: 400 });
  }
  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
  const accountId = Deno.env.get("R2_ACCOUNT_ID") ?? "";
  const accessKeyId = Deno.env.get("R2_ACCESS_KEY_ID") ?? "";
  const secretAccessKey = Deno.env.get("R2_SECRET_ACCESS_KEY") ?? "";
  const bucket = Deno.env.get("R2_BUCKET") ?? "";
  if (![url, anonKey, accountId, accessKeyId, secretAccessKey, bucket].every(Boolean)) {
    return new Response("Server configuration incomplete", { status: 503 });
  }
  // Query as the caller. RLS filters pending users and cross-batch resources.
  const db = createClient(url, anonKey, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false },
  }).schema("classmate");
  const { data, error } = await db.from("file_metadata")
    .select("r2_object_key,mime_type,status").eq("id", resource_id).single();
  if (error || !data || data.status !== "active") return new Response("Not found", { status: 404 });

  const s3 = new S3Client({
    region: "auto",
    endpoint: `https://${accountId}.r2.cloudflarestorage.com`,
    credentials: { accessKeyId, secretAccessKey },
  });
  const signedUrl = await getSignedUrl(s3, new GetObjectCommand({
    Bucket: bucket,
    Key: data.r2_object_key,
  }), { expiresIn: 60 });
  return Response.json({ url: signedUrl, expires_in: 60, mime_type: data.mime_type }, {
    headers: { "Cache-Control": "no-store" },
  });
});

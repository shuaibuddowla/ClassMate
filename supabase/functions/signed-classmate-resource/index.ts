import { createClient } from "npm:@supabase/supabase-js@2.57.0";
import { S3Client, GetObjectCommand } from "npm:@aws-sdk/client-s3@3.893.0";
import { getSignedUrl } from "npm:@aws-sdk/s3-request-presigner@3.893.0";
import { withWebCors } from "../_shared/web-cors.ts";

Deno.serve(request => withWebCors(request, async (request) => {
  if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });

  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const anonKey = Deno.env.get("SB_PUBLISHABLE_KEY") || Deno.env.get("SUPABASE_ANON_KEY") || "";
  const serviceKey = Deno.env.get("SB_SECRET_KEY") || Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  const accountId = Deno.env.get("R2_ACCOUNT_ID") ?? "";
  const accessKeyId = Deno.env.get("R2_ACCESS_KEY_ID") ?? "";
  const secretAccessKey = Deno.env.get("R2_SECRET_ACCESS_KEY") ?? "";
  const bucket = Deno.env.get("R2_BUCKET") ?? "";

  if (![url, anonKey, accountId, accessKeyId, secretAccessKey, bucket].every(Boolean)) {
    return new Response("Server configuration incomplete", { status: 503 });
  }

  const rawAuth = request.headers.get("authorization") || (request.headers.get("apikey") ? `Bearer ${request.headers.get("apikey")}` : `Bearer ${anonKey}`);
  if (!rawAuth.startsWith("Bearer ")) return new Response("Unauthorized", { status: 401 });

  const token = rawAuth.slice(7).trim();
  const isServerAdmin = Boolean(serviceKey && token === serviceKey);

  const { resource_id, disposition, filename } = await request.json().catch(() => ({}));
  if (typeof resource_id !== "string" || !/^[0-9a-f-]{36}$/i.test(resource_id)) {
    return new Response("Invalid resource ID", { status: 400 });
  }

  let data: { r2_object_key?: string | null; mime_type?: string | null; status?: string | null; link_url?: string | null } | null = null;

  // 1. Authorized server-to-server call with serviceKey (e.g. Next.js /api/public-download)
  if (isServerAdmin) {
    const admin = createClient(url, serviceKey, { auth: { persistSession: false } }).schema("classmate");
    const { data: adminData, error: adminErr } = await admin.from("file_metadata")
      .select("r2_object_key,mime_type,status,link_url")
      .eq("id", resource_id)
      .single();
    if (!adminErr && adminData && adminData.status === "active") {
      data = adminData;
    }
  } else {
    // 2. Client caller: strictly enforce Row Level Security with caller's authentication token
    const caller = createClient(url, anonKey, {
      global: { headers: { Authorization: `Bearer ${token}` } },
      auth: { persistSession: false },
    }).schema("classmate");

    const { data: callerData } = await caller.from("file_metadata")
      .select("r2_object_key,mime_type,status,link_url")
      .eq("id", resource_id)
      .single();

    if (callerData && callerData.status === "active") {
      data = callerData;
    } else {
      // 3. Fallback: check if resource is explicitly public via get_public_resource RPC
      const { data: pubData } = await caller.rpc("get_public_resource", { target_identifier: resource_id });
      if (pubData && pubData.id) {
        const admin = createClient(url, serviceKey, { auth: { persistSession: false } }).schema("classmate");
        const { data: verifiedData } = await admin.from("file_metadata")
          .select("r2_object_key,mime_type,status,link_url")
          .eq("id", resource_id)
          .single();
        if (verifiedData && verifiedData.status === "active") {
          data = verifiedData;
        }
      }
    }
  }

  if (!data || data.status !== "active") return new Response("Not found", { status: 404 });

  if (data.link_url) {
    return Response.json({ url: data.link_url, expires_in: 3600, mime_type: data.mime_type || "text/uri-list" }, {
      headers: { "Cache-Control": "no-store" },
    });
  }

  if (!data.r2_object_key) {
    return new Response("File not available", { status: 404 });
  }

  const s3 = new S3Client({
    region: "auto",
    endpoint: `https://${accountId}.r2.cloudflarestorage.com`,
    credentials: { accessKeyId, secretAccessKey },
  });

  const commandParams: any = {
    Bucket: bucket,
    Key: data.r2_object_key,
  };

  if (filename) {
    const safeAscii = String(filename).replace(/[^\x20-\x7E]/g, "_").replace(/["\r\n\\]/g, "");
    const encoded = encodeURIComponent(String(filename));
    const disp = disposition === "attachment" ? "attachment" : "inline";
    commandParams.ResponseContentDisposition = `${disp}; filename="${safeAscii}"; filename*=UTF-8''${encoded}`;
  } else if (disposition === "attachment") {
    commandParams.ResponseContentDisposition = "attachment";
  } else if (disposition === "inline") {
    commandParams.ResponseContentDisposition = "inline";
  }

  if (data.mime_type) {
    commandParams.ResponseContentType = data.mime_type;
  }

  const signedUrl = await getSignedUrl(s3, new GetObjectCommand(commandParams), { expiresIn: 3600 });

  return Response.json({ url: signedUrl, expires_in: 3600, mime_type: data.mime_type }, {
    headers: { "Cache-Control": "no-store" },
  });
}));

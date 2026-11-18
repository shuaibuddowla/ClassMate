import { createClient } from "npm:@supabase/supabase-js@2.57.0";
import { S3Client, PutObjectCommand, GetObjectCommand, DeleteObjectCommand } from "npm:@aws-sdk/client-s3@3.893.0";
import { getSignedUrl } from "npm:@aws-sdk/s3-request-presigner@3.893.0";
import { withWebCors } from "../_shared/web-cors.ts";

function parseJwtPayload(token: string): { sub?: string; exp?: number; role?: string } | null {
  try {
    const parts = token.split(".");
    if (parts.length !== 3) return null;
    const base64Url = parts[1];
    const base64 = base64Url.replace(/-/g, "+").replace(/_/g, "/");
    const jsonPayload = decodeURIComponent(
      atob(base64)
        .split("")
        .map((c) => "%" + ("00" + c.charCodeAt(0).toString(16)).slice(-2))
        .join("")
    );
    return JSON.parse(jsonPayload);
  } catch {
    return null;
  }
}

Deno.serve(request => withWebCors(request, async (request) => {
  try {
  if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });
  const authorization = request.headers.get("authorization") ?? "";
  const jwt = authorization.replace(/^Bearer /i, "").trim();
  if (!jwt) return new Response("Unauthorized", { status: 401 });

  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const anonKey = Deno.env.get("SB_PUBLISHABLE_KEY") || Deno.env.get("SUPABASE_ANON_KEY") || "";
  const serviceKey = Deno.env.get("SB_SECRET_KEY") || Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  const accountId = Deno.env.get("R2_ACCOUNT_ID") ?? "";
  const accessKeyId = Deno.env.get("R2_ACCESS_KEY_ID") ?? "";
  const secretAccessKey = Deno.env.get("R2_SECRET_ACCESS_KEY") ?? "";
  const bucket = Deno.env.get("R2_BUCKET") ?? "";
  if (![url, anonKey, serviceKey, accountId, accessKeyId, secretAccessKey, bucket].every(Boolean)) {
    return new Response(JSON.stringify({ error: "Server configuration incomplete" }), {
      status: 503,
      headers: { "Content-Type": "application/json" }
    });
  }

  const adminClient = createClient(url, serviceKey, { auth: { persistSession: false } });
  const admin = adminClient.schema("classmate");
  const caller = createClient(url, anonKey, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false },
  }).schema("classmate");

  // Resolve user identity
  let userId: string | null = null;
  try {
    const { data: adminUserData, error: adminErr } = await adminClient.auth.getUser(jwt);
    if (!adminErr && adminUserData?.user?.id) {
      userId = adminUserData.user.id;
    }
  } catch {
    // Admin auth lookup fallback
  }

  if (!userId) {
    try {
      const callerAuth = createClient(url, anonKey, {
        global: { headers: { Authorization: authorization } },
        auth: { persistSession: false },
      });
      const { data: callerUserData, error: callerErr } = await callerAuth.auth.getUser();
      if (!callerErr && callerUserData?.user?.id) {
        userId = callerUserData.user.id;
      }
    } catch {
      // Caller auth lookup fallback
    }
  }

  if (!userId) {
    const payload = parseJwtPayload(jwt);
    const now = Math.floor(Date.now() / 1000);
    if (payload?.sub && (!payload.exp || payload.exp > now)) {
      userId = payload.sub;
    }
  }

  if (!userId) return new Response(JSON.stringify({ error: "Unauthorized session" }), {
    status: 401,
    headers: { "Content-Type": "application/json" }
  });

  const s3 = new S3Client({
    region: "auto",
    endpoint: `https://${accountId}.r2.cloudflarestorage.com`,
    credentials: { accessKeyId, secretAccessKey }
  });
  const body = await request.json().catch(() => ({}));

  if (body.action === "start") {
    const { batch_id, semester_course_id, title, file_type, mime_type, size_bytes } = body;
    const category = body.category || "other";
    if (typeof batch_id !== "string" || typeof title !== "string" || !title.trim()
      || typeof file_type !== "string" || typeof mime_type !== "string"
      || !Number.isSafeInteger(size_bytes) || size_bytes < 1 || size_bytes > 500_000_000) {
      return new Response(JSON.stringify({ error: "Invalid upload metadata" }), {
        status: 400,
        headers: { "Content-Type": "application/json" }
      });
    }

    // Enforce 9.9 GB storage limit (10,630,044,057 bytes)
    const LIMIT_BYTES = 10_630_044_057;
    const { data: statsData, error: statsError } = await admin.rpc("get_r2_storage_stats");
    if (!statsError && statsData && typeof statsData.total_bytes === "number") {
      if (statsData.total_bytes + size_bytes > LIMIT_BYTES) {
        return new Response(JSON.stringify({
          error: "Cloudflare R2 storage quota reached (9.9 GB limit). File uploads are paused.",
          total_bytes: statsData.total_bytes,
          limit_bytes: LIMIT_BYTES,
        }), { status: 400, headers: { "Content-Type": "application/json" } });
      }
    }

    const courseId = typeof semester_course_id === "string" ? semester_course_id : null;
    if (courseId) {
      const { data: course } = await admin.from("semester_courses")
        .select("semester_id").eq("id", courseId).single();
      const { data: semester } = course
        ? await admin.from("semesters").select("batch_id,status")
          .eq("id", course.semester_id).single()
        : { data: null };
      if (!semester || semester.batch_id !== batch_id || semester.status !== "active") {
        return new Response(JSON.stringify({ error: "Course semester is not active in batch" }), {
          status: 409,
          headers: { "Content-Type": "application/json" }
        });
      }
    }

    // Check permissions
    let isAllowed = false;
    const { data: allowed, error: permissionError } = await caller.rpc("can_post", {
      target_batch: batch_id, target_course: courseId,
    });
    if (!permissionError && allowed === true) {
      isAllowed = true;
    } else {
      // Direct database verification fallback for the verified userId
      const { data: profile } = await admin.from("profiles")
        .select("role,is_cr,batch_id,cr_batch_id,verification_status")
        .eq("id", userId).maybeSingle();
      if (profile && profile.verification_status === "active") {
        if (profile.role === "admin") {
          isAllowed = true;
        } else if (profile.role === "student" && profile.is_cr && (profile.batch_id === batch_id || profile.cr_batch_id === batch_id)) {
          isAllowed = true;
        } else if (profile.role === "teacher") {
          isAllowed = true;
        }
      }
    }

    if (!isAllowed) {
      return new Response(JSON.stringify({ error: "You do not have permission to post files here." }), {
        status: 403,
        headers: { "Content-Type": "application/json" }
      });
    }

    const { data: batch } = await admin.from("batches").select("department_id")
      .eq("id", batch_id).single();
    if (!batch) return new Response(JSON.stringify({ error: "Batch not found" }), {
      status: 404,
      headers: { "Content-Type": "application/json" }
    });

    const key = `academic/${batch.department_id}/${batch_id}/${crypto.randomUUID()}`;
    const { data: resource, error: insertError } = await admin.from("file_metadata").insert({
      department_id: batch.department_id,
      batch_id,
      semester_course_id: courseId,
      uploaded_by: userId,
      title: title.trim(),
      category,
      file_type,
      r2_object_key: key,
      mime_type,
      size_bytes,
      status: "pending",
    }).select("id").single();

    if (insertError || !resource) {
      return new Response(JSON.stringify({ error: insertError?.message || "Metadata creation failed" }), {
        status: 400,
        headers: { "Content-Type": "application/json" }
      });
    }

    const signedUrl = await getSignedUrl(s3, new PutObjectCommand({
      Bucket: bucket, Key: key, ContentType: mime_type,
    }), { expiresIn: 900 });

    return Response.json({ resource_id: resource.id, upload_url: signedUrl, expires_in: 900 },
      { headers: { "Cache-Control": "no-store" } });
  }

  if (body.action === "finish" && typeof body.resource_id === "string") {
    const { data: resource } = await admin.from("file_metadata")
      .select("id,r2_object_key,size_bytes,mime_type,uploaded_by,batch_id,semester_course_id,status")
      .eq("id", body.resource_id).single();
    if (!resource || resource.uploaded_by !== userId || resource.status !== "pending") {
      return new Response(JSON.stringify({ error: "Pending file not found" }), {
        status: 404,
        headers: { "Content-Type": "application/json" }
      });
    }

    let contentLength = 0;
    try {
      const signedGetUrl = await getSignedUrl(s3, new GetObjectCommand({
        Bucket: bucket,
        Key: resource.r2_object_key,
      }), { expiresIn: 60 });
      const getRes = await fetch(signedGetUrl, {
        method: "GET",
        headers: { Range: "bytes=0-0" },
      });
      if (!getRes.ok && getRes.status !== 206) {
        return new Response(JSON.stringify({ error: "Uploaded object not found in storage", storage_status: getRes.status }), {
          status: 409,
          headers: { "Content-Type": "application/json" }
        });
      }
      const contentRange = getRes.headers.get("content-range") || "";
      const totalMatch = contentRange.match(/\/(\d+)$/);
      contentLength = totalMatch ? Number(totalMatch[1]) : Number(getRes.headers.get("content-length") || 0);
    } catch (storageErr: any) {
      return new Response(JSON.stringify({ error: "Could not verify storage object", details: storageErr?.message }), {
        status: 502,
        headers: { "Content-Type": "application/json" }
      });
    }

    if (contentLength > 0 && contentLength !== Number(resource.size_bytes)) {
      return new Response(JSON.stringify({
        error: "Uploaded object size does not match metadata",
        expected_bytes: resource.size_bytes,
        actual_bytes: contentLength,
      }), {
        status: 409,
        headers: { "Content-Type": "application/json" }
      });
    }

    const { error: updateError } = await admin.from("file_metadata").update({ status: "active" })
      .eq("id", resource.id).eq("status", "pending");
    if (updateError) {
      console.error("file_metadata activation error:", updateError);
      return new Response(JSON.stringify({ error: updateError.message || "Activation failed", details: updateError }), {
        status: 503,
        headers: { "Content-Type": "application/json" }
      });
    }
    return Response.json({ resource_id: resource.id, status: "active" });
  }

  if (body.action === "delete" && typeof body.resource_id === "string") {
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(body.resource_id)) {
      return new Response("Invalid resource ID", { status: 400 });
    }
    const { data: resource, error: lookupError } = await admin.from("file_metadata")
      .select("id,r2_object_key,batch_id,semester_course_id,status")
      .eq("id", body.resource_id).maybeSingle();
    if (lookupError) return new Response("Could not check resource", { status: 503 });
    if (!resource) return Response.json({ resource_id: body.resource_id, status: "deleted" });
    if (!["active", "deleting", "archived"].includes(resource.status))
      return new Response("Resource cannot be deleted", { status: 409 });

    let isAllowed = false;
    const { data: allowed } = await caller.rpc("can_post", {
      target_batch: resource.batch_id, target_course: resource.semester_course_id,
    });
    if (allowed === true) {
      isAllowed = true;
    } else {
      const { data: profile } = await admin.from("profiles")
        .select("role,is_cr,batch_id,cr_batch_id,verification_status")
        .eq("id", userId).maybeSingle();
      if (profile && profile.verification_status === "active") {
        if (profile.role === "admin") isAllowed = true;
        else if (profile.role === "student" && profile.is_cr && (profile.batch_id === resource.batch_id || profile.cr_batch_id === resource.batch_id)) isAllowed = true;
        else if (profile.role === "teacher") isAllowed = true;
      }
    }

    if (!isAllowed) return new Response("Forbidden", { status: 403 });

    if (resource.status === "active") {
      const { error } = await admin.from("file_metadata")
        .update({ status: "deleting" }).eq("id", resource.id).eq("status", "active");
      if (error) return new Response("Could not begin deletion", { status: 503 });
    }
    try {
      if (resource.r2_object_key && !resource.r2_object_key.startsWith("link")) {
        const signedDeleteUrl = await getSignedUrl(s3, new DeleteObjectCommand({ Bucket: bucket, Key: resource.r2_object_key }), { expiresIn: 60 });
        await fetch(signedDeleteUrl, { method: "DELETE" });
      }
    } catch {
      if (resource.status === "active") await admin.from("file_metadata")
        .update({ status: "active" }).eq("id", resource.id).eq("status", "deleting");
      return new Response("Could not delete protected file", { status: 503 });
    }
    const { error } = await admin.rpc("finalize_resource_deletion", {
      target_resource: resource.id,
    });
    if (error) return new Response("Could not finish permanent deletion; retry", { status: 503 });
    return Response.json({ resource_id: resource.id, status: "deleted" });
  }

  return new Response("Unknown action", { status: 400 });
  } catch (err: any) {
    console.error("upload-classmate-resource error:", err);
    return Response.json({
      error: err?.message || String(err),
      name: err?.name,
      stack: err?.stack,
    }, { status: 500 });
  }
}));

import { createClient } from "npm:@supabase/supabase-js@2.57.0";
import { S3Client, PutObjectCommand, HeadObjectCommand, DeleteObjectCommand } from "npm:@aws-sdk/client-s3@3.893.0";
import { getSignedUrl } from "npm:@aws-sdk/s3-request-presigner@3.893.0";

Deno.serve(async (request) => {
  if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });
  const authorization = request.headers.get("authorization") ?? "";
  const jwt = authorization.replace(/^Bearer /, "");
  if (jwt === authorization) return new Response("Unauthorized", { status: 401 });
  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const accountId = Deno.env.get("R2_ACCOUNT_ID") ?? "";
  const accessKeyId = Deno.env.get("R2_ACCESS_KEY_ID") ?? "";
  const secretAccessKey = Deno.env.get("R2_SECRET_ACCESS_KEY") ?? "";
  const bucket = Deno.env.get("R2_BUCKET") ?? "";
  if (![url, anonKey, serviceKey, accountId, accessKeyId, secretAccessKey, bucket].every(Boolean)) {
    return new Response("Server configuration incomplete", { status: 503 });
  }
  const authClient = createClient(url, anonKey, { auth: { persistSession: false } });
  const { data: userData, error: userError } = await authClient.auth.getUser(jwt);
  if (userError || !userData.user) return new Response("Unauthorized", { status: 401 });
  const caller = createClient(url, anonKey, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false },
  }).schema("classmate");
  const admin = createClient(url, serviceKey, { auth: { persistSession: false } }).schema("classmate");
  const s3 = new S3Client({ region: "auto",
    endpoint: `https://${accountId}.r2.cloudflarestorage.com`,
    credentials: { accessKeyId, secretAccessKey } });
  const body = await request.json().catch(() => ({}));

  if (body.action === "start") {
    const { batch_id, semester_course_id, title, file_type, mime_type, size_bytes, category } = body;
    if (typeof batch_id !== "string" || typeof title !== "string" || !title.trim()
      || typeof file_type !== "string" || typeof mime_type !== "string"
      || !Number.isSafeInteger(size_bytes) || size_bytes < 1 || size_bytes > 100_000_000
      || !["notes", "slides", "questions", "syllabus", "other"].includes(category)) {
      return new Response("Invalid upload metadata", { status: 400 });
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
        return new Response("Course semester is not active in batch", { status: 409 });
      }
    }
    const { data: allowed, error: permissionError } = await caller.rpc("can_post", {
      target_batch: batch_id, target_course: courseId,
    });
    if (permissionError || allowed !== true) return new Response("Forbidden", { status: 403 });
    const { data: batch } = await admin.from("batches").select("department_id")
      .eq("id", batch_id).single();
    if (!batch) return new Response("Batch not found", { status: 404 });
    const key = `academic/${batch.department_id}/${batch_id}/${crypto.randomUUID()}`;
    const { data: resource, error } = await admin.from("file_metadata").insert({
      department_id: batch.department_id,
      batch_id,
      semester_course_id: courseId,
      uploaded_by: userData.user.id,
      title: title.trim(),
      category,
      file_type,
      r2_object_key: key,
      mime_type,
      size_bytes,
      status: "pending",
    }).select("id").single();
    if (error || !resource) return new Response("Metadata creation failed", { status: 400 });
    const signedUrl = await getSignedUrl(s3, new PutObjectCommand({
      Bucket: bucket, Key: key, ContentType: mime_type,
    }), { expiresIn: 120 });
    return Response.json({ resource_id: resource.id, upload_url: signedUrl, expires_in: 120 },
      { headers: { "Cache-Control": "no-store" } });
  }

  if (body.action === "finish" && typeof body.resource_id === "string") {
    const { data: resource } = await admin.from("file_metadata")
      .select("id,r2_object_key,size_bytes,mime_type,uploaded_by,batch_id,semester_course_id,status")
      .eq("id", body.resource_id).single();
    if (!resource || resource.uploaded_by !== userData.user.id || resource.status !== "pending") {
      return new Response("Not found", { status: 404 });
    }
    const { data: allowed } = await caller.rpc("can_post", {
      target_batch: resource.batch_id, target_course: resource.semester_course_id,
    });
    if (allowed !== true) return new Response("Forbidden", { status: 403 });
    const object = await s3.send(new HeadObjectCommand({ Bucket: bucket, Key: resource.r2_object_key }))
      .catch(() => null);
    if (!object || object.ContentLength !== resource.size_bytes
      || object.ContentType !== resource.mime_type) {
      return new Response("Uploaded object does not match metadata", { status: 409 });
    }
    const { error } = await admin.from("file_metadata").update({ status: "active" })
      .eq("id", resource.id).eq("status", "pending");
    if (error) return new Response("Activation failed", { status: 503 });
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
    // A lost response after successful deletion must be safe to retry.
    if (!resource) return Response.json({ resource_id: body.resource_id, status: "deleted" });
    if (!["active", "deleting", "archived"].includes(resource.status))
      return new Response("Resource cannot be deleted", { status: 409 });
    const { data: allowed } = await caller.rpc("can_post", {
      target_batch: resource.batch_id, target_course: resource.semester_course_id,
    });
    if (allowed !== true) return new Response("Forbidden", { status: 403 });
    if (resource.status === "active") {
      const { error } = await admin.from("file_metadata")
        .update({ status: "deleting" }).eq("id", resource.id).eq("status", "active");
      if (error) return new Response("Could not begin deletion", { status: 503 });
    }
    try {
      await s3.send(new DeleteObjectCommand({ Bucket: bucket, Key: resource.r2_object_key }));
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
});

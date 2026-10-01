import { createClient } from "npm:@supabase/supabase-js@2.57.0";
import { GoogleAuth } from "npm:google-auth-library@9.15.1";

type Outbox = {
  id: string;
  kind: string;
  record_id: string;
  batch_id: string;
  semester_course_id: string | null;
  routing_label: string;
  attempts: number;
};

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const workerSecret = Deno.env.get("CLASSMATE_DISPATCH_SECRET") ?? "";
const serviceAccountJson = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") ?? "";

function authorized(value: string | null): boolean {
  if (!value || !workerSecret || value.length !== workerSecret.length) return false;
  let different = 0;
  for (let i = 0; i < value.length; i++) different |= value.charCodeAt(i) ^ workerSecret.charCodeAt(i);
  return different === 0;
}

Deno.serve(async (request) => {
  if (request.method !== "POST" || !authorized(request.headers.get("x-dispatch-secret"))) {
    return new Response("Forbidden", { status: 403 });
  }
  if (!supabaseUrl || !serviceKey || !serviceAccountJson) {
    return new Response("Missing server configuration", { status: 503 });
  }
  const account = JSON.parse(serviceAccountJson);
  const google = new GoogleAuth({
    credentials: account,
    scopes: ["https://www.googleapis.com/auth/firebase.messaging"],
  });
  const accessToken = await google.getAccessToken();
  if (!accessToken || !account.project_id) return new Response("FCM credentials unavailable", { status: 503 });
  const db = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false } }).schema("classmate");
  const { data, error } = await db.from("notification_outbox").select(
    "id,kind,record_id,batch_id,semester_course_id,routing_label,attempts",
  ).is("delivered_at", null).lt("attempts", 10).order("created_at").limit(20);
  if (error) return new Response("Outbox unavailable", { status: 503 });

  let delivered = 0;
  for (const event of (data ?? []) as Outbox[]) {
    // Re-read current membership for every delivery. The stored label is only
    // diagnostic data; FCM receives tokens, never a client-subscribable topic.
    const { data: students, error: studentError } = await db.from("profiles")
      .select("id").eq("role", "student").eq("verification_status", "active")
      .eq("batch_id", event.batch_id);
    if (studentError) continue;
    const ids = (students ?? []).map((row) => row.id as string);
    const { data: tokens, error: tokenError } = ids.length
      ? await db.from("device_tokens").select("fcm_token").in("profile_id", ids)
      : { data: [], error: null };
    if (tokenError) continue;

    let failed = false;
    for (const row of tokens ?? []) {
      const response = await fetch(
        `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(account.project_id)}/messages:send`,
        {
          method: "POST",
          headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
          body: JSON.stringify({ message: {
            token: row.fcm_token,
            data: {
              kind: event.kind,
              record_id: event.record_id,
            },
          } }),
        },
      );
      if (!response.ok) failed = true;
    }
    if (failed) {
      await db.from("notification_outbox").update({
        attempts: event.attempts + 1,
        last_error: "FCM delivery failed; retry pending",
      }).eq("id", event.id);
    } else {
      await db.from("notification_outbox").update({
        delivered_at: new Date().toISOString(),
        attempts: event.attempts + 1,
        last_error: null,
      }).eq("id", event.id);
      delivered++;
    }
  }
  return Response.json({ inspected: data?.length ?? 0, delivered });
});

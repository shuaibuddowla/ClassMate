import { createClient } from "npm:@supabase/supabase-js@2.57.0";
import { GoogleAuth } from "npm:google-auth-library@9.15.1";

type Event = { id: string; kind: string; record_id: string; batch_id: string; department_id: string; attempts: number };
const url = Deno.env.get("SUPABASE_URL") ?? "";
const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const secret = Deno.env.get("CLASSMATE_DISPATCH_SECRET") ?? "";
const credentials = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") ?? "";
function authorized(value: string | null): boolean {
  if (!value || !secret || value.length !== secret.length) return false;
  let mismatch = 0;
  for (let i = 0; i < value.length; i++) mismatch |= value.charCodeAt(i) ^ secret.charCodeAt(i);
  return mismatch === 0;
}
async function fingerprint(token: string): Promise<string> {
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  return Array.from(new Uint8Array(hash)).map(b => b.toString(16).padStart(2,"0")).join("");
}
Deno.serve(async request => {
  if (request.method !== "POST" || !authorized(request.headers.get("x-dispatch-secret"))) return new Response("Forbidden", { status: 403 });
  if (!url || !key || !credentials) return new Response("Missing server configuration", { status: 503 });
  try {
    const account = JSON.parse(credentials);
    const google = new GoogleAuth({ credentials: account, scopes: ["https://www.googleapis.com/auth/firebase.messaging"] });
    const token = await google.getAccessToken();
    if (!token || !account.project_id) return new Response("FCM credentials unavailable", { status: 503 });
    const db = createClient(url, key, { auth: { persistSession: false } }).schema("classmate");
    const claimed = await db.rpc("claim_notification_events", { batch_size: 20 });
    if (claimed.error) return new Response("Outbox unavailable", { status: 503 });
    const projectRef = new URL(url).hostname.split(".")[0];
    let delivered = 0;
    for (const event of (claimed.data ?? []) as Event[]) {
      let failure: string | null = null;
      try {
        // Current membership is evaluated on the server for each event. No public topics.
        const audience = await db.rpc("notification_recipients", { target_event: event.id });
        if (audience.error) throw new Error("Recipient lookup failed");
        const table = event.kind === "notice" ? "notices" : event.kind === "file" ? "file_metadata" : "class_changes";
        const record = await db.from(table).select(event.kind === "notice" ? "title,body,batch_id,department_id" : "batch_id,department_id")
          .eq("id",event.record_id).maybeSingle();
        if (record.error) throw new Error("Event lookup failed");
        if (record.data && record.data.batch_id === event.batch_id && record.data.department_id === event.department_id) {
          for (const recipient of audience.data ?? []) {
            const hash = await fingerprint(recipient.fcm_token);
            const previous = await db.from("notification_deliveries").select("token_hash").eq("outbox_id",event.id).eq("token_hash",hash).maybeSingle();
            if (previous.error) throw new Error("Delivery receipt lookup failed");
            if (previous.data) continue;
            const response = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(account.project_id)}/messages:send`, {
              method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
              body: JSON.stringify({ message: { token: recipient.fcm_token,
                android: { priority: "HIGH", ttl: "86400s" },
                data: { kind: event.kind, record_id: event.record_id, batch_id: event.batch_id,
                  recipient_id: recipient.profile_id, project_ref: projectRef,
                  title: String(record.data.title ?? "ClassMate update").slice(0,200),
                  body: String(record.data.body ?? "New class update available").slice(0,240) }
              } })
            });
            if (!response.ok) {
              const error = await response.json().catch(() => ({}));
              const unregistered = error.error?.details?.some((detail: { errorCode?: string }) => detail.errorCode === "UNREGISTERED");
              if (unregistered) await db.from("device_tokens").delete().eq("profile_id",recipient.profile_id).eq("fcm_token",recipient.fcm_token);
              else failure = `FCM HTTP ${response.status}; retry pending`;
              continue;
            }
            const receipt = await db.from("notification_deliveries").upsert({ outbox_id: event.id, profile_id: recipient.profile_id, token_hash: hash });
            if (receipt.error) failure = "Could not record delivery; retry pending";
          }
        }
      } catch (error) { failure = error instanceof Error ? error.message : "Dispatch failed"; }
      const result = await db.from("notification_outbox").update({
        processing_until: null, last_error: failure, ...(failure ? {} : { delivered_at: new Date().toISOString() })
      }).eq("id",event.id);
      if (!failure && !result.error) delivered++;
    }
    return Response.json({ inspected: claimed.data?.length ?? 0, delivered });
  } catch { return new Response("Notification service temporarily unavailable", { status: 503 }); }
});

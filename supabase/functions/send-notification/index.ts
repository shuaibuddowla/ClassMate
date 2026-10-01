import {createRemoteJWKSet, jwtVerify, type JWTPayload} from "npm:jose@6.1.0";

const FIREBASE_PROJECT_ID = Deno.env.get("FIREBASE_PROJECT_ID") ?? "classmate-a016a";
const firebaseKeys = createRemoteJWKSet(
  new URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"),
);

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-batch-id, x-semester-id",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Cache-Control": "no-store",
};

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response(null, {status: 204, headers: corsHeaders});
  try {
    if (request.method !== "POST") throw new HttpError(405, "POST required");
    if (!(request.headers.get("content-type") ?? "").startsWith("application/json")) {
      throw new HttpError(415, "JSON body required");
    }
    const contentLength = Number(request.headers.get("content-length") ?? "0");
    if (contentLength > 32_000) throw new HttpError(413, "Request body is too large");

    const auth = await authenticate(request);
    const payload = await request.json() as Record<string, unknown>;
    const data = asRecord(payload.data);
    const type = String(data.type ?? "");
    if (!type) throw new HttpError(400, "Notification type is required");

    if (type === "chat_message") {
      if (String(data.senderId ?? "") !== auth.uid) throw new HttpError(403, "Sender identity mismatch");
      if (payload.included_segments && String(data.roomId ?? "") !== "group_main") {
        throw new HttpError(403, "Only the main group chat can target a broadcast segment");
      }
    } else {
      const batchId = String(data.batchId ?? "").trim().toLowerCase();
      const contextBatch = (request.headers.get("x-batch-id") ?? "").trim().toLowerCase();
      const semester = (request.headers.get("x-semester-id") ?? "").trim().toLowerCase();
      if (!/^[a-z0-9_-]{2,30}$/.test(contextBatch) || !/^[a-z0-9_-]{1,30}$/.test(semester)) {
        throw new HttpError(400, "A valid batch and semester context are required");
      }
      if (batchId !== contextBatch) throw new HttpError(400, "Notification batch does not match request context");

      if (!await canSendV2Notification(auth.token, batchId)) {
        throw new HttpError(403, "You are not authorized for this notification batch");
      }

      // Never accept caller-provided global segments or filters. All batch
      // pushes are constrained to the verified batch tag.
      delete payload.included_segments;
      delete payload.filters;
      payload.filters = [{field: "tag", key: "batchId", relation: "=", value: batchId}];
    }

    const appId = Deno.env.get("ONESIGNAL_APP_ID");
    const restApiKey = Deno.env.get("ONESIGNAL_REST_API_KEY");
    if (!appId || !restApiKey) throw new HttpError(503, "OneSignal is not configured in Supabase");
    payload.app_id = appId;
    const response = await fetch("https://api.onesignal.com/notifications", {
      method: "POST",
      headers: {"Content-Type": "application/json", Authorization: `Key ${restApiKey}`},
      body: JSON.stringify(payload),
    });
    return jsonResponse(await response.text(), response.status, response.headers.get("content-type") ?? "application/json");
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 500;
    const message = error instanceof Error ? error.message : "Internal server error";
    return jsonResponse(JSON.stringify({error: message}), status);
  }
});

async function authenticate(request: Request): Promise<{token: string; uid: string; claims: JWTPayload}> {
  const token = (request.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
  if (!token) throw new HttpError(401, "Authentication required");
  try {
    const {payload} = await jwtVerify(token, firebaseKeys, {
      algorithms: ["RS256"],
      audience: FIREBASE_PROJECT_ID,
      issuer: `https://securetoken.google.com/${FIREBASE_PROJECT_ID}`,
    });
    if (!payload.sub) throw new Error("Missing subject");
    return {token, uid: payload.sub, claims: payload};
  } catch {
    throw new HttpError(401, "Invalid or expired Firebase session");
  }
}

async function canSendV2Notification(token: string, batchId: string): Promise<boolean> {
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const publishableKey = Deno.env.get("SUPABASE_ANON_KEY") ?? Deno.env.get("SUPABASE_PUBLISHABLE_KEY");
  if (!supabaseUrl || !publishableKey) return false;
  const response = await fetch(`${supabaseUrl.replace(/\/$/, "")}/rest/v1/rpc/can_send_v2_batch_notification`, {
    method: "POST",
    headers: {
      apikey: publishableKey,
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({target_batch_code: batchId}),
  });
  if (!response.ok) return false;
  const result = await response.json();
  return result === true || (Array.isArray(result) && result[0] === true);
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function jsonResponse(body: string, status: number, contentType = "application/json"): Response {
  return new Response(body, {status, headers: {...corsHeaders, "Content-Type": contentType}});
}

class HttpError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

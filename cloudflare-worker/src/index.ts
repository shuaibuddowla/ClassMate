import {createRemoteJWKSet, jwtVerify, type JWTPayload} from "jose";

interface Env {
  FIREBASE_PROJECT_ID: string;
  GEMINI_API_KEY: string;
  GROQ_API_KEY: string;
  GITHUB_LIBRARY_TOKEN: string;
  GITHUB_OWNER: string;
  GITHUB_REPO: string;
  GITHUB_RELEASE_TAG: string;
  TELEGRAM_BOT_TOKEN: string;
  TELEGRAM_CHANNEL_ID: string;
  ONESIGNAL_REST_API_KEY: string;
  ONESIGNAL_APP_ID: string;
  ARCHIVE_API_URL: string;
  ARCHIVE_INTEGRATION_SECRET: string;
}

interface AuthContext {
  token: string;
  uid: string;
  claims: JWTPayload;
}

interface UserAccess {
  role: string;
  permissions: Record<string, boolean>;
  batchId: string;
  adminBatchIds: string[];
}

const firebaseKeys = createRemoteJWKSet(
  new URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"),
);

const geminiModels = new Set([
  "gemini-2.5-flash",
  "gemini-3.5-flash",
  "gemini-2.5-pro",
  "gemini-1.5-flash",
  "gemini-1.5-pro",
]);

const groqModels = new Set([
  "llama-3.3-70b-versatile",
  "llama-3.1-8b-instant",
  "mixtral-8x7b-32768",
  "gemma2-9b-it",
]);

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") return cors(new Response(null, {status: 204}));

    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") {
      return json({ok: true, service: "classmate-secure-api"});
    }

    try {
      const auth = await authenticate(request, env);

      if (url.pathname === "/v1/ai/gemini" && request.method === "POST") {
        return cors(await proxyGemini(request, env));
      }
      if (url.pathname === "/v1/ai/groq" && request.method === "POST") {
        return cors(await proxyGroq(request, env));
      }
      if (url.pathname === "/v1/notifications" && request.method === "POST") {
        return cors(await sendNotification(request, env, auth));
      }
      if (url.pathname.startsWith("/v1/archive/")) {
        return cors(await proxyArchive(request, url, env, auth));
      }
      if (url.pathname === "/v1/telegram/upload" && request.method === "POST") {
        await requireBatchPermission(request, env, auth, ["canUploadPDF", "canUploadLibrary"]);
        return cors(await proxyTelegramUpload(request, env));
      }
      if (url.pathname === "/v1/telegram/test" && request.method === "POST") {
        await requireSuperAdmin(env, auth);
        return cors(await testTelegram(env));
      }
      if (url.pathname.startsWith("/v1/telegram/files/") && request.method === "GET") {
        const fileId = decodeURIComponent(url.pathname.slice("/v1/telegram/files/".length));
        return cors(await downloadTelegramFile(fileId, env));
      }
      if (url.pathname === "/v1/github/upload" && request.method === "POST") {
        await requireBatchPermission(request, env, auth, ["canUploadPDF", "canUploadLibrary"]);
        return cors(await uploadGitHubAsset(request, url, env));
      }
      if (url.pathname.startsWith("/v1/github/assets/") && request.method === "GET") {
        const assetId = url.pathname.slice("/v1/github/assets/".length);
        return cors(await downloadGitHubAsset(assetId, env));
      }

      return cors(json({error: "Not found"}, 404));
    } catch (error) {
      const status = error instanceof HttpError ? error.status : 500;
      const message = error instanceof Error ? error.message : "Internal server error";
      return cors(json({error: message}, status));
    }
  },
} satisfies ExportedHandler<Env>;

async function authenticate(request: Request, env: Env): Promise<AuthContext> {
  const header = request.headers.get("Authorization") ?? "";
  if (!header.startsWith("Bearer ")) throw new HttpError(401, "Authentication required");
  const token = header.slice(7).trim();
  if (!token) throw new HttpError(401, "Authentication required");

  try {
    const issuer = `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}`;
    const {payload} = await jwtVerify(token, firebaseKeys, {
      algorithms: ["RS256"],
      audience: env.FIREBASE_PROJECT_ID,
      issuer,
    });
    if (!payload.sub) throw new Error("Missing subject");
    return {token, uid: payload.sub, claims: payload};
  } catch {
    throw new HttpError(401, "Invalid or expired Firebase session");
  }
}

async function loadUserAccess(env: Env, auth: AuthContext): Promise<UserAccess> {
  const project = encodeURIComponent(env.FIREBASE_PROJECT_ID);
  const uid = encodeURIComponent(auth.uid);
  const response = await fetch(
    `https://firestore.googleapis.com/v1/projects/${project}/databases/(default)/documents/users/${uid}`,
    {headers: {Authorization: `Bearer ${auth.token}`}},
  );
  if (!response.ok) throw new HttpError(403, "Unable to verify user permissions");
  const document = await response.json<Record<string, unknown>>() as {
    fields?: Record<string, {
      stringValue?: string;
      mapValue?: {fields?: Record<string, {booleanValue?: boolean}>};
      arrayValue?: {values?: Array<{stringValue?: string}>};
    }>;
  };
  const fields = document.fields ?? {};
  const permissionFields = fields.permissions?.mapValue?.fields ?? {};
  const permissions: Record<string, boolean> = {};
  for (const [key, value] of Object.entries(permissionFields)) permissions[key] = value.booleanValue === true;
  const adminBatchIds = (fields.adminBatchIds?.arrayValue?.values ?? [])
    .map((value) => value.stringValue?.trim().toLowerCase())
    .filter((value): value is string => Boolean(value));
  return {
    role: fields.role?.stringValue ?? "student",
    permissions,
    batchId: fields.batchId?.stringValue?.trim().toLowerCase() ?? "",
    adminBatchIds,
  };
}

async function requirePermission(env: Env, auth: AuthContext, allowed: string[]): Promise<void> {
  const access = await loadUserAccess(env, auth);
  if (access.role === "admin" || access.role === "superadmin") return;
  if (allowed.some((permission) => access.permissions[permission])) return;
  throw new HttpError(403, "You do not have permission for this action");
}

async function requireSuperAdmin(env: Env, auth: AuthContext): Promise<void> {
  const access = await loadUserAccess(env, auth);
  if (!isGlobalSuperAdmin(access)) throw new HttpError(403, "Global superadmin access required");
}

function isGlobalSuperAdmin(access: UserAccess): boolean {
  // Keep the legacy spelling only while existing accounts are migrated.
  return access.role === "global_super_admin" || access.role === "global_superadmin";
}

function canManageBatch(access: UserAccess, batchId: string): boolean {
  return isGlobalSuperAdmin(access) ||
    ((access.role === "admin" || access.role === "superadmin" || access.role === "super_admin") &&
      access.adminBatchIds.includes(batchId));
}

function targetContext(request: Request): {batchId: string; semesterId: string} {
  const batchId = (request.headers.get("X-Batch-Id") ?? "").trim().toLowerCase();
  const semesterId = (request.headers.get("X-Semester-Id") ?? "").trim().toLowerCase();
  if (!/^[a-z0-9_-]{2,30}$/.test(batchId) || !/^[a-z0-9_-]{1,30}$/.test(semesterId)) {
    throw new HttpError(400, "A valid batch and semester context are required");
  }
  return {batchId, semesterId};
}

async function requireBatchPermission(
  request: Request,
  env: Env,
  auth: AuthContext,
  allowed: string[],
): Promise<{batchId: string; semesterId: string}> {
  const context = targetContext(request);
  const access = await loadUserAccess(env, auth);
  const permitted = canManageBatch(access, context.batchId) ||
    (access.batchId === context.batchId && allowed.some((permission) => access.permissions[permission]));
  if (!permitted) throw new HttpError(403, "You are not authorized for this batch");
  return context;
}

async function proxyArchive(
  request: Request,
  url: URL,
  env: Env,
  auth: AuthContext,
): Promise<Response> {
  if (!env.ARCHIVE_API_URL || !env.ARCHIVE_INTEGRATION_SECRET) {
    throw new HttpError(503, "Archive integration is not configured");
  }
  const relativePath = url.pathname.slice("/v1/archive".length);
  const readAllowed = request.method === "GET" &&
    (/^\/(archive|resources|search)$/.test(relativePath) ||
      /^\/(resource|download)\/[A-Za-z0-9_-]+$/.test(relativePath));
  const courseCreate = request.method === "POST" && relativePath === "/courses";
  const onboarding = request.method === "POST" && relativePath === "/onboarding";
  const uploadMutation = request.method === "POST" &&
    (relativePath === "/uploads" || /^\/uploads\/[A-Za-z0-9_-]+\/(complete|cancel)$/.test(relativePath));
  const resourceAdmin = request.method === "POST" &&
    /^\/resources\/[A-Za-z0-9_-]+\/(edit|delete)$/.test(relativePath);
  if (!readAllowed && !courseCreate && !onboarding && !uploadMutation && !resourceAdmin) {
    throw new HttpError(404, "Archive endpoint not found");
  }

  const context = targetContext(request);
  const access = await loadUserAccess(env, auth);
  const belongsToBatch = access.batchId === context.batchId;
  const managesBatch = canManageBatch(access, context.batchId);
  if (!belongsToBatch && !managesBatch) {
    throw new HttpError(403, "You are not authorized for this batch");
  }
  if (courseCreate && !managesBatch) {
    throw new HttpError(403, "Only an authorized batch administrator can create courses");
  }
  if (uploadMutation) {
    const canUpload = managesBatch || (belongsToBatch &&
      (access.permissions.canUploadPDF || access.permissions.canUploadLibrary));
    if (!canUpload) throw new HttpError(403, "You do not have permission to upload library files");
  }
  if (resourceAdmin && !isGlobalSuperAdmin(access)) {
    throw new HttpError(403, "Global super administrator access required");
  }

  const endpoint = env.ARCHIVE_API_URL.replace(/\/$/, "") + relativePath + url.search;
  const headers = new Headers();
  const contentType = request.headers.get("Content-Type");
  if (contentType) headers.set("Content-Type", contentType);
  headers.set("X-ClassMate-Integration", env.ARCHIVE_INTEGRATION_SECRET);
  headers.set("X-ClassMate-Uid", auth.uid);
  headers.set("X-ClassMate-Email", String(auth.claims.email ?? ""));
  headers.set("X-ClassMate-Name", encodeURIComponent(String(auth.claims.name ?? "ClassMate user")));
  headers.set("X-ClassMate-Batch", context.batchId);
  headers.set("X-ClassMate-Semester", context.semesterId);
  const response = await fetch(endpoint, {
    method: request.method,
    headers,
    body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
    redirect: "manual",
  });
  return new Response(response.body, {
    status: response.status,
    headers: {
      "Content-Type": response.headers.get("Content-Type") ?? "application/json",
      "Cache-Control": "no-store",
    },
  });
}

async function proxyGemini(request: Request, env: Env): Promise<Response> {
  requireJsonSize(request, 96_000);
  const model = request.headers.get("X-AI-Model") ?? "gemini-2.5-flash";
  if (!geminiModels.has(model)) throw new HttpError(400, "Unsupported Gemini model");
  return fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
    method: "POST",
    headers: {"Content-Type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY},
    body: request.body,
  });
}

async function proxyGroq(request: Request, env: Env): Promise<Response> {
  requireJsonSize(request, 96_000);
  const body = await request.text();
  let parsed: {model?: string};
  try {
    parsed = JSON.parse(body) as {model?: string};
  } catch {
    throw new HttpError(400, "Invalid JSON body");
  }
  if (!parsed.model || !groqModels.has(parsed.model)) throw new HttpError(400, "Unsupported Groq model");
  return fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: {"Content-Type": "application/json", Authorization: `Bearer ${env.GROQ_API_KEY}`},
    body,
  });
}

async function sendNotification(request: Request, env: Env, auth: AuthContext): Promise<Response> {
  requireJsonSize(request, 32_000);
  const payload = await request.json<Record<string, unknown>>();
  const data = (payload.data ?? {}) as Record<string, unknown>;
  const type = String(data.type ?? "");

  if (type === "chat_message") {
    if (String(data.senderId ?? "") !== auth.uid) throw new HttpError(403, "Sender identity mismatch");
    if (payload.included_segments && String(data.roomId ?? "") !== "group_main") {
      throw new HttpError(403, "Only the main group chat can target a broadcast segment");
    }
  } else {
    const permissions = type === "poll"
      ? ["canCreatePolls"]
      : type === "cancellation" || type === "substitute"
        ? ["canSendClassCancel"]
        : type === "resource"
          ? ["canUploadPDF", "canUploadLibrary"]
          : ["canPostNotices"];
    const batchId = String(data.batchId ?? "").trim().toLowerCase();
    const context = targetContext(request);
    if (batchId !== context.batchId) throw new HttpError(400, "Notification batch does not match request context");
    const access = await loadUserAccess(env, auth);
    const permitted = canManageBatch(access, batchId) ||
      (access.batchId === batchId && permissions.some((permission) => access.permissions[permission]));
    if (!permitted) throw new HttpError(403, "You are not authorized for this notification batch");
    // Never forward caller-controlled broadcast segments or filters. Batch
    // delivery is derived solely from the verified tenant context.
    delete payload.included_segments;
    delete payload.filters;
    payload.filters = [{field: "tag", key: "batchId", relation: "=", value: batchId}];
  }

  payload.app_id = env.ONESIGNAL_APP_ID;
  return fetch("https://api.onesignal.com/notifications", {
    method: "POST",
    headers: {"Content-Type": "application/json", Authorization: `Key ${env.ONESIGNAL_REST_API_KEY}`},
    body: JSON.stringify(payload),
  });
}

async function proxyTelegramUpload(request: Request, env: Env): Promise<Response> {
  const contentType = request.headers.get("Content-Type");
  if (!contentType?.startsWith("multipart/form-data")) throw new HttpError(400, "Multipart upload required");
  return fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendDocument`, {
    method: "POST",
    headers: {"Content-Type": contentType},
    body: request.body,
  });
}

async function testTelegram(env: Env): Promise<Response> {
  return fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendMessage`, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({chat_id: env.TELEGRAM_CHANNEL_ID, text: "✅ ClassMate bot is connected and working!"}),
  });
}

async function downloadTelegramFile(fileId: string, env: Env): Promise<Response> {
  if (!fileId || fileId.length > 512) throw new HttpError(400, "Invalid Telegram file ID");
  const metadata = await fetch(
    `https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/getFile?file_id=${encodeURIComponent(fileId)}`,
  );
  const result = await metadata.json<{ok?: boolean; result?: {file_path?: string}}>();
  const path = result.result?.file_path;
  if (!metadata.ok || !result.ok || !path) throw new HttpError(502, "Unable to resolve Telegram file");
  return fetch(`https://api.telegram.org/file/bot${env.TELEGRAM_BOT_TOKEN}/${path}`);
}

async function uploadGitHubAsset(request: Request, url: URL, env: Env): Promise<Response> {
  const name = url.searchParams.get("name")?.trim();
  if (!name || name.length > 180 || name.includes("/") || name.includes("\\")) {
    throw new HttpError(400, "Invalid asset name");
  }
  const headers = githubHeaders(env);
  const release = await fetch(
    `https://api.github.com/repos/${encodeURIComponent(env.GITHUB_OWNER)}/${encodeURIComponent(env.GITHUB_REPO)}/releases/tags/${encodeURIComponent(env.GITHUB_RELEASE_TAG)}`,
    {headers},
  );
  if (!release.ok) return release;
  const releaseJson = await release.json<{upload_url?: string}>();
  const uploadBase = releaseJson.upload_url?.split("{")[0];
  if (!uploadBase) throw new HttpError(502, "GitHub release has no upload URL");
  return fetch(`${uploadBase}?name=${encodeURIComponent(name)}`, {
    method: "POST",
    headers: {...headers, "Content-Type": request.headers.get("Content-Type") ?? "application/octet-stream"},
    body: request.body,
  });
}

async function downloadGitHubAsset(assetId: string, env: Env): Promise<Response> {
  if (!/^\d+$/.test(assetId)) throw new HttpError(400, "Invalid GitHub asset ID");
  return fetch(
    `https://api.github.com/repos/${encodeURIComponent(env.GITHUB_OWNER)}/${encodeURIComponent(env.GITHUB_REPO)}/releases/assets/${assetId}`,
    {headers: {...githubHeaders(env), Accept: "application/octet-stream"}, redirect: "follow"},
  );
}

function githubHeaders(env: Env): Record<string, string> {
  return {
    Authorization: `Bearer ${env.GITHUB_LIBRARY_TOKEN}`,
    Accept: "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "ClassMate-Cloudflare-Worker",
  };
}

function requireJsonSize(request: Request, maxBytes: number): void {
  const type = request.headers.get("Content-Type") ?? "";
  if (!type.startsWith("application/json")) throw new HttpError(415, "JSON body required");
  const length = Number(request.headers.get("Content-Length") ?? "0");
  if (length > maxBytes) throw new HttpError(413, "Request body is too large");
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {"Content-Type": "application/json; charset=utf-8"},
  });
}

function cors(response: Response): Response {
  const headers = new Headers(response.headers);
  headers.set("Access-Control-Allow-Origin", "*");
  headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-AI-Model, X-Batch-Id, X-Semester-Id");
  headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  headers.set("Cache-Control", "no-store");
  return new Response(response.body, {status: response.status, statusText: response.statusText, headers});
}

class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

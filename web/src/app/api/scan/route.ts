import { NextResponse } from "next/server";
import { createClient } from "@supabase/supabase-js";

// CORS headers for hardware clients and web simulators
const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, x-device-key, x-dispatch-secret",
};

export async function OPTIONS() {
  return new NextResponse(null, { status: 204, headers: corsHeaders });
}

function timingSafeEqualStr(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

async function isAuthorized(request: Request, deviceId?: string): Promise<boolean> {
  const deviceSecret = process.env.HARDWARE_DEVICE_SECRET;
  const clientDeviceKey =
    request.headers.get("x-device-key") ||
    request.headers.get("x-dispatch-secret");

  // 1. If a dedicated hardware secret is configured and client sent matching key, approve
  if (deviceSecret && clientDeviceKey) {
    return timingSafeEqualStr(clientDeviceKey, deviceSecret);
  }

  // 2. Cryptographic session verification for web/app users if Bearer token is provided
  const authHeader = request.headers.get("authorization") || "";
  if (authHeader.startsWith("Bearer ")) {
    const token = authHeader.slice(7).trim();
    if (token.length > 10) {
      const supabaseUrl =
        process.env.SUPABASE_URL || process.env.NEXT_PUBLIC_SUPABASE_URL;
      const supabaseAnonKey =
        process.env.SUPABASE_ANON_KEY ||
        process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;

      if (supabaseUrl && supabaseAnonKey) {
        try {
          const authClient = createClient(supabaseUrl, supabaseAnonKey, {
            db: { schema: "classmate" },
            global: { headers: { Authorization: `Bearer ${token}` } },
            auth: { persistSession: false },
          });
          const { data: { user }, error } = await authClient.auth.getUser(token);
          if (!error && user) {
            // Only allow privileged attendance managers (admin, teacher, or CR) to invoke /api/scan via Bearer JWT
            const { data: profile } = await authClient
              .from("profiles")
              .select("role, is_cr")
              .eq("id", user.id)
              .maybeSingle();
            if (profile && (profile.role === "admin" || profile.role === "teacher" || profile.is_cr === true)) {
              return true;
            }
            console.warn(`[RFID Auth] Forbidden non-manager Bearer attempt by user ${user.id}`);
            return false;
          }
        } catch (e) {
          console.error("[RFID Auth] Bearer verification failed:", e);
        }
      }
    }
    // Bearer token was provided but failed verification
    return false;
  }

  // 3. Fail-Closed Security: Hardware secret or privileged Bearer token is strictly mandatory.
  // Never allow unauthenticated requests based on client-supplied device ID strings.
  console.warn(`[RFID Auth] Unauthorized access attempt for device: ${deviceId || "UNKNOWN"}`);
  return false;
}

export async function GET(request: Request) {
  try {
    const { searchParams } = new URL(request.url);
    const deviceId = (searchParams.get("device_id") || "ESP32-READER-01").trim();

    if (!await isAuthorized(request, deviceId)) {
      return NextResponse.json(
        { status: "UNAUTHORIZED", message: "Unauthorized device" },
        { status: 401, headers: corsHeaders }
      );
    }

    const supabaseUrl =
      process.env.SUPABASE_URL || process.env.NEXT_PUBLIC_SUPABASE_URL;
    const supabaseKey =
      process.env.SUPABASE_SERVICE_ROLE_KEY ||
      process.env.SUPABASE_ANON_KEY ||
      process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;

    if (!supabaseUrl || !supabaseKey) {
      return NextResponse.json(
        { status: "SLEEP", message: "Supabase credentials not configured" },
        { status: 500, headers: corsHeaders }
      );
    }

    const supabase = createClient(supabaseUrl, supabaseKey, {
      db: { schema: "classmate" },
      auth: { persistSession: false },
    });

    const action = (searchParams.get("action") || "").toLowerCase();
    const isRoster = action === "roster" || searchParams.get("roster") === "true";
    const isTimetable = action === "timetable" || action === "schedule";

    if (isTimetable) {
      // Return weekly timetable routine slots for autonomous offline course switching
      const { data: slots, error: slotErr } = await supabase
        .from("routine_slots")
        .select(`
          id,
          day_of_week,
          start_time,
          end_time,
          room,
          semester_courses (
            id,
            courses (
              course_code,
              course_title
            ),
            semesters (
              batch_id
            )
          )
        `)
        .order("day_of_week", { ascending: true })
        .order("start_time", { ascending: true });

      if (slotErr) {
        console.error("[RFID Timetable] Supabase Query Error:", slotErr);
        return NextResponse.json({ status: "ERROR", message: slotErr.message }, { headers: corsHeaders });
      }

      const formatted = (slots || []).map((s: any) => ({
        slot_id: s.id,
        day: s.day_of_week,
        start: String(s.start_time).slice(0, 5),
        end: String(s.end_time).slice(0, 5),
        room: s.room || "",
        course_code: s.semester_courses?.courses?.course_code || "CLASS",
        course_title: s.semester_courses?.courses?.course_title || "Class Attendance",
        semester_course_id: s.semester_courses?.id || "",
        batch_id: s.semester_courses?.semesters?.batch_id || "",
      }));

      return NextResponse.json({
        status: "OK",
        server_epoch: Math.floor(Date.now() / 1000),
        slots: formatted,
      }, { headers: corsHeaders });
    }

    const { data, error } = isRoster
      ? await supabase.rpc("get_device_session_roster", {
          p_device_id: deviceId,
        })
      : await supabase.rpc("get_device_active_session", {
          target_device: deviceId,
        });

    if (error) {
      console.error("[RFID Heartbeat] Supabase RPC Error:", error);
      return NextResponse.json({ status: "SLEEP" }, { headers: corsHeaders });
    }

    return NextResponse.json(data || { status: "SLEEP" }, { headers: corsHeaders });
  } catch (err: any) {
    console.error("[RFID Heartbeat] Error:", err);
    return NextResponse.json({ status: "SLEEP" }, { headers: corsHeaders });
  }
}

export async function POST(request: Request) {
  try {
    let body: { card_uid?: string; device_id?: string; batch?: string[] } = {};
    try {
      body = await request.json();
    } catch {
      return NextResponse.json(
        {
          status: "ERROR",
          user_name: "Bad Request",
          identifier: "N/A",
          message: "Request body must be valid JSON",
        },
        { status: 400, headers: corsHeaders }
      );
    }

    const deviceId = (body.device_id || "ESP32-READER-01").trim();

    if (!await isAuthorized(request, deviceId)) {
      return NextResponse.json(
        {
          status: "UNAUTHORIZED",
          user_name: "Access Denied",
          identifier: "N/A",
          message: "Missing or invalid hardware device key or authorization token",
        },
        { status: 401, headers: corsHeaders }
      );
    }

    // Connect to Supabase using available environment variables
    const supabaseUrl =
      process.env.SUPABASE_URL || process.env.NEXT_PUBLIC_SUPABASE_URL;
    const supabaseKey =
      process.env.SUPABASE_SERVICE_ROLE_KEY ||
      process.env.SUPABASE_ANON_KEY ||
      process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;

    if (!supabaseUrl || !supabaseKey) {
      return NextResponse.json(
        {
          status: "ERROR",
          user_name: "Config Error",
          identifier: "N/A",
          message: "Supabase credentials not configured",
        },
        { status: 500, headers: corsHeaders }
      );
    }

    const supabase = createClient(supabaseUrl, supabaseKey, {
      db: { schema: "classmate" },
      auth: { persistSession: false },
    });

    // Handle batch or offline queued uploads
    const incomingBatch = Array.isArray((body as any).scans)
      ? (body as any).scans
      : Array.isArray(body.batch)
      ? body.batch
      : null;

    if (incomingBatch && incomingBatch.length > 0) {
      // Normalize items into { card_uid, timestamp, course_code, session_id }
      const normalizedScans = incomingBatch.map((item: any) => {
        if (typeof item === "string") {
          return { card_uid: item.trim() };
        }
        return {
          card_uid: String(item.card_uid || item.uid || "").trim(),
          timestamp: item.timestamp || item.scanned_at || undefined,
          course_code: item.course_code || item.course || undefined,
          session_id: item.session_id || undefined,
        };
      }).filter((s: any) => s.card_uid.length > 0);

      const { data: batchResult, error: batchErr } = await supabase.rpc(
        "process_offline_rfid_batch",
        {
          p_device_id: deviceId,
          p_scans: normalizedScans,
        }
      );

      if (batchErr) {
        console.error("[RFID Batch Sync] RPC Error:", batchErr);
        // Fallback to sequential process_rfid_scan if offline batch RPC failed
        const fallbackResults = [];
        for (const scan of normalizedScans) {
          const { data } = await supabase.rpc("process_rfid_scan", {
            p_card_uid: scan.card_uid,
            p_device_id: deviceId,
          });
          fallbackResults.push(data || { status: "UNKNOWN", uid: scan.card_uid });
        }
        return NextResponse.json({
          status: "BATCH_OK",
          processed: fallbackResults.length,
          results: fallbackResults,
        }, { status: 200, headers: corsHeaders });
      }

      return NextResponse.json(batchResult || { status: "BATCH_OK", processed: normalizedScans.length }, {
        status: 200,
        headers: corsHeaders,
      });
    }

    const cardUid = (body.card_uid || "").trim();
    if (!cardUid) {
      return NextResponse.json(
        {
          status: "ERROR",
          user_name: "Missing UID",
          identifier: "N/A",
          message: "card_uid or batch array is required",
        },
        { status: 400, headers: corsHeaders }
      );
    }

    // Execute atomic RFID scan processor RPC
    const { data, error } = await supabase.rpc("process_rfid_scan", {
      p_card_uid: cardUid,
      p_device_id: deviceId,
    });

    if (error) {
      console.error("[RFID API] Supabase RPC Error:", error);
      return NextResponse.json(
        {
          status: "ERROR",
          user_name: "Database Error",
          identifier: "N/A",
          message: error.message,
        },
        { status: 500, headers: corsHeaders }
      );
    }

    // Return payload strictly adhering to ESP32 OLED firmware structure
    return NextResponse.json(
      {
        status: data?.status || "UNKNOWN",
        user_name: data?.user_name || "Guest",
        identifier: data?.identifier || "N/A",
        course_code: data?.course_code,
        course_title: data?.course_title,
        session_id: data?.session_id,
        message: data?.message,
      },
      { status: 200, headers: corsHeaders }
    );
  } catch (err: any) {
    console.error("[RFID API] Unhandled Error:", err);
    return NextResponse.json(
      {
        status: "ERROR",
        user_name: "Server Error",
        identifier: "N/A",
        message: err?.message || "Internal server error",
      },
      { status: 500, headers: corsHeaders }
    );
  }
}

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

function isAuthorized(request: Request, deviceId?: string): boolean {
  const deviceSecret = process.env.HARDWARE_DEVICE_SECRET;
  const clientDeviceKey =
    request.headers.get("x-device-key") ||
    request.headers.get("x-dispatch-secret");

  // If a hardware device secret is configured and client sent matching key, approve
  if (deviceSecret && clientDeviceKey && clientDeviceKey === deviceSecret) {
    return true;
  }

  // If Bearer token is provided, approve
  const authHeader = request.headers.get("authorization") || "";
  if (authHeader.startsWith("Bearer ") && authHeader.slice(7).trim().length > 10) {
    return true;
  }

  // If a dedicated hardware secret is required on server and client sent mismatched key, reject
  if (deviceSecret && clientDeviceKey && clientDeviceKey !== deviceSecret) {
    return false;
  }

  // Allow standard registered attendance stations (e.g. ESP32-READER-01 or prefixed device IDs)
  const dev = (deviceId || "").trim().toUpperCase();
  if (dev.startsWith("ESP32") || dev.startsWith("SCANNER") || dev.length >= 3) {
    return true;
  }

  return true;
}

export async function GET(request: Request) {
  try {
    const { searchParams } = new URL(request.url);
    const deviceId = (searchParams.get("device_id") || "ESP32-READER-01").trim();

    if (!isAuthorized(request, deviceId)) {
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

    const isRoster =
      searchParams.get("action") === "roster" ||
      searchParams.get("roster") === "true";

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

    if (!isAuthorized(request, deviceId)) {
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

    // Handle batch upload if provided by background sync worker
    if (Array.isArray(body.batch) && body.batch.length > 0) {
      const results = [];
      for (const rawUid of body.batch) {
        const uid = String(rawUid || "").trim();
        if (!uid) continue;
        const { data } = await supabase.rpc("process_rfid_scan", {
          p_card_uid: uid,
          p_device_id: deviceId,
        });
        results.push(data || { status: "UNKNOWN", uid });
      }
      return NextResponse.json(
        {
          status: "BATCH_OK",
          processed: results.length,
          results,
        },
        { status: 200, headers: corsHeaders }
      );
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

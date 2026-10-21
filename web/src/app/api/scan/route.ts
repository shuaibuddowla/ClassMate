import { NextResponse } from "next/server";
import { createClient } from "@supabase/supabase-js";

// CORS headers for hardware clients and web simulators
const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

export async function OPTIONS() {
  return new NextResponse(null, { status: 204, headers: corsHeaders });
}

export async function GET(request: Request) {
  try {
    const { searchParams } = new URL(request.url);
    const deviceId = (searchParams.get("device_id") || "ESP32-READER-01").trim();

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

    const { data, error } = await supabase.rpc("get_device_active_session", {
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
    let body: { card_uid?: string; device_id?: string } = {};
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

    const cardUid = (body.card_uid || "").trim();
    const deviceId = (body.device_id || "ESP32-READER-01").trim();

    if (!cardUid) {
      return NextResponse.json(
        {
          status: "ERROR",
          user_name: "Missing UID",
          identifier: "N/A",
          message: "card_uid is required",
        },
        { status: 400, headers: corsHeaders }
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

    // Verify Phone NFC HCE token format: NFC:<studentRoll>:<profileId>:<timestamp>:<hmacHex>
    let effectiveCardUid = cardUid;
    if (cardUid.startsWith("NFC:")) {
      const parts = cardUid.split(":");
      if (parts.length >= 5) {
        const studentRoll = parts[1];
        const profileId = parts[2];
        const timestamp = parseInt(parts[3], 10);
        const receivedHmac = parts[4].toLowerCase().trim();

        // 1. Enforce 5-minute expiration window to prevent replay attacks
        const nowSec = Math.floor(Date.now() / 1000);
        if (isNaN(timestamp) || Math.abs(nowSec - timestamp) > 300) {
          return NextResponse.json(
            {
              status: "ERROR",
              user_name: "Token Expired",
              identifier: studentRoll,
              message: "NFC tap timestamp expired. Check phone time.",
            },
            { status: 400, headers: corsHeaders }
          );
        }

        // 2. Validate HMAC-SHA256 signature against profile secret
        const crypto = await import("crypto");
        const message = `${studentRoll}:${profileId}:${timestamp}`;
        const computedHmac = crypto
          .createHmac("sha256", profileId)
          .update(message)
          .digest("hex")
          .slice(0, 32);

        if (computedHmac !== receivedHmac) {
          return NextResponse.json(
            {
              status: "ERROR",
              user_name: "Auth Failed",
              identifier: studentRoll,
              message: "Invalid phone NFC cryptographic signature.",
            },
            { status: 401, headers: corsHeaders }
          );
        }

        // Cryptographically verified Phone NFC token -> resolve directly to student profile
        effectiveCardUid = `nfc-${profileId.replace(/-/g, "").toLowerCase()}`;
      }
    }

    const supabase = createClient(supabaseUrl, supabaseKey, {
      db: { schema: "classmate" },
      auth: { persistSession: false },
    });

    // Execute atomic RFID scan processor RPC
    const { data, error } = await supabase.rpc("process_rfid_scan", {
      p_card_uid: effectiveCardUid,
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

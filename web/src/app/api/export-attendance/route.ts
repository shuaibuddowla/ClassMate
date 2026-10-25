import { NextResponse } from "next/server";
import { createClient } from "@supabase/supabase-js";
import * as XLSX from "xlsx";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

export async function OPTIONS() {
  return new NextResponse(null, { status: 204, headers: corsHeaders });
}

function compareStudentRolls(aRoll?: string | null, bRoll?: string | null): number {
  const a = (aRoll || "").trim().toUpperCase();
  const b = (bRoll || "").trim().toUpperCase();
  if (!a && !b) return 0;
  if (!a) return 1;
  if (!b) return -1;
  const aParts = a.split(/(\d+)/).filter(Boolean);
  const bParts = b.split(/(\d+)/).filter(Boolean);
  const len = Math.min(aParts.length, bParts.length);
  for (let i = 0; i < len; i++) {
    const partA = aParts[i];
    const partB = bParts[i];
    const isNumA = /^\d+$/.test(partA);
    const isNumB = /^\d+$/.test(partB);
    if (isNumA && isNumB) {
      const numA = parseInt(partA, 10);
      const numB = parseInt(partB, 10);
      if (numA !== numB) return numA - numB;
      if (partA.length !== partB.length) return partA.localeCompare(partB);
    } else if (partA !== partB) {
      return partA.localeCompare(partB);
    }
  }
  return a.localeCompare(b);
}

export async function POST(request: Request) {
  try {
    const authHeader = request.headers.get("authorization") || "";
    const jwt = authHeader.replace(/^Bearer /i, "").trim();

    const supabaseUrl =
      process.env.SUPABASE_URL ||
      process.env.NEXT_PUBLIC_SUPABASE_URL ||
      "https://cbskpifgbdbgdmnnxfrh.supabase.co";
    const supabaseAnonKey =
      process.env.SUPABASE_ANON_KEY ||
      process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY ||
      "";
    const supabaseServiceKey =
      process.env.SUPABASE_SERVICE_ROLE_KEY || supabaseAnonKey;

    if (!jwt) {
      return NextResponse.json(
        { error: "Authentication required to export attendance" },
        { status: 401, headers: corsHeaders }
      );
    }

    const caller = createClient(
      supabaseUrl,
      supabaseAnonKey,
      {
        db: { schema: "classmate" },
        auth: { persistSession: false },
        global: { headers: { Authorization: `Bearer ${jwt}` } },
      }
    );

    const body = await request.json().catch(() => ({}));
    const courseOfferingId =
      typeof body.semester_course_id === "string"
        ? body.semester_course_id.trim()
        : null;
    const sessionId =
      typeof body.session_id === "string" ? body.session_id.trim() : null;

    // MODE 1: Full Cumulative Long-Term Course Matrix Export
    if (courseOfferingId) {
      const { data: matrix, error: matrixError } = await caller.rpc(
        "course_attendance_matrix",
        {
          target_semester_course: courseOfferingId,
        }
      );

      if (matrixError || !matrix) {
        return NextResponse.json(
          { error: matrixError?.message || "Course matrix not found" },
          { status: 403, headers: corsHeaders }
        );
      }

      const courseCode = matrix.course_code || "Course";
      const sessions = matrix.sessions || [];
      const students = [...(matrix.students || [])].sort(
        (a: any, b: any) =>
          compareStudentRolls(a.roll, b.roll) ||
          (a.full_name || "").localeCompare(b.full_name || "")
      );

      const rows = students.map((s: any, idx: number) => {
        const row: Record<string, any> = {
          SL: idx + 1,
          "Student ID / Roll": s.roll || "—",
          "Student Name": s.full_name || "—",
        };

        sessions.forEach((sess: any, sIdx: number) => {
          let timeStr = "";
          if (sess.started_at) {
            try {
              const d = new Date(sess.started_at);
              timeStr = ` (${d.toLocaleDateString("en-GB", {
                day: "2-digit",
                month: "short",
              })} ${d.toLocaleTimeString("en-US", {
                hour: "2-digit",
                minute: "2-digit",
              })})`;
            } catch {}
          }
          const colHeader = `Day ${sIdx + 1}${timeStr}`;
          const status = s.records?.[sess.session_id];
          row[colHeader] = status === "present" ? 1 : 0;
        });

        row["Total Present"] = s.present_count ?? 0;
        row["Total Classes"] = s.total_sessions ?? sessions.length;
        row["Percentage (%)"] = `${s.percentage ?? 0}%`;

        return row;
      });

      const worksheet = XLSX.utils.json_to_sheet(rows);
      const colKeys = rows.length > 0 ? Object.keys(rows[0]) : [];
      worksheet["!cols"] = colKeys.map((k) => ({
        wch: Math.max(k.length + 3, 12),
      }));

      const workbook = XLSX.utils.book_new();
      const safeSheetName = `${courseCode
        .replace(/[/\\?*[\]]/g, "_")
        .slice(0, 20)} Attendance`;
      XLSX.utils.book_append_sheet(workbook, worksheet, safeSheetName);

      const buffer = XLSX.write(workbook, { type: "buffer", bookType: "xlsx" });
      const filename = `Attendance_${courseCode}_${
        new Date().toISOString().split("T")[0]
      }.xlsx`;

      return new NextResponse(buffer, {
        status: 200,
        headers: {
          ...corsHeaders,
          "Content-Type":
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "Content-Disposition": `attachment; filename="${filename}"`,
        },
      });
    }

    // MODE 2: Single Session Export
    if (sessionId) {
      const { data: summary, error: summaryError } = await caller.rpc(
        "get_session_summary",
        {
          target_session: sessionId,
        }
      );

      if (summaryError || !summary) {
        return NextResponse.json(
          { error: summaryError?.message || "Session not found" },
          { status: 403, headers: corsHeaders }
        );
      }

      const roster = [...(summary.roster || [])].sort(
        (a: any, b: any) =>
          compareStudentRolls(a.roll_number, b.roll_number) ||
          (a.full_name || "").localeCompare(b.full_name || "")
      );

      const rows = roster.map((s: any, idx: number) => ({
        SL: idx + 1,
        "Roll Number": s.roll_number || "—",
        "Student Name": s.full_name || "—",
        Status: s.status === "present" ? "PRESENT" : "ABSENT",
        "Scan Time": s.marked_at
          ? new Date(s.marked_at).toLocaleTimeString()
          : "—",
        Method: (s.method || "RFID").toUpperCase(),
      }));

      const worksheet = XLSX.utils.json_to_sheet(rows);
      const workbook = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(workbook, worksheet, "Session Attendance");
      const buffer = XLSX.write(workbook, { type: "buffer", bookType: "xlsx" });
      const filename = `Attendance_Session_${
        new Date().toISOString().split("T")[0]
      }.xlsx`;

      return new NextResponse(buffer, {
        status: 200,
        headers: {
          ...corsHeaders,
          "Content-Type":
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "Content-Disposition": `attachment; filename="${filename}"`,
        },
      });
    }

    return NextResponse.json(
      { error: "Missing semester_course_id or session_id" },
      { status: 400, headers: corsHeaders }
    );
  } catch (err: any) {
    console.error("Export Attendance Error:", err);
    return NextResponse.json(
      { error: err.message || "Export failed" },
      { status: 500, headers: corsHeaders }
    );
  }
}

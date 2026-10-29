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



function isRecordPresent(record: unknown): boolean {
  if (!record) return false;
  if (typeof record === "boolean") return record;
  if (typeof record === "string") {
    const s = record.trim().toLowerCase();
    return s === "present" || s === "p" || s === "1" || s === "true";
  }
  if (typeof record === "object" && record !== null) {
    const obj = record as Record<string, any>;
    if (obj.present === true) return true;
    if (typeof obj.status === "string") {
      const st = obj.status.trim().toLowerCase();
      return st === "present" || st === "p";
    }
  }
  return false;
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
      if (numA !== numB) {
        return numA - numB;
      }
      if (partA.length !== partB.length) {
        return partA.localeCompare(partB);
      }
    } else if (partA !== partB) {
      return partA.localeCompare(partB);
    }
  }

  return a.localeCompare(b);
}

function detectMajorityRollPrefix(rolls: (string | null | undefined)[]): string {
  const counts = new Map<string, number>();
  for (const r of rolls) {
    const match = (r || "").trim().toUpperCase().match(/^[A-Z]+(\d{2})/);
    if (match) {
      counts.set(match[0], (counts.get(match[0]) || 0) + 1);
    }
  }
  let topPrefix = "";
  let topCount = 0;
  for (const [pfx, count] of counts.entries()) {
    if (count > topCount) {
      topCount = count;
      topPrefix = pfx;
    }
  }
  return topPrefix;
}

function isReaddRoll(roll?: string | null, majorityPrefix?: string): boolean {
  if (!roll || !majorityPrefix) return false;
  const mRoll = roll.trim().toUpperCase().match(/^[A-Z]+(\d{2})/);
  const mMaj = majorityPrefix.trim().toUpperCase().match(/^[A-Z]+(\d{2})/);
  if (mRoll && mMaj) {
    return parseInt(mRoll[1], 10) < parseInt(mMaj[1], 10);
  }
  return false;
}

function compareStudentsWithReaddLast(
  a: { roll?: string | null; full_name?: string | null; is_readd?: boolean },
  b: { roll?: string | null; full_name?: string | null; is_readd?: boolean },
  majorityPrefix?: string
): number {
  const aReadd = Boolean(a.is_readd) || isReaddRoll(a.roll, majorityPrefix);
  const bReadd = Boolean(b.is_readd) || isReaddRoll(b.roll, majorityPrefix);

  if (aReadd !== bReadd) {
    return aReadd ? 1 : -1;
  }
  return compareStudentRolls(a.roll, b.roll) || (a.full_name || "").localeCompare(b.full_name || "");
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
      const majorityPfx = detectMajorityRollPrefix((matrix.students || []).map((s: any) => s.roll));
      const students = [...(matrix.students || [])].sort(
        (a: any, b: any) =>
          compareStudentsWithReaddLast(a, b, majorityPfx)
      );

      // Map session IDs to clean compact date headers (e.g. "13 Oct", "27 Oct (2)" if multiple on same date)
      const dateCounts = new Map<string, number>();
      sessions.forEach((sess: any) => {
        let dStr = "";
        if (sess.started_at) {
          try {
            const d = new Date(sess.started_at);
            const day = String(d.getDate()).padStart(2, "0");
            const month = String(d.getMonth() + 1).padStart(2, "0");
            dStr = `${day}-${month}`;
          } catch {}
        }
        if (!dStr && sess.date) {
          try {
            const d = new Date(sess.date);
            const day = String(d.getDate()).padStart(2, "0");
            const month = String(d.getMonth() + 1).padStart(2, "0");
            dStr = `${day}-${month}`;
          } catch {
            dStr = String(sess.date);
          }
        }
        if (!dStr) dStr = "Class";
        dateCounts.set(dStr, (dateCounts.get(dStr) || 0) + 1);
      });

      const occurrenceMap = new Map<string, number>();
      const sessionHeaderMap = new Map<string, string>();
      sessions.forEach((sess: any, sIdx: number) => {
        let dStr = "";
        if (sess.started_at) {
          try {
            const d = new Date(sess.started_at);
            const day = String(d.getDate()).padStart(2, "0");
            const month = String(d.getMonth() + 1).padStart(2, "0");
            dStr = `${day}-${month}`;
          } catch {}
        }
        if (!dStr && sess.date) {
          try {
            const d = new Date(sess.date);
            const day = String(d.getDate()).padStart(2, "0");
            const month = String(d.getMonth() + 1).padStart(2, "0");
            dStr = `${day}-${month}`;
          } catch {
            dStr = String(sess.date);
          }
        }
        if (!dStr) dStr = `Class ${sIdx + 1}`;

        const total = dateCounts.get(dStr) || 1;
        const cur = (occurrenceMap.get(dStr) || 0) + 1;
        occurrenceMap.set(dStr, cur);

        if (total > 1) {
          sessionHeaderMap.set(sess.session_id, `${dStr} (${cur})`);
        } else {
          sessionHeaderMap.set(sess.session_id, dStr);
        }
      });

      const rows = students.map((s: any) => {
        const row: Record<string, any> = {
          "Student ID / Roll": s.roll || "—",
          "Student Name": s.full_name || "—",
        };

        sessions.forEach((sess: any) => {
          const colHeader = sessionHeaderMap.get(sess.session_id) || "Class";
          const rec = s.records?.[sess.session_id];
          row[colHeader] = isRecordPresent(rec) ? "P" : "—";
        });

        const pres = s.present_count ?? 0;
        const tot = s.total_sessions ?? sessions.length;
        const pct = s.percentage ?? 0;
        row["Percentage (%)"] = `${pres}/${tot} (${pct}%)`;

        return row;
      });

      const worksheet = XLSX.utils.json_to_sheet(rows);
      const colKeys = rows.length > 0 ? Object.keys(rows[0]) : [];
      worksheet["!cols"] = colKeys.map((k) => {
        if (k.toLowerCase().includes("roll") || k.toLowerCase().includes("student id")) return { wch: 11 };
        if (k.toLowerCase().includes("name")) return { wch: 16 };
        if (k.includes("%") || k.toLowerCase().includes("percentage") || k.toLowerCase().includes("attendance")) return { wch: 13 };
        // Date session columns: ultra-compact 5.2 characters
        return { wch: 5.2 };
      });

      // Landscape page setup so at least 30 class records fit in one page
      worksheet["!pageSetup"] = {
        orientation: "landscape",
        paperSize: 9, // A4
        fitToPage: true,
        fitToWidth: 1,
        fitToHeight: 0,
      };

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

        Status: isRecordPresent(s.status) ? "P" : "—",

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


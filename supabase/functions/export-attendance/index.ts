import { createClient } from "npm:@supabase/supabase-js@2.57.0";
import * as XLSX from "npm:xlsx@0.18.5";
import { withWebCors } from "../_shared/web-cors.ts";

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

Deno.serve(request => withWebCors(request, async (request) => {
  if (request.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  const authorization = request.headers.get("authorization") ?? "";
  const jwt = authorization.replace(/^Bearer /, "");
  if (!jwt || jwt === authorization) {
    return new Response("Unauthorized", { status: 401 });
  }

  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  if (!url || !anonKey || !serviceKey) {
    return new Response("Server configuration incomplete", { status: 503 });
  }

  const authClient = createClient(url, anonKey, { auth: { persistSession: false } });
  const { data: userData, error: userError } = await authClient.auth.getUser(jwt);
  if (userError || !userData?.user) {
    return new Response("Unauthorized", { status: 401 });
  }

  const caller = createClient(url, anonKey, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false },
  }).schema("classmate");

  const body = await request.json().catch(() => ({}));
  const sessionId = typeof body.session_id === "string" ? body.session_id.trim() : null;
  const courseOfferingId = typeof body.semester_course_id === "string" ? body.semester_course_id.trim() : null;

  // MODE 1: Full Cumulative Long-Term Course Matrix Export
  if (courseOfferingId) {
    const { data: matrix, error: matrixError } = await caller.rpc("course_attendance_matrix", {
      target_semester_course: courseOfferingId,
    });

    if (matrixError || !matrix) {
      return new Response(JSON.stringify({ error: matrixError?.message ?? "Course matrix not found" }), {
        status: 403,
        headers: { "Content-Type": "application/json" }
      });
    }

    const courseCode = matrix.course_code || "Course";
    const courseTitle = matrix.course_title || "Class";
    const sessions = matrix.sessions || [];
    const students = [...(matrix.students || [])].sort((a: any, b: any) =>
      compareStudentRolls(a.roll, b.roll) || (a.full_name || "").localeCompare(b.full_name || "")
    );

    // Construct flat row objects for SheetJS
    const rows = students.map((s: any, idx: number) => {
      const row: Record<string, any> = {
        "SL": idx + 1,
        "Student ID / Roll": s.roll || "—",
        "Student Name": s.full_name || "—",
      };

      // Add each session date as a column
      sessions.forEach((sess: any) => {
        const colHeader = sess.date || sess.session_id.slice(0, 8);
        const status = s.records?.[sess.session_id];
        row[colHeader] = status === "present" ? "P" : "A";
      });

      row["Total Present"] = s.present_count ?? 0;
      row["Total Classes"] = s.total_sessions ?? sessions.length;
      row["Percentage %"] = `${s.percentage ?? 0}%`;

      return row;
    });

    const worksheet = XLSX.utils.json_to_sheet(rows);
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, worksheet, `${courseCode} Attendance`);

    const xlsxBuffer = XLSX.write(workbook, { bookType: "xlsx", type: "buffer" });
    const filename = `Attendance_Matrix_${courseCode}_${new Date().toISOString().split("T")[0]}.xlsx`;

    return new Response(xlsxBuffer, {
      status: 200,
      headers: {
        "Content-Type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "Content-Disposition": `attachment; filename="${filename}"`,
        "x-file-name": filename,
      },
    });
  }

  // MODE 2: Single Session Export
  if (!sessionId) {
    return new Response(JSON.stringify({ error: "Missing session_id or semester_course_id" }), {
      status: 400,
      headers: { "Content-Type": "application/json" }
    });
  }

  // Get session summary using RPC (enforces RLS / view permissions)
  const { data: summary, error: summaryError } = await caller.rpc("get_session_summary", {
    target_session: sessionId,
  });

  if (summaryError || !summary) {
    return new Response(JSON.stringify({ error: summaryError?.message ?? "Session not found or forbidden" }), {
      status: 403,
      headers: { "Content-Type": "application/json" }
    });
  }

  // Query slot and course info
  const admin = createClient(url, serviceKey, { auth: { persistSession: false } }).schema("classmate");
  const { data: slotData } = await admin.from("routine_slots")
    .select(`
      id, room, type, day_of_week, start_time, end_time,
      semester_courses (
        courses ( course_code, course_title ),
        semesters ( name, batches ( name, department_id ) )
      )
    `)
    .eq("id", summary.routine_slot_id)
    .single();

  const courseCode = slotData?.semester_courses?.courses?.course_code ?? "Course";
  const courseTitle = slotData?.semester_courses?.courses?.course_title ?? "Class";
  const batchName = slotData?.semester_courses?.semesters?.batches?.name ?? "Batch";
  const dateStr = summary.started_at ? new Date(summary.started_at).toISOString().split("T")[0] : "Session";

  // Build rows for SheetJS
  const roster = [...(summary.roster ?? [])].sort((a: any, b: any) =>
    compareStudentRolls(a.roll_number, b.roll_number) || (a.full_name || "").localeCompare(b.full_name || "")
  );
  const rows = roster.map((s: { roll_number: string; full_name: string; status: string; marked_at: string; method: string; confidence: string }, idx: number) => ({
    "SL": idx + 1,
    "Roll Number": s.roll_number || "—",
    "Student Name": s.full_name || "—",
    "Status": s.status === "present" ? "PRESENT" : "ABSENT",
    "Scan Time": s.marked_at ? new Date(s.marked_at).toLocaleTimeString() : "—",
    "Method": s.method ? s.method.toUpperCase() : "—",
    "Verification": s.confidence || "—",
  }));

  const worksheet = XLSX.utils.json_to_sheet(rows);
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(workbook, worksheet, "Attendance");

  const xlsxBuffer = XLSX.write(workbook, { bookType: "xlsx", type: "buffer" });
  const filename = `Attendance_${courseCode}_${batchName}_${dateStr}.xlsx`;

  return new Response(xlsxBuffer, {
    status: 200,
    headers: {
      "Content-Type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "Content-Disposition": `attachment; filename="${filename}"`,
      "x-file-name": filename,
    },
  });
}));

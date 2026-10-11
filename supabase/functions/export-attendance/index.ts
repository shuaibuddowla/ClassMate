import { createClient } from "npm:@supabase/supabase-js@2.57.0";
import * as XLSX from "npm:xlsx@0.18.5";
import { withWebCors } from "../_shared/web-cors.ts";

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
  if (!sessionId) {
    return new Response(JSON.stringify({ error: "Missing session_id" }), {
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
  const roster = summary.roster ?? [];
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

  // Generate buffer
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

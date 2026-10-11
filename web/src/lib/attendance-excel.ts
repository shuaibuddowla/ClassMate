import * as XLSX from "xlsx";
import { rpc } from "@/lib/api";

export interface AttendanceMatrixResponse {
  semester_course_id: string;
  course_code: string;
  course_title: string;
  sessions: Array<{
    session_id: string;
    started_at: string;
    date: string;
    status: string;
    room: string | null;
  }>;
  students: Array<{
    student_id: string;
    roll: string;
    full_name: string;
    records: Record<string, "present" | "absent">;
    present_count: number;
    total_sessions: number;
    percentage: number;
  }>;
}

export interface SessionSummaryResponse {
  session_id: string;
  routine_slot_id: string;
  status: string;
  started_at: string;
  closed_at: string | null;
  roster: Array<{
    student_id: string;
    roll_number: string;
    full_name: string;
    status: "present" | "absent" | "excused";
    marked_at: string | null;
    method: string | null;
    confidence: string | null;
  }>;
}

/**
 * Generates and downloads a multi-date cumulative attendance matrix spreadsheet (.xlsx)
 * entirely client-side using SheetJS. Zero edge-function dependency, zero network lag.
 */
export async function downloadCourseMatrixExcel(semesterCourseId: string) {
  const matrix = await rpc<AttendanceMatrixResponse>("course_attendance_matrix", {
    target_semester_course: semesterCourseId,
  });

  if (!matrix) {
    throw new Error("Unable to load course attendance matrix");
  }

  const courseCode = matrix.course_code || "Course";
  const sessions = matrix.sessions || [];
  const students = matrix.students || [];

  // Build matrix rows
  const rows = students.map((s, idx) => {
    const row: Record<string, any> = {
      "SL": idx + 1,
      "Student ID / Roll": s.roll || "—",
      "Student Name": s.full_name || "—",
    };

    // Columns for each session date
    sessions.forEach((sess) => {
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

  const filename = `Attendance_Matrix_${courseCode}_${new Date().toISOString().split("T")[0]}.xlsx`;
  XLSX.writeFile(workbook, filename);
}

/**
 * Generates and downloads single session attendance (.xlsx) client-side.
 */
export async function downloadSessionExcel(
  sessionId: string,
  courseCode: string = "Class",
  dateStr?: string
) {
  const summary = await rpc<SessionSummaryResponse>("get_session_summary", {
    target_session: sessionId,
  });

  if (!summary || !summary.roster) {
    throw new Error("Session summary data not available");
  }

  const rows = summary.roster.map((s, idx) => ({
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
  XLSX.utils.book_append_sheet(workbook, worksheet, "Session Attendance");

  const today = dateStr || new Date().toISOString().split("T")[0];
  const filename = `Attendance_${courseCode}_${today}.xlsx`;
  XLSX.writeFile(workbook, filename);
}

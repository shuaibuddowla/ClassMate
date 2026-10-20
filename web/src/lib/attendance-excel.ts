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
 * Natural sorting for student roll numbers (e.g. CE25001, CE25002, CE25032... CE25060)
 * Extracts numeric chunks naturally so 1 < 2 < 10 < 32 < 60 regardless of prefix/padding.
 */
export function compareStudentRolls(aRoll?: string | null, bRoll?: string | null): number {
  const a = (aRoll || "").trim().toUpperCase();
  const b = (bRoll || "").trim().toUpperCase();

  if (!a && !b) return 0;
  if (!a) return 1;
  if (!b) return -1;

  // Split into chunks of digits and non-digits
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
      // If numeric values are identical, preserve zero-padding difference
      if (partA.length !== partB.length) {
        return partA.localeCompare(partB);
      }
    } else if (partA !== partB) {
      return partA.localeCompare(partB);
    }
  }

  return a.localeCompare(b);
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
  const students = [...(matrix.students || [])].sort((a, b) =>
    compareStudentRolls(a.roll, b.roll) || a.full_name.localeCompare(b.full_name)
  );

  // Build matrix rows sorted cleanly by roll
  const rows = students.map((s, idx) => {
    const row: Record<string, any> = {
      "SL": idx + 1,
      "Student ID / Roll": s.roll || "—",
      "Student Name": s.full_name || "—",
    };

    // Columns for each session with day index, date, and timestamp
    sessions.forEach((sess, sIdx) => {
      let timeStr = "";
      if (sess.started_at) {
        try {
          const d = new Date(sess.started_at);
          timeStr = ` (${d.toLocaleDateString("en-GB", { day: "2-digit", month: "short" })} ${d.toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit" })})`;
        } catch {}
      }
      const colHeader = `Day ${sIdx + 1}${timeStr}`;
      const status = s.records?.[sess.session_id];
      // Mark 1 if present, 0 if absent as requested
      row[colHeader] = status === "present" ? 1 : 0;
    });

    row["Total Present"] = s.present_count ?? 0;
    row["Total Classes"] = s.total_sessions ?? sessions.length;
    row["Percentage (%)"] = `${s.percentage ?? 0}%`;

    return row;
  });

  const worksheet = XLSX.utils.json_to_sheet(rows);

  // Column width calculations for clean auto-fit
  const colKeys = rows.length > 0 ? Object.keys(rows[0]) : [];
  worksheet["!cols"] = colKeys.map((k) => ({
    wch: Math.max(k.length + 3, 12),
  }));

  const workbook = XLSX.utils.book_new();
  const safeSheetName = `${courseCode.replace(/[/\\?*[\]]/g, "_").slice(0, 20)} Attendance`;
  XLSX.utils.book_append_sheet(workbook, worksheet, safeSheetName);

  const filename = `Attendance_${courseCode}_${new Date().toISOString().split("T")[0]}.xlsx`;
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

  const sortedRoster = [...summary.roster].sort((a, b) =>
    compareStudentRolls(a.roll_number, b.roll_number) || a.full_name.localeCompare(b.full_name)
  );

  const rows = sortedRoster.map((s, idx) => ({
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

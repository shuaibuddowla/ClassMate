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
    records: Record<string, any>;
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

export function isRecordPresent(record: unknown): boolean {
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

export function detectMajorityRollPrefix(rolls: (string | null | undefined)[]): string {
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

export function isReaddRoll(roll?: string | null, majorityPrefix?: string): boolean {
  if (!roll || !majorityPrefix) return false;
  const mRoll = roll.trim().toUpperCase().match(/^[A-Z]+(\d{2})/);
  const mMaj = majorityPrefix.trim().toUpperCase().match(/^[A-Z]+(\d{2})/);
  if (mRoll && mMaj) {
    return parseInt(mRoll[1], 10) < parseInt(mMaj[1], 10);
  }
  return false;
}

export function compareStudentsWithReaddLast(
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
  const majorityPfx = detectMajorityRollPrefix(matrix.students?.map((s) => s.roll) || []);
  const students = [...(matrix.students || [])].sort((a, b) =>
    compareStudentsWithReaddLast(a, b, majorityPfx)
  );

  // Map session IDs to clean compact date headers (e.g. "13 Oct", "27 Oct (2)" if multiple on same date)
  const dateCounts = new Map<string, number>();
  sessions.forEach((sess) => {
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
  sessions.forEach((sess, sIdx) => {
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

  // Build matrix rows: No extra SL column, re-add students at the last!
  const rows = students.map((s) => {
    const row: Record<string, any> = {
      "Student ID / Roll": s.roll || "—",
      "Student Name": s.full_name || "—",
    };

    // Columns for each session: clean compact date header (e.g. 13-10, 26-10)
    sessions.forEach((sess) => {
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

  // Ultra-compact column widths: allows 30+ classes to fit on a single page
  const colKeys = rows.length > 0 ? Object.keys(rows[0]) : [];
  worksheet["!cols"] = colKeys.map((k) => {
    if (k.toLowerCase().includes("roll") || k.toLowerCase().includes("student id")) return { wch: 11 };
    if (k.toLowerCase().includes("name")) return { wch: 16 };
    if (k.includes("%") || k.toLowerCase().includes("percentage") || k.toLowerCase().includes("attendance")) return { wch: 13 };
    // Date session columns: ultra-compact 5.2 characters
    return { wch: 5.2 };
  });

  // Configure print/page setup to fit 30+ classes across 1 landscape page
  worksheet["!pageSetup"] = {
    orientation: "landscape",
    paperSize: 9, // A4
    fitToPage: true,
    fitToWidth: 1,
    fitToHeight: 0,
  };

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

  const majorityPfx = detectMajorityRollPrefix(summary.roster?.map((s) => s.roll_number) || []);
  const sortedRoster = [...summary.roster].sort((a, b) =>
    compareStudentsWithReaddLast({ roll: a.roll_number, full_name: a.full_name, is_readd: (a as any).is_readd }, { roll: b.roll_number, full_name: b.full_name, is_readd: (b as any).is_readd }, majorityPfx)
  );

  const rows = sortedRoster.map((s) => ({
    "Roll Number": s.roll_number || "—",
    "Student Name": s.full_name || "—",
    "Status": isRecordPresent(s.status) ? "P" : "—",
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

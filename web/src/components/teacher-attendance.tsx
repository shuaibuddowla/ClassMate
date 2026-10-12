"use client";

import { useState, useMemo, useRef, useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { rpc } from "@/lib/api";
import { compareStudentRolls } from "@/lib/attendance-excel";
import { type Context } from "./app";
import { AttendanceTeacherModal } from "./attendance-teacher-modal";
import { ErrorBox, Skeleton } from "./ui";

interface SessionInfo {
  session_id: string;
  routine_slot_id: string;
  started_at: string;
  closed_at: string | null;
  status: "open" | "closed";
  room: string | null;
  present_count: number;
  total_count: number;
  percentage?: number;
}

interface TeacherCourse {
  semester_course_id: string;
  course_code: string;
  course_title: string;
  course_type: string;
  credit: number;
  teacher_name?: string;
  total_sessions: number;
  enrolled_students: number;
  average_percentage: number;
  sessions: SessionInfo[];
}

interface MatrixStudent {
  student_id: string;
  roll: string;
  full_name: string;
  records: Record<string, "present" | "absent">;
  present_count: number;
  total_sessions: number;
  percentage: number;
}

interface MatrixResponse {
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
  students: MatrixStudent[];
}

function healthColor(pct: number): string {
  if (pct >= 75) return "var(--green, #22c55e)";
  if (pct >= 60) return "var(--amber, #f59e0b)";
  return "var(--red, #ef4444)";
}

export default function TeacherAttendanceDashboard({ ctx }: { ctx: Context }) {
  const [selectedCourseId, setSelectedCourseId] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<"sessions" | "roster">("sessions");
  const [rosterSortBy, setRosterSortBy] = useState<"roll" | "risk">("roll");
  const [showExportMenu, setShowExportMenu] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [inspectSession, setInspectSession] = useState<{
    sessionId: string;
    slotId: string;
    courseTitle: string;
    courseCode: string;
  } | null>(null);

  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleOutsideClick = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setShowExportMenu(false);
      }
    };
    if (showExportMenu) {
      document.addEventListener("mousedown", handleOutsideClick);
    }
    return () => document.removeEventListener("mousedown", handleOutsideClick);
  }, [showExportMenu]);

  // Fetch overview for all courses
  const overviewQuery = useQuery({
    queryKey: [ctx.user, ctx.batch, "teacher-attendance-overview"],
    queryFn: () =>
      rpc<any[]>("teacher_course_attendance_overview", {
        target_batch: ctx.batch,
      }),
  });

  const courses: TeacherCourse[] = useMemo(() => {
    const raw = overviewQuery.data;
    if (!Array.isArray(raw)) return [];
    return raw.map((c: any) => ({
      semester_course_id: c.semester_course_id || c.offering_id || "",
      course_code: c.course_code || "",
      course_title: c.course_title || "",
      course_type: (c.course_type || "theory").toLowerCase(),
      credit: typeof c.credit === "number" ? c.credit : 3.0,
      teacher_name: c.teacher_name || "Instructor",
      total_sessions: typeof c.total_sessions === "number" ? c.total_sessions : (c.sessions?.length || 0),
      enrolled_students: c.enrolled_students ?? c.total_enrolled ?? 0,
      average_percentage: c.average_percentage ?? c.avg_percentage ?? 0,
      sessions: Array.isArray(c.sessions) ? c.sessions : [],
    }));
  }, [overviewQuery.data]);

  const activeCourse = useMemo(() => {
    if (!courses.length) return null;
    return courses.find((c) => c.semester_course_id === selectedCourseId) || courses[0];
  }, [courses, selectedCourseId]);

  // Fetch matrix only if Roster tab is open
  const matrixQuery = useQuery({
    queryKey: [activeCourse?.semester_course_id, "course-attendance-matrix"],
    queryFn: () =>
      rpc<MatrixResponse>("course_attendance_matrix", {
        target_semester_course: activeCourse!.semester_course_id,
      }),
    enabled: !!activeCourse && activeTab === "roster",
  });

  // Export handlers
  const handleExportMatrix = async () => {
    if (!activeCourse) return;
    try {
      setExporting(true);
      setShowExportMenu(false);
      const { downloadCourseMatrixExcel } = await import("@/lib/attendance-excel");
      await downloadCourseMatrixExcel(activeCourse.semester_course_id);
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : "Matrix export failed");
    } finally {
      setExporting(false);
    }
  };

  const handleExportLatestSession = async () => {
    if (!activeCourse || !activeCourse.sessions.length) {
      alert("No sessions recorded to export.");
      return;
    }
    try {
      setExporting(true);
      setShowExportMenu(false);
      const { downloadSessionExcel } = await import("@/lib/attendance-excel");
      const latest = activeCourse.sessions[0];
      const dateStr = new Date(latest.started_at).toISOString().split("T")[0];
      await downloadSessionExcel(latest.session_id, activeCourse.course_code, dateStr);
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : "Session export failed");
    } finally {
      setExporting(false);
    }
  };

  if (overviewQuery.isError) {
    return <ErrorBox error={overviewQuery.error} retry={() => overviewQuery.refetch()} />;
  }

  if (overviewQuery.isLoading) {
    return (
      <div className="attendance-loading">
        <Skeleton />
      </div>
    );
  }

  if (courses.length === 0) {
    return (
      <div className="card attendance-empty-msg">
        <p>No courses assigned to you in this batch, or attendance has not been enabled yet.</p>
      </div>
    );
  }

  // Sorted by natural roll number (CE25001...) or lowest % first for the roster tab
  const sortedStudents = [...(matrixQuery.data?.students || [])].sort((a, b) => {
    if (rosterSortBy === "roll") {
      return compareStudentRolls(a.roll, b.roll) || a.full_name.localeCompare(b.full_name);
    }
    return (a.percentage - b.percentage) || compareStudentRolls(a.roll, b.roll);
  });

  const activeAvgColor = activeCourse ? healthColor(activeCourse.average_percentage) : "inherit";

  return (
    <div className="attendance-teacher-section">
      {/* 1. Course selector: plain horizontal tabs, text only */}
      <div className="segment modes attendance-course-tabs">
        {courses.map((c) => (
          <button
            key={c.semester_course_id}
            type="button"
            className={activeCourse?.semester_course_id === c.semester_course_id ? "selected" : ""}
            onClick={() => setSelectedCourseId(c.semester_course_id)}
          >
            {c.course_code}
          </button>
        ))}
      </div>

      {activeCourse && (
        <div className="attendance-course-content">
          {/* 2. Top numbers strip & single Export Excel button */}
          <div className="attendance-teacher-top-strip">
            <div className="attendance-summary-strip">
              <span className="attendance-big-pct" style={{ color: activeAvgColor }}>
                {activeCourse.average_percentage}% avg
              </span>
              <span className="attendance-summary-sep">·</span>
              <span className="attendance-summary-text">
                {activeCourse.total_sessions} classes held
              </span>
              <span className="attendance-summary-sep">·</span>
              <span className="attendance-summary-text">
                {activeCourse.enrolled_students} students
              </span>
            </div>

            {/* One button: Export Excel with simple menu */}
            <div className="attendance-export-wrapper" ref={menuRef}>
              <button
                className="secondary attendance-export-btn"
                onClick={() => setShowExportMenu(!showExportMenu)}
                disabled={exporting}
              >
                {exporting ? "Exporting..." : "Export Excel"}
              </button>

              {showExportMenu && (
                <div className="attendance-export-menu">
                  <button type="button" onClick={handleExportMatrix}>
                    Semester Matrix (.xlsx)
                  </button>
                  <button
                    type="button"
                    onClick={handleExportLatestSession}
                    disabled={!activeCourse.sessions.length}
                  >
                    Latest Session (.xlsx)
                  </button>
                </div>
              )}
            </div>
          </div>

          {/* 3. Two plain text tabs: Sessions | Roster */}
          <div className="segment modes attendance-subtabs">
            <button
              type="button"
              className={activeTab === "sessions" ? "selected" : ""}
              onClick={() => setActiveTab("sessions")}
            >
              Sessions
            </button>
            <button
              type="button"
              className={activeTab === "roster" ? "selected" : ""}
              onClick={() => setActiveTab("roster")}
            >
              Roster
            </button>
          </div>

          {/* TAB 1: SESSIONS */}
          {activeTab === "sessions" && (
            <div className="attendance-sessions-plain-list">
              {activeCourse.sessions.length === 0 ? (
                <div className="card attendance-empty-msg">
                  <p>No class sessions held yet for this course.</p>
                </div>
              ) : (
                activeCourse.sessions.map((sess) => {
                  const dObj = new Date(sess.started_at);
                  const dateStr = !isNaN(dObj.getTime())
                    ? dObj.toLocaleDateString("en-US", {
                        month: "short",
                        day: "numeric",
                      })
                    : "";
                  const timeStr = !isNaN(dObj.getTime())
                    ? dObj.toLocaleTimeString("en-US", {
                        hour: "numeric",
                        minute: "2-digit",
                      })
                    : "";

                  return (
                    <div
                      key={sess.session_id}
                      className="attendance-plain-row card"
                      role="button"
                      tabIndex={0}
                      onClick={() =>
                        setInspectSession({
                          sessionId: sess.session_id,
                          slotId: sess.routine_slot_id || "",
                          courseTitle: activeCourse.course_title,
                          courseCode: activeCourse.course_code,
                        })
                      }
                    >
                      <span className="session-date-time">
                        {dateStr}, {timeStr}
                      </span>
                      <span className="session-counts-text">
                        {sess.present_count} / {sess.total_count} present
                      </span>
                      {sess.status === "open" && (
                        <span className="session-live-tag">Live</span>
                      )}
                    </div>
                  );
                })
              )}
            </div>
          )}

          {/* TAB 2: ROSTER */}
          {activeTab === "roster" && (
            <div className="attendance-roster-plain-section">
              {matrixQuery.isLoading ? (
                <div className="attendance-loading">
                  <Skeleton />
                </div>
              ) : matrixQuery.isError ? (
                <ErrorBox error={matrixQuery.error} retry={() => matrixQuery.refetch()} />
              ) : sortedStudents.length === 0 ? (
                <div className="card attendance-empty-msg">
                  <p>No student roster found for this course.</p>
                </div>
              ) : (
                <>
                  <div className="attendance-roster-sort-bar">
                    <span className="roster-sort-label">Sort:</span>
                    <button
                      type="button"
                      className={`roster-sort-btn ${rosterSortBy === "roll" ? "active" : ""}`}
                      onClick={() => setRosterSortBy("roll")}
                    >
                      Roll (CE25001...)
                    </button>
                    <button
                      type="button"
                      className={`roster-sort-btn ${rosterSortBy === "risk" ? "active" : ""}`}
                      onClick={() => setRosterSortBy("risk")}
                    >
                      At-Risk (Lowest %)
                    </button>
                  </div>
                  <div className="attendance-roster-table card">
                  {sortedStudents.map((st) => {
                    const isEligible = st.percentage >= 75;
                    const statusText = isEligible ? "Eligible" : "At Risk";
                    const statusColor = healthColor(st.percentage);

                    return (
                      <div key={st.student_id} className="attendance-roster-row">
                        <span className="roster-roll">{st.roll || "—"}</span>
                        <span className="roster-name">{st.full_name}</span>
                        <span className="roster-pct" style={{ color: statusColor }}>
                          {st.percentage}%
                        </span>
                        <span className="roster-status" style={{ color: statusColor }}>
                          {statusText}
                        </span>
                      </div>
                    );
                  })}
                </div>
              </>
            )}
            </div>
          )}
        </div>
      )}

      {/* Session Modal */}
      {inspectSession && (
        <AttendanceTeacherModal
          sessionId={inspectSession.sessionId}
          slotId={inspectSession.slotId}
          courseTitle={inspectSession.courseTitle}
          courseCode={inspectSession.courseCode}
          onClose={() => {
            setInspectSession(null);
            overviewQuery.refetch();
            if (activeTab === "roster") matrixQuery.refetch();
          }}
        />
      )}
    </div>
  );
}

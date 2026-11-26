"use client";

import { useState, useMemo, useEffect, useRef } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { rpc, supabase, type Row } from "@/lib/api";
import {
  downloadCourseMatrixExcel,
  compareStudentRolls,
  detectMajorityRollPrefix,
  compareStudentsWithReaddLast,
} from "@/lib/attendance-excel";
import { type Context } from "./app";
import { ErrorBox, Skeleton } from "./ui";
import {
  Radio,
  FileSpreadsheet,
  Calendar,
  Users,
  ChevronRight,
  ArrowLeft,
  CheckCircle2,
  XCircle,
  Clock,
  Search,
  Check,
  X,
  Sparkles,
  Download,
  AlertCircle,
  Square,
  CheckSquare,
} from "lucide-react";

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
  batch_id: string;
  batch_label: string;
  course_code: string;
  course_title: string;
  course_type: string;
  credit: number;
  teacher_name?: string;
  total_sessions: number;
  enrolled_students: number;
  average_percentage: number;
  sessions: SessionInfo[];
  has_live_session: boolean;
}

interface RosterStudent {
  student_id: string;
  full_name: string;
  roll_number: string;
  status: "present" | "absent";
  marked_at?: string | null;
  method?: string | null;
  confidence?: string | null;
  rfid_card_uid?: string | null;
  is_readd?: boolean;
}

interface FullSessionSummary {
  session_id: string;
  routine_slot_id?: string;
  semester_course_id?: string;
  batch_id?: string;
  course_code?: string;
  course_title?: string;
  status: "open" | "closed";
  started_at: string;
  closed_at?: string | null;
  present_count: number;
  total_enrolled: number;
  roster: RosterStudent[];
}

function healthColor(pct: number): string {
  if (pct >= 75) return "var(--green, #22c55e)";
  if (pct >= 60) return "var(--amber, #f59e0b)";
  return "var(--red, #ef4444)";
}

export default function TeacherAttendanceDashboard({ ctx }: { ctx: Context }) {
  const qc = useQueryClient();

  // State: selected course for Level 2 (Course view)
  const [selectedCourseId, setSelectedCourseId] = useState<string | null>(null);

  // State: active session for Level 3 (Fullscreen view)
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null);

  // Loading & export states
  const [exportingCourseId, setExportingCourseId] = useState<string | null>(null);
  const [startingSession, setStartingSession] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  // Determine batches to query (all batches assigned to this teacher)
  const targetBatches = useMemo(() => {
    if (ctx.batches && ctx.batches.length > 0) return ctx.batches;
    if (ctx.batch) return [{ id: ctx.batch, batch_number: ctx.batchInfo?.batch_number || 0 }];
    return [];
  }, [ctx.batches, ctx.batch, ctx.batchInfo]);

  // Query courses across all batches in parallel
  const allCoursesQuery = useQuery({
    queryKey: [ctx.user, targetBatches.map((b) => b.id).join(","), "teacher-all-courses-attendance"],
    queryFn: async () => {
      if (!targetBatches.length) return [];
      const results = await Promise.all(
        targetBatches.map(async (b) => {
          try {
            const raw = await rpc<any[]>("teacher_course_attendance_overview", {
              target_batch: b.id,
            });
            const deptCode = b.departments?.code ? String(b.departments.code).toUpperCase() + " " : "";
            const batchLabel = `${deptCode}Batch ${b.batch_number}`;
            if (!Array.isArray(raw)) return [];
            return raw.map((c: any) => ({
              semester_course_id: c.semester_course_id || c.offering_id || "",
              batch_id: b.id,
              batch_label: batchLabel,
              course_code: c.course_code || "",
              course_title: c.course_title || "",
              course_type: (c.course_type || "theory").toLowerCase(),
              credit: typeof c.credit === "number" ? c.credit : 3.0,
              teacher_name: c.teacher_name || "Instructor",
              total_sessions: typeof c.total_sessions === "number" ? c.total_sessions : (c.sessions?.length || 0),
              enrolled_students: c.enrolled_students ?? c.total_enrolled ?? 0,
              average_percentage: c.average_percentage ?? c.avg_percentage ?? 0,
              sessions: Array.isArray(c.sessions) ? c.sessions : [],
              has_live_session: Array.isArray(c.sessions) && c.sessions.some((s: any) => s.status === "open"),
            }));
          } catch (e) {
            console.error("Failed fetching overview for batch", b.id, e);
            return [];
          }
        })
      );
      return results.flat();
    },
    refetchInterval: 15_000,
  });

  const courses: TeacherCourse[] = allCoursesQuery.data || [];

  // Active course selected for Level 2
  const activeCourse = useMemo(() => {
    if (!selectedCourseId) return null;
    return courses.find((c) => c.semester_course_id === selectedCourseId) || null;
  }, [courses, selectedCourseId]);

  // Handle Export Semester Matrix Excel
  const handleExportSemesterExcel = async (semesterCourseId: string) => {
    try {
      setExportingCourseId(semesterCourseId);
      setActionError(null);
      await downloadCourseMatrixExcel(semesterCourseId);
    } catch (err: unknown) {
      setActionError(err instanceof Error ? err.message : "Semester export failed");
    } finally {
      setExportingCourseId(null);
    }
  };

  // Handle Start Attendance Session (Ad-Hoc / Direct)
  const handleStartSession = async (semesterCourseId: string) => {
    try {
      setStartingSession(true);
      setActionError(null);
      const res = await rpc<{ session_id: string; session_secret: string; status: string }>(
        "start_adhoc_attendance_session",
        { target_semester_course: semesterCourseId }
      );
      if (res && res.session_id) {
        await allCoursesQuery.refetch();
        setActiveSessionId(res.session_id);
      }
    } catch (err: unknown) {
      setActionError(err instanceof Error ? err.message : "Failed to start attendance session");
    } finally {
      setStartingSession(false);
    }
  };

  // ==========================================
  // LEVEL 3: FULLSCREEN LIVE ATTENDANCE DASHBOARD
  // ==========================================
  if (activeSessionId) {
    return (
      <FullscreenSessionDashboard
        sessionId={activeSessionId}
        onBack={() => {
          setActiveSessionId(null);
          allCoursesQuery.refetch();
        }}
        onSessionClosed={() => {
          allCoursesQuery.refetch();
        }}
      />
    );
  }

  // ==========================================
  // LEVEL 2: COURSE SESSIONS & SEMESTER EXPORT
  // ==========================================
  if (activeCourse) {
    const liveSession = activeCourse.sessions.find((s) => s.status === "open");
    const pastSessions = activeCourse.sessions.filter((s) => s.status !== "open");
    const isExporting = exportingCourseId === activeCourse.semester_course_id;

    return (
      <div className="attendance-teacher-section">
        {/* Navigation & Header */}
        <div className="course-attendance-header card">
          <div className="course-header-top">
            <button
              type="button"
              className="text-button back-to-courses-btn"
              onClick={() => setSelectedCourseId(null)}
            >
              <ArrowLeft size={16} />
              <span>All Courses</span>
            </button>
            <span className="course-batch-pill">{activeCourse.batch_label}</span>
          </div>

          <div className="course-title-row">
            <div>
              <span className="course-code-badge">{activeCourse.course_code}</span>
              <h1 className="course-main-title">{activeCourse.course_title}</h1>
              <p className="course-meta-sub">
                {activeCourse.total_sessions} classes held · {activeCourse.enrolled_students} enrolled students ·{" "}
                <strong style={{ color: healthColor(activeCourse.average_percentage) }}>
                  {activeCourse.average_percentage}% avg attendance
                </strong>
              </p>
            </div>

            {/* Top Right Actions */}
            <div className="course-header-actions">
              <button
                type="button"
                className="primary export-semester-btn"
                disabled={isExporting}
                onClick={() => handleExportSemesterExcel(activeCourse.semester_course_id)}
                title="Export semester-wide attendance matrix (.xlsx)"
              >
                <FileSpreadsheet size={16} />
                <span>{isExporting ? "Exporting..." : "Export Semester Excel (.xlsx)"}</span>
              </button>

              <button
                type="button"
                className="secondary start-session-btn"
                disabled={startingSession}
                onClick={() => handleStartSession(activeCourse.semester_course_id)}
              >
                <Radio size={16} />
                <span>{startingSession ? "Launching..." : "Start Attendance Session"}</span>
              </button>
            </div>
          </div>
        </div>

        {actionError && <ErrorBox error={actionError} />}

        {/* Ongoing Live Session Banner */}
        {liveSession && (
          <div className="live-session-banner-card card">
            <div className="live-session-left">
              <div className="live-pulsing-tag">
                <span className="pulse-dot" />
                <strong>LIVE ATTENDANCE IN PROGRESS</strong>
              </div>
              <h3>Active Classroom Session</h3>
              <p>
                Started at{" "}
                {new Date(liveSession.started_at).toLocaleTimeString("en-US", {
                  hour: "numeric",
                  minute: "2-digit",
                })}{" "}
                · Room: {liveSession.room || "Classroom"}
              </p>
            </div>

            <div className="live-session-right">
              <div className="live-stat-box">
                <span className="live-stat-num">{liveSession.present_count}</span>
                <span className="live-stat-label">/ {liveSession.total_count} Present</span>
              </div>
              <button
                type="button"
                className="primary open-live-btn"
                onClick={() => setActiveSessionId(liveSession.session_id)}
              >
                <span>Open Live Dashboard (Fullscreen)</span>
                <ChevronRight size={16} />
              </button>
            </div>
          </div>
        )}

        {/* Past Sessions List */}
        <div className="sessions-section-title-row">
          <h2>Recorded Class Sessions ({pastSessions.length})</h2>
          <span className="sessions-hint">Click any session to view detailed roster or make adjustments</span>
        </div>

        {pastSessions.length === 0 && !liveSession ? (
          <div className="card attendance-empty-state-card">
            <Calendar size={32} />
            <h3>No attendance sessions held yet</h3>
            <p>Click "Start Attendance Session" above to launch live RFID scanning for this course.</p>
          </div>
        ) : (
          <div className="past-sessions-grid">
            {pastSessions.map((sess) => {
              const dObj = new Date(sess.started_at);
              const dateStr = !isNaN(dObj.getTime())
                ? dObj.toLocaleDateString("en-US", {
                    weekday: "short",
                    month: "short",
                    day: "numeric",
                    year: "numeric",
                  })
                : "Class Date";
              const timeStr = !isNaN(dObj.getTime())
                ? dObj.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" })
                : "";

              return (
                <div
                  key={sess.session_id}
                  className="card past-session-card"
                  role="button"
                  tabIndex={0}
                  onClick={() => setActiveSessionId(sess.session_id)}
                >
                  <div className="session-card-header">
                    <span className="session-date-label">{dateStr}</span>
                    <span className="session-room-badge">Room {sess.room || "Station"}</span>
                  </div>

                  <div className="session-card-body">
                    <div className="session-time-row">
                      <Clock size={14} />
                      <span>{timeStr}</span>
                    </div>

                    <div className="session-turnout-row">
                      <strong>{sess.present_count}</strong>
                      <span>/ {sess.total_count} Present</span>
                      <span
                        className="session-pct-pill"
                        style={{
                          backgroundColor: `${healthColor(sess.percentage ?? 0)}15`,
                          color: healthColor(sess.percentage ?? 0),
                        }}
                      >
                        {sess.percentage ?? 0}%
                      </span>
                    </div>
                  </div>

                  <div className="session-card-footer">
                    <span>Inspect Roster</span>
                    <ChevronRight size={15} />
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    );
  }

  // ==========================================
  // LEVEL 1: COURSES ACROSS ALL BATCHES
  // ==========================================
  return (
    <div className="attendance-teacher-section">
      <div className="page-heading">
        <div>
          <span className="eyebrow">TEACHING WORKSPACE</span>
          <h1>Attendance Dashboard</h1>
          <p>Select an assigned course to view session history, export semester matrices, or launch attendance.</p>
        </div>
        <Radio size={28} className="text-blue" />
      </div>

      {actionError && <ErrorBox error={actionError} />}
      {allCoursesQuery.isError && (
        <ErrorBox error={allCoursesQuery.error} retry={() => allCoursesQuery.refetch()} />
      )}

      {allCoursesQuery.isLoading ? (
        <div className="attendance-loading">
          <Skeleton />
        </div>
      ) : courses.length === 0 ? (
        <div className="card attendance-empty-state-card">
          <Radio size={36} />
          <h3>No assigned courses found</h3>
          <p>
            You are not currently assigned to any active courses in your batches. Contact your department administrator
            to assign your courses.
          </p>
        </div>
      ) : (
        <div className="teacher-courses-grid">
          {courses.map((c) => {
            const isExporting = exportingCourseId === c.semester_course_id;

            return (
              <div
                key={c.semester_course_id}
                className={`card teacher-course-card ${c.has_live_session ? "has-live-session" : ""}`}
                role="button"
                tabIndex={0}
                onClick={() => setSelectedCourseId(c.semester_course_id)}
              >
                {c.has_live_session && (
                  <div className="course-live-banner">
                    <span className="pulse-dot" />
                    <span>LIVE ATTENDANCE RUNNING</span>
                  </div>
                )}

                <div className="course-card-top">
                  <span className="course-card-code">{c.course_code}</span>
                  <span className="course-card-batch">{c.batch_label}</span>
                </div>

                <h3 className="course-card-title">{c.course_title}</h3>

                <div className="course-card-metrics">
                  <div className="metric-item">
                    <span className="metric-num">{c.total_sessions}</span>
                    <span className="metric-label">Classes Held</span>
                  </div>
                  <div className="metric-item">
                    <span className="metric-num">{c.enrolled_students}</span>
                    <span className="metric-label">Students</span>
                  </div>
                  <div className="metric-item">
                    <span
                      className="metric-num"
                      style={{ color: healthColor(c.average_percentage) }}
                    >
                      {c.total_sessions > 0 ? `${c.average_percentage}%` : "—"}
                    </span>
                    <span className="metric-label">Avg Attendance</span>
                  </div>
                </div>

                <div className="course-card-bottom">
                  <button
                    type="button"
                    className="icon export-course-mini-btn"
                    title="Export Semester Excel (.xlsx)"
                    disabled={isExporting}
                    onClick={(e) => {
                      e.stopPropagation();
                      handleExportSemesterExcel(c.semester_course_id);
                    }}
                  >
                    <FileSpreadsheet size={16} />
                    <span>{isExporting ? "Exporting..." : "Export Excel"}</span>
                  </button>

                  <div className="course-card-enter">
                    <span>View Sessions</span>
                    <ChevronRight size={16} />
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ==========================================
// LEVEL 3: FULLSCREEN LIVE ATTENDANCE DASHBOARD
// ==========================================
export function FullscreenSessionDashboard({
  sessionId,
  onBack,
  onSessionClosed,
}: {
  sessionId: string;
  onBack: () => void;
  onSessionClosed: () => void;
}) {
  const qc = useQueryClient();
  const [summary, setSummary] = useState<FullSessionSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filterMode, setFilterMode] = useState<"all" | "present" | "absent">("all");
  const [searchQuery, setSearchQuery] = useState("");
  const [closing, setClosing] = useState(false);
  const [confirmCloseOpen, setConfirmCloseOpen] = useState(false);
  const [actionBanner, setActionBanner] = useState<string | null>(null);
  const [togglingStudentId, setTogglingStudentId] = useState<string | null>(null);

  const client = supabase();

  // Fetch summary
  const fetchSummary = async (isInitial = false) => {
    try {
      if (isInitial) setLoading(true);
      const data = await rpc<FullSessionSummary>("get_session_summary", {
        target_session: sessionId,
      });
      if (data) {
        setSummary(data);
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Failed to load session details");
    } finally {
      if (isInitial) setLoading(false);
    }
  };

  useEffect(() => {
    fetchSummary(true);

    // Realtime postgres changes on attendance_records for this session
    const channel = client
      .channel(`session:records:${sessionId}`)
      .on(
        "postgres_changes",
        {
          event: "*",
          schema: "classmate",
          table: "attendance_records",
          filter: `session_id=eq.${sessionId}`,
        },
        () => {
          fetchSummary(false);
        }
      )
      .subscribe();

    // Heartbeat poll every 2.5s while open to catch fast RFID swipes
    const interval = setInterval(() => {
      if (summary?.status === "open") {
        fetchSummary(false);
      }
    }, 2500);

    return () => {
      clearInterval(interval);
      client.removeChannel(channel);
    };
  }, [sessionId, summary?.status]);

  // Handle Close Session
  const handleCloseSession = async () => {
    try {
      setClosing(true);
      setActionBanner(null);
      await rpc("close_attendance_session", { target_session: sessionId });
      await fetchSummary(false);
      onSessionClosed();
    } catch (err: unknown) {
      setActionBanner(err instanceof Error ? err.message : "Failed to close session");
    } finally {
      setClosing(false);
    }
  };

  // Handle Safe Toggle Attendance Checklist Item
  const handleToggleStudent = async (studentId: string, currentStatus: "present" | "absent") => {
    try {
      setTogglingStudentId(studentId);
      const willBePresent = currentStatus !== "present";
      const newStatus: "present" | "absent" = willBePresent ? "present" : "absent";

      // Optimistic UI update
      setSummary((prev) => {
        if (!prev) return prev;
        const newRoster: RosterStudent[] = prev.roster.map((s) => {
          if (s.student_id === studentId) {
            return {
              ...s,
              status: newStatus,
              method: willBePresent ? "manual" : "none",
              marked_at: willBePresent ? new Date().toISOString() : null,
            };
          }
          return s;
        });
        const newPresCount = newRoster.filter((s) => s.status === "present").length;
        return {
          ...prev,
          present_count: newPresCount,
          roster: newRoster,
        };
      });

      // Call database toggle RPC
      await rpc("toggle_student_attendance", {
        target_session: sessionId,
        target_student: studentId,
        present: willBePresent,
      });

      // Refresh to confirm
      await fetchSummary(false);
    } catch (err: unknown) {
      setActionBanner(err instanceof Error ? err.message : "Failed to update attendance for student");
      await fetchSummary(false);
    } finally {
      setTogglingStudentId(null);
    }
  };

  // Sorted and filtered students
  const majorityPfx = useMemo(() => {
    return detectMajorityRollPrefix(summary?.roster.map((s) => s.roll_number) || []);
  }, [summary?.roster]);

  const displayedStudents = useMemo(() => {
    if (!summary?.roster) return [];
    let list = [...summary.roster];

    // Search filter
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase().trim();
      list = list.filter(
        (s) => s.roll_number.toLowerCase().includes(q) || s.full_name.toLowerCase().includes(q)
      );
    }

    // Status filter
    if (filterMode === "present") list = list.filter((s) => s.status === "present");
    if (filterMode === "absent") list = list.filter((s) => s.status === "absent");

    // Natural sort: roll numbers in natural order, re-add students at the bottom
    return list.sort((a, b) =>
      compareStudentsWithReaddLast(
        { roll: a.roll_number, full_name: a.full_name, is_readd: a.is_readd },
        { roll: b.roll_number, full_name: b.full_name, is_readd: b.is_readd },
        majorityPfx
      )
    );
  }, [summary?.roster, searchQuery, filterMode, majorityPfx]);

  const totalEnrolled = summary?.total_enrolled || summary?.roster.length || 0;
  const presentCount = summary?.present_count || summary?.roster.filter((s) => s.status === "present").length || 0;
  const absentCount = Math.max(0, totalEnrolled - presentCount);
  const percentage = totalEnrolled > 0 ? Math.round((presentCount / totalEnrolled) * 100) : 0;
  const isLive = summary?.status === "open";

  return (
    <div className="fullscreen-attendance-dashboard" role="dialog" aria-modal="true">
      {/* Top Navigation Bar */}
      <header className="fullscreen-topbar">
        <div className="fullscreen-topbar-left">
          <button
            type="button"
            className="fullscreen-back-btn"
            onClick={onBack}
            aria-label="Back to sessions"
          >
            <ArrowLeft size={18} />
            <div className="back-btn-text">
              <strong>Back to Sessions</strong>
              {isLive && <small>Session continues in background</small>}
            </div>
          </button>
        </div>

        <div className="fullscreen-topbar-center">
          <div className="fullscreen-course-badge-row">
            <span className="course-code-tag">{summary?.course_code || "COURSE"}</span>
            <span className="course-title-tag">{summary?.course_title || "Attendance Session"}</span>
            {isLive ? (
              <span className="live-status-pill">
                <span className="pulse-dot" />
                <span>LIVE RFID ATTENDANCE</span>
              </span>
            ) : (
              <span className="closed-status-pill">CLOSED SESSION</span>
            )}
          </div>
        </div>

        <div className="fullscreen-topbar-right">
          {isLive && (
            <button
              type="button"
              className="finish-session-btn danger"
              disabled={closing}
              onClick={() => setConfirmCloseOpen(true)}
            >
              <CheckCircle2 size={16} />
              <span>{closing ? "Closing..." : "Finish & Close Attendance Session"}</span>
            </button>
          )}
        </div>
      </header>

      {actionBanner && (
        <div style={{ margin: "12px 20px 0", padding: "10px 14px", borderRadius: 10, background: "rgba(239, 68, 68, 0.12)", border: "1px solid rgba(239, 68, 68, 0.3)", color: "#ef4444", fontSize: 13, fontWeight: 600, display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <span>{actionBanner}</span>
          <button type="button" onClick={() => setActionBanner(null)} style={{ background: "none", border: "none", color: "inherit", cursor: "pointer" }}><X size={14} /></button>
        </div>
      )}

      {confirmCloseOpen && (
        <div style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.55)", backdropFilter: "blur(4px)", zIndex: 1200, display: "flex", alignItems: "center", justifyContent: "center", padding: 16 }}>
          <div style={{ background: "var(--surface, #fff)", border: "1px solid var(--border, #e2e8f0)", borderRadius: 20, maxWidth: 420, width: "100%", padding: 24, boxShadow: "0 20px 50px rgba(0,0,0,0.35)" }}>
            <h3 style={{ margin: "0 0 8px", fontSize: 17, fontWeight: 800 }}>Finish & Close Attendance Session?</h3>
            <p style={{ margin: "0 0 18px", fontSize: 13.5, color: "var(--muted)", lineHeight: 1.5 }}>
              This will lock attendance for <strong>{summary?.course_code || "this class"}</strong> ({presentCount}/{totalEnrolled} present) and unlink the RFID reader.
            </p>
            <div style={{ display: "flex", gap: 10 }}>
              <button type="button" className="secondary" style={{ flex: 1 }} onClick={() => setConfirmCloseOpen(false)} disabled={closing}>
                Keep Open
              </button>
              <button
                type="button"
                className="primary"
                style={{ flex: 1, background: "#ef4444", borderColor: "#ef4444" }}
                disabled={closing}
                onClick={async () => {
                  setConfirmCloseOpen(false);
                  await handleCloseSession();
                }}
              >
                {closing ? "Closing..." : "Close Session"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Main Container */}
      <main className="fullscreen-main-content">
        {/* Statistics Bar */}
        <div className="fullscreen-stats-row">
          <div className="fullscreen-stat-card">
            <span className="stat-label">Total Enrolled</span>
            <strong className="stat-value">{totalEnrolled}</strong>
          </div>

          <div className="fullscreen-stat-card present-card">
            <span className="stat-label">Present</span>
            <strong className="stat-value text-green">{presentCount}</strong>
          </div>

          <div className="fullscreen-stat-card absent-card">
            <span className="stat-label">Absent</span>
            <strong className="stat-value text-muted">{absentCount}</strong>
          </div>

          <div className="fullscreen-stat-card pct-card">
            <span className="stat-label">Attendance Rate</span>
            <strong className="stat-value" style={{ color: healthColor(percentage) }}>
              {percentage}%
            </strong>
          </div>
        </div>

        {/* Toolbar: Search & Filters */}
        <div className="fullscreen-toolbar">
          <div className="fullscreen-search-box">
            <Search size={16} />
            <input
              type="search"
              placeholder="Search by student name or roll..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              aria-label="Search student roster"
            />
            {searchQuery && (
              <button
                type="button"
                className="icon clear-search-btn"
                onClick={() => setSearchQuery("")}
              >
                <X size={15} />
              </button>
            )}
          </div>

          <div className="fullscreen-filter-tabs">
            <button
              type="button"
              className={filterMode === "all" ? "active" : ""}
              onClick={() => setFilterMode("all")}
            >
              All ({totalEnrolled})
            </button>
            <button
              type="button"
              className={filterMode === "present" ? "active" : ""}
              onClick={() => setFilterMode("present")}
            >
              Present ({presentCount})
            </button>
            <button
              type="button"
              className={filterMode === "absent" ? "active" : ""}
              onClick={() => setFilterMode("absent")}
            >
              Absent ({absentCount})
            </button>
          </div>
        </div>

        {/* Checklist Table */}
        {loading ? (
          <div className="fullscreen-loading">
            <Skeleton />
          </div>
        ) : error ? (
          <ErrorBox error={error} retry={() => fetchSummary(true)} />
        ) : (
          <div className="checklist-table-container card">
            <table className="checklist-table" aria-label="Student Attendance Checklist">
              <thead>
                <tr>
                  <th style={{ width: "60px", textAlign: "center" }}>Check</th>
                  <th style={{ width: "160px" }}>Student ID / Roll</th>
                  <th>Student Name</th>
                  <th style={{ width: "130px" }}>Status</th>
                  <th style={{ width: "200px" }}>Method / Scanned At</th>
                  <th style={{ width: "100px", textAlign: "right" }}>Action</th>
                </tr>
              </thead>
              <tbody>
                {displayedStudents.length === 0 ? (
                  <tr>
                    <td colSpan={6} style={{ textAlign: "center", padding: "32px", color: "var(--muted)" }}>
                      No students matching the current filter.
                    </td>
                  </tr>
                ) : (
                  displayedStudents.map((s) => {
                    const isPresent = s.status === "present";
                    const isPending = togglingStudentId === s.student_id;
                    const isRfid = s.method === "rfid";

                    return (
                      <tr
                        key={s.student_id}
                        className={`checklist-row ${isPresent ? "row-present" : "row-absent"}`}
                        onClick={() => handleToggleStudent(s.student_id, s.status)}
                      >
                        {/* Checkbox */}
                        <td style={{ textAlign: "center" }} onClick={(e) => e.stopPropagation()}>
                          <button
                            type="button"
                            className={`checklist-checkbox-btn ${isPresent ? "checked" : ""}`}
                            disabled={isPending}
                            onClick={() => handleToggleStudent(s.student_id, s.status)}
                            aria-label={`Mark ${s.full_name} as ${isPresent ? "absent" : "present"}`}
                          >
                            {isPresent ? <CheckSquare size={20} /> : <Square size={20} />}
                          </button>
                        </td>

                        {/* Roll */}
                        <td>
                          <span className="student-roll-code">{s.roll_number || "—"}</span>
                        </td>

                        {/* Name */}
                        <td>
                          <span className="student-name-text">{s.full_name}</span>
                        </td>

                        {/* Status badge */}
                        <td>
                          {isPresent ? (
                            <span className="status-badge present-badge">
                              <Check size={13} />
                              <span>Present</span>
                            </span>
                          ) : (
                            <span className="status-badge absent-badge">
                              <X size={13} />
                              <span>Absent</span>
                            </span>
                          )}
                        </td>

                        {/* Method & Timestamp */}
                        <td>
                          {isPresent ? (
                            <div className="method-pill-group">
                              {isRfid ? (
                                <span className="rfid-method-tag" title="Scanned via hardware RFID card">
                                  <Radio size={13} />
                                  <span>RFID Card</span>
                                </span>
                              ) : (
                                <span className="manual-method-tag" title="Teacher checklist manual mark">
                                  <Check size={13} />
                                  <span>Manual</span>
                                </span>
                              )}
                              {s.marked_at && (
                                <span className="marked-time-tag">
                                  {new Date(s.marked_at).toLocaleTimeString("en-US", {
                                    hour: "numeric",
                                    minute: "2-digit",
                                    second: "2-digit",
                                  })}
                                </span>
                              )}
                            </div>
                          ) : (
                            <span style={{ color: "var(--muted)" }}>—</span>
                          )}
                        </td>

                        {/* Quick action button */}
                        <td style={{ textAlign: "right" }} onClick={(e) => e.stopPropagation()}>
                          <button
                            type="button"
                            className={`mini-toggle-btn ${isPresent ? "toggle-unmark" : "toggle-mark"}`}
                            disabled={isPending}
                            onClick={() => handleToggleStudent(s.student_id, s.status)}
                          >
                            {isPending ? "..." : isPresent ? "Mark Absent" : "Mark Present"}
                          </button>
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>
        )}
      </main>
    </div>
  );
}

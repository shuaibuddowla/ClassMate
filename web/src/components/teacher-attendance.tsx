"use client";

import { useState, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { rpc } from "@/lib/api";
import { type Context } from "./app";
import { AttendanceTeacherModal } from "./attendance-teacher-modal";
import { ErrorBox, Skeleton } from "./ui";
import {
  CalendarDays,
  Users,
  Download,
  CheckCircle2,
  AlertTriangle,
  ChevronRight,
  TrendingUp,
  Clock3,
  Calendar,
  Layers,
  Sparkles,
  RefreshCw,
  Search,
  FileSpreadsheet,
  GraduationCap,
  ShieldCheck,
  UserX,
  BookOpen,
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
  course_code: string;
  course_title: string;
  course_type: string;
  credit: number;
  teacher_name?: string;
  total_sessions: number;
  enrolled_students: number;
  average_percentage: number;
  attendance_enabled?: boolean;
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

export default function TeacherAttendanceDashboard({ ctx }: { ctx: Context }) {
  const [selectedCourseId, setSelectedCourseId] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<"sessions" | "roster">("sessions");
  const [rosterFilter, setRosterFilter] = useState<"all" | "eligible" | "shortage">("all");
  const [rosterSearch, setRosterSearch] = useState<string>("");
  const [sessionSearch, setSessionSearch] = useState<string>("");
  const [reopenSession, setReopenSession] = useState<{
    sessionId: string;
    slotId: string;
    courseTitle: string;
    courseCode: string;
  } | null>(null);
  const [exportingCourseId, setExportingCourseId] = useState<string | null>(null);
  const [exportingSessionId, setExportingSessionId] = useState<string | null>(null);

  // 1. Fetch overview for all courses taught by caller in target batch
  const overviewQuery = useQuery({
    queryKey: [ctx.user, ctx.batch, "teacher-attendance-overview"],
    queryFn: () =>
      rpc<any[]>("teacher_course_attendance_overview", {
        target_batch: ctx.batch,
      }),
  });

  // Defensive data normalization
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
      attendance_enabled: c.attendance_enabled ?? true,
      sessions: Array.isArray(c.sessions) ? c.sessions : [],
    }));
  }, [overviewQuery.data]);

  const activeCourse = useMemo(() => {
    if (!courses.length) return null;
    return courses.find((c) => c.semester_course_id === selectedCourseId) || courses[0];
  }, [courses, selectedCourseId]);

  // 2. Fetch full course attendance matrix when "Student Roster" tab is selected
  const matrixQuery = useQuery({
    queryKey: [activeCourse?.semester_course_id, "course-attendance-matrix"],
    queryFn: () =>
      rpc<MatrixResponse>("course_attendance_matrix", {
        target_semester_course: activeCourse!.semester_course_id,
      }),
    enabled: !!activeCourse && activeTab === "roster",
  });

  // Export full course cumulative Excel matrix
  const handleExportCourseMatrix = async (course: TeacherCourse) => {
    try {
      setExportingCourseId(course.semester_course_id);
      const { downloadCourseMatrixExcel } = await import("@/lib/attendance-excel");
      await downloadCourseMatrixExcel(course.semester_course_id);
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : "Export failed");
    } finally {
      setExportingCourseId(null);
    }
  };

  // Export individual session Excel roster
  const handleExportSession = async (sess: SessionInfo, courseCode: string) => {
    try {
      setExportingSessionId(sess.session_id);
      const { downloadSessionExcel } = await import("@/lib/attendance-excel");
      const dateStr = new Date(sess.started_at).toISOString().split("T")[0];
      await downloadSessionExcel(sess.session_id, courseCode, dateStr);
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : "Session export failed");
    } finally {
      setExportingSessionId(null);
    }
  };

  // Filtered sessions
  const filteredSessions = useMemo(() => {
    if (!activeCourse) return [];
    const sessions = activeCourse.sessions || [];
    if (!sessionSearch.trim()) return sessions;
    const q = sessionSearch.toLowerCase();
    return sessions.filter((s) => {
      const dateFormatted = new Date(s.started_at).toLocaleDateString("en-US", {
        weekday: "short",
        month: "short",
        day: "numeric",
        year: "numeric",
      }).toLowerCase();
      const roomMatch = s.room ? s.room.toLowerCase().includes(q) : false;
      return dateFormatted.includes(q) || roomMatch || s.status.includes(q);
    });
  }, [activeCourse, sessionSearch]);

  // Filtered matrix students
  const filteredStudents = useMemo(() => {
    const students = matrixQuery.data?.students || [];
    return students.filter((s) => {
      // 1. Eligibility filter (MBSTU standard: 75% cutoff)
      if (rosterFilter === "eligible" && s.percentage < 75) return false;
      if (rosterFilter === "shortage" && s.percentage >= 75) return false;

      // 2. Search query filter
      if (rosterSearch.trim()) {
        const q = rosterSearch.toLowerCase();
        const matchName = s.full_name.toLowerCase().includes(q);
        const matchRoll = s.roll.toLowerCase().includes(q);
        return matchName || matchRoll;
      }
      return true;
    });
  }, [matrixQuery.data?.students, rosterFilter, rosterSearch]);

  const studentCounts = useMemo(() => {
    const students = matrixQuery.data?.students || [];
    const total = students.length;
    const eligible = students.filter((s) => s.percentage >= 75).length;
    const shortage = total - eligible;
    return { total, eligible, shortage };
  }, [matrixQuery.data?.students]);

  return (
    <div className="attendance-dashboard-screen">
      {/* Header */}
      <div className="attendance-screen-header">
        <div>
          <span className="eyebrow">TEACHING WORKSPACE</span>
          <h1>Course Attendance & Records</h1>
          <p>
            Track long-term attendance, monitor individual student eligibility, and export
            official cumulative spreadsheets.
          </p>
        </div>
        <div className="header-actions">
          <button
            className="secondary"
            onClick={() => {
              overviewQuery.refetch();
              if (activeTab === "roster") matrixQuery.refetch();
            }}
            disabled={overviewQuery.isFetching || matrixQuery.isFetching}
            title="Refresh attendance records"
          >
            <RefreshCw
              size={16}
              className={overviewQuery.isFetching || matrixQuery.isFetching ? "spin" : ""}
            />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {/* Defensive Error Display */}
      {overviewQuery.isError && (
        <ErrorBox
          error={overviewQuery.error}
          retry={() => overviewQuery.refetch()}
        />
      )}

      {overviewQuery.isLoading ? (
        <div className="attendance-loading-state">
          <RefreshCw className="spin" size={28} />
          <span>Loading course attendance records...</span>
        </div>
      ) : courses.length === 0 ? (
        <div className="attendance-empty-card card">
          <CalendarDays size={42} className="text-muted" />
          <h3>No Courses Found</h3>
          <p>
            You are not currently assigned to any courses in this batch, or no routine
            slots have been created yet.
          </p>
          <small className="text-muted">
            Check with your batch administrator or select another batch from your profile menu.
          </small>
        </div>
      ) : (
        <div className="attendance-dashboard-layout">
          {/* Top Course Selection Bar */}
          <div className="attendance-courses-bar">
            {courses.map((c) => {
              const isSelected = activeCourse?.semester_course_id === c.semester_course_id;
              const isHealthy = c.average_percentage >= 75;
              return (
                <button
                  key={c.semester_course_id}
                  type="button"
                  className={`course-overview-pill ${isSelected ? "selected" : ""}`}
                  onClick={() => setSelectedCourseId(c.semester_course_id)}
                >
                  <div className="pill-code-row">
                    <strong className="course-code-highlight">{c.course_code}</strong>
                    <span className={`pill-type-tag type-${c.course_type}`}>
                      {c.course_type.toUpperCase()}
                    </span>
                  </div>
                  <span className="pill-title" title={c.course_title}>
                    {c.course_title}
                  </span>
                  <div className="pill-metrics">
                    <span>
                      <Calendar size={13} /> {c.total_sessions} {c.total_sessions === 1 ? "class" : "classes"}
                    </span>
                    <span className={isHealthy ? "text-success" : "text-amber"}>
                      <TrendingUp size={13} /> {c.average_percentage}% avg
                    </span>
                  </div>
                </button>
              );
            })}
          </div>

          {/* Active Course Workspace */}
          {activeCourse && (
            <div className="active-course-panel">
              {/* Course Hero Stats Card */}
              <div className="course-hero-card card">
                <div className="hero-top-row">
                  <div className="hero-info">
                    <div className="hero-badges">
                      <span className={`pill-type-tag type-${activeCourse.course_type}`}>
                        {activeCourse.course_type.toUpperCase()}
                      </span>
                      <span className="course-credit-badge">
                        {activeCourse.credit} Credits
                      </span>
                      {activeCourse.sessions.some((s) => s.status === "open") && (
                        <span className="live-session-pill">
                          <span className="live-pulse-dot" /> Live Session Active
                        </span>
                      )}
                    </div>
                    <h2>
                      {activeCourse.course_title}{" "}
                      <span className="course-code-muted">({activeCourse.course_code})</span>
                    </h2>
                    <p className="hero-subtitle">
                      Instructor: <strong>{activeCourse.teacher_name}</strong> ·{" "}
                      {activeCourse.enrolled_students} enrolled students
                    </p>
                  </div>

                  <div className="hero-actions">
                    <button
                      className="primary export-matrix-btn"
                      disabled={exportingCourseId === activeCourse.semester_course_id}
                      onClick={() => handleExportCourseMatrix(activeCourse)}
                    >
                      <Download size={17} />
                      <span>
                        {exportingCourseId === activeCourse.semester_course_id
                          ? "Exporting Matrix..."
                          : "Download Course Excel Matrix (.xlsx)"}
                      </span>
                    </button>
                  </div>
                </div>

                {/* Key Metrics Grid */}
                <div className="hero-stats-group">
                  <div className="hero-stat-box">
                    <span className="stat-label">Average Attendance</span>
                    <strong
                      className={`stat-number ${
                        activeCourse.average_percentage >= 75 ? "text-success" : "text-amber"
                      }`}
                    >
                      {activeCourse.average_percentage}%
                    </strong>
                    <small className="stat-subtext">
                      {activeCourse.average_percentage >= 75
                        ? "Overall class is on track"
                        : "Below standard 75% threshold"}
                    </small>
                  </div>

                  <div className="hero-stat-box">
                    <span className="stat-label">Total Classes Held</span>
                    <strong className="stat-number">{activeCourse.total_sessions}</strong>
                    <small className="stat-subtext">Sessions recorded in routine</small>
                  </div>

                  <div className="hero-stat-box">
                    <span className="stat-label">Enrolled Students</span>
                    <strong className="stat-number">{activeCourse.enrolled_students}</strong>
                    <small className="stat-subtext">Active batch roster size</small>
                  </div>

                  <div className="hero-stat-box">
                    <span className="stat-label">Latest Class</span>
                    <strong className="stat-date">
                      {activeCourse.sessions[0]
                        ? new Date(activeCourse.sessions[0].started_at).toLocaleDateString("en-US", {
                            month: "short",
                            day: "numeric",
                          })
                        : "None yet"}
                    </strong>
                    <small className="stat-subtext">
                      {activeCourse.sessions[0]
                        ? `${activeCourse.sessions[0].present_count} / ${activeCourse.sessions[0].total_count} present`
                        : "No session taken"}
                    </small>
                  </div>
                </div>
              </div>

              {/* View Selector Tabs: Class Sessions vs Student Roster */}
              <div className="attendance-tab-nav">
                <div className="segmented-tab-buttons">
                  <button
                    type="button"
                    className={`tab-btn ${activeTab === "sessions" ? "active" : ""}`}
                    onClick={() => setActiveTab("sessions")}
                  >
                    <Clock3 size={16} />
                    <span>Class Sessions</span>
                    <span className="tab-counter-badge">{activeCourse.sessions.length}</span>
                  </button>

                  <button
                    type="button"
                    className={`tab-btn ${activeTab === "roster" ? "active" : ""}`}
                    onClick={() => setActiveTab("roster")}
                  >
                    <GraduationCap size={16} />
                    <span>Student Roster & Eligibility</span>
                    <span className="tab-counter-badge">{activeCourse.enrolled_students}</span>
                  </button>
                </div>
              </div>

              {/* TAB 1: CLASS SESSIONS */}
              {activeTab === "sessions" && (
                <div className="course-sessions-section">
                  <div className="section-toolbar">
                    <div className="search-box-wrap">
                      <Search size={15} className="search-icon" />
                      <input
                        type="text"
                        placeholder="Filter sessions by date, room, or status..."
                        value={sessionSearch}
                        onChange={(e) => setSessionSearch(e.target.value)}
                        className="session-search-input"
                      />
                    </div>
                    <span className="section-count">
                      Showing {filteredSessions.length} of {activeCourse.sessions.length} sessions
                    </span>
                  </div>

                  {activeCourse.sessions.length === 0 ? (
                    <div className="no-sessions-card card">
                      <Layers size={36} className="text-muted" />
                      <h3>No attendance sessions taken yet</h3>
                      <p>
                        Open the <strong>Timetable</strong> tab during your scheduled class to start a
                        live rotating QR attendance session.
                      </p>
                    </div>
                  ) : filteredSessions.length === 0 ? (
                    <div className="no-sessions-card card">
                      <Search size={32} className="text-muted" />
                      <p>No sessions match your search filter.</p>
                      <button
                        className="secondary"
                        onClick={() => setSessionSearch("")}
                      >
                        Clear Filter
                      </button>
                    </div>
                  ) : (
                    <div className="sessions-table-card card">
                      <div className="sessions-list">
                        {filteredSessions.map((sess, idx) => {
                          const dateObj = new Date(sess.started_at);
                          const dateFormatted = dateObj.toLocaleDateString("en-US", {
                            weekday: "short",
                            month: "short",
                            day: "numeric",
                            year: "numeric",
                          });
                          const timeFormatted = dateObj.toLocaleTimeString("en-US", {
                            hour: "2-digit",
                            minute: "2-digit",
                          });
                          const pct =
                            sess.total_count > 0
                              ? Math.round((sess.present_count / sess.total_count) * 100)
                              : 0;
                          const isExportingThis = exportingSessionId === sess.session_id;

                          return (
                            <div key={sess.session_id} className="session-item-row">
                              <div className="session-date-col">
                                <span className="session-index">
                                  #{activeCourse.sessions.length - idx}
                                </span>
                                <div>
                                  <div className="session-title-line">
                                    <strong>{dateFormatted}</strong>
                                    {sess.status === "open" ? (
                                      <span className="session-status-tag status-live">
                                        <span className="live-pulse-dot" /> Live Now
                                      </span>
                                    ) : (
                                      <span className="session-status-tag status-closed">Closed</span>
                                    )}
                                  </div>
                                  <small>
                                    Started at {timeFormatted}
                                    {sess.room ? ` · Room ${sess.room}` : ""}
                                  </small>
                                </div>
                              </div>

                              <div className="session-metrics-col">
                                <div className="session-progress-container">
                                  <div className="session-ratio-row">
                                    <span className="present-count-text">
                                      <strong>{sess.present_count}</strong> / {sess.total_count} present
                                    </span>
                                    <span
                                      className={`pct-badge ${
                                        pct >= 75 ? "badge-good" : "badge-warn"
                                      }`}
                                    >
                                      {pct}%
                                    </span>
                                  </div>
                                  <div className="session-fill-track">
                                    <div
                                      className={`session-fill-bar ${
                                        pct >= 75 ? "fill-green" : "fill-amber"
                                      }`}
                                      style={{ width: `${Math.min(pct, 100)}%` }}
                                    />
                                  </div>
                                </div>
                              </div>

                              <div className="session-actions-col">
                                <button
                                  className="secondary session-export-btn"
                                  disabled={isExportingThis}
                                  onClick={() => handleExportSession(sess, activeCourse.course_code)}
                                  title="Export single session Excel sheet"
                                >
                                  <FileSpreadsheet size={15} />
                                  <span>{isExportingThis ? "Exporting..." : "Excel"}</span>
                                </button>

                                <button
                                  className="secondary session-open-btn"
                                  onClick={() =>
                                    setReopenSession({
                                      sessionId: sess.session_id,
                                      slotId: sess.routine_slot_id || "",
                                      courseTitle: activeCourse.course_title,
                                      courseCode: activeCourse.course_code,
                                    })
                                  }
                                >
                                  <span>Inspect Roster</span>
                                  <ChevronRight size={15} />
                                </button>
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    </div>
                  )}
                </div>
              )}

              {/* TAB 2: STUDENT ROSTER & ELIGIBILITY */}
              {activeTab === "roster" && (
                <div className="student-roster-section">
                  {matrixQuery.isLoading ? (
                    <div className="attendance-loading-state">
                      <RefreshCw className="spin" size={24} />
                      <span>Loading cumulative student performance roster...</span>
                    </div>
                  ) : matrixQuery.isError ? (
                    <ErrorBox
                      error={matrixQuery.error}
                      retry={() => matrixQuery.refetch()}
                    />
                  ) : (
                    <>
                      {/* Roster Filter & Search Bar */}
                      <div className="roster-control-panel card">
                        <div className="roster-filters-row">
                          <div className="roster-chips-group">
                            <button
                              type="button"
                              className={`filter-chip ${rosterFilter === "all" ? "active" : ""}`}
                              onClick={() => setRosterFilter("all")}
                            >
                              All Students ({studentCounts.total})
                            </button>
                            <button
                              type="button"
                              className={`filter-chip chip-success ${
                                rosterFilter === "eligible" ? "active" : ""
                              }`}
                              onClick={() => setRosterFilter("eligible")}
                            >
                              <ShieldCheck size={14} />
                              Eligible ≥ 75% ({studentCounts.eligible})
                            </button>
                            <button
                              type="button"
                              className={`filter-chip chip-danger ${
                                rosterFilter === "shortage" ? "active" : ""
                              }`}
                              onClick={() => setRosterFilter("shortage")}
                            >
                              <AlertTriangle size={14} />
                              Shortage &lt; 75% ({studentCounts.shortage})
                            </button>
                          </div>

                          <div className="roster-search-wrap">
                            <Search size={15} className="search-icon" />
                            <input
                              type="text"
                              placeholder="Search by student name or roll..."
                              value={rosterSearch}
                              onChange={(e) => setRosterSearch(e.target.value)}
                              className="roster-search-input"
                            />
                          </div>
                        </div>

                        {/* Roster Overview Summary Banner */}
                        <div className="roster-summary-banner">
                          <span>
                            <strong>{studentCounts.eligible}</strong> out of{" "}
                            <strong>{studentCounts.total}</strong> students currently qualify for regular
                            exam collegiate status.
                          </span>
                          {studentCounts.shortage > 0 && (
                            <span className="shortage-warning-tag">
                              <AlertTriangle size={13} /> {studentCounts.shortage} students need attention
                            </span>
                          )}
                        </div>
                      </div>

                      {/* Students Performance Table */}
                      {filteredStudents.length === 0 ? (
                        <div className="no-sessions-card card">
                          <Users size={32} className="text-muted" />
                          <p>No students match your selected filter.</p>
                        </div>
                      ) : (
                        <div className="roster-table-card card">
                          <div className="roster-table-header">
                            <div className="th-roll">Roll / ID</div>
                            <div className="th-name">Student Name</div>
                            <div className="th-attendance">Attendance</div>
                            <div className="th-percentage">Percentage</div>
                            <div className="th-status">Eligibility</div>
                          </div>

                          <div className="roster-table-body">
                            {filteredStudents.map((st) => {
                              const pct = st.percentage ?? 0;
                              let statusTag = {
                                label: "Collegiate (Eligible)",
                                cls: "status-eligible",
                              };
                              if (pct < 60) {
                                statusTag = {
                                  label: "Critical Shortage",
                                  cls: "status-critical",
                                };
                              } else if (pct < 75) {
                                statusTag = {
                                  label: "Non-Collegiate (Fine)",
                                  cls: "status-warning",
                                };
                              }

                              return (
                                <div key={st.student_id} className="roster-table-row">
                                  <div className="td-roll">
                                    <span className="roll-badge">{st.roll || "—"}</span>
                                  </div>
                                  <div className="td-name">
                                    <strong>{st.full_name}</strong>
                                  </div>
                                  <div className="td-attendance">
                                    <span className="classes-ratio">
                                      <strong>{st.present_count}</strong> / {st.total_sessions} classes
                                    </span>
                                  </div>
                                  <div className="td-percentage">
                                    <div className="pct-bar-wrap">
                                      <span
                                        className={`pct-number ${
                                          pct >= 75
                                            ? "text-success"
                                            : pct >= 60
                                              ? "text-amber"
                                              : "text-danger"
                                        }`}
                                      >
                                        {pct}%
                                      </span>
                                      <div className="mini-track">
                                        <div
                                          className={`mini-bar ${
                                            pct >= 75
                                              ? "fill-green"
                                              : pct >= 60
                                                ? "fill-amber"
                                                : "fill-red"
                                          }`}
                                          style={{ width: `${Math.min(pct, 100)}%` }}
                                        />
                                      </div>
                                    </div>
                                  </div>
                                  <div className="td-status">
                                    <span className={`eligibility-badge ${statusTag.cls}`}>
                                      {statusTag.label}
                                    </span>
                                  </div>
                                </div>
                              );
                            })}
                          </div>
                        </div>
                      )}
                    </>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* Inspect Session Modal (QR, live roster, manual override) */}
      {reopenSession && (
        <AttendanceTeacherModal
          sessionId={reopenSession.sessionId}
          slotId={reopenSession.slotId}
          courseTitle={reopenSession.courseTitle}
          courseCode={reopenSession.courseCode}
          onClose={() => {
            setReopenSession(null);
            overviewQuery.refetch();
            if (activeTab === "roster") matrixQuery.refetch();
          }}
        />
      )}
    </div>
  );
}

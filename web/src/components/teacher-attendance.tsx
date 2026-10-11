"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { rpc, type Row } from "@/lib/api";
import { type Context } from "./app";
import { AttendanceTeacherModal } from "./attendance-teacher-modal";
import {
  CalendarDays,
  Users,
  Download,
  CheckCircle2,
  AlertTriangle,
  ChevronRight,
  TrendingUp,
  BarChart2,
  Clock3,
  Calendar,
  Layers,
  Sparkles,
  RefreshCw,
} from "lucide-react";

interface TeacherCourse {
  semester_course_id: string;
  course_code: string;
  course_title: string;
  course_type: string;
  total_sessions: number;
  enrolled_students: number;
  average_percentage: number;
  sessions: Array<{
    session_id: string;
    started_at: string;
    closed_at: string | null;
    status: "open" | "closed";
    room: string | null;
    present_count: number;
    total_count: number;
  }>;
}

export default function TeacherAttendanceDashboard({ ctx }: { ctx: Context }) {
  const [selectedCourseId, setSelectedCourseId] = useState<string | null>(null);
  const [reopenSession, setReopenSession] = useState<{
    sessionId: string;
    slotId: string;
    courseTitle: string;
    courseCode: string;
  } | null>(null);
  const [exportingCourseId, setExportingCourseId] = useState<string | null>(null);

  // Fetch all courses overview for current teacher in current batch
  const overviewQuery = useQuery({
    queryKey: [ctx.user, ctx.batch, "teacher-attendance-overview"],
    queryFn: () =>
      rpc<TeacherCourse[]>("teacher_course_attendance_overview", {
        target_batch: ctx.batch,
      }),
  });

  const courses: TeacherCourse[] = overviewQuery.data || [];
  const activeCourse =
    courses.find((c) => c.semester_course_id === selectedCourseId) || courses[0] || null;

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

  return (
    <div className="attendance-dashboard-screen">
      {/* Header */}
      <div className="attendance-screen-header">
        <div>
          <span className="eyebrow">TEACHING WORKSPACE</span>
          <h1>Course Attendance Records</h1>
          <p>
            Track long-term attendance, view class sessions, and export multi-date
            attendance sheets for your courses.
          </p>
        </div>
        <div className="header-actions">
          <button
            className="secondary"
            onClick={() => overviewQuery.refetch()}
            disabled={overviewQuery.isFetching}
          >
            <RefreshCw size={16} className={overviewQuery.isFetching ? "spin" : ""} />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {overviewQuery.isLoading ? (
        <div className="attendance-loading-state">
          <RefreshCw className="spin" size={28} />
          <span>Loading course attendance overview...</span>
        </div>
      ) : courses.length === 0 ? (
        <div className="attendance-empty-card card">
          <CalendarDays size={42} className="text-muted" />
          <h3>No Courses Found</h3>
          <p>
            You are not currently assigned to any courses in this batch, or no
            routine slots have been created yet.
          </p>
        </div>
      ) : (
        <div className="attendance-dashboard-layout">
          {/* Left / Top Course Cards Selector */}
          <div className="attendance-courses-bar">
            {courses.map((c) => {
              const isSelected = activeCourse?.semester_course_id === c.semester_course_id;
              return (
                <button
                  key={c.semester_course_id}
                  type="button"
                  className={`course-overview-pill ${isSelected ? "selected" : ""}`}
                  onClick={() => setSelectedCourseId(c.semester_course_id)}
                >
                  <div className="pill-code-row">
                    <strong>{c.course_code}</strong>
                    <span className="pill-type-tag">{c.course_type?.toUpperCase()}</span>
                  </div>
                  <span className="pill-title">{c.course_title}</span>
                  <div className="pill-metrics">
                    <span>
                      <Calendar size={13} /> {c.total_sessions} classes
                    </span>
                    <span>
                      <TrendingUp size={13} /> {c.average_percentage}%
                    </span>
                  </div>
                </button>
              );
            })}
          </div>

          {/* Active Course Details Panel */}
          {activeCourse && (
            <div className="active-course-panel">
              {/* Course Hero Stats Card */}
              <div className="course-hero-card card">
                <div className="hero-info">
                  <div className="hero-title-row">
                    <h2>
                      {activeCourse.course_title}{" "}
                      <span className="course-code-muted">({activeCourse.course_code})</span>
                    </h2>
                  </div>
                  <p>
                    {activeCourse.enrolled_students} enrolled students ·{" "}
                    {activeCourse.total_sessions} total attendance sessions taken
                  </p>
                </div>

                <div className="hero-stats-group">
                  <div className="hero-stat-box">
                    <span className="stat-label">Average Attendance</span>
                    <strong className="stat-number">{activeCourse.average_percentage}%</strong>
                  </div>
                  <div className="hero-stat-box">
                    <span className="stat-label">Total Classes</span>
                    <strong className="stat-number">{activeCourse.total_sessions}</strong>
                  </div>
                  <div className="hero-stat-box">
                    <span className="stat-label">Enrolled Students</span>
                    <strong className="stat-number">{activeCourse.enrolled_students}</strong>
                  </div>
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
                        ? "Exporting Sheet..."
                        : "Download Course Excel Matrix (.xlsx)"}
                    </span>
                  </button>
                </div>
              </div>

              {/* Session History List */}
              <div className="course-sessions-section">
                <div className="section-title-bar">
                  <h3>
                    <Clock3 size={18} /> Past Attendance Sessions
                  </h3>
                  <span className="section-count">{activeCourse.sessions.length} sessions</span>
                </div>

                {activeCourse.sessions.length === 0 ? (
                  <div className="no-sessions-card card">
                    <Layers size={32} className="text-muted" />
                    <p>No attendance has been taken yet for this course.</p>
                    <small>
                      Open the Timetable during your scheduled class to start a live session.
                    </small>
                  </div>
                ) : (
                  <div className="sessions-table-card card">
                    <div className="sessions-list">
                      {activeCourse.sessions.map((sess, idx) => {
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

                        return (
                          <div key={sess.session_id} className="session-item-row">
                            <div className="session-date-col">
                              <span className="session-index">#{activeCourse.sessions.length - idx}</span>
                              <div>
                                <strong>{dateFormatted}</strong>
                                <small>Started at {timeFormatted} {sess.room ? `· Room ${sess.room}` : ""}</small>
                              </div>
                            </div>

                            <div className="session-metrics-col">
                              <div className="session-attendance-badge">
                                <strong>{sess.present_count} / {sess.total_count}</strong>
                                <span>({pct}%)</span>
                              </div>
                              <span
                                className={`session-status-tag ${
                                  sess.status === "open" ? "status-live" : "status-closed"
                                }`}
                              >
                                {sess.status === "open" ? "Live Now" : "Closed"}
                              </span>
                            </div>

                            <div className="session-actions-col">
                              <button
                                className="session-open-btn secondary"
                                onClick={() =>
                                  setReopenSession({
                                    sessionId: sess.session_id,
                                    slotId: "",
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
            </div>
          )}
        </div>
      )}

      {/* Re-open / Inspect Session Modal */}
      {reopenSession && (
        <AttendanceTeacherModal
          sessionId={reopenSession.sessionId}
          slotId={reopenSession.slotId}
          courseTitle={reopenSession.courseTitle}
          courseCode={reopenSession.courseCode}
          onClose={() => {
            setReopenSession(null);
            overviewQuery.refetch();
          }}
        />
      )}
    </div>
  );
}

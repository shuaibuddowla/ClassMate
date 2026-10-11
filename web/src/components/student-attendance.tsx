"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { rpc, type Row } from "@/lib/api";
import { type Context } from "./app";
import {
  CheckCircle2,
  AlertTriangle,
  Clock3,
  Calendar,
  BookOpen,
  TrendingUp,
  Percent,
  RefreshCw,
  ChevronDown,
  ChevronUp,
} from "lucide-react";

interface SessionHistoryItem {
  session_id: string;
  date: string;
  time?: string;
  room?: string;
  status: "present" | "absent";
  marked_at?: string;
}

interface StudentCourseAttendance {
  semester_course_id: string;
  course_code: string;
  course_title: string;
  course_type: string;
  classes_held: number;
  classes_attended: number;
  percentage: number;
  attendance_enabled?: boolean;
  history?: SessionHistoryItem[];
  recent_history?: SessionHistoryItem[];
}

interface StudentAttendanceResponse {
  overall_percentage: number;
  total_classes_held: number;
  total_classes_attended: number;
  courses: StudentCourseAttendance[];
}

export default function StudentAttendanceDashboard({ ctx }: { ctx: Context }) {
  const [expandedCourseIds, setExpandedCourseIds] = useState<Record<string, boolean>>({});

  const toggleCourse = (id: string) => {
    setExpandedCourseIds((prev) => ({ ...prev, [id]: !prev[id] }));
  };

  const query = useQuery({
    queryKey: [ctx.user, ctx.batch, "student-my-attendance"],
    queryFn: () =>
      rpc<StudentAttendanceResponse>("student_my_attendance", {
        target_batch: ctx.batch,
      }),
  });

  const data = query.data;
  const courses = data?.courses || [];
  const overallPercentage = data?.overall_percentage || 0;
  const totalHeld = data?.total_classes_held || 0;
  const totalAttended = data?.total_classes_attended || 0;

  const isHealthy = overallPercentage >= 75;

  return (
    <div className="student-attendance-view">
      {/* Overview Stat Banner */}
      <div className={`student-attendance-hero card ${isHealthy ? "healthy" : "warning"}`}>
        <div className="hero-progress-ring-box">
          <div className="stat-circle">
            <span className="stat-big-number">{overallPercentage}%</span>
            <small>OVERALL</small>
          </div>
        </div>

        <div className="hero-text-details">
          <div className="hero-badge-row">
            {isHealthy ? (
              <span className="badge-pill badge-green">
                <CheckCircle2 size={13} /> On Track (≥ 75%)
              </span>
            ) : (
              <span className="badge-pill badge-amber">
                <AlertTriangle size={13} /> Attention Needed (&lt; 75%)
              </span>
            )}
          </div>
          <h2>My Academic Attendance</h2>
          <p>
            You have attended <strong>{totalAttended}</strong> out of{" "}
            <strong>{totalHeld}</strong> total classes recorded so far this semester.
          </p>
        </div>

        <button
          className="icon-refresh-btn"
          onClick={() => query.refetch()}
          disabled={query.isFetching}
          aria-label="Refresh attendance records"
        >
          <RefreshCw size={17} className={query.isFetching ? "spin" : ""} />
        </button>
      </div>

      {/* Courses Breakdown */}
      <div className="student-courses-section">
        <div className="section-header-row">
          <h3>
            <BookOpen size={18} /> Course-wise Attendance
          </h3>
          <span>{courses.length} courses enrolled</span>
        </div>

        {query.isLoading ? (
          <div className="attendance-loading-state">
            <RefreshCw className="spin" size={24} />
            <span>Calculating your attendance metrics...</span>
          </div>
        ) : courses.length === 0 ? (
          <div className="card empty-card">
            <Calendar size={36} className="text-muted" />
            <p>No course attendance records found for this semester yet.</p>
          </div>
        ) : (
          <div className="student-course-cards-grid">
            {courses.map((c) => {
              const isCourseHealthy = c.percentage >= 75;
              const hasClasses = c.classes_held > 0;
              const isExpanded = Boolean(expandedCourseIds[c.semester_course_id]);
              const historyList = c.history || c.recent_history || [];

              return (
                <div
                  key={c.semester_course_id}
                  className={`course-attendance-card card ${isExpanded ? "expanded" : ""}`}
                >
                  <div
                    className="card-clickable-header"
                    role="button"
                    tabIndex={0}
                    onClick={() => toggleCourse(c.semester_course_id)}
                    aria-expanded={isExpanded}
                  >
                    <div className="card-top-row">
                      <div>
                        <span className="course-code-tag">{c.course_code}</span>
                        <h4>{c.course_title}</h4>
                      </div>
                      <div className="percentage-badge">
                        <span className={`pct-number ${isCourseHealthy ? "good" : "low"}`}>
                          {c.percentage}%
                        </span>
                      </div>
                    </div>

                    {/* Progress bar */}
                    <div className="progress-bar-track">
                      <div
                        className={`progress-bar-fill ${isCourseHealthy ? "good" : "low"}`}
                        style={{ width: `${Math.min(100, c.percentage)}%` }}
                      />
                    </div>

                    <div className="card-metrics-row">
                      <span>
                        Attended: <strong>{c.classes_attended}</strong> / {c.classes_held} classes
                      </span>
                      <span className="course-expand-hint">
                        {isExpanded ? "Hide log" : "View by date"}
                        {isExpanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                      </span>
                    </div>
                  </div>

                  {/* Expandable Date-by-Date Attendance Log */}
                  {isExpanded && (
                    <div className="course-history-expanded">
                      <div className="history-expanded-header">
                        <span>Attendance by Date ({historyList.length} classes recorded)</span>
                      </div>
                      {historyList.length === 0 ? (
                        <p className="history-empty">No class sessions recorded yet for this course.</p>
                      ) : (
                        <div className="history-sessions-list">
                          {historyList.map((h, idx) => {
                            const isPresent = h.status === "present";
                            const dObj = new Date(h.date);
                            const dateStr = !isNaN(dObj.getTime())
                              ? dObj.toLocaleDateString("en-GB", {
                                  weekday: "short",
                                  day: "numeric",
                                  month: "short",
                                })
                              : h.date;

                            return (
                              <div
                                key={h.session_id || idx}
                                className={`history-session-item ${h.status}`}
                              >
                                <div className="session-item-date">
                                  <Calendar size={14} className="session-icon" />
                                  <strong>{dateStr}</strong>
                                  {h.time && <span className="session-item-time">{h.time}</span>}
                                  {h.room && h.room !== "null" && (
                                    <span className="session-item-room">· Room {h.room}</span>
                                  )}
                                </div>
                                <div className="session-item-status">
                                  <span
                                    className={`status-pill ${
                                      isPresent ? "pill-present" : "pill-absent"
                                    }`}
                                  >
                                    {isPresent ? (
                                      <CheckCircle2 size={13} />
                                    ) : (
                                      <AlertTriangle size={13} />
                                    )}
                                    {isPresent ? "Present" : "Absent"}
                                  </span>
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

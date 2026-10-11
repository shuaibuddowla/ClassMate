"use client";

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
} from "lucide-react";

interface StudentCourseAttendance {
  semester_course_id: string;
  course_code: string;
  course_title: string;
  course_type: string;
  classes_held: number;
  classes_attended: number;
  percentage: number;
  recent_history: Array<{
    session_id: string;
    date: string;
    time: string;
    status: "present" | "absent";
  }>;
}

interface StudentAttendanceResponse {
  overall_percentage: number;
  total_classes_held: number;
  total_classes_attended: number;
  courses: StudentCourseAttendance[];
}

export default function StudentAttendanceDashboard({ ctx }: { ctx: Context }) {
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

              return (
                <div key={c.semester_course_id} className="course-attendance-card card">
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
                    <span className="course-type-label">{c.course_type?.toUpperCase()}</span>
                  </div>

                  {/* Recent session history chips */}
                  {c.recent_history.length > 0 && (
                    <div className="recent-history-row">
                      <small>Recent sessions:</small>
                      <div className="recent-chips">
                        {c.recent_history.slice(0, 5).map((h) => (
                          <span
                            key={h.session_id}
                            className={`history-chip ${h.status}`}
                            title={`${h.date}: ${h.status}`}
                          >
                            {h.date.slice(5)}: {h.status === "present" ? "✓" : "✗"}
                          </span>
                        ))}
                      </div>
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

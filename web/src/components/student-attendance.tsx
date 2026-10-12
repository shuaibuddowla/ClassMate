"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { rpc } from "@/lib/api";
import { type Context } from "./app";
import { ErrorBox, Skeleton } from "./ui";

interface SessionHistoryItem {
  session_id: string;
  date: string;
  time?: string;
  room?: string;
  status: "present" | "absent" | "late";
  marked_at?: string;
}

interface StudentCourseAttendance {
  semester_course_id: string;
  course_code: string;
  course_title: string;
  course_type: string;
  teacher_name?: string;
  classes_held: number;
  classes_attended: number;
  percentage: number;
  history?: SessionHistoryItem[];
}

interface StudentAttendanceResponse {
  overall_percentage: number;
  total_classes_held: number;
  total_classes_attended: number;
  courses: StudentCourseAttendance[];
}

function healthColor(pct: number): string {
  if (pct >= 75) return "var(--green, #22c55e)";
  if (pct >= 60) return "var(--amber, #f59e0b)";
  return "var(--red, #ef4444)";
}

export default function StudentAttendanceDashboard({ ctx }: { ctx: Context }) {
  const [selectedCourseId, setSelectedCourseId] = useState<string | null>(null);
  const [showAllHistory, setShowAllHistory] = useState<Record<string, boolean>>({});

  const query = useQuery({
    queryKey: [ctx.user, ctx.batch, "student-my-attendance"],
    queryFn: () =>
      rpc<StudentAttendanceResponse>("student_my_attendance", {
        target_batch: ctx.batch,
      }),
  });

  if (query.isError) {
    return <ErrorBox error={query.error} retry={() => query.refetch()} />;
  }

  if (query.isLoading) {
    return (
      <div className="attendance-loading">
        <Skeleton />
      </div>
    );
  }

  const data = query.data;
  const courses = data?.courses || [];
  const overallPercentage = data?.overall_percentage ?? 0;
  const totalHeld = data?.total_classes_held ?? 0;
  const totalAttended = data?.total_classes_attended ?? 0;

  const activeCourse =
    courses.length === 1
      ? courses[0]
      : courses.find((c) => c.semester_course_id === selectedCourseId);

  const displayPct = activeCourse ? activeCourse.percentage : overallPercentage;
  const displayColor = healthColor(displayPct);
  const healthBadge =
    displayPct >= 75
      ? { label: "Eligible", className: "badge-eligible" }
      : displayPct >= 60
      ? { label: "Warning", className: "badge-warning" }
      : { label: "At Risk", className: "badge-risk" };

  const summaryText = activeCourse
    ? activeCourse.classes_held === 0
      ? `No classes held yet in ${activeCourse.course_code}`
      : `${activeCourse.classes_attended} of ${activeCourse.classes_held} classes attended in ${activeCourse.course_code}`
    : courses.length > 1
    ? totalHeld === 0
      ? `No classes held yet across ${courses.length} tracked courses`
      : `${totalAttended} of ${totalHeld} classes attended across ${courses.length} tracked courses`
    : `${totalAttended} of ${totalHeld} classes attended`;

  return (
    <div className="attendance-student-section">
      {/* Top Overview Hero Card */}
      <div className="card attendance-hero-card">
        <div className="attendance-hero-top">
          <div className="attendance-hero-metric">
            <span className="attendance-hero-pct" style={{ color: displayColor }}>
              {displayPct}%
            </span>
            <span className={`attendance-hero-badge ${healthBadge.className}`}>
              {healthBadge.label}
            </span>
          </div>
          <span className="attendance-hero-sub">{summaryText}</span>
        </div>
        <div className="attendance-hero-track">
          <div
            className="attendance-hero-fill"
            style={{
              width: `${Math.min(100, Math.max(0, displayPct))}%`,
              background: displayColor,
            }}
          />
        </div>
      </div>

      <div className="attendance-section-heading">
        <span>TRACKED COURSES</span>
      </div>

      {/* List of course cards */}
      {courses.length === 0 ? (
        <div className="card attendance-empty-msg">
          <p>No courses are currently tracking attendance in this batch.</p>
        </div>
      ) : (
        <div className="attendance-courses-list">
          {courses.map((c) => {
            const isSelected = selectedCourseId === c.semester_course_id;
            const pct = c.percentage ?? 0;
            const barColor = healthColor(pct);
            const courseBadgeClass =
              pct >= 75
                ? "badge-eligible"
                : pct >= 60
                ? "badge-warning"
                : "badge-risk";
            const history = c.history || [];

            return (
              <div
                key={c.semester_course_id}
                className={`attendance-course-card ${isSelected ? "selected" : ""}`}
                style={{ "--accent-color": barColor } as React.CSSProperties}
              >
                <div
                  className="attendance-course-header"
                  role="button"
                  tabIndex={0}
                  onClick={() =>
                    setSelectedCourseId(isSelected ? null : c.semester_course_id)
                  }
                >
                  <div className="attendance-course-info">
                    <div className="attendance-title-row">
                      <h3 className="attendance-course-title">
                        {c.course_code} — {c.course_title}
                      </h3>
                      <span className={`attendance-pct-pill ${courseBadgeClass}`}>
                        {pct}%
                      </span>
                    </div>

                    {c.teacher_name && c.teacher_name !== "null" && (
                      <p className="attendance-course-teacher">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ display: "inline-block", verticalAlign: "middle", marginRight: "4px" }}>
                          <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" />
                          <circle cx="12" cy="7" r="4" />
                        </svg>
                        {c.teacher_name}
                      </p>
                    )}

                    <div className="attendance-bar-row">
                      <div className="attendance-track">
                        <div
                          className="attendance-fill"
                          style={{
                            width: `${Math.min(100, Math.max(0, pct))}%`,
                            background: barColor,
                          }}
                        />
                      </div>
                      <span className="attendance-classes-count">
                        {c.classes_attended} / {c.classes_held} classes
                      </span>
                      <span className={`attendance-chevron ${isSelected ? "rotated" : ""}`}>
                        ❯
                      </span>
                    </div>
                  </div>
                </div>

                {/* Plain chronological list on card tap */}
                {isSelected && (
                  <div className="attendance-history-list">
                    {history.length === 0 ? (
                      <p className="attendance-history-empty">No class sessions recorded yet.</p>
                    ) : (
                      <>
                        {(showAllHistory[c.semester_course_id]
                          ? history
                          : history.slice(0, 4)
                        ).map((h, idx) => {
                          const dObj = new Date(h.date);
                          const dateStr = !isNaN(dObj.getTime())
                            ? dObj.toLocaleDateString("en-US", {
                                weekday: "short",
                                month: "short",
                                day: "numeric",
                              })
                            : h.date;
                          const timeStr = h.time ? ` · ${h.time}` : "";
                          const isPresent = h.status === "present";
                          const isLate = h.status === "late";
                          const statusClass = isPresent
                            ? "status-pill-present"
                            : isLate
                            ? "status-pill-late"
                            : "status-pill-absent";

                          return (
                            <div
                              key={h.session_id || idx}
                              className="attendance-history-row"
                            >
                              <span className="attendance-history-date">
                                {dateStr}
                                {timeStr}
                              </span>
                              <span className={`attendance-history-status ${statusClass}`}>
                                {isPresent ? "✓ Present" : isLate ? "⏰ Late" : "✗ Absent"}
                              </span>
                            </div>
                          );
                        })}
                        {history.length > 4 && (
                          <button
                            type="button"
                            className="attendance-view-all-btn"
                            onClick={() =>
                              setShowAllHistory((prev) => ({
                                ...prev,
                                [c.semester_course_id]: !prev[c.semester_course_id],
                              }))
                            }
                          >
                            {showAllHistory[c.semester_course_id]
                              ? "Show less"
                              : `View all (${history.length} sessions)  ↓`}
                          </button>
                        )}
                      </>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

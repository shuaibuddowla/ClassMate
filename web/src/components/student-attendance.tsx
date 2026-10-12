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
  status: "present" | "absent";
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
  const overallColor = healthColor(overallPercentage);

  return (
    <div className="attendance-student-section">
      {/* Top strip: one line, big bold number */}
      <div className="attendance-summary-strip">
        <span className="attendance-big-pct" style={{ color: overallColor }}>
          {overallPercentage}%
        </span>
        <span className="attendance-summary-sep">·</span>
        <span className="attendance-summary-text">
          {totalAttended} of {totalHeld} classes attended
        </span>
      </div>

      {/* Plain list of course cards */}
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
            const history = c.history || [];

            return (
              <div
                key={c.semester_course_id}
                className="attendance-course-card"
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
                    <h3 className="attendance-course-title">
                      {c.course_code} — {c.course_title}
                    </h3>
                    {c.teacher_name && c.teacher_name !== "null" && (
                      <p className="attendance-course-teacher">{c.teacher_name}</p>
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
                      <span className="attendance-pct-label" style={{ color: barColor }}>
                        {pct}%
                      </span>
                    </div>
                    <span className="attendance-classes-count">
                      {c.classes_attended} / {c.classes_held} classes
                    </span>
                  </div>
                </div>

                {/* Plain chronological list on card tap */}
                {isSelected && (
                  <div className="attendance-history-list">
                    {history.length === 0 ? (
                      <p className="attendance-history-empty">No class sessions recorded yet.</p>
                    ) : (
                      history.map((h, idx) => {
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

                        return (
                          <div
                            key={h.session_id || idx}
                            className="attendance-history-row"
                          >
                            <span className="attendance-history-date">
                              {dateStr}
                              {timeStr}
                            </span>
                            <span
                              className="attendance-history-status"
                              style={{
                                color: isPresent
                                  ? "var(--green, #22c55e)"
                                  : "var(--red, #ef4444)",
                              }}
                            >
                              {isPresent ? "✓ Present" : "✗ Absent"}
                            </span>
                          </div>
                        );
                      })
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

"use client";

import { useState, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { rpc } from "@/lib/api";
import { type Context } from "./app";
import { ErrorBox, Skeleton } from "./ui";
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
  Search,
  ScanLine,
  ShieldCheck,
  GraduationCap,
  Sparkles,
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
  credit?: number;
  teacher_name?: string;
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

interface Props {
  ctx: Context;
  onOpenScanner?: () => void;
}

export default function StudentAttendanceDashboard({ ctx, onOpenScanner }: Props) {
  const [expandedCourseIds, setExpandedCourseIds] = useState<Record<string, boolean>>({});
  const [filterMode, setFilterMode] = useState<"all" | "ontrack" | "attention">("all");
  const [searchQuery, setSearchQuery] = useState<string>("");

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
  const rawCourses = data?.courses || [];

  // Defensive normalization: only courses with attendance enabled
  const courses = useMemo(() => {
    if (!Array.isArray(rawCourses)) return [];
    return rawCourses.map((c) => ({
      ...c,
      course_type: (c.course_type || "theory").toLowerCase(),
      classes_held: c.classes_held ?? 0,
      classes_attended: c.classes_attended ?? 0,
      percentage: typeof c.percentage === "number" ? c.percentage : 0,
      history: Array.isArray(c.history) ? c.history : [],
    }));
  }, [rawCourses]);

  const overallPercentage = data?.overall_percentage ?? 0;
  const totalHeld = data?.total_classes_held ?? 0;
  const totalAttended = data?.total_classes_attended ?? 0;
  const totalMissed = Math.max(0, totalHeld - totalAttended);

  // Status classification
  const isHealthy = overallPercentage >= 75;
  const isCritical = totalHeld > 0 && overallPercentage < 60;

  // Filtered courses
  const filteredCourses = useMemo(() => {
    return courses.filter((c) => {
      // 1. Eligibility filter
      if (filterMode === "ontrack" && c.percentage < 75) return false;
      if (filterMode === "attention" && (c.percentage >= 75 || c.classes_held === 0)) return false;

      // 2. Search query filter
      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase();
        const codeMatch = c.course_code.toLowerCase().includes(q);
        const titleMatch = c.course_title.toLowerCase().includes(q);
        return codeMatch || titleMatch;
      }
      return true;
    });
  }, [courses, filterMode, searchQuery]);

  const counts = useMemo(() => {
    const total = courses.length;
    const onTrack = courses.filter((c) => c.percentage >= 75).length;
    const attention = courses.filter((c) => c.classes_held > 0 && c.percentage < 75).length;
    return { total, onTrack, attention };
  }, [courses]);

  return (
    <div className="student-attendance-view">
      {/* Defensive Error Box */}
      {query.isError && (
        <ErrorBox error={query.error} retry={() => query.refetch()} />
      )}

      {/* Overview Stat Banner */}
      <div
        className={`student-attendance-hero card ${
          isCritical ? "critical" : isHealthy ? "healthy" : "warning"
        }`}
      >
        <div className="hero-progress-ring-box">
          <div className="stat-circle">
            <span className="stat-big-number">{overallPercentage}%</span>
            <small>OVERALL</small>
          </div>
        </div>

        <div className="hero-text-details">
          <div className="hero-badge-row">
            {isCritical ? (
              <span className="badge-pill badge-red">
                <AlertTriangle size={13} /> Discollegiate (&lt; 60%)
              </span>
            ) : isHealthy ? (
              <span className="badge-pill badge-green">
                <ShieldCheck size={13} /> Collegiate Standing (≥ 75%)
              </span>
            ) : (
              <span className="badge-pill badge-amber">
                <AlertTriangle size={13} /> Non-Collegiate Warning (60% – 74%)
              </span>
            )}
            <span className="badge-pill badge-muted">
              {courses.length} {courses.length === 1 ? "Course Tracked" : "Courses Tracked"}
            </span>
          </div>

          <h2>My Academic Attendance</h2>
          <p>
            You have attended <strong>{totalAttended}</strong> out of{" "}
            <strong>{totalHeld}</strong> total classes recorded so far across your active courses.
          </p>

          <div className="student-hero-stats-row">
            <div className="student-hero-stat">
              <span className="stat-sub-label">Attended</span>
              <strong>{totalAttended} classes</strong>
            </div>
            <div className="student-hero-stat">
              <span className="stat-sub-label">Missed</span>
              <strong className={totalMissed > 0 ? "text-amber" : "text-success"}>
                {totalMissed} classes
              </strong>
            </div>
            <div className="student-hero-stat">
              <span className="stat-sub-label">Total Held</span>
              <strong>{totalHeld} classes</strong>
            </div>
          </div>
        </div>

        <div className="hero-actions-column">
          {onOpenScanner && (
            <button
              className="primary scan-attendance-btn"
              onClick={onOpenScanner}
              title="Scan Live Classroom QR Code"
            >
              <ScanLine size={17} />
              <span>Scan QR Code</span>
            </button>
          )}

          <button
            className="secondary icon-refresh-btn"
            onClick={() => query.refetch()}
            disabled={query.isFetching}
            title="Refresh attendance records"
          >
            <RefreshCw size={15} className={query.isFetching ? "spin" : ""} />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {/* Courses Breakdown Section */}
      <div className="student-courses-section">
        {/* Controls Toolbar: Filter Chips + Search Input */}
        <div className="student-toolbar-row">
          <div className="student-chips-group">
            <button
              type="button"
              className={`student-filter-chip ${filterMode === "all" ? "active" : ""}`}
              onClick={() => setFilterMode("all")}
            >
              All Courses ({counts.total})
            </button>
            <button
              type="button"
              className={`student-filter-chip chip-good ${
                filterMode === "ontrack" ? "active" : ""
              }`}
              onClick={() => setFilterMode("ontrack")}
            >
              <ShieldCheck size={14} />
              On Track ≥ 75% ({counts.onTrack})
            </button>
            <button
              type="button"
              className={`student-filter-chip chip-warn ${
                filterMode === "attention" ? "active" : ""
              }`}
              onClick={() => setFilterMode("attention")}
            >
              <AlertTriangle size={14} />
              Need Attention ({counts.attention})
            </button>
          </div>

          <div className="student-search-box">
            <Search size={14} className="search-icon" />
            <input
              type="text"
              placeholder="Search course code or title..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="student-search-input"
            />
          </div>
        </div>

        {query.isLoading ? (
          <div className="attendance-loading-state">
            <RefreshCw className="spin" size={24} />
            <span>Calculating your attendance metrics...</span>
          </div>
        ) : courses.length === 0 ? (
          <div className="card student-empty-card">
            <Calendar size={40} className="text-muted" />
            <h3>No Courses in Attendance Tracking</h3>
            <p>
              Your department administrator hasn&apos;t enabled app-based attendance for any
              courses in this batch yet.
            </p>
            <small className="text-muted">
              Once attendance is enabled in the Course Settings, your live metrics will appear here.
            </small>
          </div>
        ) : filteredCourses.length === 0 ? (
          <div className="card student-empty-card">
            <Search size={34} className="text-muted" />
            <p>No courses match your search or filter.</p>
            <button className="secondary" onClick={() => { setSearchQuery(""); setFilterMode("all"); }}>
              Reset Filters
            </button>
          </div>
        ) : (
          <div className="student-course-cards-grid">
            {filteredCourses.map((c) => {
              const isCourseHealthy = c.percentage >= 75;
              const isCourseCritical = c.classes_held > 0 && c.percentage < 60;
              const isExpanded = Boolean(expandedCourseIds[c.semester_course_id]);
              const historyList = c.history || [];

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
                      <div className="course-title-group">
                        <div className="course-code-badges">
                          <span className="course-code-tag">{c.course_code}</span>
                          <span className={`course-type-pill type-${c.course_type}`}>
                            {c.course_type.toUpperCase()}
                          </span>
                        </div>
                        <h4>{c.course_title}</h4>
                        {c.teacher_name && (
                          <small className="course-instructor-text">
                            Instructor: {c.teacher_name}
                          </small>
                        )}
                      </div>

                      <div className="percentage-badge">
                        <span
                          className={`pct-number ${
                            isCourseCritical ? "critical" : isCourseHealthy ? "good" : "low"
                          }`}
                        >
                          {c.percentage}%
                        </span>
                      </div>
                    </div>

                    {/* Visual Progress Bar */}
                    <div className="progress-bar-track">
                      <div
                        className={`progress-bar-fill ${
                          isCourseCritical ? "critical" : isCourseHealthy ? "good" : "low"
                        }`}
                        style={{ width: `${Math.min(100, c.percentage)}%` }}
                      />
                    </div>

                    <div className="card-metrics-row">
                      <span className="attended-ratio">
                        Attended: <strong>{c.classes_attended}</strong> / {c.classes_held} classes
                      </span>
                      <span className="course-expand-hint">
                        {isExpanded ? "Hide log" : `View ${historyList.length} classes`}
                        {isExpanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                      </span>
                    </div>
                  </div>

                  {/* Expandable Date-by-Date Attendance Log */}
                  {isExpanded && (
                    <div className="course-history-expanded">
                      <div className="history-expanded-header">
                        <span>Attendance History ({historyList.length} classes held)</span>
                      </div>
                      {historyList.length === 0 ? (
                        <p className="history-empty">No class sessions recorded yet for this course.</p>
                      ) : (
                        <div className="history-sessions-list">
                          {historyList.map((h, idx) => {
                            const isPresent = h.status === "present";
                            const dObj = new Date(h.date);
                            const dateStr = !isNaN(dObj.getTime())
                              ? dObj.toLocaleDateString("en-US", {
                                  weekday: "short",
                                  month: "short",
                                  day: "numeric",
                                  year: "numeric",
                                })
                              : h.date;

                            return (
                              <div
                                key={h.session_id || idx}
                                className={`history-session-item ${h.status}`}
                              >
                                <div className="session-item-date">
                                  <span className="session-item-index">
                                    #{historyList.length - idx}
                                  </span>
                                  <div>
                                    <strong>{dateStr}</strong>
                                    <small>
                                      {h.time || "Class period"}
                                      {h.room && h.room !== "null" && h.room !== "TBA"
                                        ? ` · Room ${h.room}`
                                        : ""}
                                    </small>
                                  </div>
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

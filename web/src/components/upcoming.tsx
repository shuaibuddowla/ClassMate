"use client";

import { useState, useEffect, useMemo, useId } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  CalendarCheck,
  CalendarDays,
  Clock,
  MapPin,
  Plus,
  BookOpen,
  Edit2,
  Trash2,
  Menu,
  ArrowLeft,
  ChevronRight,
  Sparkles,
  History,
  ExternalLink,
  CheckCircle2,
  AlertCircle,
  FileText,
} from "lucide-react";
import { supabase, rpc, rows, type Row } from "@/lib/api";
import type { Context } from "./app";
import { Modal, Empty, Skeleton, ErrorBox, Confirm } from "./ui";

export interface Assessment {
  id: string;
  batch_id: string;
  course_id: string | null;
  course_code: string;
  course_title: string;
  type: string;
  title: string;
  deadline_date: string; // YYYY-MM-DD
  deadline_time: string; // e.g. "10:00 AM" or "10:00"
  room: string;
  syllabus: string;
  notice_id?: string | null;
  posted_by: string;
  created_at: number;
}

export type AssessmentTypeKey =
  | "ct"
  | "assignment"
  | "quiz"
  | "lab_report"
  | "presentation";

export const ASSESSMENT_CONFIGS: Record<
  string,
  { label: string; short: string; emoji: string; color: string; bg: string; border: string }
> = {
  ct: {
    label: "Class Test",
    short: "CT",
    emoji: "📝",
    color: "#8b5cf6",
    bg: "rgba(139, 92, 246, 0.12)",
    border: "rgba(139, 92, 246, 0.3)",
  },
  assignment: {
    label: "Assignment",
    short: "Assignment",
    emoji: "📚",
    color: "#3b82f6",
    bg: "rgba(59, 130, 246, 0.12)",
    border: "rgba(59, 130, 246, 0.3)",
  },
  quiz: {
    label: "Quiz",
    short: "Quiz",
    emoji: "⚡",
    color: "#f59e0b",
    bg: "rgba(245, 158, 11, 0.12)",
    border: "rgba(245, 158, 11, 0.3)",
  },
  lab_report: {
    label: "Lab Report",
    short: "Lab Report",
    emoji: "🔬",
    color: "#10b981",
    bg: "rgba(16, 185, 129, 0.12)",
    border: "rgba(16, 185, 129, 0.3)",
  },
  presentation: {
    label: "Presentation",
    short: "Presentation",
    emoji: "📊",
    color: "#ec4899",
    bg: "rgba(236, 72, 153, 0.12)",
    border: "rgba(236, 72, 153, 0.3)",
  },
};

/**
 * Normalizes different assessment type keys (e.g. "class_test", "CLASS_TEST", "ct", "CT")
 * to standard keys: "ct", "assignment", "quiz", "lab_report", "presentation".
 */
export function normalizeAssessmentType(type?: string): string {
  if (!type) return "ct";
  const norm = type.trim().toLowerCase().replace(/[\s_-]+/g, "");
  if (norm === "ct" || norm === "classtest" || norm === "test") return "ct";
  if (norm === "assignment" || norm === "asgn" || norm === "assgn") return "assignment";
  if (norm === "quiz") return "quiz";
  if (norm === "labreport" || norm === "lab" || norm === "report") return "lab_report";
  if (norm === "presentation" || norm === "pres" || norm === "slide") return "presentation";
  return norm;
}

/**
 * Smart formatting for room:
 * When user enters only digits (e.g. "338"), prefix with "Room ".
 * When user enters custom text (e.g. "Online", "Software Lab 2"), preserve intact.
 */
export function formatRoom(room?: string): string {
  if (!room || !room.trim()) return "";
  const trimmed = room.trim();
  if (/^\d+$/.test(trimmed)) {
    return `Room ${trimmed}`;
  }
  return trimmed;
}

export function parseDeadlineDateTime(dateStr: string, timeStr?: string): Date {
  if (!dateStr) return new Date(0);
  const [y, m, d] = dateStr.split("-").map(Number);
  let hour = 23;
  let minute = 59;

  if (timeStr && timeStr.trim()) {
    const raw = timeStr.trim().toUpperCase();
    const isPm = raw.includes("PM");
    const isAm = raw.includes("AM");
    const clean = raw.replace("AM", "").replace("PM", "").trim();
    const parts = clean.split(":");
    if (parts.length >= 1) {
      let h = parseInt(parts[0], 10);
      if (!isNaN(h)) {
        if (isPm && h < 12) h += 12;
        if (isAm && h === 12) h = 0;
        hour = h;
      }
    }
    if (parts.length >= 2) {
      const min = parseInt(parts[1], 10);
      if (!isNaN(min)) minute = min;
    }
  }

  // Format with Bangladesh Standard Time (+06:00) so countdowns are consistent across all client timezones
  const pad = (n: number) => String(n).padStart(2, "0");
  const isoString = `${y}-${pad(m)}-${pad(d)}T${pad(hour)}:${pad(minute)}:00+06:00`;
  const parsed = new Date(isoString);
  return isNaN(parsed.getTime()) ? new Date(y, m - 1, d, hour, minute, 0) : parsed;
}

export function isPastAssessment(item: Assessment): boolean {
  try {
    const deadline = parseDeadlineDateTime(item.deadline_date, item.deadline_time);
    return deadline.getTime() < Date.now();
  } catch {
    return false;
  }
}

export function formatCountdown(dateStr: string, timeStr?: string): string {
  if (!dateStr) return "";
  try {
    const target = parseDeadlineDateTime(dateStr, timeStr);
    const now = Date.now();
    const diffMs = target.getTime() - now;
    if (diffMs <= 0) return "Completed";

    const diffHours = Math.floor(diffMs / (1000 * 60 * 60));
    const diffDays = Math.floor(diffHours / 24);
    const remainingHours = diffHours % 24;

    if (diffDays === 0) {
      if (diffHours === 0) {
        const diffMins = Math.max(1, Math.floor(diffMs / (1000 * 60)));
        return `In ${diffMins}m`;
      }
      return `In ${diffHours}h`;
    }
    if (diffDays === 1) {
      return `In 1 day ${remainingHours}h`;
    }
    return `In ${diffDays} days ${remainingHours}h`;
  } catch {
    return "";
  }
}

export function formatDisplayDate(dateStr: string): string {
  if (!dateStr) return "";
  try {
    const [y, m, d] = dateStr.split("-").map(Number);
    const date = new Date(y, m - 1, d);
    return date.toLocaleDateString("en-US", {
      weekday: "short",
      month: "short",
      day: "numeric",
      year: "numeric",
    });
  } catch {
    return dateStr;
  }
}

export function Upcoming({ ctx }: { ctx: Context }) {
  const qc = useQueryClient();
  const [filter, setFilter] = useState<string>("all");
  const [showHistory, setShowHistory] = useState<boolean>(false);
  const [detailAssessment, setDetailAssessment] = useState<Assessment | null>(null);
  const [isPosting, setIsPosting] = useState(false);
  const [editingAssessment, setEditingAssessment] = useState<Assessment | null>(null);
  const [deletingAssessment, setDeletingAssessment] = useState<Assessment | null>(null);

  const canManage = Boolean(
    ctx.owner ||
      ctx.profile?.role === "admin" ||
      ctx.profile?.role === "teacher" ||
      ctx.profile?.is_cr
  );

  // Fetch assessments for the active batch
  const {
    data: allAssessments = [],
    isLoading,
    error,
    refetch,
  } = useQuery({
    queryKey: [ctx.user, ctx.batch, "assessments"],
    enabled: !!ctx.batch,
    queryFn: async () => {
      const data = await rows(
        "assessments",
        { batch_id: ctx.batch },
        "*",
        "deadline_date"
      );
      return (data || []) as Assessment[];
    },
  });

  // Supabase real-time updates for batch assessments
  useEffect(() => {
    if (!ctx.batch) return;
    const channel = supabase()
      .channel(`assessments-feed:${ctx.batch}`)
      .on(
        "postgres_changes",
        {
          event: "*",
          schema: "classmate",
          table: "assessments",
          filter: `batch_id=eq.${ctx.batch}`,
        },
        () => {
          qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch, "assessments"] });
        }
      )
      .subscribe();

    return () => {
      channel.unsubscribe();
    };
  }, [ctx.batch, ctx.user, qc]);

  // Handle deep linking from URL query/hash (e.g. /#upcoming?id=xyz)
  useEffect(() => {
    if (typeof window === "undefined" || !allAssessments.length) return;
    const hash = window.location.hash;
    if (hash.includes("id=")) {
      const match = hash.match(/id=([^&]+)/);
      if (match && match[1]) {
        const found = allAssessments.find((a) => a.id === match[1]);
        if (found) setDetailAssessment(found);
      }
    }
  }, [allAssessments]);

  // Handle history sub-route and popstate for system back navigation
  useEffect(() => {
    if (typeof window === "undefined") return;
    const checkHash = () => {
      const hash = window.location.hash || "";
      if (hash.includes("history")) {
        setShowHistory(true);
      } else {
        setShowHistory(false);
      }
    };
    checkHash();
    window.addEventListener("popstate", checkHash);
    return () => window.removeEventListener("popstate", checkHash);
  }, []);

  const openHistory = () => {
    setShowHistory(true);
    history.pushState({ classmateUpcomingHistory: true }, "", "/#upcoming/history");
  };

  const closeHistory = () => {
    if (history.state?.classmateUpcomingHistory) {
      history.back();
    } else {
      setShowHistory(false);
      history.replaceState({}, "", "/#upcoming");
    }
  };

  // Split into upcoming and past
  const upcomingItems = useMemo(() => {
    return allAssessments.filter((a) => !isPastAssessment(a));
  }, [allAssessments]);

  const pastItems = useMemo(() => {
    return allAssessments.filter((a) => isPastAssessment(a)).reverse();
  }, [allAssessments]);

  // Filter items based on active chip and showHistory
  const filteredItems = useMemo(() => {
    const source = showHistory ? pastItems : upcomingItems;
    if (filter === "all") return source;
    return source.filter((a) => normalizeAssessmentType(a.type) === filter);
  }, [showHistory, filter, upcomingItems, pastItems]);

  // The hero assessment is the nearest upcoming item (shown when not viewing history)
  const heroAssessment = useMemo(() => {
    if (showHistory) return null;
    return filteredItems.length > 0 ? filteredItems[0] : null;
  }, [showHistory, filteredItems]);

  // The timeline list of items (excluding hero if hero is shown)
  const timelineItems = useMemo(() => {
    if (showHistory) return filteredItems;
    if (heroAssessment) {
      return filteredItems.slice(1);
    }
    return filteredItems;
  }, [showHistory, filteredItems, heroAssessment]);

  // Open course folder in Courses tab
  const handleOpenCourseFiles = (courseId?: string | null) => {
    if (!courseId) return;
    window.location.hash = `#courses/course/${courseId}`;
  };

  // Delete Assessment
  const handleDeleteAssessment = async (assessment: Assessment) => {
    try {
      // Optimistic update
      qc.setQueryData<Assessment[]>(
        [ctx.user, ctx.batch, "assessments"],
        (old = []) => old.filter((a) => a.id !== assessment.id)
      );
      setDetailAssessment(null);
      setDeletingAssessment(null);

      const { error } = await supabase()
        .schema("classmate")
        .from("assessments")
        .delete()
        .eq("id", assessment.id);

      if (error) throw error;

      // Clean up companion notice if attached
      if (assessment.notice_id) {
        try {
          await supabase()
            .schema("classmate")
            .from("notices")
            .delete()
            .eq("id", assessment.notice_id);
          qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch, "notices"] });
        } catch {
          // Non-critical if user lacks notice delete permission
        }
      }

      qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch, "assessments"] });
    } catch (err) {
      console.error("Failed to delete assessment:", err);
      qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch, "assessments"] });
    }
  };

  return (
    <div className="upcoming-container">
      {/* Header */}
      <header className="upcoming-header">
        <div className="upcoming-header-top">
          <div className="upcoming-title-group">
            {showHistory ? (
              <button
                type="button"
                className="icon back-btn"
                aria-label="Back to Upcoming"
                onClick={closeHistory}
              >
                <ArrowLeft size={22} strokeWidth={2.2} />
              </button>
            ) : (
              ctx.openDrawer && (
                <button
                  type="button"
                  className="icon hamburger-btn"
                  aria-label="Open navigation menu"
                  onClick={ctx.openDrawer}
                >
                  <Menu size={22} strokeWidth={2.2} />
                </button>
              )
            )}
            <div className="upcoming-title-text">
              <h1>{showHistory ? "Assessment History" : "Upcoming"}</h1>
              <p>
                {showHistory
                  ? "Past exams, completed assignments & archives"
                  : "Class tests, assignments & lab deadlines"}
              </p>
            </div>
          </div>

          <div className="upcoming-header-actions">
            {!showHistory ? (
              <button
                type="button"
                className="upcoming-history-btn"
                onClick={openHistory}
                aria-label="View assessment history"
              >
                <History size={16} strokeWidth={2.2} />
                <span>History</span>
              </button>
            ) : (
              <span className="upcoming-records-count-pill">
                {pastItems.length} Records
              </span>
            )}

            {canManage && !showHistory && (
              <button
                type="button"
                className="upcoming-post-btn"
                onClick={() => {
                  setEditingAssessment(null);
                  setIsPosting(true);
                }}
              >
                <Plus size={18} strokeWidth={2.5} />
                <span>Post</span>
              </button>
            )}
          </div>
        </div>
      </header>

      {/* Filter Chips Bar */}
      <div className="upcoming-filters-wrap">
        <div className="upcoming-filters" role="tablist" aria-label="Assessment Filters">
          <button
            type="button"
            role="tab"
            aria-selected={filter === "all"}
            className={`upcoming-filter-chip ${filter === "all" ? "active" : ""}`}
            onClick={() => setFilter("all")}
          >
            <span>✨</span>
            <span>All</span>
          </button>

          {Object.entries(ASSESSMENT_CONFIGS).map(([key, cfg]) => {
            const currentList = showHistory ? pastItems : upcomingItems;
            const count = currentList.filter(
              (a) => normalizeAssessmentType(a.type) === key
            ).length;
            return (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={filter === key}
                className={`upcoming-filter-chip ${filter === key ? "active" : ""}`}
                onClick={() => setFilter(key)}
              >
                <span>{cfg.emoji}</span>
                <span>
                  {cfg.short} {count > 0 ? `(${count})` : ""}
                </span>
              </button>
            );
          })}
        </div>
      </div>

      {isLoading && <Skeleton />}
      <ErrorBox error={error} retry={() => refetch()} />

      {!isLoading && !error && (
        <>
          {/* Hero Assessment Badge (if available in current filter) */}
          {heroAssessment && (
            <div
              className="upcoming-hero-card"
              onClick={() => setDetailAssessment(heroAssessment)}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  setDetailAssessment(heroAssessment);
                }
              }}
            >
              <div className="upcoming-hero-top">
                <div className="upcoming-next-up-badge">
                  <span className="upcoming-pulse-dot" />
                  <span>NEXT UP</span>
                </div>

                {(() => {
                  const cfg =
                    ASSESSMENT_CONFIGS[normalizeAssessmentType(heroAssessment.type)] ||
                    ASSESSMENT_CONFIGS.ct;
                  return (
                    <span
                      className="upcoming-type-pill"
                      style={{
                        color: cfg.color,
                        backgroundColor: cfg.bg,
                        border: `1px solid ${cfg.border}`,
                      }}
                    >
                      {cfg.emoji} {cfg.label}
                    </span>
                  );
                })()}
              </div>

              <div className="upcoming-course-label">
                {heroAssessment.course_code
                  ? `${heroAssessment.course_code} · ${heroAssessment.course_title}`
                  : heroAssessment.course_title}
              </div>

              <h2 className="upcoming-hero-title">{heroAssessment.title}</h2>

              <div className="upcoming-hero-meta">
                <span className="upcoming-countdown-badge">
                  <Clock size={14} />
                  <span>
                    {formatCountdown(
                      heroAssessment.deadline_date,
                      heroAssessment.deadline_time
                    )}
                  </span>
                </span>

                <span className="upcoming-meta-item">
                  <CalendarDays size={14} />
                  <span>{formatDisplayDate(heroAssessment.deadline_date)}</span>
                </span>

                {heroAssessment.deadline_time && (
                  <span className="upcoming-meta-item">
                    <Clock size={14} />
                    <span>{heroAssessment.deadline_time}</span>
                  </span>
                )}

                {heroAssessment.room && (
                  <span className="upcoming-meta-item">
                    <MapPin size={14} />
                    <span>{formatRoom(heroAssessment.room)}</span>
                  </span>
                )}
              </div>
            </div>
          )}

          {/* Timeline List */}
          <div className="upcoming-section-header">
            <h3 className="upcoming-section-title">
              {showHistory
                ? `Past Records (${timelineItems.length})`
                : `Next Upcomings (${timelineItems.length})`}
            </h3>
          </div>

          {timelineItems.length === 0 && !heroAssessment ? (
            <div className="upcoming-empty">
              <div className="upcoming-empty-icon">
                <CalendarCheck size={28} />
              </div>
              <h3>
                {showHistory
                  ? "No past assessments found"
                  : "No upcoming assessments scheduled"}
              </h3>
              <p>
                {showHistory
                  ? "Completed exams and past deadlines will appear here for records."
                  : "Enjoy your free time! When CRs or instructors post an assessment, it will show up here."}
              </p>
              {canManage && !showHistory && (
                <button
                  type="button"
                  className="upcoming-post-btn"
                  style={{ marginTop: "1rem" }}
                  onClick={() => {
                    setEditingAssessment(null);
                    setIsPosting(true);
                  }}
                >
                  <Plus size={16} />
                  <span>Post First Assessment</span>
                </button>
              )}
            </div>
          ) : (
            <div className="upcoming-list">
              {timelineItems.map((item) => {
                const cfg =
                  ASSESSMENT_CONFIGS[normalizeAssessmentType(item.type)] ||
                  ASSESSMENT_CONFIGS.ct;
                const countdown = formatCountdown(item.deadline_date, item.deadline_time);

                return (
                  <div
                    key={item.id}
                    className="upcoming-card"
                    onClick={() => setDetailAssessment(item)}
                    role="button"
                    tabIndex={0}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        setDetailAssessment(item);
                      }
                    }}
                  >
                    <div className="upcoming-card-header">
                      <span className="upcoming-card-course">
                        {item.course_code
                          ? `${item.course_code} · ${item.course_title}`
                          : item.course_title}
                      </span>
                      <span
                        className="upcoming-type-pill"
                        style={{
                          color: cfg.color,
                          backgroundColor: cfg.bg,
                          border: `1px solid ${cfg.border}`,
                        }}
                      >
                        {cfg.emoji} {cfg.label}
                      </span>
                    </div>

                    <h4 className="upcoming-card-title">{item.title}</h4>

                    <div className="upcoming-card-footer">
                      <div className="upcoming-card-details">
                        <span className="upcoming-meta-item">
                          <CalendarDays size={14} />
                          <span>{formatDisplayDate(item.deadline_date)}</span>
                        </span>

                        {item.deadline_time && (
                          <span className="upcoming-meta-item">
                            <Clock size={14} />
                            <span>{item.deadline_time}</span>
                          </span>
                        )}

                        {item.room && (
                          <span className="upcoming-meta-item">
                            <MapPin size={14} />
                            <span>{formatRoom(item.room)}</span>
                          </span>
                        )}
                      </div>

                      {countdown && (
                        <span className="upcoming-countdown-badge">
                          <span>{countdown}</span>
                        </span>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </>
      )}

      {/* Assessment Detail Modal */}
      {detailAssessment && (
        <Modal
          title="Assessment Details"
          close={() => setDetailAssessment(null)}
          className="upcoming-modal"
        >
          <div className="upcoming-detail-wrap">
            <div className="upcoming-detail-header-card">
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "space-between",
                  marginBottom: "0.5rem",
                }}
              >
                {(() => {
                  const cfg =
                    ASSESSMENT_CONFIGS[normalizeAssessmentType(detailAssessment.type)] ||
                    ASSESSMENT_CONFIGS.ct;
                  return (
                    <span
                      className="upcoming-type-pill"
                      style={{
                        color: cfg.color,
                        backgroundColor: cfg.bg,
                        border: `1px solid ${cfg.border}`,
                      }}
                    >
                      {cfg.emoji} {cfg.label}
                    </span>
                  );
                })()}

                <span className="upcoming-countdown-badge">
                  {formatCountdown(
                    detailAssessment.deadline_date,
                    detailAssessment.deadline_time
                  )}
                </span>
              </div>

              <div className="upcoming-course-label">
                {detailAssessment.course_code
                  ? `${detailAssessment.course_code} · ${detailAssessment.course_title}`
                  : detailAssessment.course_title}
              </div>

              <h2
                style={{
                  fontSize: "1.25rem",
                  fontWeight: 800,
                  margin: "0.25rem 0",
                  lineHeight: 1.3,
                }}
              >
                {detailAssessment.title}
              </h2>
            </div>

            {/* Metadata Grid */}
            <div className="upcoming-detail-grid">
              <div className="upcoming-detail-box">
                <span>DEADLINE DATE</span>
                <strong>{formatDisplayDate(detailAssessment.deadline_date)}</strong>
              </div>

              <div className="upcoming-detail-box">
                <span>TIME</span>
                <strong>{detailAssessment.deadline_time || "Not specified"}</strong>
              </div>

              <div className="upcoming-detail-box">
                <span>ROOM / VENUE</span>
                <strong>{formatRoom(detailAssessment.room) || "TBA"}</strong>
              </div>

              <div className="upcoming-detail-box">
                <span>POSTED BY</span>
                <strong>{detailAssessment.posted_by || "CR / Admin"}</strong>
              </div>
            </div>

            {/* Syllabus Topics & Instructions */}
            {detailAssessment.syllabus && detailAssessment.syllabus.trim() && (
              <div className="upcoming-syllabus-card">
                <div className="upcoming-syllabus-title">
                  <FileText size={16} />
                  <span>Syllabus Topics & Instructions</span>
                </div>
                <p className="upcoming-syllabus-content">{detailAssessment.syllabus}</p>
              </div>
            )}

            {/* Action: Open Course Files */}
            {detailAssessment.course_id && (
              <button
                type="button"
                className="secondary"
                style={{
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  gap: "0.5rem",
                  width: "100%",
                  padding: "0.75rem",
                  borderRadius: "0.75rem",
                  fontWeight: 600,
                }}
                onClick={() => {
                  setDetailAssessment(null);
                  handleOpenCourseFiles(detailAssessment.course_id);
                }}
              >
                <BookOpen size={18} />
                <span>Open Course Files & Resources</span>
                <ChevronRight size={16} />
              </button>
            )}

            {/* Admin Management Actions */}
            {canManage && (
              <div className="upcoming-detail-actions">
                <button
                  type="button"
                  className="secondary"
                  style={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", gap: "0.4rem" }}
                  onClick={() => {
                    const item = detailAssessment;
                    setDetailAssessment(null);
                    setEditingAssessment(item);
                    setIsPosting(true);
                  }}
                >
                  <Edit2 size={16} />
                  <span>Edit Assessment</span>
                </button>

                <button
                  type="button"
                  className="secondary danger"
                  style={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", gap: "0.4rem" }}
                  onClick={() => setDeletingAssessment(detailAssessment)}
                >
                  <Trash2 size={16} />
                  <span>Delete</span>
                </button>
              </div>
            )}
          </div>
        </Modal>
      )}

      {/* Delete Confirmation */}
      {deletingAssessment && (
        <Confirm
          title="Delete Assessment?"
          body={`Are you sure you want to delete "${deletingAssessment.title}"? This cannot be undone.`}
          action={() => handleDeleteAssessment(deletingAssessment)}
          close={() => setDeletingAssessment(null)}
        />
      )}

      {/* Post / Edit Assessment Modal */}
      {isPosting && (
        <PostAssessmentModal
          ctx={ctx}
          initial={editingAssessment}
          close={() => {
            setIsPosting(false);
            setEditingAssessment(null);
          }}
          onSuccess={() => {
            setIsPosting(false);
            setEditingAssessment(null);
            qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch, "assessments"] });
          }}
        />
      )}
    </div>
  );
}

/**
 * Post / Edit Assessment Modal Dialog
 * Strict empty states for course, date, time, and room.
 * Autofills from class routine only when both course and date are chosen.
 * Syllabus is completely optional without mentioning the word "optional".
 */
function PostAssessmentModal({
  ctx,
  initial,
  close,
  onSuccess,
}: {
  ctx: Context;
  initial: Assessment | null;
  close: () => void;
  onSuccess: () => void;
}) {
  const qc = useQueryClient();
  const [type, setType] = useState<string>(
    initial?.type ? normalizeAssessmentType(initial.type) : "ct"
  );
  const [courseOfferingId, setCourseOfferingId] = useState<string>(
    initial?.course_id || ""
  );
  const [title, setTitle] = useState<string>(initial?.title || "");
  const [deadlineDate, setDeadlineDate] = useState<string>(
    initial?.deadline_date || ""
  );
  const [deadlineTime, setDeadlineTime] = useState<string>(
    initial?.deadline_time || ""
  );
  const [room, setRoom] = useState<string>(initial?.room || "");
  const [syllabus, setSyllabus] = useState<string>(initial?.syllabus || "");
  const [alsoPostNotice, setAlsoPostNotice] = useState<boolean>(initial == null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [autofillStatus, setAutofillStatus] = useState<{
    success: boolean;
    message: string;
  } | null>(null);

  // Available courses for active batch (sorted by course code)
  const courses = [...(ctx.courses || [])].sort((a, b) =>
    String(a.course_code || "").localeCompare(String(b.course_code || ""), undefined, { numeric: true, sensitivity: "base" })
  );

  // When both course and deadlineDate are selected, query routine slots for autofill
  useEffect(() => {
    if (initial) return; // Don't overwrite existing fields when editing
    if (!courseOfferingId || !deadlineDate) {
      setAutofillStatus(null);
      return;
    }

    let active = true;

    async function checkRoutine() {
      try {
        const [y, m, d] = deadlineDate.split("-").map(Number);
        const dayOfWeek = new Date(y, m - 1, d).getDay(); // 0=Sun, 1=Mon...

        const { data: slots, error } = await supabase()
          .schema("classmate")
          .from("routine_slots")
          .select("start_time, room, day_of_week")
          .eq("semester_course_id", courseOfferingId)
          .eq("day_of_week", dayOfWeek)
          .order("start_time", { ascending: true })
          .limit(1);

        if (!active) return;
        if (error) throw error;

        if (slots && slots.length > 0) {
          const slot = slots[0];
          // Format slot time to 12-hour AM/PM if raw HH:mm:ss
          let formattedTime = slot.start_time || "";
          if (formattedTime.includes(":")) {
            const parts = formattedTime.split(":");
            let h = parseInt(parts[0], 10);
            const min = parts[1] || "00";
            const ampm = h < 12 ? "AM" : "PM";
            if (h === 0) h = 12;
            else if (h > 12) h -= 12;
            formattedTime = `${String(h).padStart(2, "0")}:${min} ${ampm}`;
          }

          if (formattedTime) setDeadlineTime(formattedTime);
          if (slot.room) setRoom(slot.room);

          setAutofillStatus({
            success: true,
            message: `Autofilled routine: ${formattedTime} · ${formatRoom(slot.room)}`,
          });
        } else {
          setAutofillStatus({
            success: false,
            message: "No class scheduled on this day. Please enter time & room manually.",
          });
        }
      } catch (err) {
        if (active) setAutofillStatus(null);
      }
    }

    checkRoutine();

    return () => {
      active = false;
    };
  }, [courseOfferingId, deadlineDate, initial]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!title.trim()) {
      setError("Please enter an assessment title.");
      return;
    }
    if (!courseOfferingId) {
      setError("Please select a course.");
      return;
    }
    if (!deadlineDate) {
      setError("Please select a deadline date.");
      return;
    }

    setError(null);
    setIsSubmitting(true);

    try {
      const selectedCourse = courses.find((c) => c.offering_id === courseOfferingId);
      const assessmentId = initial?.id || crypto.randomUUID();
      const courseCode = selectedCourse?.course_code || "";
      const courseTitle = selectedCourse?.course_title || "";
      const posterName =
        ctx.profile?.full_name?.trim() ||
        (ctx.profile?.role === "teacher" ? "Instructor" : "CR / Admin");

      let linkedNoticeId: string | null = initial?.notice_id || null;

      // Post notice first if requested so its generated notice ID is linked atomically to the assessment
      if (alsoPostNotice && !initial) {
        try {
          const typeLabel =
            ASSESSMENT_CONFIGS[type]?.label || "Assessment";
          const courseDisplay = courseTitle || courseCode;
          const noticeTitle = `${typeLabel} • ${courseDisplay}`;
          const article = /^[aeiou]/i.test(typeLabel) ? "An" : "A";
          const noticeBody = `${article} ${typeLabel} has been announced. Click below to view details.\n\n[assessment_id:${assessmentId}]`;

          const noticeRow = await rpc<Row>("post_notice", {
            title: noticeTitle,
            body: noticeBody,
            semester_course_id: courseOfferingId,
            target_batch_id: ctx.batch,
          });
          if (noticeRow && noticeRow.id) {
            linkedNoticeId = String(noticeRow.id);
          }
          qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch, "notices"] });
        } catch (noticeErr) {
          console.warn("Could not post companion notice:", noticeErr);
        }
      }

      const itemToSave: Assessment = {
        id: assessmentId,
        batch_id: ctx.batch,
        course_id: courseOfferingId,
        course_code: courseCode,
        course_title: courseTitle,
        type: type === "ct" ? "class_test" : type,
        title: title.trim(),
        deadline_date: deadlineDate,
        deadline_time: deadlineTime.trim(),
        room: room.trim(),
        syllabus: syllabus.trim(),
        notice_id: linkedNoticeId,
        posted_by: initial?.posted_by || posterName,
        created_at: initial?.created_at || Date.now(),
      };

      // Optimistic cache update for instant responsiveness
      qc.setQueryData<Assessment[]>(
        [ctx.user, ctx.batch, "assessments"],
        (old = []) => {
          if (initial) {
            return old.map((a) => (a.id === initial.id ? itemToSave : a));
          }
          return [...old, itemToSave].sort((a, b) =>
            a.deadline_date.localeCompare(b.deadline_date)
          );
        }
      );

      // Upsert to classmate.assessments table in Supabase
      const { error: upsertError } = await supabase()
        .schema("classmate")
        .from("assessments")
        .upsert(itemToSave);

      if (upsertError) throw upsertError;

      onSuccess();
    } catch (err: any) {
      setError(err?.message || "Failed to save assessment.");
      qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch, "assessments"] });
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <Modal
      title={initial ? "Edit Assessment" : "Post Assessment"}
      close={close}
      className="upcoming-modal"
    >
      <form onSubmit={handleSubmit} className="upcoming-form">
        {/* 1. Assessment Type Chips */}
        <div className="upcoming-form-group">
          <label>Assessment Type</label>
          <div className="upcoming-type-selector">
            {Object.entries(ASSESSMENT_CONFIGS).map(([key, cfg]) => {
              const isSelected = type === key;
              return (
                <button
                  key={key}
                  type="button"
                  className="upcoming-type-btn"
                  style={{
                    color: isSelected ? cfg.color : undefined,
                    borderColor: isSelected ? cfg.color : undefined,
                    backgroundColor: isSelected ? cfg.bg : undefined,
                  }}
                  onClick={() => setType(key)}
                >
                  <span>{cfg.emoji}</span>
                  <span>{cfg.label}</span>
                </button>
              );
            })}
          </div>
        </div>

        {/* 2. Course Selection Dropdown (Unselected by default) */}
        <div className="upcoming-form-group">
          <label htmlFor="assessment-course">Course</label>
          <select
            id="assessment-course"
            value={courseOfferingId}
            onChange={(e) => setCourseOfferingId(e.target.value)}
            required
            style={{
              padding: "0.45rem 0.75rem",
              borderRadius: "0.6rem",
              border: "1px solid var(--border-color, rgba(0,0,0,0.15))",
              background: "var(--bg-card, #ffffff)",
              color: "var(--text-color, #0f172a)",
              fontWeight: 600,
              fontSize: "0.85rem",
            }}
          >
            <option value="">Select course</option>
            {courses.map((c) => (
              <option key={c.offering_id} value={c.offering_id}>
                {c.course_code} · {c.course_title}
              </option>
            ))}
          </select>
        </div>

        {/* 3. Title */}
        <div className="upcoming-form-group">
          <label htmlFor="assessment-title">Assessment Title / Topic</label>
          <input
            id="assessment-title"
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="e.g. CT-2 on Chapter 3 & 4, Lab Report 03"
            required
            style={{
              padding: "0.45rem 0.75rem",
              borderRadius: "0.6rem",
              border: "1px solid var(--border-color, rgba(0,0,0,0.15))",
              background: "var(--bg-card, #ffffff)",
              color: "var(--text-color, #0f172a)",
              fontSize: "0.85rem",
            }}
          />
        </div>

        {/* 4. Deadline Date Picker (Default empty state "Select date") */}
        <div className="upcoming-form-group">
          <label htmlFor="assessment-date">Deadline Date</label>
          <input
            id="assessment-date"
            type="date"
            value={deadlineDate}
            onChange={(e) => setDeadlineDate(e.target.value)}
            required
            style={{
              padding: "0.45rem 0.75rem",
              borderRadius: "0.6rem",
              border: "1px solid var(--border-color, rgba(0,0,0,0.15))",
              background: "var(--bg-card, #ffffff)",
              color: "var(--text-color, #0f172a)",
              fontWeight: 600,
              fontSize: "0.85rem",
            }}
          />
        </div>

        {/* Routine Autofill Status Hint */}
        {autofillStatus && (
          <div
            className={`upcoming-autofill-hint ${
              autofillStatus.success ? "success" : "info"
            }`}
          >
            {autofillStatus.success ? (
              <CheckCircle2 size={15} />
            ) : (
              <AlertCircle size={15} />
            )}
            <span>{autofillStatus.message}</span>
          </div>
        )}

        {/* 5. Time & Room Inputs (Empty states until selected/filled) */}
        <div className="upcoming-form-row">
          <div className="upcoming-form-group">
            <label htmlFor="assessment-time">Time</label>
            <input
              id="assessment-time"
              type="text"
              value={deadlineTime}
              onChange={(e) => setDeadlineTime(e.target.value)}
              placeholder="e.g. 10:00 AM"
              style={{
                padding: "0.45rem 0.75rem",
                borderRadius: "0.6rem",
                border: "1px solid var(--border-color, rgba(0,0,0,0.15))",
                background: "var(--bg-card, #ffffff)",
                color: "var(--text-color, #0f172a)",
                fontSize: "0.85rem",
              }}
            />
          </div>

          <div className="upcoming-form-group">
            <label htmlFor="assessment-room">Room / Venue</label>
            <input
              id="assessment-room"
              type="text"
              value={room}
              onChange={(e) => setRoom(e.target.value)}
              placeholder="e.g. 338 or Online"
              style={{
                padding: "0.45rem 0.75rem",
                borderRadius: "0.6rem",
                border: "1px solid var(--border-color, rgba(0,0,0,0.15))",
                background: "var(--bg-card, #ffffff)",
                color: "var(--text-color, #0f172a)",
                fontSize: "0.85rem",
              }}
            />
          </div>
        </div>

        {/* 6. Syllabus Topics & Instructions (Optional without mentioning optional) */}
        <div className="upcoming-form-group">
          <label htmlFor="assessment-syllabus">Syllabus Topics & Instructions</label>
          <textarea
            id="assessment-syllabus"
            rows={2}
            value={syllabus}
            onChange={(e) => setSyllabus(e.target.value)}
            placeholder="Enter topics, chapters, materials allowed, submission format..."
            style={{
              padding: "0.45rem 0.75rem",
              borderRadius: "0.6rem",
              border: "1px solid var(--border-color, rgba(0,0,0,0.15))",
              background: "var(--bg-card, #ffffff)",
              color: "var(--text-color, #0f172a)",
              resize: "vertical",
              fontSize: "0.85rem",
              minHeight: "55px",
            }}
          />
        </div>

        {/* 7. Checkbox: Also post to notice feed */}
        {!initial && (
          <label
            style={{
              display: "flex",
              alignItems: "center",
              gap: "0.6rem",
              cursor: "pointer",
              fontSize: "0.875rem",
              fontWeight: 600,
              padding: "0.25rem 0",
            }}
          >
            <input
              type="checkbox"
              checked={alsoPostNotice}
              onChange={(e) => setAlsoPostNotice(e.target.checked)}
              style={{ width: "17px", height: "17px" }}
            />
            <span>Also post to notice feed</span>
          </label>
        )}

        {error && <div className="error">{error}</div>}

        {/* Submit */}
        <div style={{ display: "flex", gap: "0.75rem", marginTop: "0.5rem" }}>
          <button type="button" className="secondary" onClick={close} style={{ flex: 1 }}>
            Cancel
          </button>
          <button
            type="submit"
            className="primary"
            disabled={isSubmitting}
            style={{ flex: 2 }}
          >
            {isSubmitting
              ? "Saving..."
              : initial
              ? "Save Changes"
              : "Post Assessment & Add to Upcoming"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

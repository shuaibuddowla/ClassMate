"use client";

import { useState, useEffect, useRef } from "react";
import { QRCodeSVG } from "qrcode.react";
import { supabase } from "@/lib/api";
import {
  X,
  CheckCircle2,
  Users,
  Download,
  Clock,
  UserCheck,
  UserX,
  RefreshCw,
  AlertCircle
} from "lucide-react";
import { compareStudentRolls } from "@/lib/attendance-excel";

interface TeacherModalProps {
  sessionId?: string;
  slotId: string;
  courseTitle: string;
  courseCode?: string;
  batchId?: string;
  onClose: () => void;
}

interface RosterMember {
  student_id: string;
  full_name: string;
  roll_number: string;
  status: "present" | "absent";
  marked_at?: string;
  method?: string;
  confidence?: string;
}

interface SummaryData {
  session_id: string;
  routine_slot_id: string;
  status: "open" | "closed";
  started_at: string;
  closed_at?: string;
  roster: RosterMember[];
}

// Generate client-side HMAC token for QR rotation
async function generateQrToken(secret: string, timeWindow: number): Promise<string> {
  try {
    const enc = new TextEncoder();
    const key = await window.crypto.subtle.importKey(
      "raw",
      enc.encode(secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"]
    );
    const signature = await window.crypto.subtle.sign(
      "HMAC",
      key,
      enc.encode(timeWindow.toString())
    );
    return Array.from(new Uint8Array(signature))
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
  } catch (e) {
    console.error("HMAC generation failed:", e);
    return "";
  }
}

export function AttendanceTeacherModal({
  sessionId: initialSessionId,
  slotId,
  courseTitle,
  courseCode,
  onClose,
}: TeacherModalProps) {
  const [sessionId, setSessionId] = useState<string | null>(initialSessionId || null);
  const [sessionSecret, setSessionSecret] = useState<string | null>(null);
  const [status, setStatus] = useState<"open" | "closed">("open");
  const [loading, setLoading] = useState<boolean>(!initialSessionId);
  const [error, setError] = useState<string | null>(null);

  // Rotating QR state
  const [qrPayload, setQrPayload] = useState<string>("");
  const [secondsLeft, setSecondsLeft] = useState<number>(15);

  // Live data
  const [summary, setSummary] = useState<SummaryData | null>(null);
  const [showRoster, setShowRoster] = useState<boolean>(false);
  const [filterRoster, setFilterRoster] = useState<"all" | "present" | "absent">("all");
  const [exporting, setExporting] = useState<boolean>(false);
  const [overridePending, setOverridePending] = useState<string | null>(null);

  const client = supabase();

  // 1. Initialize or load session
  useEffect(() => {
    let active = true;

    async function init() {
      setLoading(true);
      setError(null);

      try {
        if (!sessionId) {
          // Start a new session
          const { data, error: startError } = await client
            .schema("classmate")
            .rpc("start_attendance_session", {
              target_slot: slotId,
            });

          if (startError) throw startError;
          if (active && data) {
            setSessionId(data.session_id);
            setSessionSecret(data.session_secret);
            setStatus("open");
          }
        } else {
          // If already created, fetch summary to get secret/status
          const { data, error: sumErr } = await client
            .schema("classmate")
            .rpc("get_session_summary", { target_session: sessionId });

          if (sumErr) throw sumErr;
          if (active && data) {
            setSummary(data);
            setStatus(data.status);
          }

          // Fetch secret if session is open
          const { data: sessRow } = await client
            .schema("classmate")
            .from("attendance_sessions")
            .select("session_secret, status")
            .eq("id", sessionId)
            .single();

          if (active && sessRow) {
            setSessionSecret(sessRow.session_secret);
            setStatus(sessRow.status as "open" | "closed");
          }
        }
      } catch (err: any) {
        if (active) {
          console.error("Attendance session init error:", err);
          const msg =
            err?.message ||
            err?.details ||
            err?.error_description ||
            (typeof err === "string" ? err : "Failed to start attendance session");
          setError(msg);
        }
      } finally {
        if (active) setLoading(false);
      }
    }

    init();
    return () => {
      active = false;
    };
  }, [slotId, sessionId, client]);

  // 2. Fetch and refresh summary
  const fetchSummary = async (sid: string) => {
    try {
      const { data } = await client
        .schema("classmate")
        .rpc("get_session_summary", { target_session: sid });
      if (data) setSummary(data);
    } catch {
      // ignore transient error
    }
  };

  useEffect(() => {
    if (sessionId) {
      fetchSummary(sessionId);
    }
  }, [sessionId]);

  // 3. Supabase Realtime for attendance records
  useEffect(() => {
    if (!sessionId) return;

    const channel = client
      .channel(`attendance:${sessionId}`)
      .on(
        "postgres_changes",
        {
          event: "*",
          schema: "classmate",
          table: "attendance_records",
          filter: `session_id=eq.${sessionId}`,
        },
        () => {
          fetchSummary(sessionId);
        }
      )
      .subscribe();

    return () => {
      client.removeChannel(channel);
    };
  }, [sessionId, client]);

  // 4. QR Rotation loop (every 15s)
  useEffect(() => {
    if (status !== "open" || !sessionId || !sessionSecret) return;

    let timer: NodeJS.Timeout;

    const updateQr = async () => {
      const nowSec = Math.floor(Date.now() / 1000);
      const windowIdx = Math.floor(nowSec / 15);
      const secRemaining = 15 - (nowSec % 15);
      setSecondsLeft(secRemaining);

      const token = await generateQrToken(sessionSecret, windowIdx);
      if (token) {
        setQrPayload(
          JSON.stringify({
            s: sessionId,
            w: windowIdx,
            t: token,
          })
        );
      }
    };

    updateQr();
    timer = setInterval(updateQr, 1000);

    return () => clearInterval(timer);
  }, [status, sessionId, sessionSecret]);

  // 5. Close session action
  const handleCloseSession = async () => {
    if (!sessionId) return;
    try {
      setLoading(true);
      await client
        .schema("classmate")
        .rpc("close_attendance_session", { target_session: sessionId });
      setStatus("closed");
      await fetchSummary(sessionId);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Failed to close session");
    } finally {
      setLoading(false);
    }
  };

  // 6. Manual override student
  const handleToggleOverride = async (studentId: string, currentPresent: boolean) => {
    if (!sessionId) return;
    setOverridePending(studentId);
    try {
      await client
        .schema("classmate")
        .rpc("manual_override_attendance", {
          target_session: sessionId,
          target_student: studentId,
          present: !currentPresent,
        });
      await fetchSummary(sessionId);
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : "Failed to override attendance");
    } finally {
      setOverridePending(null);
    }
  };

  // 7. Export to Excel (.xlsx) client-side
  const handleExport = async () => {
    if (!sessionId) return;
    setExporting(true);
    try {
      const { downloadSessionExcel } = await import("@/lib/attendance-excel");
      await downloadSessionExcel(sessionId, courseCode || "Class");
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : "Export failed");
    } finally {
      setExporting(false);
    }
  };

  const sortedRoster = (summary?.roster ? [...summary.roster] : []).sort(
    (a, b) =>
      compareStudentRolls(a.roll_number, b.roll_number) ||
      a.full_name.localeCompare(b.full_name)
  );

  const presentCount = sortedRoster.filter((r) => r.status === "present").length;
  const totalCount = sortedRoster.length;
  const percentPresent = totalCount > 0 ? Math.round((presentCount / totalCount) * 100) : 0;

  const presentStudentsSorted = sortedRoster.filter((r) => r.status === "present");

  const filteredRoster = sortedRoster.filter((r) => {
    if (filterRoster === "present") return r.status === "present";
    if (filterRoster === "absent") return r.status === "absent";
    return true;
  });

  return (
    <div className="attendance-modal-overlay">
      <div className="attendance-modal-container">
        {/* Header */}
        <div className="attendance-modal-header">
          <div className="attendance-header-meta">
            <span className="attendance-badge-live">
              {status === "open" ? (
                <>
                  <span className="pulse-dot" /> LIVE ATTENDANCE
                </>
              ) : (
                "SESSION CLOSED"
              )}
            </span>
            <h2>{courseTitle}</h2>
            <p>{courseCode ? `${courseCode} · ` : ""}Timetable Live Session</p>
          </div>
          <button className="icon-close" onClick={onClose} aria-label="Close modal">
            <X size={20} />
          </button>
        </div>

        {error && (
          <div className="attendance-error-banner">
            <AlertCircle size={16} />
            <span>{error}</span>
          </div>
        )}

        {/* Content Body */}
        <div className="attendance-modal-body">
          {status === "open" && !showRoster ? (
            <div className="attendance-qr-view">
              <div className="qr-wrapper-card">
                {qrPayload ? (
                  <QRCodeSVG
                    value={qrPayload}
                    size={230}
                    level="M"
                    includeMargin={true}
                    className="qr-svg-canvas"
                  />
                ) : (
                  <div className="qr-loading-placeholder">
                    <RefreshCw className="spin" size={32} />
                    <span>Generating Rotating QR...</span>
                  </div>
                )}

                {/* Progress countdown bar */}
                <div className="qr-rotation-indicator">
                  <div className="qr-countdown-text">
                    <Clock size={13} />
                    <span>Rotates in <strong>{secondsLeft}s</strong></span>
                  </div>
                  <div className="qr-progress-track">
                    <div
                      className="qr-progress-bar"
                      style={{ width: `${(secondsLeft / 15) * 100}%` }}
                    />
                  </div>
                </div>
              </div>

              {/* Live Count Pill */}
              <div className="attendance-live-counter-pill">
                <div className="counter-main">
                  <CheckCircle2 size={20} className="check-icon" />
                  <strong>
                    {presentCount} / {totalCount}
                  </strong>
                  <span>present ({percentPresent}%)</span>
                </div>
                <button
                  className="attendance-secondary-btn"
                  onClick={() => setShowRoster(true)}
                >
                  <Users size={16} />
                  <span>View List</span>
                </button>
              </div>

              {/* Scanned Students Instant Live Feed (Sorted CE25001 -> CE25060) */}
              {presentCount > 0 && (
                <div className="attendance-live-scanned-panel">
                  <div className="live-scanned-header">
                    <span>Scanned Students ({presentCount}) · Sorted by Roll</span>
                  </div>
                  <div className="live-scanned-grid">
                    {presentStudentsSorted.map((student) => (
                      <div key={student.student_id} className="live-scanned-item">
                        <span className="live-roll">{student.roll_number || "—"}</span>
                        <span className="live-name">{student.full_name}</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          ) : (
            /* Summary & Roster View */
            <div className="attendance-summary-view">
              <div className="summary-stats-banner">
                <div className="summary-stat-box">
                  <span className="stat-label">Present</span>
                  <span className="stat-value text-green">{presentCount}</span>
                </div>
                <div className="summary-stat-box">
                  <span className="stat-label">Absent</span>
                  <span className="stat-value text-red">
                    {Math.max(0, totalCount - presentCount)}
                  </span>
                </div>
                <div className="summary-stat-box">
                  <span className="stat-label">Total Roster</span>
                  <span className="stat-value">{totalCount}</span>
                </div>
                <div className="summary-stat-box">
                  <span className="stat-label">Turnout</span>
                  <span className="stat-value">{percentPresent}%</span>
                </div>
              </div>

              {/* Filter Tabs & Rejoin */}
              <div className="roster-toolbar">
                <div className="roster-tabs">
                  <button
                    className={filterRoster === "all" ? "active" : ""}
                    onClick={() => setFilterRoster("all")}
                  >
                    All ({totalCount})
                  </button>
                  <button
                    className={filterRoster === "present" ? "active" : ""}
                    onClick={() => setFilterRoster("present")}
                  >
                    Present ({presentCount})
                  </button>
                  <button
                    className={filterRoster === "absent" ? "active" : ""}
                    onClick={() => setFilterRoster("absent")}
                  >
                    Absent ({Math.max(0, totalCount - presentCount)})
                  </button>
                </div>

                {status === "open" && showRoster && (
                  <button
                    className="rejoin-qr-btn"
                    onClick={() => setShowRoster(false)}
                  >
                    Show QR Code
                  </button>
                )}
              </div>

              {/* Student List */}
              <div className="roster-list-container">
                {filteredRoster.length === 0 ? (
                  <div className="roster-empty">No students in this list</div>
                ) : (
                  filteredRoster.map((student) => {
                    const isPresent = student.status === "present";
                    const isPendingThis = overridePending === student.student_id;

                    return (
                      <div
                        key={student.student_id}
                        className={`roster-row ${isPresent ? "present-row" : "absent-row"}`}
                      >
                        <div className="roster-student-info">
                          <span className="roster-roll">
                            {student.roll_number || "—"}
                          </span>
                          <span className="roster-name">{student.full_name}</span>
                          {student.marked_at && (
                            <span className="roster-time">
                              {new Date(student.marked_at).toLocaleTimeString([], {
                                hour: "2-digit",
                                minute: "2-digit",
                                second: "2-digit",
                              })}
                            </span>
                          )}
                        </div>

                        <div className="roster-actions">
                          <span
                            className={`roster-status-badge ${
                              isPresent ? "badge-present" : "badge-absent"
                            }`}
                          >
                            {isPresent ? "Present" : "Absent"}
                          </span>
                          <button
                            className="override-toggle-btn"
                            disabled={isPendingThis}
                            onClick={() =>
                              handleToggleOverride(student.student_id, isPresent)
                            }
                            title={
                              isPresent
                                ? "Override to absent"
                                : "Override to present"
                            }
                          >
                            {isPresent ? (
                              <UserX size={15} />
                            ) : (
                              <UserCheck size={15} />
                            )}
                          </button>
                        </div>
                      </div>
                    );
                  })
                )}
              </div>
            </div>
          )}
        </div>

        {/* Pinned Bottom Actions Bar - ALWAYS visible, never hidden behind scroll or content */}
        <div className="attendance-modal-footer">
          {status === "open" && !showRoster ? (
            <button
              className="attendance-close-btn"
              onClick={handleCloseSession}
              disabled={loading}
            >
              Close Attendance
            </button>
          ) : (
            <div className="summary-footer-actions">
              <button
                className="export-excel-btn"
                onClick={handleExport}
                disabled={exporting}
              >
                <Download size={16} />
                <span>{exporting ? "Generating Excel..." : "Export to Excel (.xlsx)"}</span>
              </button>
              {status === "open" && (
                <button
                  className="attendance-close-btn-secondary"
                  onClick={handleCloseSession}
                  disabled={loading}
                >
                  Close Session
                </button>
              )}
              <button className="attendance-done-btn" onClick={onClose}>
                Done
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

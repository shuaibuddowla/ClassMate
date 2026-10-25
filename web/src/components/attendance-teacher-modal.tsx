"use client";

import { useState, useEffect, useRef } from "react";
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
  AlertCircle,
  Radio,
  Cpu,
  CreditCard,
  Plus,
  Check,
  Sparkles,
  Bell,
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

interface UnregisteredScan {
  id: string;
  card_uid: string;
  device_id: string;
  scanned_at: string;
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

  // Tab navigation: 'roster' | 'rfid'
  const [activeTab, setActiveTab] = useState<"roster" | "rfid">("roster");

  // Live data
  const [summary, setSummary] = useState<SummaryData | null>(null);
  const [filterRoster, setFilterRoster] = useState<"all" | "present" | "absent">("all");
  const [exporting, setExporting] = useState<boolean>(false);
  const [overridePending, setOverridePending] = useState<string | null>(null);

  // Hardware RFID state
  const [isHardwareLinked, setIsHardwareLinked] = useState<boolean>(false);
  const [hardwareLinking, setHardwareLinking] = useState<boolean>(false);
  const [unregisteredScans, setUnregisteredScans] = useState<UnregisteredScan[]>([]);
  const [simulatorUid, setSimulatorUid] = useState<string>("2101001-DEMO");
  const [simulatorResult, setSimulatorResult] = useState<any | null>(null);
  const [simulating, setSimulating] = useState<boolean>(false);
  const [enrollCardUid, setEnrollCardUid] = useState<string>("");
  const [selectedStudentForEnroll, setSelectedStudentForEnroll] = useState<string>("");
  const [enrolling, setEnrolling] = useState<boolean>(false);
  const [notifying, setNotifying] = useState<boolean>(false);
  const [notifyMessage, setNotifyMessage] = useState<string | null>(null);

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

  // 2. Fetch summary & check hardware link status
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

  const fetchHardwareStatus = async (sid: string) => {
    try {
      const { data } = await client
        .schema("classmate")
        .from("attendance_devices")
        .select("id, active_session_id")
        .eq("id", "ESP32-READER-01")
        .single();
      if (data) {
        setIsHardwareLinked(data.active_session_id === sid);
      }
    } catch {
      // ignore
    }
  };

  const fetchUnregistered = async () => {
    try {
      const { data } = await client
        .schema("classmate")
        .rpc("get_unregistered_rfid_scans");
      if (data) setUnregisteredScans(data);
    } catch {
      // ignore
    }
  };

  useEffect(() => {
    if (sessionId) {
      fetchSummary(sessionId);
      fetchHardwareStatus(sessionId);
      fetchUnregistered();
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
      .on(
        "postgres_changes",
        {
          event: "INSERT",
          schema: "classmate",
          table: "unregistered_rfid_scans",
        },
        () => {
          fetchUnregistered();
        }
      )
      .subscribe();

    return () => {
      client.removeChannel(channel);
    };
  }, [sessionId, client]);

  // 4. Close session
  const handleCloseSession = async () => {
    if (!sessionId) return;
    setLoading(true);
    try {
      // Unlink hardware tracker if linked
      if (isHardwareLinked) {
        await client.schema("classmate").rpc("link_attendance_device", {
          p_device_id: "ESP32-READER-01",
          p_target_session: null,
        });
        setIsHardwareLinked(false);
      }

      const { error: closeErr } = await client
        .schema("classmate")
        .rpc("close_attendance_session", {
          target_session: sessionId,
        });

      if (closeErr) throw closeErr;
      setStatus("closed");
      setActiveTab("roster");
      await fetchSummary(sessionId);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Failed to close session");
    } finally {
      setLoading(false);
    }
  };

  // 6. Toggle Hardware Tracker Linking (Dedicated Station Mode)
  const handleToggleHardwareLink = async () => {
    if (!sessionId) return;
    setHardwareLinking(true);
    try {
      const nextSession = isHardwareLinked ? null : sessionId;
      const { error: linkErr } = await client
        .schema("classmate")
        .rpc("link_attendance_device", {
          p_device_id: "ESP32-READER-01",
          p_target_session: nextSession,
        });
      if (linkErr) throw linkErr;
      setIsHardwareLinked(!isHardwareLinked);
    } catch (err: any) {
      alert(err?.message || "Failed to link hardware tracker");
    } finally {
      setHardwareLinking(false);
    }
  };

  // 7. Virtual Card Simulator (Scope for Testing)
  const handleSimulateScan = async (uidToTest: string) => {
    if (!uidToTest.trim()) return;
    setSimulating(true);
    setSimulatorResult(null);
    try {
      const { data: sessData } = await client.auth.getSession();
      const token = sessData?.session?.access_token;
      const res = await fetch("/api/scan", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({
          card_uid: uidToTest.trim(),
          device_id: "ESP32-READER-01",
        }),
      });
      const data = await res.json();
      setSimulatorResult(data);
      if (sessionId) {
        fetchSummary(sessionId);
        fetchUnregistered();
      }
    } catch (err: any) {
      setSimulatorResult({
        status: "ERROR",
        user_name: "Network Error",
        identifier: "N/A",
        message: err?.message || "Failed to reach /api/scan",
      });
    } finally {
      setSimulating(false);
    }
  };

  // 8. Card Enrollment
  const handleEnrollCard = async () => {
    if (!selectedStudentForEnroll || !enrollCardUid.trim()) {
      alert("Please select a student and provide a card UID.");
      return;
    }
    setEnrolling(true);
    try {
      const { error: enrollErr } = await client
        .schema("classmate")
        .rpc("enroll_student_rfid", {
          p_student_id: selectedStudentForEnroll,
          p_card_uid: enrollCardUid.trim(),
        });
      if (enrollErr) throw enrollErr;
      alert(`Card ${enrollCardUid.trim()} enrolled successfully!`);
      setEnrollCardUid("");
      setSelectedStudentForEnroll("");
      fetchUnregistered();
      if (sessionId) fetchSummary(sessionId);
    } catch (err: any) {
      alert(err?.message || "Failed to enroll card");
    } finally {
      setEnrolling(false);
    }
  };

  // 9. Manual override student
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

  // 10. Export to Excel (.xlsx) client-side
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

  const handleNotifySession = async () => {
    if (!sessionId || notifying) return;
    setNotifying(true);
    setNotifyMessage(null);
    try {
      const { data, error: notifyErr } = await client
        .schema("classmate")
        .rpc("notify_attendance_session", {
          target_session: sessionId,
        });

      if (notifyErr) throw notifyErr;

      const recipientCount = data?.recipients ?? 0;
      setNotifyMessage(
        recipientCount > 0
          ? `Push notification sent to ${recipientCount} student device${recipientCount > 1 ? "s" : ""}!`
          : "Notification dispatched to enrolled batch!"
      );
      setTimeout(() => setNotifyMessage(null), 5000);
    } catch (err: any) {
      console.error("Failed to notify attendance session:", err);
      setError(err.message || "Failed to dispatch notification");
      setTimeout(() => setError(null), 5000);
    } finally {
      setNotifying(false);
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
      <div className="attendance-modal-container" style={{ maxWidth: 560 }}>
        {/* Header */}
        <div className="attendance-modal-header">
          <div className="attendance-header-meta">
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <span className="attendance-badge-live">
                {status === "open" ? (
                  <>
                    <span className="pulse-dot" /> LIVE ATTENDANCE
                  </>
                ) : (
                  "SESSION CLOSED"
                )}
              </span>

              {/* Station Linked Badge */}
              {isHardwareLinked && (
                <span
                  style={{
                    display: "inline-flex",
                    alignItems: "center",
                    gap: 5,
                    fontSize: 10,
                    fontWeight: 700,
                    padding: "3px 8px",
                    borderRadius: 999,
                    background: "rgba(16, 185, 129, 0.15)",
                    color: "#10b981",
                  }}
                >
                  <Radio size={11} className="animate-pulse" /> ESP32 STATION LINKED
                </span>
              )}
            </div>
            <h2>{courseTitle}</h2>
            <p>{courseCode ? `${courseCode} · ` : ""}Dedicated Attendance Station</p>
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

        {notifyMessage && (
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 8,
              padding: "10px 16px",
              background: "rgba(16, 185, 129, 0.12)",
              borderBottom: "1px solid rgba(16, 185, 129, 0.25)",
              color: "#10b981",
              fontSize: 13,
              fontWeight: 600,
            }}
          >
            <CheckCircle2 size={16} />
            <span>{notifyMessage}</span>
          </div>
        )}

        {/* Segmented Tab Navigation */}
        <div
          style={{
            display: "flex",
            borderBottom: "1px solid var(--border)",
            background: "var(--soft)",
            padding: "4px 8px",
            gap: 6,
          }}
        >
          <button
            onClick={() => setActiveTab("roster")}
            style={{
              flex: 1,
              padding: "7px 12px",
              borderRadius: 8,
              border: "none",
              fontSize: 12.5,
              fontWeight: 600,
              cursor: "pointer",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              gap: 6,
              background: activeTab === "roster" ? "var(--surface)" : "transparent",
              color: activeTab === "roster" ? "var(--text)" : "var(--muted)",
              boxShadow: activeTab === "roster" ? "0 1px 3px rgba(0,0,0,0.1)" : "none",
            }}
          >
            <Users size={14} /> Live Roster ({presentCount}/{totalCount})
          </button>
          <button
            onClick={() => setActiveTab("rfid")}
            style={{
              flex: 1,
              padding: "7px 12px",
              borderRadius: 8,
              border: "none",
              fontSize: 12.5,
              fontWeight: 600,
              cursor: "pointer",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              gap: 6,
              background: activeTab === "rfid" ? "var(--surface)" : "transparent",
              color: activeTab === "rfid" ? "var(--text)" : "var(--muted)",
              boxShadow: activeTab === "rfid" ? "0 1px 3px rgba(0,0,0,0.1)" : "none",
            }}
          >
            <Radio
              size={14}
              style={{ color: isHardwareLinked ? "#10b981" : "inherit" }}
            />{" "}
            RFID Station &amp; Test
          </button>
        </div>

        {/* Content Body */}
        <div className="attendance-modal-body">
          {/* TAB 1: ROSTER VIEW */}
          {activeTab === "roster" && (
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

              {/* Filter Tabs */}
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
                              {student.method === "rfid" ? "📡 RFID · " : "✏️ Manual · "}
                              {new Date(student.marked_at).toLocaleTimeString([], {
                                hour: "2-digit",
                                minute: "2-digit",
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

          {/* TAB 3: RFID HARDWARE & TESTING SIMULATOR */}
          {activeTab === "rfid" && (
            <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
              {/* Station Link Status Card */}
              <div
                style={{
                  border: "1px solid var(--border)",
                  borderRadius: 14,
                  padding: 16,
                  background: isHardwareLinked
                    ? "rgba(16, 185, 129, 0.08)"
                    : "var(--soft)",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "space-between",
                }}
              >
                <div>
                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <Cpu
                      size={18}
                      style={{ color: isHardwareLinked ? "#10b981" : "var(--muted)" }}
                    />
                    <strong style={{ fontSize: 14 }}>
                      ESP32 Tracker (ESP32-READER-01)
                    </strong>
                  </div>
                  <p
                    style={{
                      fontSize: 12,
                      color: "var(--muted)",
                      margin: "4px 0 0",
                    }}
                  >
                    {isHardwareLinked
                      ? "Device is actively bound to this course session."
                      : "Device is unlinked. Link it to log taps into this class."}
                  </p>
                </div>

                <button
                  onClick={handleToggleHardwareLink}
                  disabled={hardwareLinking || status !== "open"}
                  style={{
                    padding: "8px 14px",
                    borderRadius: 8,
                    border: "none",
                    fontWeight: 700,
                    fontSize: 12.5,
                    cursor: "pointer",
                    background: isHardwareLinked ? "#ef4444" : "#10b981",
                    color: "#fff",
                  }}
                >
                  {hardwareLinking
                    ? "Updating..."
                    : isHardwareLinked
                    ? "Unlink Station"
                    : "Link Station"}
                </button>
              </div>

              {/* Scope for Testing: Virtual Card Simulator */}
              <div
                style={{
                  border: "1px solid var(--border)",
                  borderRadius: 14,
                  padding: 16,
                  background: "var(--surface)",
                }}
              >
                <div style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 8 }}>
                  <Sparkles size={16} color="#3b82f6" />
                  <strong style={{ fontSize: 13.5 }}>Testing Scope: Virtual Card Simulator</strong>
                </div>
                <p style={{ fontSize: 12, color: "var(--muted)", margin: "0 0 12px" }}>
                  Test hardware responses and attendance logging without needing physical cards.
                </p>

                <div style={{ display: "flex", gap: 8, marginBottom: 10 }}>
                  <input
                    type="text"
                    value={simulatorUid}
                    onChange={(e) => setSimulatorUid(e.target.value)}
                    placeholder="Enter Card UID (e.g. 2101001-DEMO)"
                    style={{
                      flex: 1,
                      padding: "8px 12px",
                      borderRadius: 8,
                      border: "1px solid var(--border)",
                      background: "var(--soft)",
                      color: "var(--text)",
                      fontSize: 13,
                    }}
                  />
                  <button
                    onClick={() => handleSimulateScan(simulatorUid)}
                    disabled={simulating}
                    style={{
                      padding: "8px 14px",
                      borderRadius: 8,
                      border: "none",
                      background: "#3b82f6",
                      color: "#fff",
                      fontWeight: 600,
                      fontSize: 12.5,
                      cursor: "pointer",
                    }}
                  >
                    {simulating ? "Tapping..." : "Simulate Tap"}
                  </button>
                </div>

                {/* Quick Presets */}
                <div style={{ display: "flex", gap: 6, flexWrap: "wrap", marginBottom: 12 }}>
                  <button
                    onClick={() => {
                      setSimulatorUid("2101001-DEMO");
                      handleSimulateScan("2101001-DEMO");
                    }}
                    style={{
                      fontSize: 11,
                      padding: "4px 8px",
                      borderRadius: 6,
                      border: "1px solid var(--border)",
                      background: "var(--soft)",
                      color: "var(--text)",
                      cursor: "pointer",
                    }}
                  >
                    ⚡ Tap Test Student
                  </button>
                  <button
                    onClick={() => {
                      const randUid = "UNREG-" + Math.floor(1000 + Math.random() * 9000);
                      setSimulatorUid(randUid);
                      handleSimulateScan(randUid);
                    }}
                    style={{
                      fontSize: 11,
                      padding: "4px 8px",
                      borderRadius: 6,
                      border: "1px solid var(--border)",
                      background: "var(--soft)",
                      color: "var(--text)",
                      cursor: "pointer",
                    }}
                  >
                    ❓ Tap Unknown Card
                  </button>
                </div>

                {/* Simulated OLED Feedback Banner */}
                {simulatorResult && (
                  <div
                    style={{
                      borderRadius: 10,
                      padding: 12,
                      background:
                        simulatorResult.status === "CHECK_IN"
                          ? "#064e3b"
                          : simulatorResult.status === "UNREGISTERED"
                          ? "#7f1d1d"
                          : "#1e293b",
                      color: "#fff",
                      fontFamily: "monospace",
                      fontSize: 12,
                      display: "flex",
                      flexDirection: "column",
                      gap: 4,
                    }}
                  >
                    <div style={{ display: "flex", justifyContent: "space-between" }}>
                      <span>[ESP32 OLED SIMULATOR]</span>
                      <strong>{simulatorResult.status}</strong>
                    </div>
                    <div>Name: {simulatorResult.user_name || "—"}</div>
                    <div>ID: {simulatorResult.identifier || "—"}</div>
                    {simulatorResult.message && (
                      <div style={{ color: "#facc15" }}>Note: {simulatorResult.message}</div>
                    )}
                  </div>
                )}
              </div>

              {/* Unregistered Cards Triage & Enrollment */}
              <div
                style={{
                  border: "1px solid var(--border)",
                  borderRadius: 14,
                  padding: 16,
                  background: "var(--surface)",
                }}
              >
                <div style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 12 }}>
                  <CreditCard size={16} color="#10b981" />
                  <strong style={{ fontSize: 13.5 }}>Unregistered Cards Queue &amp; Enrollment</strong>
                </div>

                {unregisteredScans.length === 0 ? (
                  <p style={{ fontSize: 12, color: "var(--muted)", margin: 0 }}>
                    No pending unregistered cards. Tap any unknown card to enroll it here.
                  </p>
                ) : (
                  <div style={{ display: "flex", flexDirection: "column", gap: 8, marginBottom: 14 }}>
                    {unregisteredScans.map((scan) => (
                      <div
                        key={scan.id}
                        style={{
                          display: "flex",
                          alignItems: "center",
                          justifyContent: "space-between",
                          padding: "8px 12px",
                          borderRadius: 8,
                          background: "var(--soft)",
                          fontSize: 12,
                        }}
                      >
                        <div>
                          <strong>UID: {scan.card_uid}</strong>
                          <span style={{ color: "var(--muted)", marginLeft: 8 }}>
                            {new Date(scan.scanned_at).toLocaleTimeString([], {
                              hour: "2-digit",
                              minute: "2-digit",
                              second: "2-digit",
                            })}
                          </span>
                        </div>
                        <button
                          onClick={() => setEnrollCardUid(scan.card_uid)}
                          style={{
                            padding: "4px 10px",
                            borderRadius: 6,
                            border: "none",
                            background: "#10b981",
                            color: "#fff",
                            fontSize: 11,
                            fontWeight: 700,
                            cursor: "pointer",
                          }}
                        >
                          Enroll
                        </button>
                      </div>
                    ))}
                  </div>
                )}

                {/* Enrollment Form */}
                <div
                  style={{
                    borderTop: "1px solid var(--border)",
                    paddingTop: 12,
                    display: "flex",
                    flexDirection: "column",
                    gap: 8,
                  }}
                >
                  <span style={{ fontSize: 12, fontWeight: 600 }}>Enroll Card to Student:</span>
                  <div style={{ display: "flex", gap: 8 }}>
                    <input
                      type="text"
                      value={enrollCardUid}
                      onChange={(e) => setEnrollCardUid(e.target.value)}
                      placeholder="Card UID"
                      style={{
                        width: 140,
                        padding: "6px 10px",
                        borderRadius: 6,
                        border: "1px solid var(--border)",
                        background: "var(--soft)",
                        color: "var(--text)",
                        fontSize: 12,
                      }}
                    />
                    <select
                      value={selectedStudentForEnroll}
                      onChange={(e) => setSelectedStudentForEnroll(e.target.value)}
                      style={{
                        flex: 1,
                        padding: "6px 10px",
                        borderRadius: 6,
                        border: "1px solid var(--border)",
                        background: "var(--soft)",
                        color: "var(--text)",
                        fontSize: 12,
                      }}
                    >
                      <option value="">Select student from roster...</option>
                      {sortedRoster.map((s) => (
                        <option key={s.student_id} value={s.student_id}>
                          {s.roll_number} - {s.full_name}
                        </option>
                      ))}
                    </select>
                    <button
                      onClick={handleEnrollCard}
                      disabled={enrolling || !enrollCardUid || !selectedStudentForEnroll}
                      style={{
                        padding: "6px 12px",
                        borderRadius: 6,
                        border: "none",
                        background: "#10b981",
                        color: "#fff",
                        fontWeight: 700,
                        fontSize: 12,
                        cursor: "pointer",
                      }}
                    >
                      {enrolling ? "Saving..." : "Save"}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Pinned Bottom Actions Bar */}
        <div className="attendance-modal-footer">
          {status === "open" ? (
            <div style={{ display: "flex", gap: 10, width: "100%" }}>
              <button
                type="button"
                onClick={handleNotifySession}
                disabled={notifying || loading || !sessionId}
                style={{
                  display: "inline-flex",
                  alignItems: "center",
                  justifyContent: "center",
                  gap: 6,
                  padding: "10px 14px",
                  borderRadius: 8,
                  border: "1px solid rgba(59, 130, 246, 0.4)",
                  background: "rgba(59, 130, 246, 0.1)",
                  color: "#3b82f6",
                  fontSize: 13,
                  fontWeight: 600,
                  cursor: notifying ? "not-allowed" : "pointer",
                  transition: "all 0.15s ease",
                  whiteSpace: "nowrap",
                }}
                title="Send push notification to all enrolled batch students"
              >
                <Bell size={15} className={notifying ? "animate-pulse" : ""} />
                <span>{notifying ? "Notifying..." : "Notify Batch"}</span>
              </button>
              <button
                className="attendance-close-btn"
                style={{ flex: 1 }}
                onClick={handleCloseSession}
                disabled={loading}
              >
                Close Attendance
              </button>
              <button className="attendance-done-btn" onClick={onClose}>
                Done
              </button>
            </div>
          ) : (
            <div className="summary-footer-actions">
              <button
                className="export-excel-btn"
                onClick={handleExport}
                disabled={exporting}
              >
                <Download size={16} />
                <span>
                  {exporting ? "Generating Excel..." : "Export to Excel (.xlsx)"}
                </span>
              </button>
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

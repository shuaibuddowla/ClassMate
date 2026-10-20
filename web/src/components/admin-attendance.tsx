"use client";

import { useState, useEffect, useMemo } from "react";
import { supabase } from "@/lib/api";
import { Context } from "./app";
import {
  Radio,
  Play,
  Square,
  Search,
  CreditCard,
  Users,
  Download,
  Calendar,
  CheckCircle2,
  Clock,
  AlertCircle,
  RefreshCw,
  BookOpen,
  ChevronDown,
  ChevronUp,
  Check,
  X,
  Sparkles,
  ArrowLeft,
  FileSpreadsheet,
} from "lucide-react";
import { downloadCourseMatrixExcel } from "@/lib/attendance-excel";

interface StudentItem {
  student_id: string;
  roll: string;
  full_name: string;
  rfid_card_uid?: string | null;
}

interface CourseItem {
  semester_course_id: string;
  course_code: string;
  course_title: string;
  teacher_name: string;
  total_sessions: number;
  enrolled_students: number;
  average_percentage: number;
  last_session_at?: string;
  sessions?: any[];
}

interface MatrixData {
  semester_course_id: string;
  course_code: string;
  course_title: string;
  sessions: Array<{
    session_id: string;
    started_at: string;
    date: string;
    time?: string;
    status: string;
    room?: string;
  }>;
  students: Array<{
    student_id: string;
    roll: string;
    full_name: string;
    records: Record<string, "present" | "absent">;
    present_count: number;
    total_sessions: number;
    percentage: number;
  }>;
}

export default function AdminAttendance({ ctx }: { ctx: Context }) {
  const client = supabase();
  const batchId = ctx.batch;

  // Navigation Sub-View
  const [activeView, setActiveView] = useState<"courses" | "cards">("courses");

  // Active ad-hoc session state
  const [activeSession, setActiveSession] = useState<{
    session_id: string;
    course_code: string;
    course_title: string;
    started_at: string;
  } | null>(null);
  const [activeSessionLoading, setActiveSessionLoading] = useState(true);
  const [stoppingSession, setStoppingSession] = useState(false);

  // Take Attendance Modal
  const [takeModalOpen, setTakeModalOpen] = useState(false);
  const [startingSession, setStartingSession] = useState(false);
  const [selectedCourseForStart, setSelectedCourseForStart] = useState("");

  // Card Registration State
  const [students, setStudents] = useState<StudentItem[]>([]);
  const [studentSearch, setStudentSearch] = useState("");
  const [selectedStudent, setSelectedStudent] = useState<StudentItem | null>(null);
  const [cardUidInput, setCardUidInput] = useState("");
  const [detectedUid, setDetectedUid] = useState<string | null>(null);
  const [registeringCard, setRegisteringCard] = useState(false);
  const [registerSuccess, setRegisterSuccess] = useState<string | null>(null);

  // Student Roster Card Directory Filter & Search
  const [cardFilter, setCardFilter] = useState<"all" | "registered" | "unregistered">("all");
  const [rosterSearch, setRosterSearch] = useState("");

  // Course Attendance History & Matrix
  const [courses, setCourses] = useState<CourseItem[]>([]);
  const [coursesLoading, setCoursesLoading] = useState(true);
  const [expandedCourseId, setExpandedCourseId] = useState<string | null>(null);
  const [matrixData, setMatrixData] = useState<MatrixData | null>(null);
  const [matrixLoading, setMatrixLoading] = useState(false);
  const [exportingCourseId, setExportingCourseId] = useState<string | null>(null);

  // General error banner
  const [error, setError] = useState<string | null>(null);

  // 1. Check Device & Active Session
  const fetchActiveDeviceState = async () => {
    try {
      const { data, error: err } = await client
        .schema("classmate")
        .rpc("get_device_active_session", {
          target_device: "ESP32-READER-01",
        });
      if (err) throw err;
      if (data && data.status === "ACTIVE") {
        setActiveSession({
          session_id: data.session_id,
          course_code: data.course_code,
          course_title: data.course_title,
          started_at: data.started_at,
        });
      } else {
        setActiveSession(null);
      }
    } catch (e: any) {
      console.error("Error fetching device state:", e);
    } finally {
      setActiveSessionLoading(false);
    }
  };

  // 2. Fetch Students for Card Registration
  const fetchStudents = async () => {
    if (!batchId) return;
    try {
      const { data, error: err } = await client
        .schema("classmate")
        .rpc("admin_batch_students", {
          target_batch: batchId,
        });
      if (err) throw err;
      if (data) setStudents(data);
    } catch (e: any) {
      console.error("Error fetching students:", e);
    }
  };

  // 3. Fetch Course Attendance Overview
  const fetchCourses = async () => {
    if (!batchId) return;
    setCoursesLoading(true);
    try {
      const { data, error: err } = await client
        .schema("classmate")
        .rpc("teacher_course_attendance_overview", {
          target_batch: batchId,
        });
      if (err) throw err;
      if (data) setCourses(data);
    } catch (e: any) {
      console.error("Error fetching courses:", e);
    } finally {
      setCoursesLoading(false);
    }
  };

  // 4. Fetch Course Attendance Matrix
  const fetchMatrix = async (semesterCourseId: string) => {
    setMatrixLoading(true);
    try {
      const { data, error: err } = await client
        .schema("classmate")
        .rpc("course_attendance_matrix", {
          target_semester_course: semesterCourseId,
        });
      if (err) throw err;
      if (data) setMatrixData(data);
    } catch (e: any) {
      console.error("Error fetching attendance matrix:", e);
      setError(e.message || "Failed to load course attendance matrix");
    } finally {
      setMatrixLoading(false);
    }
  };

  useEffect(() => {
    fetchActiveDeviceState();
    fetchStudents();
    fetchCourses();

    // Listen for unregistered card taps in real-time
    const scanChannel = client
      .channel("unregistered_scans_realtime")
      .on(
        "postgres_changes",
        {
          event: "INSERT",
          schema: "classmate",
          table: "unregistered_rfid_scans",
        },
        (payload) => {
          if (payload.new?.card_uid) {
            setDetectedUid(payload.new.card_uid);
            setCardUidInput(payload.new.card_uid);
          }
        }
      )
      .subscribe();

    return () => {
      client.removeChannel(scanChannel);
    };
  }, [batchId]);

  // Handle Start Ad-Hoc Session
  const handleStartSession = async () => {
    if (!selectedCourseForStart || startingSession) return;
    setStartingSession(true);
    setError(null);
    try {
      const { data, error: err } = await client
        .schema("classmate")
        .rpc("start_adhoc_attendance_session", {
          target_semester_course: selectedCourseForStart,
          target_device: "ESP32-READER-01",
        });

      if (err) throw err;

      setActiveSession({
        session_id: data.session_id,
        course_code: data.course_code,
        course_title: data.course_title,
        started_at: new Date().toISOString(),
      });
      setTakeModalOpen(false);
      fetchCourses();
    } catch (e: any) {
      console.error("Failed to start session:", e);
      setError(e.message || "Failed to start attendance session");
    } finally {
      setStartingSession(false);
    }
  };

  // Handle Close Session
  const handleCloseSession = async () => {
    if (!activeSession || stoppingSession) return;
    setStoppingSession(true);
    setError(null);
    try {
      const { error: err } = await client
        .schema("classmate")
        .rpc("close_adhoc_attendance_session", {
          target_session: activeSession.session_id,
        });
      if (err) throw err;
      setActiveSession(null);
      fetchCourses();
      if (expandedCourseId) {
        fetchMatrix(expandedCourseId);
      }
    } catch (e: any) {
      console.error("Failed to close session:", e);
      setError(e.message || "Failed to close attendance session");
    } finally {
      setStoppingSession(false);
    }
  };

  // Handle Card Registration
  const handleRegisterCard = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedStudent || !cardUidInput.trim() || registeringCard) return;

    setRegisteringCard(true);
    setRegisterSuccess(null);
    setError(null);

    try {
      const { error: err } = await client
        .schema("classmate")
        .rpc("enroll_student_rfid", {
          target_student: selectedStudent.student_id,
          target_card_uid: cardUidInput.trim().toUpperCase(),
        });

      if (err) throw err;

      setRegisterSuccess(
        `Card ${cardUidInput.trim().toUpperCase()} linked to ${selectedStudent.full_name} (${selectedStudent.roll})!`
      );
      setCardUidInput("");
      setDetectedUid(null);
      setSelectedStudent(null);
      setStudentSearch("");
      fetchStudents();
      setTimeout(() => setRegisterSuccess(null), 5000);
    } catch (e: any) {
      console.error("Failed to register card:", e);
      setError(e.message || "Failed to link card");
    } finally {
      setRegisteringCard(false);
    }
  };

  // Handle Course Tile Click
  const handleToggleCourse = (courseId: string) => {
    if (expandedCourseId === courseId) {
      setExpandedCourseId(null);
      setMatrixData(null);
    } else {
      setExpandedCourseId(courseId);
      fetchMatrix(courseId);
    }
  };

  // Handle Excel Export
  const handleExport = async (e: React.MouseEvent, courseId: string) => {
    e.stopPropagation();
    setExportingCourseId(courseId);
    try {
      await downloadCourseMatrixExcel(courseId);
    } catch (e: any) {
      alert(e.message || "Export failed");
    } finally {
      setExportingCourseId(null);
    }
  };

  // Filtered Students for Registration Autocomplete
  const filteredStudents = useMemo(() => {
    if (!studentSearch.trim()) return [];
    const q = studentSearch.toLowerCase();
    return students.filter(
      (s) =>
        s.roll.toLowerCase().includes(q) ||
        s.full_name.toLowerCase().includes(q)
    );
  }, [students, studentSearch]);

  // Registered vs Unregistered Stats
  const registeredCount = useMemo(
    () => students.filter((s) => Boolean(s.rfid_card_uid)).length,
    [students]
  );
  const unregisteredCount = useMemo(
    () => students.filter((s) => !s.rfid_card_uid).length,
    [students]
  );

  // Filtered Roster for Directory Table
  const filteredRoster = useMemo(() => {
    return students.filter((s) => {
      if (cardFilter === "registered" && !s.rfid_card_uid) return false;
      if (cardFilter === "unregistered" && s.rfid_card_uid) return false;
      if (rosterSearch.trim()) {
        const q = rosterSearch.toLowerCase();
        return (
          s.roll.toLowerCase().includes(q) ||
          s.full_name.toLowerCase().includes(q) ||
          (s.rfid_card_uid && s.rfid_card_uid.toLowerCase().includes(q))
        );
      }
      return true;
    });
  }, [students, cardFilter, rosterSearch]);

  return (
    <div
      style={{
        width: "100%",
        maxWidth: 1040,
        margin: "0 auto",
        padding: "16px 8px 80px",
        display: "flex",
        flexDirection: "column",
        gap: 16,
        boxSizing: "border-box",
      }}
    >
      {/* 1. Header Banner & Action */}
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "flex-start",
          flexWrap: "wrap",
          gap: 14,
        }}
      >
        <div>
          <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 6 }}>
            <button
              type="button"
              onClick={() => {
                location.hash = "settings";
              }}
              aria-label="Back to Settings"
              title="Back to Settings"
              style={{
                display: "inline-flex",
                alignItems: "center",
                justifyContent: "center",
                width: 28,
                height: 28,
                borderRadius: 7,
                border: "1px solid var(--border)",
                background: "var(--card)",
                color: "var(--text)",
                cursor: "pointer",
                padding: 0,
              }}
            >
              <ArrowLeft size={15} />
            </button>
            <span
              style={{
                fontSize: 11,
                fontWeight: 700,
                letterSpacing: "0.06em",
                textTransform: "uppercase",
                padding: "2px 8px",
                borderRadius: 999,
                background: "rgba(59, 130, 246, 0.15)",
                color: "var(--primary)",
              }}
            >
              ADMIN WORKSPACE
            </span>
            <span style={{ fontSize: 13, color: "var(--muted)" }}>
              {ctx.batchInfo?.departments?.code?.toUpperCase()} Batch {ctx.batchInfo?.batch_number}
            </span>
          </div>
          <h1 style={{ fontSize: 24, fontWeight: 700, margin: 0, color: "var(--text)" }}>
            Manage Attendance
          </h1>
          <p style={{ margin: "4px 0 0", fontSize: 13.5, color: "var(--muted)" }}>
            Schedule-independent attendance station, live card registration, and course matrices.
          </p>
        </div>

        {/* Top Actions: Take Attendance Button */}
        <div>
          {activeSession ? (
            <button
              onClick={handleCloseSession}
              disabled={stoppingSession}
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 8,
                padding: "10px 18px",
                borderRadius: 10,
                border: "none",
                background: "#ef4444",
                color: "#fff",
                fontWeight: 700,
                fontSize: 13.5,
                cursor: stoppingSession ? "not-allowed" : "pointer",
                boxShadow: "0 2px 8px rgba(239, 68, 68, 0.3)",
              }}
            >
              <Square size={16} fill="currentColor" />
              <span>{stoppingSession ? "Closing..." : "Close Attendance"}</span>
            </button>
          ) : (
            <button
              onClick={() => setTakeModalOpen(true)}
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 8,
                padding: "10px 18px",
                borderRadius: 10,
                border: "none",
                background: "var(--primary)",
                color: "#fff",
                fontWeight: 700,
                fontSize: 13.5,
                cursor: "pointer",
                boxShadow: "0 2px 8px rgba(37, 99, 235, 0.3)",
              }}
            >
              <Play size={16} fill="currentColor" />
              <span>Take Attendance</span>
            </button>
          )}
        </div>
      </div>

      {error && (
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 10,
            padding: "12px 16px",
            borderRadius: 10,
            background: "rgba(239, 68, 68, 0.12)",
            border: "1px solid rgba(239, 68, 68, 0.25)",
            color: "#ef4444",
            fontSize: 13.5,
          }}
        >
          <AlertCircle size={18} />
          <span>{error}</span>
        </div>
      )}

      {/* 2. Hardware Station Status Card */}
      <div
        className="card"
        style={{
          padding: 16,
          borderRadius: 14,
          background: activeSession
            ? "linear-gradient(135deg, rgba(16, 185, 129, 0.08), rgba(5, 150, 105, 0.03))"
            : "var(--surface)",
          border: activeSession
            ? "1.5px solid rgba(16, 185, 129, 0.35)"
            : "1px solid var(--border)",
        }}
      >
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            flexWrap: "wrap",
            gap: 12,
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
            <div
              style={{
                width: 44,
                height: 44,
                borderRadius: 12,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                background: activeSession ? "rgba(16, 185, 129, 0.2)" : "var(--soft)",
                color: activeSession ? "#10b981" : "var(--muted)",
                flexShrink: 0,
              }}
            >
              <Radio size={22} className={activeSession ? "animate-pulse" : ""} />
            </div>
            <div>
              <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
                <strong style={{ fontSize: 15, color: "var(--text)" }}>
                  ESP32 Station (ESP32-READER-01)
                </strong>
                <span
                  style={{
                    fontSize: 10.5,
                    fontWeight: 700,
                    padding: "2px 7px",
                    borderRadius: 999,
                    background: activeSession ? "rgba(16, 185, 129, 0.18)" : "var(--soft)",
                    color: activeSession ? "#10b981" : "var(--muted)",
                  }}
                >
                  {activeSession ? "ACTIVE · BROADCASTING" : "STATION ASLEEP"}
                </span>
              </div>
              <p style={{ margin: "2px 0 0", fontSize: 13, color: "var(--muted)" }}>
                {activeSession
                  ? `Live in session: ${activeSession.course_code} - ${activeSession.course_title}`
                  : "Device screen is asleep. Start an attendance session to wake OLED and show course marquee."}
              </p>
            </div>
          </div>

          {activeSession && (
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <button
                onClick={handleCloseSession}
                disabled={stoppingSession}
                style={{
                  padding: "7px 14px",
                  borderRadius: 8,
                  border: "1px solid rgba(239, 68, 68, 0.3)",
                  background: "rgba(239, 68, 68, 0.1)",
                  color: "#ef4444",
                  fontWeight: 600,
                  fontSize: 12.5,
                  cursor: "pointer",
                }}
              >
                {stoppingSession ? "Stopping..." : "Stop Session"}
              </button>
            </div>
          )}
        </div>
      </div>

      {/* 3. Segmented Navigation Switch (Pill Buttons) */}
      <div
        style={{
          display: "flex",
          gap: 8,
          padding: 4,
          borderRadius: 12,
          background: "var(--soft)",
          border: "1px solid var(--border)",
          width: "fit-content",
        }}
      >
        <button
          type="button"
          onClick={() => setActiveView("courses")}
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: 8,
            padding: "8px 16px",
            borderRadius: 9,
            border: "none",
            background: activeView === "courses" ? "var(--surface)" : "transparent",
            color: activeView === "courses" ? "var(--text)" : "var(--muted)",
            boxShadow: activeView === "courses" ? "0 2px 6px rgba(0,0,0,0.08)" : "none",
            fontWeight: activeView === "courses" ? 700 : 500,
            fontSize: 13,
            cursor: "pointer",
            transition: "all 0.15s ease",
          }}
        >
          <BookOpen size={16} />
          <span>Course Records &amp; Export</span>
          <span
            style={{
              fontSize: 11,
              padding: "1px 6px",
              borderRadius: 999,
              background: activeView === "courses" ? "rgba(59, 130, 246, 0.15)" : "var(--soft)",
              color: activeView === "courses" ? "var(--primary)" : "var(--muted)",
              fontWeight: 700,
            }}
          >
            {courses.length}
          </span>
        </button>

        <button
          type="button"
          onClick={() => setActiveView("cards")}
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: 8,
            padding: "8px 16px",
            borderRadius: 9,
            border: "none",
            background: activeView === "cards" ? "var(--surface)" : "transparent",
            color: activeView === "cards" ? "var(--text)" : "var(--muted)",
            boxShadow: activeView === "cards" ? "0 2px 6px rgba(0,0,0,0.08)" : "none",
            fontWeight: activeView === "cards" ? 700 : 500,
            fontSize: 13,
            cursor: "pointer",
            transition: "all 0.15s ease",
          }}
        >
          <CreditCard size={16} />
          <span>Register RFID Cards</span>
          <span
            style={{
              fontSize: 11,
              padding: "1px 6px",
              borderRadius: 999,
              background: activeView === "cards" ? "rgba(16, 185, 129, 0.15)" : "var(--soft)",
              color: activeView === "cards" ? "#10b981" : "var(--muted)",
              fontWeight: 700,
            }}
          >
            {registeredCount}/{students.length}
          </span>
        </button>
      </div>

      {/* 4. SUB-VIEW A: COURSE ATTENDANCE RECORDS */}
      {activeView === "courses" && (
        <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          <div
            style={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              flexWrap: "wrap",
              gap: 10,
            }}
          >
            <div>
              <h2 style={{ fontSize: 17, fontWeight: 700, margin: 0, color: "var(--text)" }}>
                Active Semester Courses
              </h2>
              <p style={{ margin: "2px 0 0", fontSize: 13, color: "var(--muted)" }}>
                Click &quot;View Day Roster&quot; on any course to view day-wise matrix or export full data to Excel.
              </p>
            </div>
            <button
              onClick={fetchCourses}
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 6,
                padding: "6px 12px",
                borderRadius: 8,
                border: "1px solid var(--border)",
                background: "var(--surface)",
                color: "var(--text)",
                fontSize: 12.5,
                cursor: "pointer",
              }}
            >
              <RefreshCw size={13} className={coursesLoading ? "spin" : ""} /> Refresh
            </button>
          </div>

          {coursesLoading ? (
            <div style={{ padding: 40, textAlign: "center", color: "var(--muted)" }}>
              <RefreshCw size={24} className="spin" style={{ margin: "0 auto 8px" }} />
              <p style={{ fontSize: 13 }}>Loading course records…</p>
            </div>
          ) : courses.length === 0 ? (
            <div
              className="card"
              style={{ padding: 32, textAlign: "center", color: "var(--muted)", borderRadius: 14 }}
            >
              <BookOpen size={32} style={{ margin: "0 auto 8px", opacity: 0.5 }} />
              <p style={{ fontSize: 14, margin: 0 }}>No active courses found for this batch.</p>
            </div>
          ) : (
            <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              {courses.map((c) => {
                const isExpanded = expandedCourseId === c.semester_course_id;

                return (
                  <div
                    key={c.semester_course_id}
                    className="card"
                    style={{
                      borderRadius: 14,
                      overflow: "hidden",
                      border: isExpanded ? "1.5px solid var(--primary)" : "1px solid var(--border)",
                      background: "var(--card)",
                      transition: "all 0.2s ease",
                    }}
                  >
                    {/* Modern Course Card Content */}
                    <div style={{ padding: "16px 20px" }}>
                      {/* Row 1: Course Code Pill + Teacher Name */}
                      <div
                        style={{
                          display: "flex",
                          alignItems: "center",
                          justifyContent: "space-between",
                          flexWrap: "wrap",
                          gap: 8,
                          marginBottom: 8,
                        }}
                      >
                        <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
                          <span
                            style={{
                              fontSize: 12,
                              fontWeight: 700,
                              letterSpacing: "0.03em",
                              padding: "3px 10px",
                              borderRadius: 6,
                              background: "rgba(59, 130, 246, 0.12)",
                              color: "var(--primary)",
                              display: "inline-flex",
                              alignItems: "center",
                            }}
                          >
                            {c.course_code}
                          </span>
                          <span style={{ fontSize: 13, color: "var(--muted)" }}>
                            · {c.teacher_name}
                          </span>
                        </div>
                        <span style={{ fontSize: 12, color: "var(--muted)", fontWeight: 500 }}>
                          {c.enrolled_students} Enrolled
                        </span>
                      </div>

                      {/* Row 2: Course Title */}
                      <h3
                        style={{
                          fontSize: 16,
                          fontWeight: 700,
                          margin: "0 0 14px",
                          color: "var(--text)",
                          lineHeight: 1.35,
                        }}
                      >
                        {c.course_title}
                      </h3>

                      {/* Row 3: Stats Chips & Action Buttons */}
                      <div
                        style={{
                          display: "flex",
                          alignItems: "center",
                          justifyContent: "space-between",
                          flexWrap: "wrap",
                          gap: 10,
                          paddingTop: 12,
                          borderTop: "1px solid var(--border)",
                        }}
                      >
                        {/* Stat Chips */}
                        <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
                          <div
                            style={{
                              display: "inline-flex",
                              alignItems: "center",
                              gap: 6,
                              padding: "5px 10px",
                              borderRadius: 8,
                              background: "var(--soft)",
                              fontSize: 12,
                              fontWeight: 600,
                              color: "var(--text)",
                            }}
                          >
                            <Calendar size={13} style={{ color: "var(--primary)" }} />
                            <span>{c.total_sessions} Classes Held</span>
                          </div>
                          <div
                            style={{
                              display: "inline-flex",
                              alignItems: "center",
                              gap: 6,
                              padding: "5px 10px",
                              borderRadius: 8,
                              background: "rgba(16, 185, 129, 0.1)",
                              fontSize: 12,
                              fontWeight: 600,
                              color: "#10b981",
                            }}
                          >
                            <CheckCircle2 size={13} />
                            <span>{c.average_percentage}% Avg Attendance</span>
                          </div>
                        </div>

                        {/* Action Buttons */}
                        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                          <button
                            type="button"
                            onClick={(e) => handleExport(e, c.semester_course_id)}
                            disabled={exportingCourseId === c.semester_course_id}
                            style={{
                              display: "inline-flex",
                              alignItems: "center",
                              gap: 6,
                              padding: "6px 12px",
                              borderRadius: 8,
                              border: "1px solid var(--border)",
                              background: "var(--surface)",
                              color: "var(--text)",
                              fontSize: 12,
                              fontWeight: 600,
                              cursor: "pointer",
                            }}
                            title="Download full attendance matrix (.xlsx)"
                          >
                            <FileSpreadsheet
                              size={14}
                              className={exportingCourseId === c.semester_course_id ? "spin" : ""}
                              style={{ color: "#10b981" }}
                            />
                            <span>
                              {exportingCourseId === c.semester_course_id
                                ? "Exporting..."
                                : "Excel Export"}
                            </span>
                          </button>

                          <button
                            type="button"
                            onClick={() => handleToggleCourse(c.semester_course_id)}
                            style={{
                              display: "inline-flex",
                              alignItems: "center",
                              gap: 6,
                              padding: "6px 12px",
                              borderRadius: 8,
                              border: isExpanded
                                ? "1px solid var(--primary)"
                                : "1px solid var(--border)",
                              background: isExpanded ? "var(--primary)" : "var(--soft)",
                              color: isExpanded ? "#fff" : "var(--text)",
                              fontSize: 12,
                              fontWeight: 600,
                              cursor: "pointer",
                            }}
                          >
                            <span>{isExpanded ? "Hide Roster" : "View Day Roster"}</span>
                            {isExpanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                          </button>
                        </div>
                      </div>
                    </div>

                    {/* Expanded Day-Wise Roster Matrix */}
                    {isExpanded && (
                      <div
                        style={{
                          borderTop: "1px solid var(--border)",
                          padding: "16px 20px",
                          background: "var(--surface)",
                        }}
                      >
                        {matrixLoading ? (
                          <div style={{ padding: 24, textAlign: "center", color: "var(--muted)" }}>
                            <RefreshCw size={20} className="spin" style={{ margin: "0 auto 6px" }} />
                            <p style={{ fontSize: 12.5, margin: 0 }}>Loading day-wise matrix…</p>
                          </div>
                        ) : !matrixData || matrixData.sessions.length === 0 ? (
                          <div
                            style={{
                              padding: 16,
                              textAlign: "center",
                              color: "var(--muted)",
                              fontSize: 13,
                            }}
                          >
                            No attendance sessions have been recorded for this course yet.
                          </div>
                        ) : (
                          <div>
                            <div
                              style={{
                                display: "flex",
                                justifyContent: "space-between",
                                alignItems: "center",
                                flexWrap: "wrap",
                                gap: 10,
                                marginBottom: 12,
                              }}
                            >
                              <div style={{ fontSize: 13, color: "var(--muted)" }}>
                                Showing <strong>{matrixData.students.length}</strong> enrolled students
                                across <strong>{matrixData.sessions.length}</strong> recorded sessions.
                              </div>
                              <button
                                onClick={(e) => handleExport(e, c.semester_course_id)}
                                style={{
                                  display: "inline-flex",
                                  alignItems: "center",
                                  gap: 6,
                                  padding: "6px 12px",
                                  borderRadius: 6,
                                  border: "none",
                                  background: "#10b981",
                                  color: "#fff",
                                  fontSize: 12,
                                  fontWeight: 700,
                                  cursor: "pointer",
                                }}
                              >
                                <Download size={13} /> Download Excel Matrix (.xlsx)
                              </button>
                            </div>

                            {/* Responsive Matrix Table with Sticky Columns */}
                            <div
                              style={{
                                overflowX: "auto",
                                border: "1px solid var(--border)",
                                borderRadius: 10,
                                background: "var(--card)",
                              }}
                            >
                              <table
                                style={{
                                  width: "100%",
                                  borderCollapse: "collapse",
                                  fontSize: 12.5,
                                  textAlign: "left",
                                }}
                              >
                                <thead>
                                  <tr
                                    style={{
                                      background: "var(--soft)",
                                      borderBottom: "1px solid var(--border)",
                                    }}
                                  >
                                    <th
                                      style={{
                                        padding: "10px 12px",
                                        width: 44,
                                        position: "sticky",
                                        left: 0,
                                        background: "var(--soft)",
                                        zIndex: 2,
                                      }}
                                    >
                                      #
                                    </th>
                                    <th
                                      style={{
                                        padding: "10px 12px",
                                        minWidth: 90,
                                        position: "sticky",
                                        left: 44,
                                        background: "var(--soft)",
                                        zIndex: 2,
                                      }}
                                    >
                                      Roll
                                    </th>
                                    <th
                                      style={{
                                        padding: "10px 12px",
                                        minWidth: 150,
                                        position: "sticky",
                                        left: 134,
                                        background: "var(--soft)",
                                        zIndex: 2,
                                        borderRight: "1px solid var(--border)",
                                      }}
                                    >
                                      Student Name
                                    </th>
                                    {matrixData.sessions.map((s, idx) => (
                                      <th
                                        key={s.session_id}
                                        style={{
                                          padding: "8px 10px",
                                          textAlign: "center",
                                          minWidth: 84,
                                          borderLeft: "1px solid var(--border)",
                                        }}
                                      >
                                        <div style={{ fontWeight: 700, fontSize: 11.5 }}>
                                          Day {idx + 1}
                                        </div>
                                        <div style={{ fontSize: 10, color: "var(--muted)" }}>
                                          {s.date}
                                        </div>
                                      </th>
                                    ))}
                                    <th
                                      style={{
                                        padding: "10px 12px",
                                        textAlign: "center",
                                        minWidth: 80,
                                        borderLeft: "1px solid var(--border)",
                                      }}
                                    >
                                      Present
                                    </th>
                                    <th
                                      style={{
                                        padding: "10px 12px",
                                        textAlign: "center",
                                        minWidth: 70,
                                        borderLeft: "1px solid var(--border)",
                                      }}
                                    >
                                      %
                                    </th>
                                  </tr>
                                </thead>
                                <tbody>
                                  {matrixData.students.map((stu, sIdx) => (
                                    <tr
                                      key={stu.student_id}
                                      style={{
                                        borderBottom: "1px solid var(--border)",
                                        background:
                                          sIdx % 2 === 0 ? "transparent" : "rgba(0,0,0,0.015)",
                                      }}
                                    >
                                      <td
                                        style={{
                                          padding: "8px 12px",
                                          color: "var(--muted)",
                                          position: "sticky",
                                          left: 0,
                                          background: "var(--card)",
                                          zIndex: 1,
                                        }}
                                      >
                                        {sIdx + 1}
                                      </td>
                                      <td
                                        style={{
                                          padding: "8px 12px",
                                          fontWeight: 600,
                                          position: "sticky",
                                          left: 44,
                                          background: "var(--card)",
                                          zIndex: 1,
                                        }}
                                      >
                                        {stu.roll}
                                      </td>
                                      <td
                                        style={{
                                          padding: "8px 12px",
                                          position: "sticky",
                                          left: 134,
                                          background: "var(--card)",
                                          zIndex: 1,
                                          borderRight: "1px solid var(--border)",
                                          whiteSpace: "nowrap",
                                        }}
                                      >
                                        {stu.full_name}
                                      </td>
                                      {matrixData.sessions.map((sess) => {
                                        const isPresent =
                                          stu.records?.[sess.session_id] === "present";
                                        return (
                                          <td
                                            key={sess.session_id}
                                            style={{
                                              padding: "6px 8px",
                                              textAlign: "center",
                                              borderLeft: "1px solid var(--border)",
                                            }}
                                          >
                                            <span
                                              style={{
                                                display: "inline-block",
                                                width: 24,
                                                height: 24,
                                                lineHeight: "24px",
                                                borderRadius: 6,
                                                fontSize: 12,
                                                fontWeight: 700,
                                                background: isPresent
                                                  ? "rgba(16, 185, 129, 0.15)"
                                                  : "var(--soft)",
                                                color: isPresent ? "#10b981" : "var(--muted)",
                                              }}
                                            >
                                              {isPresent ? "1" : "0"}
                                            </span>
                                          </td>
                                        );
                                      })}
                                      <td
                                        style={{
                                          padding: "8px 12px",
                                          textAlign: "center",
                                          fontWeight: 700,
                                          borderLeft: "1px solid var(--border)",
                                        }}
                                      >
                                        {stu.present_count}/{matrixData.sessions.length}
                                      </td>
                                      <td
                                        style={{
                                          padding: "8px 12px",
                                          textAlign: "center",
                                          fontWeight: 700,
                                          borderLeft: "1px solid var(--border)",
                                          color: stu.percentage >= 75 ? "#10b981" : "#ef4444",
                                        }}
                                      >
                                        {stu.percentage}%
                                      </td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            </div>
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
      )}

      {/* 5. SUB-VIEW B: REGISTER STUDENT RFID CARDS */}
      {activeView === "cards" && (
        <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
          {/* Pairing Workspace Card */}
          <div className="card" style={{ padding: 20, borderRadius: 14 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 6 }}>
              <CreditCard size={20} style={{ color: "var(--primary)" }} />
              <h2 style={{ fontSize: 17, fontWeight: 700, margin: 0, color: "var(--text)" }}>
                Pair Student with RFID Card
              </h2>
            </div>
            <p style={{ margin: "0 0 16px", fontSize: 13, color: "var(--muted)" }}>
              Search for a student, tap their physical RFID card on the ESP32 station or enter the hex UID manually.
            </p>

            {detectedUid && (
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "space-between",
                  flexWrap: "wrap",
                  gap: 10,
                  padding: "10px 14px",
                  borderRadius: 10,
                  background: "rgba(59, 130, 246, 0.12)",
                  border: "1px solid rgba(59, 130, 246, 0.3)",
                  color: "var(--primary)",
                  marginBottom: 16,
                  fontSize: 13,
                }}
              >
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <Sparkles size={16} />
                  <span>
                    <strong>Card Detected on Station:</strong> {detectedUid}
                  </span>
                </div>
                <button
                  type="button"
                  onClick={() => setCardUidInput(detectedUid)}
                  style={{
                    padding: "5px 12px",
                    borderRadius: 6,
                    border: "none",
                    background: "var(--primary)",
                    color: "#fff",
                    fontSize: 12,
                    fontWeight: 600,
                    cursor: "pointer",
                  }}
                >
                  Use Detected UID
                </button>
              </div>
            )}

            {registerSuccess && (
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 8,
                  padding: "10px 14px",
                  borderRadius: 8,
                  background: "rgba(16, 185, 129, 0.12)",
                  border: "1px solid rgba(16, 185, 129, 0.25)",
                  color: "#10b981",
                  marginBottom: 16,
                  fontSize: 13,
                  fontWeight: 600,
                }}
              >
                <CheckCircle2 size={16} />
                <span>{registerSuccess}</span>
              </div>
            )}

            <form
              onSubmit={handleRegisterCard}
              style={{
                display: "grid",
                gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))",
                gap: 14,
              }}
            >
              {/* Field 1: Student Roll / ID search */}
              <div style={{ position: "relative" }}>
                <label
                  style={{
                    display: "block",
                    fontSize: 12,
                    fontWeight: 600,
                    color: "var(--muted)",
                    marginBottom: 6,
                  }}
                >
                  1. STUDENT ID / ROLL
                </label>
                <div style={{ position: "relative" }}>
                  <Search
                    size={15}
                    style={{
                      position: "absolute",
                      left: 12,
                      top: "50%",
                      transform: "translateY(-50%)",
                      color: "var(--muted)",
                    }}
                  />
                  <input
                    type="text"
                    placeholder={
                      selectedStudent
                        ? `${selectedStudent.roll} - ${selectedStudent.full_name}`
                        : "Search roll or name..."
                    }
                    value={studentSearch}
                    onChange={(e) => {
                      setStudentSearch(e.target.value);
                      if (selectedStudent) setSelectedStudent(null);
                    }}
                    style={{
                      width: "100%",
                      padding: "9px 12px 9px 34px",
                      borderRadius: 8,
                      border: "1px solid var(--border)",
                      background: "var(--soft)",
                      color: "var(--text)",
                      fontSize: 13,
                      outline: "none",
                      boxSizing: "border-box",
                    }}
                  />
                  {selectedStudent && (
                    <button
                      type="button"
                      onClick={() => {
                        setSelectedStudent(null);
                        setStudentSearch("");
                      }}
                      style={{
                        position: "absolute",
                        right: 8,
                        top: "50%",
                        transform: "translateY(-50%)",
                        border: "none",
                        background: "transparent",
                        color: "var(--muted)",
                        cursor: "pointer",
                      }}
                    >
                      <X size={15} />
                    </button>
                  )}
                </div>

                {/* Suggestions Dropdown */}
                {filteredStudents.length > 0 && !selectedStudent && (
                  <div
                    style={{
                      position: "absolute",
                      top: "100%",
                      left: 0,
                      right: 0,
                      zIndex: 50,
                      marginTop: 4,
                      background: "var(--surface)",
                      border: "1px solid var(--border)",
                      borderRadius: 8,
                      boxShadow: "0 10px 25px rgba(0,0,0,0.18)",
                      maxHeight: 200,
                      overflowY: "auto",
                    }}
                  >
                    {filteredStudents.map((s) => (
                      <div
                        key={s.student_id}
                        onClick={() => {
                          setSelectedStudent(s);
                          setStudentSearch("");
                          if (s.rfid_card_uid) setCardUidInput(s.rfid_card_uid);
                        }}
                        style={{
                          padding: "8px 12px",
                          cursor: "pointer",
                          fontSize: 13,
                          borderBottom: "1px solid var(--soft)",
                          display: "flex",
                          justifyContent: "space-between",
                          alignItems: "center",
                        }}
                        onMouseEnter={(e) => (e.currentTarget.style.background = "var(--soft)")}
                        onMouseLeave={(e) => (e.currentTarget.style.background = "transparent")}
                      >
                        <div>
                          <strong>{s.roll}</strong> — {s.full_name}
                        </div>
                        {s.rfid_card_uid ? (
                          <span
                            style={{
                              fontSize: 10.5,
                              color: "#10b981",
                              background: "rgba(16,185,129,0.1)",
                              padding: "2px 7px",
                              borderRadius: 4,
                              fontWeight: 600,
                            }}
                          >
                            Linked: ••••{s.rfid_card_uid.slice(-4)}
                          </span>
                        ) : (
                          <span
                            style={{
                              fontSize: 10.5,
                              color: "var(--muted)",
                              background: "var(--soft)",
                              padding: "2px 7px",
                              borderRadius: 4,
                            }}
                          >
                            No Card
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Field 2: Card UID Input */}
              <div>
                <label
                  style={{
                    display: "block",
                    fontSize: 12,
                    fontWeight: 600,
                    color: "var(--muted)",
                    marginBottom: 6,
                  }}
                >
                  2. CARD UID (HEX)
                </label>
                <input
                  type="text"
                  placeholder="e.g. 03E1AA05"
                  value={cardUidInput}
                  onChange={(e) => setCardUidInput(e.target.value.toUpperCase())}
                  style={{
                    width: "100%",
                    padding: "9px 12px",
                    borderRadius: 8,
                    border: "1px solid var(--border)",
                    background: "var(--soft)",
                    color: "var(--text)",
                    fontSize: 13,
                    fontFamily: "monospace",
                    outline: "none",
                    boxSizing: "border-box",
                  }}
                />
              </div>

              {/* Submit Button */}
              <div style={{ display: "flex", alignItems: "flex-end" }}>
                <button
                  type="submit"
                  disabled={!selectedStudent || !cardUidInput.trim() || registeringCard}
                  style={{
                    width: "100%",
                    padding: "10px 16px",
                    borderRadius: 8,
                    border: "none",
                    background:
                      selectedStudent && cardUidInput.trim() ? "var(--primary)" : "var(--soft)",
                    color: selectedStudent && cardUidInput.trim() ? "#fff" : "var(--muted)",
                    fontSize: 13,
                    fontWeight: 700,
                    cursor:
                      selectedStudent && cardUidInput.trim() ? "pointer" : "not-allowed",
                  }}
                >
                  {registeringCard ? "Linking..." : "Register Card"}
                </button>
              </div>
            </form>
          </div>

          {/* Batch Card Status & Enrollment Directory */}
          <div className="card" style={{ padding: 20, borderRadius: 14 }}>
            <div
              style={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                flexWrap: "wrap",
                gap: 12,
                marginBottom: 16,
              }}
            >
              <div>
                <h3 style={{ fontSize: 16, fontWeight: 700, margin: 0, color: "var(--text)" }}>
                  Batch Student Card Directory
                </h3>
                <p style={{ margin: "2px 0 0", fontSize: 12.5, color: "var(--muted)" }}>
                  Review cards assigned to students. Click &quot;Pair Card&quot; on any student to load them into the form above.
                </p>
              </div>

              {/* Roster Search */}
              <div style={{ position: "relative", minWidth: 200 }}>
                <Search
                  size={14}
                  style={{
                    position: "absolute",
                    left: 10,
                    top: "50%",
                    transform: "translateY(-50%)",
                    color: "var(--muted)",
                  }}
                />
                <input
                  type="text"
                  placeholder="Filter directory..."
                  value={rosterSearch}
                  onChange={(e) => setRosterSearch(e.target.value)}
                  style={{
                    width: "100%",
                    padding: "6px 10px 6px 30px",
                    borderRadius: 7,
                    border: "1px solid var(--border)",
                    background: "var(--soft)",
                    color: "var(--text)",
                    fontSize: 12,
                    outline: "none",
                    boxSizing: "border-box",
                  }}
                />
              </div>
            </div>

            {/* Filter Tabs */}
            <div style={{ display: "flex", gap: 6, marginBottom: 14 }}>
              {(["all", "registered", "unregistered"] as const).map((filter) => (
                <button
                  key={filter}
                  type="button"
                  onClick={() => setCardFilter(filter)}
                  style={{
                    padding: "5px 12px",
                    borderRadius: 6,
                    border: "none",
                    background: cardFilter === filter ? "var(--primary)" : "var(--soft)",
                    color: cardFilter === filter ? "#fff" : "var(--muted)",
                    fontSize: 12,
                    fontWeight: 600,
                    cursor: "pointer",
                    textTransform: "capitalize",
                  }}
                >
                  {filter === "all"
                    ? `All (${students.length})`
                    : filter === "registered"
                    ? `Registered (${registeredCount})`
                    : `Unregistered (${unregisteredCount})`}
                </button>
              ))}
            </div>

            {/* Roster Table */}
            <div
              style={{
                overflowX: "auto",
                border: "1px solid var(--border)",
                borderRadius: 8,
                background: "var(--surface)",
              }}
            >
              <table
                style={{
                  width: "100%",
                  borderCollapse: "collapse",
                  fontSize: 12.5,
                  textAlign: "left",
                }}
              >
                <thead>
                  <tr style={{ background: "var(--soft)", borderBottom: "1px solid var(--border)" }}>
                    <th style={{ padding: "8px 12px", width: 50 }}>#</th>
                    <th style={{ padding: "8px 12px", minWidth: 90 }}>Roll</th>
                    <th style={{ padding: "8px 12px", minWidth: 160 }}>Student Name</th>
                    <th style={{ padding: "8px 12px", minWidth: 140 }}>Card UID</th>
                    <th style={{ padding: "8px 12px", width: 100, textAlign: "right" }}>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredRoster.length === 0 ? (
                    <tr>
                      <td
                        colSpan={5}
                        style={{ padding: 24, textAlign: "center", color: "var(--muted)" }}
                      >
                        No matching students found in directory.
                      </td>
                    </tr>
                  ) : (
                    filteredRoster.map((s, idx) => (
                      <tr
                        key={s.student_id}
                        style={{
                          borderBottom: "1px solid var(--border)",
                          background: idx % 2 === 0 ? "transparent" : "rgba(0,0,0,0.015)",
                        }}
                      >
                        <td style={{ padding: "8px 12px", color: "var(--muted)" }}>{idx + 1}</td>
                        <td style={{ padding: "8px 12px", fontWeight: 700 }}>{s.roll}</td>
                        <td style={{ padding: "8px 12px" }}>{s.full_name}</td>
                        <td style={{ padding: "8px 12px" }}>
                          {s.rfid_card_uid ? (
                            <span
                              style={{
                                display: "inline-flex",
                                alignItems: "center",
                                gap: 6,
                                padding: "2px 8px",
                                borderRadius: 5,
                                background: "rgba(16, 185, 129, 0.12)",
                                color: "#10b981",
                                fontFamily: "monospace",
                                fontSize: 12,
                                fontWeight: 700,
                              }}
                            >
                              <Check size={12} /> {s.rfid_card_uid}
                            </span>
                          ) : (
                            <span
                              style={{
                                padding: "2px 8px",
                                borderRadius: 5,
                                background: "var(--soft)",
                                color: "var(--muted)",
                                fontSize: 11,
                              }}
                            >
                              Unregistered
                            </span>
                          )}
                        </td>
                        <td style={{ padding: "8px 12px", textAlign: "right" }}>
                          <button
                            type="button"
                            onClick={() => {
                              setSelectedStudent(s);
                              setCardUidInput(s.rfid_card_uid || detectedUid || "");
                              window.scrollTo({ top: 180, behavior: "smooth" });
                            }}
                            style={{
                              padding: "4px 10px",
                              borderRadius: 6,
                              border: "1px solid var(--border)",
                              background: "var(--surface)",
                              color: "var(--primary)",
                              fontWeight: 600,
                              fontSize: 11.5,
                              cursor: "pointer",
                            }}
                          >
                            {s.rfid_card_uid ? "Edit Card" : "Pair Card"}
                          </button>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* 6. Take Attendance Modal */}
      {takeModalOpen && (
        <div className="attendance-modal-overlay">
          <div className="attendance-modal-container" style={{ maxWidth: 440, padding: 24 }}>
            <div
              style={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                marginBottom: 14,
              }}
            >
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <Play size={18} style={{ color: "var(--primary)" }} />
                <h3 style={{ margin: 0, fontSize: 17, fontWeight: 700, color: "var(--text)" }}>
                  Take Attendance
                </h3>
              </div>
              <button
                onClick={() => setTakeModalOpen(false)}
                style={{
                  border: "none",
                  background: "transparent",
                  cursor: "pointer",
                  color: "var(--muted)",
                }}
              >
                <X size={18} />
              </button>
            </div>

            <p style={{ margin: "0 0 16px", fontSize: 13, color: "var(--muted)" }}>
              Select a course to start attendance immediately. The ESP32 reader screen will wake up and display the course name marquee.
            </p>

            <div style={{ marginBottom: 18 }}>
              <label
                style={{
                  display: "block",
                  fontSize: 12,
                  fontWeight: 600,
                  color: "var(--muted)",
                  marginBottom: 6,
                }}
              >
                SELECT COURSE
              </label>
              <select
                value={selectedCourseForStart}
                onChange={(e) => setSelectedCourseForStart(e.target.value)}
                style={{
                  width: "100%",
                  padding: "10px 12px",
                  borderRadius: 8,
                  border: "1px solid var(--border)",
                  background: "var(--soft)",
                  color: "var(--text)",
                  fontSize: 13,
                  outline: "none",
                  boxSizing: "border-box",
                }}
              >
                <option value="">Choose course to launch...</option>
                {courses.map((c) => (
                  <option key={c.semester_course_id} value={c.semester_course_id}>
                    {c.course_code} - {c.course_title}
                  </option>
                ))}
              </select>
            </div>

            <div style={{ display: "flex", gap: 10 }}>
              <button
                type="button"
                onClick={() => setTakeModalOpen(false)}
                style={{
                  flex: 1,
                  padding: "10px 16px",
                  borderRadius: 8,
                  border: "1px solid var(--border)",
                  background: "var(--surface)",
                  color: "var(--text)",
                  fontWeight: 600,
                  fontSize: 13,
                  cursor: "pointer",
                }}
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleStartSession}
                disabled={!selectedCourseForStart || startingSession}
                style={{
                  flex: 1,
                  padding: "10px 16px",
                  borderRadius: 8,
                  border: "none",
                  background: "var(--primary)",
                  color: "#fff",
                  fontWeight: 700,
                  fontSize: 13,
                  cursor: !selectedCourseForStart || startingSession ? "not-allowed" : "pointer",
                }}
              >
                {startingSession ? "Launching..." : "Start Station"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

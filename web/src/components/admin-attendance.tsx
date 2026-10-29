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
  ChevronRight,
  Check,
  X,
  Sparkles,
  ArrowLeft,
  FileSpreadsheet,
  Wifi,
  Activity,
  Copy,
  ExternalLink,
} from "lucide-react";
import { downloadCourseMatrixExcel, isRecordPresent } from "@/lib/attendance-excel";

interface StudentItem {
  student_id: string;
  roll: string;
  full_name: string;
  rfid_card_uid?: string | null;
  is_readd?: boolean;
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
    records: Record<string, any>;
    present_count: number;
    total_sessions: number;
    percentage: number;
    is_readd?: boolean;
  }>;
}

export default function AdminAttendance({ ctx }: { ctx: Context }) {
  const client = supabase();
  const batchId = ctx.batch;

  // View Mode: 'courses' (Matrix & Registers) vs 'cards' (RFID Enrollment Directory)
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
  const [copiedUid, setCopiedUid] = useState<string | null>(null);

  // Student Roster Card Directory Filter & Search
  const [cardFilter, setCardFilter] = useState<"all" | "registered" | "unregistered">("all");
  const [rosterSearch, setRosterSearch] = useState("");

  // Course Attendance History & Matrix
  const [courses, setCourses] = useState<CourseItem[]>([]);
  const [coursesLoading, setCoursesLoading] = useState(true);
  const [selectedCourseRoster, setSelectedCourseRoster] = useState<CourseItem | null>(null);
  const [matrixSearch, setMatrixSearch] = useState("");
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
      if (data) {
        setCourses(data);
        // Default select first course if none selected yet
        if (data.length > 0 && !selectedCourseRoster) {
          handleOpenCourseRoster(data[0]);
        }
      }
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
      if (selectedCourseRoster) {
        fetchMatrix(selectedCourseRoster.semester_course_id);
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
      let { error: err } = await client
        .schema("classmate")
        .rpc("enroll_student_rfid", {
          p_student_id: selectedStudent.student_id,
          p_card_uid: cardUidInput.trim().toUpperCase(),
        });

      if (err && err.message?.includes("schema cache")) {
        const retry = await client
          .schema("classmate")
          .rpc("enroll_student_rfid", {
            target_student: selectedStudent.student_id,
            target_card_uid: cardUidInput.trim().toUpperCase(),
          });
        err = retry.error;
      }

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

  // Open dedicated Course Attendance Roster View
  const handleOpenCourseRoster = (course: CourseItem) => {
    setSelectedCourseRoster(course);
    setMatrixSearch("");
    setMatrixData(null);
    fetchMatrix(course.semester_course_id);
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

  const handleCopyUid = (uid: string) => {
    navigator.clipboard.writeText(uid);
    setCopiedUid(uid);
    setTimeout(() => setCopiedUid(null), 2000);
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

  // Overall Batch Average Presence
  const overallAvgPresence = useMemo(() => {
    if (!courses.length) return 0;
    const sum = courses.reduce((acc, c) => acc + (c.average_percentage || 0), 0);
    return Math.round((sum / courses.length) * 10) / 10;
  }, [courses]);

  const totalClassesConducted = useMemo(() => {
    if (!courses.length) return 0;
    return courses.reduce((acc, c) => acc + (c.total_sessions || 0), 0);
  }, [courses]);

  // Filtered Matrix Students
  const filteredMatrixStudents = useMemo(() => {
    if (!matrixData?.students) return [];
    if (!matrixSearch.trim()) return matrixData.students;
    const q = matrixSearch.toLowerCase().trim();
    return matrixData.students.filter(
      (s) =>
        s.roll.toLowerCase().includes(q) ||
        s.full_name.toLowerCase().includes(q)
    );
  }, [matrixData, matrixSearch]);

  // Filtered Roster for Card Directory Table
  const filteredRoster = useMemo(() => {
    const list = students.filter((s) => {
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

    return [...list].sort((a, b) => {
      const aReadd = Boolean(a.is_readd);
      const bReadd = Boolean(b.is_readd);
      if (aReadd !== bReadd) return aReadd ? 1 : -1;
      return (a.roll || "").localeCompare(b.roll || "", undefined, { numeric: true });
    });
  }, [students, cardFilter, rosterSearch]);

  return (
    <div
      style={{
        width: "100%",
        maxWidth: 1160,
        margin: "0 auto",
        padding: "16px 12px 80px",
        display: "flex",
        flexDirection: "column",
        gap: 20,
        boxSizing: "border-box",
      }}
    >
      {/* ========================================================================= */}
      {/* 1. BENTO COMMAND HEADER */}
      {/* ========================================================================= */}
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          flexWrap: "wrap",
          gap: 16,
          padding: "4px 0",
        }}
      >
        <div>
          <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 4 }}>
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
                width: 32,
                height: 32,
                borderRadius: 8,
                border: "1px solid var(--border)",
                background: "var(--card)",
                color: "var(--text)",
                cursor: "pointer",
                padding: 0,
                transition: "all 0.15s ease",
              }}
            >
              <ArrowLeft size={16} />
            </button>
            <span
              style={{
                fontSize: 11,
                fontWeight: 800,
                letterSpacing: "0.08em",
                textTransform: "uppercase",
                padding: "3px 10px",
                borderRadius: 999,
                background: activeSession ? "rgba(16, 185, 129, 0.15)" : "var(--blue-soft)",
                color: activeSession ? "#10b981" : "var(--primary)",
                display: "inline-flex",
                alignItems: "center",
                gap: 6,
              }}
            >
              {activeSession ? (
                <>
                  <span
                    style={{
                      width: 6,
                      height: 6,
                      borderRadius: "50%",
                      background: "#10b981",
                      boxShadow: "0 0 6px #10b981",
                    }}
                  />
                  <span>LIVE: {activeSession.course_code}</span>
                </>
              ) : (
                "ATTENDANCE HUB"
              )}
            </span>
            <span style={{ fontSize: 13, color: "var(--muted)", fontWeight: 500 }}>
              {ctx.batchInfo?.departments?.code?.toUpperCase()} Batch {ctx.batchInfo?.batch_number}
            </span>
          </div>
          <h1 style={{ fontSize: 26, fontWeight: 800, margin: 0, color: "var(--text)", letterSpacing: "-0.02em" }}>
            Manage Attendance
          </h1>
        </div>

        {/* Top Actions: Live Session Controller & View Tabs */}
        <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
          {/* Segmented View Switcher */}
          <div
            style={{
              display: "flex",
              alignItems: "center",
              background: "var(--surface)",
              border: "1px solid var(--border)",
              borderRadius: 12,
              padding: 3,
            }}
          >
            <button
              onClick={() => setActiveView("courses")}
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 6,
                padding: "7px 14px",
                borderRadius: 9,
                fontSize: 12.5,
                fontWeight: 700,
                border: "none",
                cursor: "pointer",
                transition: "all 0.15s ease",
                background: activeView === "courses" ? "var(--primary)" : "transparent",
                color: activeView === "courses" ? "#ffffff" : "var(--muted)",
                boxShadow: activeView === "courses" ? "0 2px 8px rgba(37,99,235,0.25)" : "none",
              }}
            >
              <BookOpen size={14} />
              <span>Courses & Matrix</span>
            </button>

            <button
              onClick={() => setActiveView("cards")}
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 6,
                padding: "7px 14px",
                borderRadius: 9,
                fontSize: 12.5,
                fontWeight: 700,
                border: "none",
                cursor: "pointer",
                transition: "all 0.15s ease",
                background: activeView === "cards" ? "var(--primary)" : "transparent",
                color: activeView === "cards" ? "#ffffff" : "var(--muted)",
                boxShadow: activeView === "cards" ? "0 2px 8px rgba(37,99,235,0.25)" : "none",
              }}
            >
              <CreditCard size={14} />
              <span>RFID Cards ({registeredCount}/{students.length})</span>
            </button>
          </div>

          {/* Live Session Toggle */}
          {activeSession ? (
            <button
              onClick={handleCloseSession}
              disabled={stoppingSession}
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 8,
                padding: "9px 16px",
                borderRadius: 12,
                border: "none",
                background: "#ef4444",
                color: "#fff",
                fontWeight: 700,
                fontSize: 13,
                cursor: stoppingSession ? "not-allowed" : "pointer",
                boxShadow: "0 2px 10px rgba(239, 68, 68, 0.3)",
              }}
            >
              <Square size={15} fill="currentColor" />
              <span>{stoppingSession ? "Closing..." : "Close Attendance"}</span>
            </button>
          ) : (
            <button
              onClick={() => setTakeModalOpen(true)}
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 8,
                padding: "9px 18px",
                borderRadius: 12,
                border: "none",
                background: "var(--primary)",
                color: "#fff",
                fontWeight: 700,
                fontSize: 13,
                cursor: "pointer",
                boxShadow: "0 2px 10px rgba(37, 99, 235, 0.3)",
              }}
            >
              <Play size={15} fill="currentColor" />
              <span>Take Attendance</span>
            </button>
          )}
        </div>
      </div>

      {/* Error Alert Banner */}
      {error && (
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 10,
            padding: "12px 16px",
            borderRadius: 12,
            background: "rgba(239, 68, 68, 0.12)",
            border: "1px solid rgba(239, 68, 68, 0.25)",
            color: "#ef4444",
            fontSize: 13,
          }}
        >
          <AlertCircle size={18} />
          <span style={{ flex: 1 }}>{error}</span>
          <button
            onClick={() => setError(null)}
            style={{ background: "none", border: "none", color: "#ef4444", cursor: "pointer" }}
          >
            <X size={16} />
          </button>
        </div>
      )}



      {/* ========================================================================= */}
      {/* 3. SUB-VIEW A: COURSES & MATRICES */}
      {/* ========================================================================= */}
      {activeView === "courses" && (
        <div
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fit, minmax(320px, 1fr))",
            gap: 18,
            alignItems: "start",
          }}
        >
          {/* Left Column: Course Selector Rail */}
          <div
            style={{
              background: "var(--card)",
              border: "1px solid var(--border)",
              borderRadius: 18,
              padding: 16,
              display: "flex",
              flexDirection: "column",
              gap: 12,
              boxShadow: "var(--shadow)",
            }}
          >
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <h2 style={{ fontSize: 14, fontWeight: 800, margin: 0, color: "var(--text)" }}>
                Course Offerings
              </h2>
              <span style={{ fontSize: 12, color: "var(--muted)" }}>
                {courses.length} courses
              </span>
            </div>

            {coursesLoading ? (
              <div style={{ padding: "30px 0", textAlign: "center", color: "var(--muted)", fontSize: 13 }}>
                Loading courses...
              </div>
            ) : courses.length === 0 ? (
              <div style={{ padding: "30px 0", textAlign: "center", color: "var(--muted)", fontSize: 13 }}>
                No active courses registered for this batch.
              </div>
            ) : (
              <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                {courses.map((course) => {
                  const isSelected = selectedCourseRoster?.semester_course_id === course.semester_course_id;
                  const isLiveCourse = activeSession?.course_code === course.course_code;

                  return (
                    <div
                      key={course.semester_course_id}
                      onClick={() => handleOpenCourseRoster(course)}
                      style={{
                        padding: "12px 14px",
                        borderRadius: 14,
                        border: isSelected
                          ? "1.5px solid var(--primary)"
                          : "1px solid var(--border)",
                        background: isSelected ? "var(--blue-soft)" : "var(--surface)",
                        cursor: "pointer",
                        transition: "all 0.15s ease",
                        display: "flex",
                        flexDirection: "column",
                        gap: 6,
                      }}
                    >
                      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                        <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                          <span
                            style={{
                              fontSize: 12.5,
                              fontWeight: 800,
                              color: isSelected ? "var(--primary)" : "var(--text)",
                            }}
                          >
                            {course.course_code}
                          </span>
                          {isLiveCourse && (
                            <span
                              style={{
                                fontSize: 10,
                                fontWeight: 800,
                                padding: "1px 6px",
                                borderRadius: 6,
                                background: "rgba(16, 185, 129, 0.2)",
                                color: "#10b981",
                              }}
                            >
                              LIVE
                            </span>
                          )}
                        </div>

                        <span
                          style={{
                            fontSize: 12,
                            fontWeight: 800,
                            color:
                              (course.average_percentage || 0) >= 75
                                ? "var(--green)"
                                : (course.average_percentage || 0) >= 60
                                ? "#f59e0b"
                                : "var(--red)",
                          }}
                        >
                          {course.average_percentage || 0}% avg
                        </span>
                      </div>

                      <div
                        style={{
                          fontSize: 13,
                          fontWeight: 600,
                          color: "var(--text)",
                          whiteSpace: "nowrap",
                          overflow: "hidden",
                          textOverflow: "ellipsis",
                        }}
                      >
                        {course.course_title}
                      </div>

                      <div
                        style={{
                          display: "flex",
                          justifyContent: "space-between",
                          alignItems: "center",
                          fontSize: 11.5,
                          color: "var(--muted)",
                          borderTop: "1px solid var(--border)",
                          paddingTop: 6,
                          marginTop: 2,
                        }}
                      >
                        <span>{course.total_sessions || 0} Sessions Held</span>
                        <span>{course.teacher_name || "Instructor"}</span>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {/* Right Column: Attendance Matrix & Roster Viewer */}
          <div
            style={{
              background: "var(--card)",
              border: "1px solid var(--border)",
              borderRadius: 18,
              padding: 18,
              display: "flex",
              flexDirection: "column",
              gap: 14,
              boxShadow: "var(--shadow)",
              minHeight: 460,
            }}
          >
            {selectedCourseRoster ? (
              <>
                <div
                  style={{
                    display: "flex",
                    justifyContent: "space-between",
                    alignItems: "flex-start",
                    flexWrap: "wrap",
                    gap: 12,
                  }}
                >
                  <div>
                    <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                      <h2 style={{ fontSize: 16, fontWeight: 800, margin: 0, color: "var(--text)" }}>
                        {selectedCourseRoster.course_code}: {selectedCourseRoster.course_title}
                      </h2>
                    </div>
                    <p style={{ margin: "2px 0 0", fontSize: 12.5, color: "var(--muted)" }}>
                      Full Attendance Matrix & Collegiate Tracking • {matrixData?.sessions.length || 0} Sessions Logged
                    </p>
                  </div>

                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <button
                      onClick={(e) => handleExport(e, selectedCourseRoster.semester_course_id)}
                      disabled={exportingCourseId === selectedCourseRoster.semester_course_id}
                      style={{
                        display: "inline-flex",
                        alignItems: "center",
                        gap: 6,
                        padding: "7px 14px",
                        borderRadius: 10,
                        border: "1px solid var(--border)",
                        background: "var(--surface)",
                        color: "var(--text)",
                        fontSize: 12,
                        fontWeight: 700,
                        cursor: exportingCourseId ? "wait" : "pointer",
                      }}
                    >
                      <FileSpreadsheet size={14} color="var(--green)" />
                      <span>{exportingCourseId ? "Exporting..." : "Export Excel (.xlsx)"}</span>
                    </button>
                  </div>
                </div>

                {/* Filter and Search Bar inside Matrix */}
                <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
                  <div
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 8,
                      flex: 1,
                      background: "var(--surface)",
                      border: "1px solid var(--border)",
                      borderRadius: 10,
                      padding: "6px 12px",
                    }}
                  >
                    <Search size={14} color="var(--muted)" />
                    <input
                      type="text"
                      placeholder="Search student roll or name..."
                      value={matrixSearch}
                      onChange={(e) => setMatrixSearch(e.target.value)}
                      style={{
                        border: "none",
                        background: "transparent",
                        outline: "none",
                        color: "var(--text)",
                        fontSize: 12.5,
                        width: "100%",
                      }}
                    />
                    {matrixSearch && (
                      <button
                        onClick={() => setMatrixSearch("")}
                        style={{ background: "none", border: "none", color: "var(--muted)", cursor: "pointer", padding: 0 }}
                      >
                        <X size={13} />
                      </button>
                    )}
                  </div>
                </div>

                {/* Matrix Grid Table */}
                {matrixLoading ? (
                  <div style={{ padding: "60px 0", textAlign: "center", color: "var(--muted)", fontSize: 13 }}>
                    Loading attendance matrix...
                  </div>
                ) : !matrixData || !matrixData.sessions.length ? (
                  <div
                    style={{
                      padding: "50px 20px",
                      textAlign: "center",
                      color: "var(--muted)",
                      background: "var(--surface)",
                      borderRadius: 14,
                      border: "1px dashed var(--border)",
                    }}
                  >
                    <Calendar size={28} style={{ margin: "0 auto 8px", opacity: 0.5 }} />
                    <div style={{ fontSize: 14, fontWeight: 700, color: "var(--text)" }}>
                      No Sessions Recorded Yet
                    </div>
                    <div style={{ fontSize: 12, marginTop: 4 }}>
                      Start an attendance session or tap cards on ESP32-READER-01 to begin.
                    </div>
                  </div>
                ) : (
                  <div
                    style={{
                      overflowX: "auto",
                      border: "1px solid var(--border)",
                      borderRadius: 12,
                      maxHeight: "520px",
                    }}
                  >
                    <table
                      style={{
                        width: "100%",
                        borderCollapse: "collapse",
                        textAlign: "left",
                        fontSize: 12,
                      }}
                    >
                      <thead>
                        <tr
                          style={{
                            background: "var(--surface)",
                            borderBottom: "1px solid var(--border)",
                            color: "var(--muted)",
                            fontWeight: 700,
                          }}
                        >
                          <th style={{ padding: "10px 12px", position: "sticky", left: 0, background: "var(--surface)", zIndex: 2 }}>
                            Roll
                          </th>
                          <th style={{ padding: "10px 12px", position: "sticky", left: 78, background: "var(--surface)", zIndex: 2 }}>
                            Student Name
                          </th>
                          {matrixData.sessions.map((sess) => (
                            <th key={sess.session_id} style={{ padding: "10px 8px", textAlign: "center", whiteSpace: "nowrap" }}>
                              {sess.date}
                            </th>
                          ))}
                          <th style={{ padding: "10px 12px", textAlign: "right" }}>%</th>
                          <th style={{ padding: "10px 12px", textAlign: "center" }}>Eligibility</th>
                        </tr>
                      </thead>
                      <tbody>
                        {filteredMatrixStudents.map((st) => {
                          const isCollegiate = st.percentage >= 75;
                          const isNonCollegiate = st.percentage >= 60 && st.percentage < 75;

                          return (
                            <tr
                              key={st.student_id}
                              style={{
                                borderBottom: "1px solid var(--border)",
                                transition: "background 0.1s ease",
                              }}
                            >
                              <td
                                style={{
                                  padding: "9px 12px",
                                  fontWeight: 800,
                                  color: "var(--text)",
                                  position: "sticky",
                                  left: 0,
                                  background: "var(--card)",
                                  zIndex: 1,
                                }}
                              >
                                {st.roll}
                              </td>
                              <td
                                style={{
                                  padding: "9px 12px",
                                  fontWeight: 600,
                                  color: "var(--text)",
                                  whiteSpace: "nowrap",
                                  position: "sticky",
                                  left: 78,
                                  background: "var(--card)",
                                  zIndex: 1,
                                }}
                              >
                                {st.full_name}
                              </td>

                              {/* Presence Cells */}
                              {matrixData.sessions.map((sess) => {
                                const rec = st.records ? st.records[sess.session_id] : null;
                                const isPresent = isRecordPresent(rec);

                                return (
                                  <td key={sess.session_id} style={{ padding: "6px 8px", textAlign: "center" }}>
                                    <span
                                      style={{
                                        display: "inline-block",
                                        width: 26,
                                        height: 26,
                                        lineHeight: "26px",
                                        borderRadius: 6,
                                        fontWeight: 800,
                                        fontSize: 11,
                                        background: isPresent ? "rgba(16, 185, 129, 0.18)" : "rgba(239, 68, 68, 0.08)",
                                        color: isPresent ? "#10b981" : "var(--muted)",
                                      }}
                                    >
                                      {isPresent ? "P" : "—"}
                                    </span>
                                  </td>
                                );
                              })}

                              {/* Percentage */}
                              <td
                                style={{
                                  padding: "9px 12px",
                                  textAlign: "right",
                                  fontWeight: 800,
                                  color: isCollegiate
                                    ? "var(--green)"
                                    : isNonCollegiate
                                    ? "#f59e0b"
                                    : "var(--red)",
                                }}
                              >
                                {st.percentage}%
                              </td>

                              {/* Collegiate Badge */}
                              <td style={{ padding: "9px 12px", textAlign: "center" }}>
                                <span
                                  style={{
                                    fontSize: 10.5,
                                    fontWeight: 700,
                                    padding: "2px 8px",
                                    borderRadius: 999,
                                    background: isCollegiate
                                      ? "rgba(16, 185, 129, 0.15)"
                                      : isNonCollegiate
                                      ? "rgba(245, 158, 11, 0.15)"
                                      : "rgba(239, 68, 68, 0.15)",
                                    color: isCollegiate
                                      ? "#10b981"
                                      : isNonCollegiate
                                      ? "#f59e0b"
                                      : "#ef4444",
                                  }}
                                >
                                  {isCollegiate ? "Collegiate" : isNonCollegiate ? "Non-Coll" : "Discoll"}
                                </span>
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                )}
              </>
            ) : (
              <div style={{ padding: "60px 0", textAlign: "center", color: "var(--muted)", fontSize: 13 }}>
                Select a course on the left to inspect its attendance matrix.
              </div>
            )}
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* 4. SUB-VIEW B: RFID CARDS DIRECTORY & ENROLLMENT */}
      {/* ========================================================================= */}
      {activeView === "cards" && (
        <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
          {/* Card Linker Bento Box */}
          <div
            style={{
              background: "var(--card)",
              border: "1px solid var(--border)",
              borderRadius: 18,
              padding: 20,
              boxShadow: "var(--shadow)",
              display: "flex",
              flexDirection: "column",
              gap: 14,
            }}
          >
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 10 }}>
              <div>
                <h2 style={{ fontSize: 16, fontWeight: 800, margin: 0, color: "var(--text)" }}>
                  Link RFID Student Card
                </h2>
                <p style={{ margin: "2px 0 0", fontSize: 12.5, color: "var(--muted)" }}>
                  Tap card on ESP32-READER-01 for auto-detection or type the 8-character hex UID manually.
                </p>
              </div>

              {detectedUid && (
                <div
                  style={{
                    display: "inline-flex",
                    alignItems: "center",
                    gap: 8,
                    padding: "6px 12px",
                    borderRadius: 999,
                    background: "rgba(16, 185, 129, 0.15)",
                    color: "#10b981",
                    fontSize: 12,
                    fontWeight: 700,
                  }}
                >
                  <Sparkles size={14} />
                  <span>Card Detected: {detectedUid}</span>
                </div>
              )}
            </div>

            {/* Registration Form */}
            <form onSubmit={handleRegisterCard} style={{ display: "flex", flexWrap: "wrap", gap: 12 }}>
              {/* Step 1: Select Student */}
              <div style={{ flex: 1, minWidth: 260, position: "relative" }}>
                <label style={{ fontSize: 11, fontWeight: 700, color: "var(--muted)", display: "block", marginBottom: 5 }}>
                  1. SELECT STUDENT (NAME OR ROLL)
                </label>
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    background: "var(--surface)",
                    border: "1px solid var(--border)",
                    borderRadius: 10,
                    padding: "8px 12px",
                  }}
                >
                  <Search size={14} color="var(--muted)" style={{ marginRight: 8 }} />
                  <input
                    type="text"
                    placeholder="Search roll or student name..."
                    value={selectedStudent ? `${selectedStudent.roll} - ${selectedStudent.full_name}` : studentSearch}
                    onChange={(e) => {
                      setSelectedStudent(null);
                      setStudentSearch(e.target.value);
                    }}
                    style={{
                      border: "none",
                      background: "transparent",
                      outline: "none",
                      color: "var(--text)",
                      fontSize: 13,
                      width: "100%",
                    }}
                  />
                  {selectedStudent && (
                    <button
                      type="button"
                      onClick={() => {
                        setSelectedStudent(null);
                        setStudentSearch("");
                      }}
                      style={{ background: "none", border: "none", color: "var(--muted)", cursor: "pointer", padding: 0 }}
                    >
                      <X size={14} />
                    </button>
                  )}
                </div>

                {/* Autocomplete Dropdown */}
                {!selectedStudent && filteredStudents.length > 0 && (
                  <div
                    style={{
                      position: "absolute",
                      top: "100%",
                      left: 0,
                      right: 0,
                      marginTop: 4,
                      background: "var(--card)",
                      border: "1px solid var(--border)",
                      borderRadius: 12,
                      boxShadow: "0 8px 24px rgba(0,0,0,0.15)",
                      zIndex: 30,
                      maxHeight: 200,
                      overflowY: "auto",
                    }}
                  >
                    {filteredStudents.map((st) => (
                      <div
                        key={st.student_id}
                        onClick={() => {
                          setSelectedStudent(st);
                          setStudentSearch("");
                        }}
                        style={{
                          padding: "10px 14px",
                          borderBottom: "1px solid var(--border)",
                          cursor: "pointer",
                          display: "flex",
                          justifyContent: "space-between",
                          alignItems: "center",
                          fontSize: 12.5,
                        }}
                      >
                        <div>
                          <strong style={{ color: "var(--text)" }}>{st.roll}</strong> - {st.full_name}
                        </div>
                        {st.rfid_card_uid ? (
                          <span style={{ fontSize: 11, color: "#10b981", fontWeight: 700 }}>
                            Bound: {st.rfid_card_uid}
                          </span>
                        ) : (
                          <span style={{ fontSize: 11, color: "var(--muted)" }}>No card</span>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Step 2: Card UID Input */}
              <div style={{ flex: 1, minWidth: 220 }}>
                <label style={{ fontSize: 11, fontWeight: 700, color: "var(--muted)", display: "block", marginBottom: 5 }}>
                  2. CARD UID (HEX)
                </label>
                <input
                  type="text"
                  placeholder="e.g. F9831C02"
                  value={cardUidInput}
                  onChange={(e) => setCardUidInput(e.target.value.toUpperCase())}
                  style={{
                    width: "100%",
                    background: "var(--surface)",
                    border: "1px solid var(--border)",
                    borderRadius: 10,
                    padding: "8px 12px",
                    color: "var(--text)",
                    fontSize: 13,
                    fontWeight: 700,
                    fontFamily: "monospace",
                    letterSpacing: "0.08em",
                    boxSizing: "border-box",
                  }}
                />
              </div>

              {/* Step 3: Submit */}
              <div style={{ display: "flex", alignItems: "flex-end" }}>
                <button
                  type="submit"
                  disabled={!selectedStudent || !cardUidInput.trim() || registeringCard}
                  style={{
                    padding: "9px 20px",
                    borderRadius: 10,
                    border: "none",
                    background: !selectedStudent || !cardUidInput.trim() ? "var(--border)" : "var(--primary)",
                    color: "#fff",
                    fontWeight: 700,
                    fontSize: 13,
                    cursor: !selectedStudent || !cardUidInput.trim() || registeringCard ? "not-allowed" : "pointer",
                    height: 38,
                    display: "inline-flex",
                    alignItems: "center",
                    gap: 6,
                    boxShadow: "0 2px 8px rgba(37,99,235,0.2)",
                  }}
                >
                  <CreditCard size={15} />
                  <span>{registeringCard ? "Linking..." : "Link Card"}</span>
                </button>
              </div>
            </form>

            {/* Success Feedback */}
            {registerSuccess && (
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 8,
                  padding: "10px 14px",
                  borderRadius: 10,
                  background: "rgba(16, 185, 129, 0.15)",
                  color: "#10b981",
                  fontSize: 13,
                  fontWeight: 600,
                }}
              >
                <CheckCircle2 size={16} />
                <span>{registerSuccess}</span>
              </div>
            )}
          </div>

          {/* Student Roster Directory Table */}
          <div
            style={{
              background: "var(--card)",
              border: "1px solid var(--border)",
              borderRadius: 18,
              padding: 18,
              boxShadow: "var(--shadow)",
              display: "flex",
              flexDirection: "column",
              gap: 12,
            }}
          >
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 10 }}>
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <h3 style={{ fontSize: 15, fontWeight: 800, margin: 0, color: "var(--text)" }}>
                  Enrolled Students Directory
                </h3>
                <span style={{ fontSize: 12, color: "var(--muted)" }}>
                  {filteredRoster.length} students shown
                </span>
              </div>

              {/* Directory Filter Pills */}
              <div style={{ display: "flex", gap: 6 }}>
                <button
                  onClick={() => setCardFilter("all")}
                  style={{
                    padding: "4px 10px",
                    borderRadius: 8,
                    fontSize: 12,
                    fontWeight: 700,
                    border: "none",
                    cursor: "pointer",
                    background: cardFilter === "all" ? "var(--primary)" : "var(--surface)",
                    color: cardFilter === "all" ? "#fff" : "var(--muted)",
                  }}
                >
                  All ({students.length})
                </button>
                <button
                  onClick={() => setCardFilter("registered")}
                  style={{
                    padding: "4px 10px",
                    borderRadius: 8,
                    fontSize: 12,
                    fontWeight: 700,
                    border: "none",
                    cursor: "pointer",
                    background: cardFilter === "registered" ? "var(--primary)" : "var(--surface)",
                    color: cardFilter === "registered" ? "#fff" : "var(--muted)",
                  }}
                >
                  Bound ({registeredCount})
                </button>
                <button
                  onClick={() => setCardFilter("unregistered")}
                  style={{
                    padding: "4px 10px",
                    borderRadius: 8,
                    fontSize: 12,
                    fontWeight: 700,
                    border: "none",
                    cursor: "pointer",
                    background: cardFilter === "unregistered" ? "#ef4444" : "var(--surface)",
                    color: cardFilter === "unregistered" ? "#fff" : "var(--muted)",
                  }}
                >
                  Missing ({unregisteredCount})
                </button>
              </div>
            </div>

            {/* Search Input */}
            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: 8,
                background: "var(--surface)",
                border: "1px solid var(--border)",
                borderRadius: 10,
                padding: "6px 12px",
              }}
            >
              <Search size={14} color="var(--muted)" />
              <input
                type="text"
                placeholder="Filter by roll, student name, or card UID..."
                value={rosterSearch}
                onChange={(e) => setRosterSearch(e.target.value)}
                style={{
                  border: "none",
                  background: "transparent",
                  outline: "none",
                  color: "var(--text)",
                  fontSize: 12.5,
                  width: "100%",
                }}
              />
              {rosterSearch && (
                <button
                  onClick={() => setRosterSearch("")}
                  style={{ background: "none", border: "none", color: "var(--muted)", cursor: "pointer", padding: 0 }}
                >
                  <X size={13} />
                </button>
              )}
            </div>

            {/* Table */}
            <div style={{ overflowX: "auto", border: "1px solid var(--border)", borderRadius: 12 }}>
              <table style={{ width: "100%", borderCollapse: "collapse", textAlign: "left", fontSize: 12.5 }}>
                <thead>
                  <tr style={{ background: "var(--surface)", borderBottom: "1px solid var(--border)", color: "var(--muted)" }}>
                    <th style={{ padding: "10px 14px", fontWeight: 700 }}>Roll</th>
                    <th style={{ padding: "10px 14px", fontWeight: 700 }}>Student Name</th>
                    <th style={{ padding: "10px 14px", fontWeight: 700 }}>RFID Card Status</th>
                    <th style={{ padding: "10px 14px", textAlign: "right", fontWeight: 700 }}>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredRoster.map((st) => (
                    <tr key={st.student_id} style={{ borderBottom: "1px solid var(--border)" }}>
                      <td style={{ padding: "10px 14px", fontWeight: 800, color: "var(--text)" }}>
                        {st.roll}
                      </td>
                      <td style={{ padding: "10px 14px", fontWeight: 600, color: "var(--text)" }}>
                        {st.full_name}
                      </td>
                      <td style={{ padding: "10px 14px" }}>
                        {st.rfid_card_uid ? (
                          <div style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
                            <span
                              style={{
                                fontFamily: "monospace",
                                fontWeight: 800,
                                fontSize: 12,
                                padding: "2px 8px",
                                borderRadius: 6,
                                background: "rgba(16, 185, 129, 0.15)",
                                color: "#10b981",
                                letterSpacing: "0.06em",
                              }}
                            >
                              {st.rfid_card_uid}
                            </span>
                            <button
                              onClick={() => handleCopyUid(st.rfid_card_uid!)}
                              title="Copy Card UID"
                              style={{ background: "none", border: "none", color: "var(--muted)", cursor: "pointer", padding: 2 }}
                            >
                              <Copy size={13} />
                            </button>
                            {copiedUid === st.rfid_card_uid && (
                              <span style={{ fontSize: 10, color: "var(--green)" }}>Copied!</span>
                            )}
                          </div>
                        ) : (
                          <span
                            style={{
                              fontSize: 11,
                              fontWeight: 700,
                              padding: "2px 8px",
                              borderRadius: 6,
                              background: "rgba(239, 68, 68, 0.12)",
                              color: "#ef4444",
                            }}
                          >
                            NO CARD BOUND
                          </span>
                        )}
                      </td>
                      <td style={{ padding: "10px 14px", textAlign: "right" }}>
                        <button
                          onClick={() => {
                            setSelectedStudent(st);
                            setCardUidInput(st.rfid_card_uid || "");
                            window.scrollTo({ top: 0, behavior: "smooth" });
                          }}
                          style={{
                            padding: "4px 10px",
                            borderRadius: 6,
                            fontSize: 11.5,
                            fontWeight: 700,
                            border: "1px solid var(--border)",
                            background: "var(--surface)",
                            color: "var(--primary)",
                            cursor: "pointer",
                          }}
                        >
                          {st.rfid_card_uid ? "Change Card" : "Assign Card"}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* 5. MODAL: TAKE ATTENDANCE (AD-HOC COURSE PICKER) */}
      {/* ========================================================================= */}
      {takeModalOpen && (
        <div
          style={{
            position: "fixed",
            inset: 0,
            background: "rgba(0, 0, 0, 0.65)",
            backdropFilter: "blur(4px)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            padding: 16,
            zIndex: 100,
          }}
        >
          <div
            style={{
              background: "var(--card)",
              border: "1px solid var(--border)",
              borderRadius: 20,
              padding: 24,
              maxWidth: 440,
              width: "100%",
              boxShadow: "0 20px 40px rgba(0,0,0,0.3)",
              display: "flex",
              flexDirection: "column",
              gap: 16,
            }}
          >
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <Play size={18} color="var(--primary)" fill="var(--primary)" />
                <h3 style={{ fontSize: 17, fontWeight: 800, margin: 0, color: "var(--text)" }}>
                  Start Attendance Session
                </h3>
              </div>
              <button
                onClick={() => setTakeModalOpen(false)}
                style={{ background: "none", border: "none", color: "var(--muted)", cursor: "pointer", padding: 0 }}
              >
                <X size={18} />
              </button>
            </div>

            <p style={{ margin: 0, fontSize: 13, color: "var(--muted)", lineHeight: 1.5 }}>
              Choose a course to begin attendance. The station <strong>ESP32-READER-01</strong> will immediately download the roster cache and display the course title for student card tapping.
            </p>

            <div>
              <label style={{ fontSize: 11.5, fontWeight: 700, color: "var(--muted)", display: "block", marginBottom: 6 }}>
                SELECT COURSE
              </label>
              <select
                value={selectedCourseForStart}
                onChange={(e) => setSelectedCourseForStart(e.target.value)}
                style={{
                  width: "100%",
                  padding: "10px 14px",
                  borderRadius: 10,
                  border: "1px solid var(--border)",
                  background: "var(--surface)",
                  color: "var(--text)",
                  fontSize: 13,
                  fontWeight: 600,
                  outline: "none",
                }}
              >
                <option value="">-- Select Course Offering --</option>
                {courses.map((c) => (
                  <option key={c.semester_course_id} value={c.semester_course_id}>
                    {c.course_code} - {c.course_title}
                  </option>
                ))}
              </select>
            </div>

            <div style={{ display: "flex", gap: 10, marginTop: 4 }}>
              <button
                type="button"
                onClick={() => setTakeModalOpen(false)}
                style={{
                  flex: 1,
                  padding: "10px 16px",
                  borderRadius: 10,
                  border: "1px solid var(--border)",
                  background: "var(--surface)",
                  color: "var(--text)",
                  fontWeight: 700,
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
                  borderRadius: 10,
                  border: "none",
                  background: !selectedCourseForStart ? "var(--border)" : "var(--primary)",
                  color: "#fff",
                  fontWeight: 700,
                  fontSize: 13,
                  cursor: !selectedCourseForStart || startingSession ? "not-allowed" : "pointer",
                  boxShadow: "0 2px 10px rgba(37,99,235,0.3)",
                }}
              >
                {startingSession ? "Starting..." : "Begin Session"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

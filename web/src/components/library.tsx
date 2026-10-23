"use client";
import { useState, useEffect } from "react";
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  Search,
  Plus,
  FileText,
  ChevronRight,
  Upload,
  MoreVertical,
  Trash2,
  BookOpen,
  ArrowLeft,
  Presentation,
  HelpCircle,
  GraduationCap,
  ExternalLink,
  Link2,
  Radio,
  CheckCircle2,
  XCircle,
  Clock,
  X,
  AlertCircle,
  AlertTriangle,
} from "lucide-react";
import { rpc, supabase, edge, openFile, uploadFile, type Row } from "@/lib/api";
import { type R2StorageStats, formatBytes } from "./r2-storage-modal";
import type { Context } from "./app";
import { Confirm, Empty, ErrorBox, Form, Modal, Skeleton } from "./ui";

function GoogleDriveIcon({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 87.3 78" fill="none" style={{ display: "inline-block", verticalAlign: "middle" }}>
      <path d="m6.6 66.85 3.85 6.65c.8 1.4 1.95 2.5 3.3 3.3l13.75-23.8H0c0 1.55.4 3.1 1.2 4.5z" fill="#0066DA" />
      <path d="m43.65 25-13.75-23.8c-1.35.8-2.5 1.9-3.3 3.3l-25.4 44c-.8 1.4-1.2 2.95-1.2 4.5h27.5z" fill="#00AC47" />
      <path d="m73.55 76.8c1.35-.8 2.5-1.9 3.3-3.3l1.6-2.75 7.65-13.25c.8-1.4 1.2-2.95 1.2-4.5H59.8l6.1 10.55z" fill="#EA4335" />
      <path d="m43.65 25 13.75-23.8c-1.35-.8-2.9-1.2-4.5-1.2h-18.5c-1.6 0-3.15.45-4.5 1.2z" fill="#00832D" />
      <path d="m59.8 53H27.5L13.75 76.8c1.35.8 2.9 1.2 4.5 1.2h50.8c1.6 0 3.15-.4 4.5-1.2z" fill="#2684FC" />
      <path d="m73.4 26.5-12.7-22c-.8-1.4-1.95-2.5-3.3-3.3L43.65 25l16.15 28h27.5c0-1.55-.4-3.1-1.2-4.5z" fill="#FFBA00" />
    </svg>
  );
}

interface AttendanceHistoryItem {
  session_id: string;
  date: string;
  time?: string;
  room?: string;
  status: "present" | "absent";
  method?: "rfid" | "manual";
  marked_at?: string;
}

interface StudentAttendanceCourse {
  semester_course_id: string;
  course_code: string;
  course_title: string;
  course_type: string;
  credit?: number;
  teacher_name?: string;
  attendance_enabled?: boolean;
  classes_held: number;
  classes_attended: number;
  percentage: number;
  history: AttendanceHistoryItem[];
}

interface StudentAttendanceSummary {
  courses: StudentAttendanceCourse[];
  total_classes_held: number;
  total_classes_attended: number;
  overall_percentage: number;
}

export function Library({ ctx }: { ctx: Context }) {
  const [type, setType] = useState("theory"),
    [search, setSearch] = useState(""),
    [searchOpen, setSearchOpen] = useState(false),
    [course, setCourse] = useState(""),
    [upload, setUpload] = useState(false),
    [allFiles, setAllFiles] = useState(false),
    [editing, setEditing] = useState<Row | null>(null),
    [deleting, setDeleting] = useState<Row | null>(null),
    [error, setError] = useState<unknown>(null),
    [attendanceModalOpen, setAttendanceModalOpen] = useState(false),
    [selectedAttendanceCourse, setSelectedAttendanceCourse] = useState<StudentAttendanceCourse | null>(null);
  const qc = useQueryClient();

  const myAttendance = useQuery({
    queryKey: [ctx.user, ctx.batch, "student_my_attendance"],
    queryFn: async () => {
      try {
        const data = await rpc<StudentAttendanceSummary>("student_my_attendance", {
          target_batch: ctx.batch,
        });
        return data || { courses: [], total_classes_held: 0, total_classes_attended: 0, overall_percentage: 0 };
      } catch {
        return { courses: [], total_classes_held: 0, total_classes_attended: 0, overall_percentage: 0 };
      }
    },
  });

  const activeRfidCourses = myAttendance.data?.courses || [];

  useEffect(() => {
    const restore = () => {
      const match = location.hash.match(/^#(?:courses|library)\/course\/([0-9a-f-]{36})$/i);
      setCourse(match && ctx.courses.some(c => c.offering_id === match[1]) ? match[1] : "");
    };
    restore();
    window.addEventListener("hashchange", restore);
    window.addEventListener("popstate", restore);
    return () => { window.removeEventListener("hashchange", restore); window.removeEventListener("popstate", restore); };
  }, [ctx.batch, ctx.courses]);
  const selectedCourse = ctx.courses.find(c => c.offering_id === course);
  function openCourse(id: string) {
    setSearch(""); setCourse(id);
    history.pushState({classmateCourse:true}, "", `#courses/course/${id}`);
  }
  function closeCourse() {
    if (history.state?.classmateCourse) history.back();
    else { history.replaceState(null, "", "#courses"); setCourse(""); }
  }
  const permissions = useQuery({
    queryKey: [
      ctx.user,
      ctx.batch,
      "file-permissions",
      ctx.courses.map((c) => c.offering_id).join(","),
    ],
    queryFn: async () => {
      const general = await rpc<boolean>("can_post", {
        target_batch: ctx.batch,
        target_course: null,
      });
      return {
        general,
        courses: Object.fromEntries(
          await Promise.all(
            ctx.courses.map(
              async (c) =>
                [
                  c.offering_id,
                  await rpc<boolean>("can_post", {
                    target_batch: ctx.batch,
                    target_course: c.offering_id,
                  }),
                ] as const,
            ),
          ),
        ),
      };
    },
  });
  const files = useInfiniteQuery({
    queryKey: [ctx.user, ctx.batch, "files", course, search],
    initialPageParam: 0,
    queryFn: async ({ pageParam }) => {
      let q = supabase()
        .schema("classmate")
        .from("file_metadata")
        .select(
          "id,title,category,semester_course_id,size_bytes,mime_type,uploaded_by,created_at,link_url,file_type",
        )
        .eq("batch_id", ctx.batch)
        .eq("status", "active")
        .order("created_at", { ascending: false })
        .order("id")
        .range(pageParam, pageParam + 49);
      if (course) q = q.eq("semester_course_id", course);
      if (search.trim())
        q = q.ilike("title", `%${search.replace(/[%_]/g, "")}%`);
      const { data, error } = await q;
      if (error) throw error;
      return data || [];
    },
    getNextPageParam: (last, pages) =>
      last.length === 50 ? pages.length * 50 : undefined,
  });
  const entries = files.data?.pages.flat() || [];
  const refresh = () =>
    qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch] });

  const isDriveLink = (url?: string, f?: Row) =>
    Boolean(
      (url && (/drive\.google\.com/i.test(url) || /docs\.google\.com/i.test(url) || /google\.com\/drive/i.test(url) || /drive\.link/i.test(url))) ||
      f?.provider === "drive"
    );

  const isExternal = (f: Row) =>
    Boolean(f.link_url || f.file_type === "link");

  const renderFiles = (items: Row[]) =>
    items.map((f) => {
      const external = isExternal(f);
      const isDrive = isDriveLink(f.link_url, f);
      const fileIconClass = isDrive
        ? "file-icon-drive"
        : external
        ? "file-icon-link"
        : "";

      const fileIconNode = isDrive ? (
        <GoogleDriveIcon size={20} />
      ) : external ? (
        <ExternalLink size={20} />
      ) : (
        <FileText size={20} />
      );

      return (
        <div className={`card file-row ${external ? "file-row-link" : ""}`} key={f.id}>
          <button
            className="file-open"
            onClick={() => openFile(f).catch(setError)}
          >
            <span className={`file-icon ${fileIconClass}`}>
              {fileIconNode}
            </span>
            <span>
              <strong className="file-title-row">
                {f.title}
                {isDrive && <span className="drive-badge">Drive</span>}
                {external && !isDrive && <span className="drive-badge external-badge">Link</span>}
              </strong>
              <small>
                {external ? (isDrive ? "Google Drive" : "External link") : `${Math.ceil(f.size_bytes / 1024)} KB`}
              </small>
            </span>
          </button>
          {(ctx.owner ||
            (ctx.profile.is_cr && ctx.profile.cr_batch_id === ctx.batch)) && (
            <button
              className="icon"
              aria-label="Edit file"
              onClick={() => setEditing(f)}
            >
              <MoreVertical size={20} />
            </button>
          )}
          {!ctx.owner &&
            !ctx.profile.is_cr &&
            (permissions.data?.courses[f.semester_course_id] ||
              (!f.semester_course_id && permissions.data?.general)) && (
              <button
                className="icon danger"
                aria-label="Delete file"
                onClick={() => setDeleting(f)}
              >
                <Trash2 size={19} />
              </button>
            )}
        </div>
      );
    });
  return (
    <div
      className={`library-screen ${selectedCourse ? "course-files-page" : ""}`}
    >
      <div className="page-heading">
        <div>
          {selectedCourse ? (
            <button className="inline" onClick={closeCourse}>
              <ArrowLeft size={18} /> Back to Courses
            </button>
          ) : (
            <span className="eyebrow">COURSES & RESOURCES</span>
          )}
          <h1>{selectedCourse?.course_title || "Courses"}</h1>
          <p>
            {selectedCourse && <span>{selectedCourse.course_code} · </span>}{entries.length}
            {files.hasNextPage ? "+" : ""}{" "}
            {entries.length === 1 ? "file" : "files"} shown
          </p>
        </div>
        <div className="library-heading-actions">
          <button
            type="button"
            className="icon library-search-toggle"
            aria-label="Search courses and files"
            aria-expanded={searchOpen}
            onClick={() => setSearchOpen(!searchOpen)}
          >
            <Search size={20} />
          </button>
          {(permissions.data?.general ||
            Object.values(permissions.data?.courses || {}).some(Boolean)) && (
            <button className="primary" onClick={() => setUpload(true)}>
              <Plus size={18} />
              <span>Add file</span>
            </button>
          )}
        </div>
      </div>
      <label className={`search library-search ${searchOpen || search ? "open" : ""}`}>
        <Search size={18} />
        <input
          value={search}
          maxLength={100}
          aria-label="Search courses and files"
          placeholder="Search files and courses"
          onChange={(e) => setSearch(e.target.value)}
        />
      </label>
      {!selectedCourse && (
        <button
          type="button"
          className="attendance-hero-badge"
          onClick={() => {
            setAttendanceModalOpen(true);
            setSelectedAttendanceCourse(null);
          }}
        >
          <div className="attendance-hero-icon-box">
            <Radio size={22} />
          </div>
          <div className="attendance-hero-info">
            <div className="attendance-hero-header">
              <span className="attendance-hero-title">My Attendance</span>
              <span className="attendance-hero-live-dot" />
            </div>
            <p className="attendance-hero-sub">
              {activeRfidCourses.length > 0
                ? `${activeRfidCourses.length} ${activeRfidCourses.length === 1 ? "Course" : "Courses"}`
                : "No courses"}
            </p>
          </div>
          <div className="attendance-hero-action">
            <span>Logs</span>
            <ChevronRight size={16} />
          </div>
        </button>
      )}

      {!selectedCourse && <section className="library-courses">
        <div className="section-heading">
          <h2>Courses</h2>
          <small>
            {ctx.courses.length}{" "}
            {ctx.courses.length === 1 ? "course" : "courses"}
          </small>
        </div>
        <div className="segment">
          <button
            className={type === "theory" ? "selected" : ""}
            onClick={() => {
              setType("theory");
              setCourse("");
            }}
          >
            Theory
          </button>
          <button
            className={type === "lab" ? "selected" : ""}
            onClick={() => {
              setType("lab");
              setCourse("");
            }}
          >
            Labs
          </button>
        </div>
        <div className="course-grid">
            {ctx.courses
              .filter((c) => {
                const matchesType = c.course_type === type;
                const q = search.trim().toLowerCase();
                const matchesSearch =
                  !q ||
                  (c.course_title && c.course_title.toLowerCase().includes(q)) ||
                  (c.course_code && c.course_code.toLowerCase().includes(q));
                return matchesType && matchesSearch;
              })
              .map((c) => (
                <button
                  className={`card course-tile ${course === c.offering_id ? "active" : ""}`}
                  key={c.offering_id}
                  onClick={() => openCourse(c.offering_id)}
                >
                  <BookOpen size={23} />
                  <span>
                    <strong>{c.course_title}</strong>
                    <small>{c.course_code}</small>
                  </span>
                  <ChevronRight size={17} />
                </button>
              ))}
          </div>
      </section>}
      <section className="library-files">
        <div className="section-heading">
          <h2>
            {course ? "Course files" : "Recently shared"}
          </h2>
          {!course && (
            <button className="inline" onClick={() => setAllFiles(true)}>
              All files <ChevronRight size={13} />
            </button>
          )}
        </div>
        <ErrorBox error={error || files.error} retry={() => files.refetch()} />
        {files.isPending ? (
          <Skeleton />
        ) : !entries.length ? (
          <Empty
            title="No files here yet"
            body="Shared resources will appear as they are uploaded."
          />
        ) : (
          <div className="file-list">
            {renderFiles(
              course ? entries : entries.slice(0, 3),
            )}
          </div>
        )}
        {files.hasNextPage && (
          <button
            disabled={files.isFetching}
            onClick={() => files.fetchNextPage()}
          >
            Load more files
          </button>
        )}
      </section>
      {allFiles && (
        <Modal title="Shared files" close={() => setAllFiles(false)}>
          <div className="file-list">{renderFiles(entries)}</div>
          {files.hasNextPage && (
            <button
              onClick={() => files.fetchNextPage()}
              disabled={files.isFetching}
            >
              Load more files
            </button>
          )}
        </Modal>
      )}
      {upload && (
        <Modal title="Share a resource" close={() => setUpload(false)}>
          <UploadForm
            ctx={ctx}
            permissions={permissions.data}
            defaultCourse={course || undefined}
            done={async () => {
              await refresh();
              setUpload(false);
            }}
          />
        </Modal>
      )}
      {editing && (
        <Modal title="Edit resource" close={() => setEditing(null)}>
          <Form
            fields={[
              {
                name: "target_title",
                label: "Resource title",
                required: true,
                value: editing.title,
              },
              ...(editing.link_url || editing.file_type === "link"
                ? [
                    {
                      name: "target_link_url",
                      label: "Google Drive or Resource link",
                      required: true,
                      value: editing.link_url || "",
                    },
                  ]
                : []),
              {
                name: "target_course",
                label: "Subject",
                value:
                  editing.semester_course_id || ctx.courses[0]?.offering_id,
                required: true,
                options: ctx.courses.map((c) => ({
                  value: c.offering_id,
                  label: c.course_title,
                })),
              },
            ]}
            submit={async (d) => {
              await rpc("edit_resource_metadata", {
                target_resource: editing.id,
                target_title: d.target_title,
                target_category: editing.category || "notes",
                target_course: d.target_course || null,
                target_link_url: d.target_link_url || null,
              });
              await refresh();
              setEditing(null);
            }}
          />
          <button
            className="text-button danger"
            onClick={() => {
              setDeleting(editing);
              setEditing(null);
            }}
          >
            Delete permanently
          </button>
        </Modal>
      )}
      {deleting && (
        <Confirm
          title="Permanently delete file?"
          body="The file and its academic entry will no longer be available."
          close={() => setDeleting(null)}
          action={async () => {
            if (deleting.link_url || deleting.file_type === "link") {
              await rpc("delete_library_resource", {
                target_resource: deleting.id,
              });
            } else {
              try {
                await edge("upload-classmate-resource", {
                  action: "delete",
                  resource_id: deleting.id,
                });
              } catch {
                await rpc("delete_library_resource", {
                  target_resource: deleting.id,
                });
              }
            }
            await refresh();
          }}
        />
      )}
      {attendanceModalOpen && (
        <div
          className="attendance-bottom-drawer-backdrop"
          onClick={() => {
            setAttendanceModalOpen(false);
            setSelectedAttendanceCourse(null);
          }}
        >
          <div
            className={`attendance-bottom-drawer ${
              selectedAttendanceCourse ? "expanded-record-mode" : "course-picker-mode"
            }`}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="attendance-drawer-handle" />

            {!selectedAttendanceCourse ? (
              <div className="attendance-drawer-courses-view">
                <div className="attendance-drawer-header">
                  <div className="attendance-drawer-title-group">
                    <div className="attendance-drawer-icon-box">
                      <Radio size={20} />
                    </div>
                    <div>
                      <h3>My Attendance</h3>
                      <p>Select a course to view previous records</p>
                    </div>
                  </div>
                  <button
                    type="button"
                    className="attendance-drawer-close-btn"
                    onClick={() => {
                      setAttendanceModalOpen(false);
                      setSelectedAttendanceCourse(null);
                    }}
                  >
                    <X size={18} />
                  </button>
                </div>

                <div className="attendance-drawer-course-list">
                  {activeRfidCourses.length === 0 ? (
                    <Empty
                      title="No Attendance Sessions Yet"
                      body="Attendance logs will appear here once an attendance session has been conducted."
                    />
                  ) : (
                    activeRfidCourses.map((c) => (
                      <button
                        key={c.semester_course_id}
                        type="button"
                        className="rfid-course-card"
                        onClick={() => setSelectedAttendanceCourse(c)}
                      >
                        <div className="rfid-course-header">
                          <span className="rfid-course-type">{c.course_type?.toUpperCase()}</span>
                          <span style={{ fontSize: "11px", fontWeight: 700, color: c.percentage >= 75 ? "var(--green)" : "var(--amber)" }}>
                            {c.classes_attended}/{c.classes_held} ({c.percentage}%)
                          </span>
                        </div>
                        <strong className="rfid-course-title">{c.course_title}</strong>
                        <div className="rfid-course-footer">
                          <span>{c.course_code}</span>
                          <span className="rfid-view-log">
                            View Daily Log <ChevronRight size={14} />
                          </span>
                        </div>
                      </button>
                    ))
                  )}
                </div>
              </div>
            ) : (
              <div className="attendance-drawer-table-view">
                <div className="attendance-drawer-table-header">
                  <button
                    type="button"
                    className="attendance-drawer-back-btn"
                    onClick={() => setSelectedAttendanceCourse(null)}
                  >
                    <ArrowLeft size={16} /> Courses
                  </button>
                  <div className="attendance-drawer-course-heading">
                    <h4>{selectedAttendanceCourse.course_title}</h4>
                    <p>{selectedAttendanceCourse.course_code} · Daily Attendance Log</p>
                  </div>
                  <button
                    type="button"
                    className="attendance-drawer-close-btn"
                    onClick={() => {
                      setAttendanceModalOpen(false);
                      setSelectedAttendanceCourse(null);
                    }}
                  >
                    <X size={18} />
                  </button>
                </div>

                {selectedAttendanceCourse.history.length === 0 ? (
                  <Empty
                    title="No History Records"
                    body="No individual class session logs found for this course."
                  />
                ) : (
                  <div className="attendance-table-wrap">
                    <table className="attendance-daily-table">
                      <thead>
                        <tr>
                          <th>Date</th>
                          <th>Class</th>
                          <th>Status</th>
                          <th>Time</th>
                        </tr>
                      </thead>
                      <tbody>
                        {selectedAttendanceCourse.history.map((row, idx) => {
                          const isPresent = row.status === "present";
                          const dateObj = new Date(row.date);
                          const isValidDate = !isNaN(dateObj.getTime());
                          const formattedDate = isValidDate
                            ? new Intl.DateTimeFormat("en-US", {
                                month: "short",
                                day: "numeric",
                                year: "numeric",
                              }).format(dateObj)
                            : row.date;
                          const formattedDay = isValidDate
                            ? new Intl.DateTimeFormat("en-US", {
                                weekday: "short",
                              }).format(dateObj)
                            : "";

                          return (
                            <tr key={row.session_id || idx}>
                              <td className="attendance-col-date">
                                <strong>{formattedDate}</strong>
                                {formattedDay && <small>{formattedDay}</small>}
                              </td>
                              <td>Class #{selectedAttendanceCourse.history.length - idx}</td>
                              <td>
                                <span
                                  className={`attendance-status-pill ${
                                    isPresent ? "present" : "absent"
                                  }`}
                                >
                                  {isPresent ? (
                                    <CheckCircle2 size={13} />
                                  ) : (
                                    <XCircle size={13} />
                                  )}
                                  {isPresent ? "Present" : "Absent"}
                                </span>
                              </td>
                              <td className="attendance-col-time">
                                {isPresent ? (
                                  <span>{row.time || "Logged"}</span>
                                ) : (
                                  <span className="text-muted">—</span>
                                )}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function UploadForm({
  ctx,
  permissions,
  defaultCourse,
  done,
}: {
  ctx: Context;
  permissions?: Row;
  defaultCourse?: string;
  done: () => Promise<void>;
}) {
  const [tab, setTab] = useState<"file" | "link">("file");
  const [file, setFile] = useState<File | null>(null);

  const storageQuery = useQuery({
    queryKey: ["r2-storage-stats"],
    queryFn: () => rpc<R2StorageStats>("get_r2_storage_stats"),
    staleTime: 30_000,
  });
  const isStorageExceeded = storageQuery.data?.status === "exceeded";
  const isStorageWarning = storageQuery.data?.status === "warning";

  return (
    <div className="upload-form-wrapper">
      <div className="segment upload-mode-toggle" style={{ marginBottom: "1rem" }}>
        <button
          type="button"
          className={tab === "file" ? "selected" : ""}
          onClick={() => setTab("file")}
        >
          <Upload size={14} style={{ marginRight: "6px" }} />
          Upload File
        </button>
        <button
          type="button"
          className={tab === "link" ? "selected" : ""}
          onClick={() => setTab("link")}
        >
          <ExternalLink size={14} style={{ marginRight: "6px" }} />
          Drive Link
        </button>
      </div>

      {tab === "file" && isStorageExceeded ? (
        <div style={{ padding: "1.25rem", background: "rgba(239, 68, 68, 0.1)", border: "1px solid #ef4444", borderRadius: "12px", textAlign: "center", marginBottom: "1rem" }}>
          <AlertCircle size={28} style={{ color: "#ef4444", margin: "0 auto 8px" }} />
          <div style={{ fontWeight: 700, color: "var(--text)", marginBottom: "4px" }}>
            Storage Quota Exceeded (9.9 GB Limit)
          </div>
          <div style={{ fontSize: "12.5px", color: "var(--muted)", lineHeight: 1.5 }}>
            Cloudflare R2 free tier storage is at capacity. Direct file uploads are paused.
            You can still share resources without limits using <strong>Drive Link</strong>!
          </div>
          <button
            type="button"
            className="primary"
            style={{ marginTop: "12px", width: "100%" }}
            onClick={() => setTab("link")}
          >
            Switch to Drive Link
          </button>
        </div>
      ) : tab === "file" ? (
        <div>
          {isStorageWarning && storageQuery.data && (
            <div style={{ padding: "0.6rem 0.85rem", background: "rgba(245, 158, 11, 0.1)", border: "1px solid #f59e0b", borderRadius: "8px", fontSize: "11.5px", color: "var(--text)", marginBottom: "0.75rem", display: "flex", alignItems: "center", gap: "6px" }}>
              <AlertTriangle size={14} style={{ color: "#f59e0b", flexShrink: 0 }} />
              <span>Storage notice: Cloudflare R2 is at <strong>{(storageQuery.data.total_bytes / (1024**3)).toFixed(2)} GB</strong> (approaching 9.9 GB limit).</span>
            </div>
          )}
          <Form
            label="Upload file"
            fields={[
              {
                name: "course",
                label: "Subject",
                value: defaultCourse || undefined,
                required: !permissions?.general,
                options: ctx.courses
                  .filter((c) => permissions?.courses[c.offering_id])
                  .map((c) => ({ value: c.offering_id, label: c.course_title })),
              },
              { name: "title", label: "File name", required: true },
            ]}
            extra={
              <label className="upload-area">
                <Upload />
                <strong>{file?.name || "Choose a file"}</strong>
                <small>Up to 500 MB · shared with your batch</small>
                <input
                  type="file"
                  required
                  onChange={(e) => setFile(e.target.files?.[0] || null)}
                />
              </label>
            }
            submit={async (d) => {
              if (!file) throw new Error("Choose a file.");
              if (file.size < 1 || file.size > 500_000_000)
                throw new Error("Choose a file smaller than 500 MB.");
              if (isStorageExceeded) {
                throw new Error("Storage quota exceeded (9.9 GB limit). Please share via Drive Link.");
              }
              if (storageQuery.data && (storageQuery.data.total_bytes + file.size > 10_630_044_057)) {
                throw new Error(`This file (${(file.size / (1024 * 1024)).toFixed(1)} MB) exceeds the remaining 9.9 GB storage quota.`);
              }
              await uploadFile(file, {
                batch_id: ctx.batch,
                semester_course_id: d.course || null,
                title: d.title,
                category: "notes",
              });
              await done();
            }}
          />
        </div>
      ) : (
        <Form
          label="Share Drive Link"
          fields={[
            {
              name: "title",
              label: "Resource Title",
              required: true,
              placeholder: "e.g. Lecture Slides / Lab Manual",
            },
            {
              name: "url",
              label: "Google Drive or Resource Link",
              required: true,
              placeholder: "https://drive.google.com/...",
            },
            {
              name: "course",
              label: "Subject",
              value: defaultCourse || undefined,
              required: !permissions?.general,
              options: ctx.courses
                .filter((c) => permissions?.courses[c.offering_id])
                .map((c) => ({ value: c.offering_id, label: c.course_title })),
            },
          ]}
          submit={async (d) => {
            const rawUrl = (d.url || "").trim();
            if (!rawUrl) throw new Error("Please enter a Google Drive or resource link.");
            const urlWithScheme = /^https?:\/\//i.test(rawUrl) ? rawUrl : `https://${rawUrl}`;
            try {
              new URL(urlWithScheme);
            } catch {
              throw new Error("Please enter a valid link URL (e.g. https://drive.google.com/...)");
            }
            await rpc("create_library_link", {
              target_batch: ctx.batch,
              target_course: d.course || null,
              target_title: d.title.trim(),
              target_category: "notes",
              target_url: urlWithScheme,
            });
            await done();
          }}
        />
      )}
    </div>
  );
}

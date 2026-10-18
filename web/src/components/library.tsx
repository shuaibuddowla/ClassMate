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
  ExternalLink,
  Link2,
  Radio,
  CheckCircle2,
  XCircle,
  Clock,
} from "lucide-react";
import { rpc, supabase, edge, openFile, uploadFile, type Row } from "@/lib/api";
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

const categories = ["notes", "slides", "questions", "syllabus", "other"];
const SKELETON_ATTENDANCE_ROWS = [
  { date: "Oct 19, 2026", day: "Monday", classNo: "14", status: "Present", punchTime: "10:04 AM", room: "Room 304 (AB-2)" },
  { date: "Oct 14, 2026", day: "Wednesday", classNo: "13", status: "Present", punchTime: "10:01 AM", room: "Room 304 (AB-2)" },
  { date: "Oct 12, 2026", day: "Monday", classNo: "12", status: "Absent", punchTime: "", room: "" },
  { date: "Oct 07, 2026", day: "Wednesday", classNo: "11", status: "Present", punchTime: "09:58 AM", room: "Room 304 (AB-2)" },
  { date: "Oct 05, 2026", day: "Monday", classNo: "10", status: "Present", punchTime: "10:05 AM", room: "Room 304 (AB-2)" },
];

export function Library({ ctx }: { ctx: Context }) {
  const [type, setType] = useState("theory"),
    [search, setSearch] = useState(""),
    [category, setCategory] = useState("all"),
    [course, setCourse] = useState(""),
    [upload, setUpload] = useState(false),
    [allFiles, setAllFiles] = useState(false),
    [editing, setEditing] = useState<Row | null>(null),
    [deleting, setDeleting] = useState<Row | null>(null),
    [error, setError] = useState<unknown>(null),
    [attendanceModalOpen, setAttendanceModalOpen] = useState(false),
    [selectedAttendanceCourse, setSelectedAttendanceCourse] = useState<Row | null>(null);
  const qc = useQueryClient();

  const activeRfidCourses = ctx.courses.length > 0 
    ? ctx.courses.slice(0, Math.min(ctx.courses.length, 2))
    : [
        { offering_id: "demo-1", course_title: "Computer Organization & Architecture", course_code: "CSE 2105", course_type: "theory" },
        { offering_id: "demo-2", course_title: "Data Structures & Algorithms Lab", course_code: "CSE 2102", course_type: "lab" },
      ];

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
    setCategory("all"); setSearch(""); setCourse(id);
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
    queryKey: [ctx.user, ctx.batch, "files", course, category, search],
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
      if (category !== "all") q = q.eq("category", category);
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

  const isDriveLink = (url?: string) =>
    Boolean(url && (/drive\.google\.com/i.test(url) || /docs\.google\.com/i.test(url)));

  const isExternal = (f: Row) =>
    Boolean(f.link_url || f.file_type === "link");

  const renderFiles = (items: Row[]) =>
    items.map((f) => {
      const external = isExternal(f);
      const isDrive = isDriveLink(f.link_url);
      return (
        <div className={`card file-row ${external ? "file-row-link" : ""}`} key={f.id}>
          <button
            className="file-open"
            onClick={() => openFile(f).catch(setError)}
          >
            <span className={`file-icon ${isDrive ? "file-icon-drive" : external ? "file-icon-link" : ""}`}>
              {isDrive ? <GoogleDriveIcon size={20} /> : external ? <ExternalLink size={20} /> : <FileText />}
            </span>
            <span>
              <strong className="file-title-row">
                {f.title}
                {isDrive && <span className="drive-badge">Drive</span>}
                {external && !isDrive && <span className="drive-badge external-badge">Link</span>}
              </strong>
              <small>
                {f.category} · {external ? (isDrive ? "Google Drive" : "External link") : `${Math.ceil(f.size_bytes / 1024)} KB`}
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
      className={`library-screen ${selectedCourse ? "course-files-page" : ""} ${type === "syllabus" ? "syllabus-view" : ""}`}
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
        {(permissions.data?.general ||
          Object.values(permissions.data?.courses || {}).some(Boolean)) && (
          <button className="primary" onClick={() => setUpload(true)}>
            <Plus size={18} />
            Add file
          </button>
        )}
      </div>
      <label className="search">
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
              {activeRfidCourses.length} {activeRfidCourses.length === 1 ? "Course" : "Courses"} Active for RFID Tracking · Tap to view
            </p>
          </div>
          <div className="attendance-hero-action">
            <span>Logs</span>
            <ChevronRight size={16} />
          </div>
        </button>
      )}
      <div className="category-list">
        {["all", ...categories].map((c) => (
          <button
            className={category === c ? "selected" : ""}
            key={c}
            onClick={() => setCategory(c)}
          >
            {c}
          </button>
        ))}
      </div>
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
              if (type === "syllabus") setCategory("all");
              setType("theory");
              setCourse("");
            }}
          >
            Theory
          </button>
          <button
            className={type === "lab" ? "selected" : ""}
            onClick={() => {
              if (type === "syllabus") setCategory("all");
              setType("lab");
              setCourse("");
            }}
          >
            Labs
          </button>
          <button
            className={type === "syllabus" ? "selected" : ""}
            onClick={() => {
              setType("syllabus");
              setCategory("syllabus");
              setCourse("");
              setSearch("");
            }}
          >
            Syllabus
          </button>
        </div>
        {type !== "syllabus" && (
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
        )}
      </section>}
      <section className="library-files">
        <div className="section-heading">
          <h2>
            {course
              ? "Course files"
              : type === "syllabus"
                ? "Syllabus files"
                : "Recently shared"}
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
              course || type === "syllabus" ? entries : entries.slice(0, 3),
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
                name: "target_category",
                label: "Category",
                required: true,
                value: editing.category,
                options: categories.map((value) => ({ value, label: value })),
              },
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
                target_category: d.target_category,
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
        <Modal
          title={
            selectedAttendanceCourse
              ? `${selectedAttendanceCourse.course_code} Attendance`
              : "My Attendance (RFID)"
          }
          close={() => {
            setAttendanceModalOpen(false);
            setSelectedAttendanceCourse(null);
          }}
        >
          {!selectedAttendanceCourse ? (
            <div className="rfid-modal-body">
              <p className="rfid-modal-intro">
                Select an active RFID-monitored course to inspect your daily attendance log.
              </p>
              <div className="rfid-course-list">
                {activeRfidCourses.map((c) => (
                  <button
                    key={c.offering_id}
                    type="button"
                    className="rfid-course-card"
                    onClick={() => setSelectedAttendanceCourse(c)}
                  >
                    <div className="rfid-course-header">
                      <span className="rfid-badge">RFID ACTIVE</span>
                      <span className="rfid-course-type">{c.course_type?.toUpperCase()}</span>
                    </div>
                    <strong className="rfid-course-title">{c.course_title}</strong>
                    <div className="rfid-course-footer">
                      <span>{c.course_code}</span>
                      <span className="rfid-view-log">
                        View Daily Log <ChevronRight size={14} />
                      </span>
                    </div>
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <div className="attendance-table-container">
              <div className="attendance-table-nav">
                <button
                  type="button"
                  className="inline"
                  onClick={() => setSelectedAttendanceCourse(null)}
                >
                  <ArrowLeft size={16} /> All Active RFID Courses
                </button>
                <span className="attendance-device-pill">📡 ESP32 Hardware Reader</span>
              </div>

              <div className="attendance-course-summary-card">
                <h4>{selectedAttendanceCourse.course_title}</h4>
                <p>{selectedAttendanceCourse.course_code} · Daily Attendance Log</p>
              </div>

              <div className="attendance-table-wrap">
                <table className="attendance-daily-table">
                  <thead>
                    <tr>
                      <th>Date</th>
                      <th>Class</th>
                      <th>Status</th>
                      <th>Time / Reader</th>
                    </tr>
                  </thead>
                  <tbody>
                    {SKELETON_ATTENDANCE_ROWS.map((row, idx) => (
                      <tr key={idx}>
                        <td className="attendance-col-date">
                          <strong>{row.date}</strong>
                          <small>{row.day}</small>
                        </td>
                        <td>Class #{row.classNo}</td>
                        <td>
                          <span className={`attendance-status-pill ${row.status.toLowerCase()}`}>
                            {row.status === "Present" ? (
                              <CheckCircle2 size={13} />
                            ) : (
                              <XCircle size={13} />
                            )}
                            {row.status}
                          </span>
                        </td>
                        <td className="attendance-col-time">
                          {row.punchTime ? (
                            <>
                              <span>{row.punchTime}</span>
                              <small>{row.room}</small>
                            </>
                          ) : (
                            <span className="text-muted">—</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="attendance-skeleton-notice">
                ℹ️ UI Preview · Live records will sync automatically once the RFID attendance hardware API is connected.
              </p>
            </div>
          )}
        </Modal>
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

      {tab === "file" ? (
        <Form
          label="Upload file"
          fields={[
            {
              name: "category",
              label: "Category",
              required: true,
              options: categories.map((value) => ({ value, label: value })),
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
            await uploadFile(file, {
              batch_id: ctx.batch,
              semester_course_id: d.course || null,
              title: d.title,
              category: d.category,
            });
            await done();
          }}
        />
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
              name: "category",
              label: "Category",
              required: true,
              options: categories.map((value) => ({ value, label: value })),
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
              target_category: d.category,
              target_url: urlWithScheme,
            });
            await done();
          }}
        />
      )}
    </div>
  );
}

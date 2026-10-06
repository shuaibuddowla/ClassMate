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
} from "lucide-react";
import { rpc, supabase, edge, openFile, uploadFile, type Row } from "@/lib/api";
import type { Context } from "./app";
import { Confirm, Empty, ErrorBox, Form, Modal, Skeleton } from "./ui";
const categories = ["notes", "slides", "questions", "syllabus", "other"];
export function Library({ ctx }: { ctx: Context }) {
  const [type, setType] = useState("theory"),
    [search, setSearch] = useState(""),
    [category, setCategory] = useState("all"),
    [course, setCourse] = useState(""),
    [upload, setUpload] = useState(false),
    [allFiles, setAllFiles] = useState(false),
    [editing, setEditing] = useState<Row | null>(null),
    [deleting, setDeleting] = useState<Row | null>(null),
    [error, setError] = useState<unknown>(null);
  const qc = useQueryClient();
  useEffect(() => {
    const restore = () => {
      const match = location.hash.match(/^#library\/course\/([0-9a-f-]{36})$/i);
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
    history.pushState({classmateCourse:true}, "", `#library/course/${id}`);
  }
  function closeCourse() {
    if (history.state?.classmateCourse) history.back();
    else { history.replaceState(null, "", "#library"); setCourse(""); }
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
          "id,title,category,semester_course_id,size_bytes,mime_type,uploaded_by,created_at",
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
  const renderFiles = (items: Row[]) =>
    items.map((f) => (
      <div className="card file-row" key={f.id}>
        <button
          className="file-open"
          onClick={() => openFile(f.id).catch(setError)}
        >
          <span className="file-icon">
            <FileText />
          </span>
          <span>
            <strong>{f.title}</strong>
            <small>
              {f.category} · {Math.ceil(f.size_bytes / 1024)} KB
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
    ));
  return (
    <div
      className={`library-screen ${selectedCourse ? "course-files-page" : ""} ${type === "syllabus" ? "syllabus-view" : ""}`}
    >
      <div className="page-heading">
        <div>
          {selectedCourse ? <button className="inline" onClick={closeCourse}><ArrowLeft size={18}/> Back to Library</button> : <span className="eyebrow">LEARN TOGETHER</span>}
          <h1>{selectedCourse?.course_title || "Library"}</h1>
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
          aria-label="Search library"
          placeholder="Search files and courses"
          onChange={(e) => setSearch(e.target.value)}
        />
      </label>
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
              .filter((c) => c.course_type === type)
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
                label: "File name",
                required: true,
                value: editing.title,
              },
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
                ...d,
                target_course: d.target_course || null,
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
            await edge("upload-classmate-resource", {
              action: "delete",
              resource_id: deleting.id,
            });
            await refresh();
          }}
        />
      )}
    </div>
  );
}
function UploadForm({
  ctx,
  permissions,
  done,
}: {
  ctx: Context;
  permissions?: Row;
  done: () => Promise<void>;
}) {
  const [file, setFile] = useState<File | null>(null);
  return (
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
          <small>Up to 100 MB · shared with your batch</small>
          <input
            type="file"
            required
            onChange={(e) => setFile(e.target.files?.[0] || null)}
          />
        </label>
      }
      submit={async (d) => {
        if (!file) throw new Error("Choose a file.");
        if (file.size < 1 || file.size > 100_000_000)
          throw new Error("Choose a file smaller than 100 MB.");
        await uploadFile(file, {
          batch_id: ctx.batch,
          semester_course_id: d.course || null,
          title: d.title,
          category: d.category,
        });
        await done();
      }}
    />
  );
}

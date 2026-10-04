"use client";
import { academicSession, sessionEnd } from "@/lib/academic-session";
import { useState } from "react";
import {
  useQuery,
  useInfiniteQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  ArrowLeft,
  Plus,
  Search,
  ChevronRight,
  BookOpen,
  Users,
  GraduationCap,
  Building,
  Activity,
  Trash2,
} from "lucide-react";
import { rpc, rows, type Row } from "@/lib/api";
import type { Context } from "./app";
import {
  Avatar,
  Confirm,
  ErrorBox,
  Form,
  Modal,
  Skeleton,
  type Field,
} from "./ui";
type Action = {
  title: string;
  name: string;
  args?: Row;
  fields: Field[];
  destructive?: boolean;
  teacherName?: string;
};
export default function Manage({
  ctx,
  back,
}: {
  ctx: Context;
  back: () => void;
}) {
  const [section, setSection] = useState(ctx.batch ? "courses" : "structure"),
    [action, setAction] = useState<Action | null>(null),
    [error, setError] = useState<unknown>(null),
    [deleting, setDeleting] = useState<Row | null>(null);
  const qc = useQueryClient();
  const canManage = useQuery({
    queryKey: [ctx.user, ctx.batch, "catalog-permission"],
    enabled: !!ctx.batch,
    queryFn: () =>
      rpc<boolean>("can_manage_batch_catalog", { target_batch: ctx.batch }),
  });
  const catalog = useQuery({
    queryKey: [ctx.user, ctx.batch, "global-courses"],
    enabled: ctx.owner && section === "catalog",
    queryFn: () =>
      rows(
        "courses",
        { department_id: ctx.batchInfo.department_id },
        "*",
        "course_code",
      ),
  });
  const teachers = useQuery({
    queryKey: [ctx.user, "teachers"],
    enabled: ctx.owner && section === "teachers",
    queryFn: () => rpc<Row[]>("owner_teachers"),
  });
  const departments = useQuery({
    queryKey: [ctx.user, "all-departments"],
    enabled: ctx.owner && section === "structure",
    queryFn: () => rows("departments", {}, "*", "name"),
  });
  const semesters = useQuery({
    queryKey: [ctx.user, ctx.batch, "semesters"],
    enabled: ctx.owner && section === "structure",
    queryFn: () =>
      rows("semesters", { batch_id: ctx.batch }, "*", "semester_number"),
  });
  const health = useQuery({
    queryKey: [ctx.user, "health"],
    enabled: ctx.owner && section === "health",
    queryFn: () => rpc("system_health"),
    refetchInterval: 60_000,
  });
  const refresh = () => qc.invalidateQueries({ queryKey: [ctx.user] });
  const courseAction = (c: Row = {}) =>
    setAction({
      title: c.offering_id ? "Edit batch course" : "Add course",
      name: "save_batch_course",
      teacherName: c.teacher_name || "",
      args: {
        target_batch: ctx.batch,
        target_offering: c.offering_id || null,
        target_teacher_record: c.teacher_record_id || null,
        target_credit: c.credit || null,
        target_catalog_course: null,
      },
      fields: [
        {
          name: "target_code",
          label: "Course code",
          required: true,
          value: c.course_code,
        },
        {
          name: "target_title",
          label: "Course name",
          required: true,
          value: c.course_title,
        },
        {
          name: "target_teacher_name",
          label: "Teacher name (optional)",
          value: c.teacher_name,
        },
      ],
    });
  const tabs = [["courses", "Courses", BookOpen]] as [
    string,
    string,
    typeof BookOpen,
  ][];
  if (ctx.owner)
    tabs.push(
      ...([
        ["catalog", "Shared catalog", BookOpen],
        ["teachers", "Teachers", GraduationCap],
        ["people", "People & approvals", Users],
        ["structure", "Academic structure", Building],
        ["health", "System health", Activity],
      ] as [string, string, typeof BookOpen][]),
    );
  if (canManage.isPending && !ctx.owner) return <Skeleton />;
  if (!ctx.owner && !canManage.data)
    return (
      <div className="card">
        <h2>Management unavailable</h2>
        <p>Your current role does not allow batch configuration.</p>
        <button onClick={back}>Back to Profile</button>
      </div>
    );
  return (
    <>
      <button className="text-button" onClick={back}>
        <ArrowLeft size={17} />
        Profile
      </button>
      <div className="page-heading">
        <div>
          <span className="eyebrow">YOUR ACADEMIC ENVIRONMENT</span>
          <h1>Manage</h1>
          <p>Changes are shared with your batch.</p>
        </div>
      </div>
      <div className="manage-tabs">
        {tabs.map(([value, label, Icon]) => (
          <button
            key={value}
            className={section === value ? "selected" : ""}
            onClick={() => setSection(value as string)}
          >
            <Icon size={17} />
            {label}
          </button>
        ))}
      </div>
      <ErrorBox
        error={
          error ||
          catalog.error ||
          teachers.error ||
          departments.error ||
          semesters.error ||
          health.error
        }
      />
      {section === "courses" && (
        <>
          <div className="section-heading">
            <h2>Batch courses</h2>
            <button className="primary" onClick={() => courseAction()}>
              <Plus size={17} />
              Add course
            </button>
          </div>
          {ctx.courses.length === 0 && (
            <p className="card">
              Start by adding your courses and teachers. Course names ending in
              “Lab” are detected automatically.
            </p>
          )}
          <div className="course-grid">
            {ctx.courses.map((c) => (
              <div className="card course-manage" key={c.offering_id}>
                <button onClick={() => courseAction(c)}>
                  <strong>{c.course_title}</strong>
                  <small>
                    {c.course_code} ·{" "}
                    {c.teacher_name || "Teacher not yet configured"}
                  </small>
                </button>
                <button
                  className="icon danger"
                  aria-label="Remove course from batch"
                  onClick={() => setDeleting({ ...c, scope: "batch" })}
                >
                  <Trash2 size={17} />
                </button>
              </div>
            ))}
          </div>
        </>
      )}
      {section === "catalog" && (
        <>
          <p>
            These are shared courses across batches. Global deletion also
            removes linked data.
          </p>
          {catalog.data?.map((c) => (
            <div className="card course-manage" key={c.id}>
              <button
                onClick={() =>
                  setAction({
                    title: "Edit shared course",
                    name: "edit_course",
                    args: { target_course: c.id },
                    fields: [
                      {
                        name: "target_code",
                        label: "Course code",
                        required: true,
                        value: c.course_code,
                      },
                      {
                        name: "target_title",
                        label: "Course name",
                        required: true,
                        value: c.course_title,
                      },
                    ],
                  })
                }
              >
                <strong>{c.course_title}</strong>
                <small>{c.course_code}</small>
              </button>
              <button
                className="icon danger"
                aria-label="Delete shared course"
                onClick={async () => {
                  try {
                    const impact = await rpc("course_deletion_preview", {
                      target_course: c.id,
                    });
                    setDeleting({ ...c, scope: "global", impact });
                  } catch (e) {
                    setError(e);
                  }
                }}
              >
                <Trash2 size={17} />
              </button>
              <button
                className="inline"
                onClick={() => {
                  courseAction(c);
                  setAction((a) =>
                    a
                      ? {
                          ...a,
                          args: { ...a.args, target_catalog_course: c.id },
                        }
                      : a,
                  );
                }}
              >
                Add to this batch
              </button>
            </div>
          ))}
        </>
      )}
      {section === "teachers" && (
        <>
          <div className="section-heading">
            <h2>Teacher directory</h2>
            <button
              className="primary"
              onClick={() =>
                setAction({
                  title: "Register teacher",
                  name: "owner_create_teacher",
                  args: { target_department: ctx.batchInfo.department_id },
                  fields: [
                    {
                      name: "target_name",
                      label: "Teacher name",
                      required: true,
                    },
                    {
                      name: "target_email",
                      label: "University email (optional)",
                      type: "email",
                    },
                  ],
                })
              }
            >
              <Plus size={17} />
              Add teacher
            </button>
          </div>
          {teachers.data?.map((t) => (
            <div className="card person" key={t.id}>
              <Avatar name={t.full_name} />
              <span>
                <strong>{t.full_name}</strong>
                <small>
                  {t.email || "Email not yet added"} · {t.course_count} courses
                </small>
              </span>
              <button
                onClick={() =>
                  setAction({
                    title: "Edit teacher",
                    name: "owner_save_teacher",
                    args: { target_record: t.id },
                    fields: [
                      {
                        name: "target_name",
                        label: "Name",
                        required: true,
                        value: t.full_name,
                      },
                      {
                        name: "target_email",
                        label: "University email",
                        type: "email",
                        value: t.email,
                      },
                    ],
                  })
                }
              >
                Edit
              </button>
              <button
                onClick={() =>
                  setAction({
                    title: "Assign teacher",
                    name: "owner_assign_teacher_record",
                    args: { target_record: t.id },
                    fields: [
                      {
                        name: "target_offering",
                        label: "Course in current batch",
                        required: true,
                        options: ctx.courses.map((c) => ({
                          value: c.offering_id,
                          label: c.course_title,
                        })),
                      },
                    ],
                  })
                }
              >
                Assign
              </button>
            </div>
          ))}
        </>
      )}
      {section === "people" && (
        <People ctx={ctx} open={setAction} refresh={refresh} />
      )}
      {section === "structure" && (
        <>
          <div className="section-heading">
            <h2>Departments & batches</h2>
            <button
              className="primary"
              onClick={() =>
                setAction({
                  title: "Create department",
                  name: "create_department",
                  fields: [
                    {
                      name: "target_name",
                      label: "Department name",
                      required: true,
                    },
                    {
                      name: "target_code",
                      label: "Department code",
                      required: true,
                    },
                  ],
                })
              }
            >
              Add department
            </button>
          </div>
          {departments.data?.map((d) => (
            <button
              className="card course-tile"
              key={d.id}
              onClick={() =>
                setAction({
                  title: "Configure department",
                  name: "configure_department",
                  args: { target_department: d.id },
                  fields: [
                    {
                      name: "target_prefix",
                      label: "Student email prefix",
                      value: d.email_prefix,
                    },
                    {
                      name: "target_offset",
                      label: "Session to batch offset",
                      type: "number",
                      value: d.session_offset,
                    },
                    {
                      name: "target_active",
                      label: "Status",
                      value: String(d.is_active),
                      required: true,
                      options: [
                        { value: "true", label: "Active" },
                        { value: "false", label: "Inactive" },
                      ],
                    },
                  ],
                })
              }
            >
              <Building size={20} />
              <span>
                <strong>{d.name}</strong>
                <small>
                  {d.is_active ? "Active" : "Inactive"} · {d.code}
                </small>
              </span>
              <ChevronRight size={16} />
            </button>
          ))}
          <button
            className="secondary"
            onClick={() =>
              setAction({
                title: "Create batch",
                name: "create_batch",
                fields: [
                  {
                    name: "target_department",
                    label: "Department",
                    required: true,
                    options: (departments.data || []).map((d) => ({
                      value: d.id,
                      label: d.name,
                    })),
                  },
                  {
                    name: "target_batch_number",
                    label: "Batch number",
                    type: "number",
                    required: true,
                  },
                  {
                    name: "target_session",
                    label: "Academic session (e.g. 24-25)",
                    required: true,
                    min: "0",
                    max: "99",
                  },
                ],
              })
            }
          >
            Create batch
          </button>
          <h2>Semesters in this batch</h2>
          {semesters.data?.map((s) => (
            <div className="card person" key={s.id}>
              <span>
                <strong>Semester {s.semester_number}</strong>
                <small>{s.status}</small>
              </span>
              {s.status === "not_started" && (
                <button
                  onClick={() =>
                    setAction({
                      title: "Publish semester",
                      name: "publish_semester",
                      args: { target_semester: s.id },
                      fields: [],
                    })
                  }
                >
                  Publish
                </button>
              )}
              <button
                onClick={() =>
                  setAction({
                    title: "Clone into this semester",
                    name: "clone_semester",
                    args: { to_id: s.id },
                    fields: [
                      {
                        name: "from_id",
                        label: "Source semester",
                        required: true,
                        options: (semesters.data || [])
                          .filter((x) => x.id !== s.id)
                          .map((x) => ({
                            value: x.id,
                            label: `Semester ${x.semester_number} · ${x.status}`,
                          })),
                      },
                    ],
                  })
                }
              >
                Clone
              </button>
            </div>
          ))}
        </>
      )}
      {section === "health" && (
        <Health
          data={health.data}
          loading={health.isPending}
          refresh={() => health.refetch()}
          retry={async (id) => {
            await rpc("retry_failed_notifications", { target_event: id });
            await health.refetch();
          }}
        />
      )}
      {action && (
        <Modal title={action.title} close={() => setAction(null)}>
          <Form
            fields={action.fields}
            label={action.fields.length ? "Save" : "Confirm"}
            submit={async (d) => {
              const args: Row = { ...action.args, ...d };
              if (
                action.name === "save_batch_course" &&
                d.target_teacher_name !== action.teacherName
              )
                args.target_teacher_record = null;
              for (const key of [
                "target_offset",
                "target_batch_number",
                "target_session",
                "corrected_session",
              ])
                if (key in args)
                  args[key] = key.endsWith("session")
                    ? sessionEnd(args[key])
                    : Number(args[key]);
              if ("target_active" in args)
                args.target_active = args.target_active === "true";
              if ("target_email" in args)
                args.target_email = args.target_email || null;
              await rpc(action.name, args);
              await refresh();
              setAction(null);
            }}
          />
        </Modal>
      )}
      {deleting && (
        <Confirm
          title={
            deleting.scope === "global"
              ? "Delete course across all batches?"
              : "Remove batch course?"
          }
          body={
            deleting.scope === "global"
              ? `${deleting.course_title}: ${deleting.impact.batches} batches, ${deleting.impact.periods} periods, ${deleting.impact.files} files and ${deleting.impact.notices} notices will be affected. This cannot be undone.`
              : `Remove ${deleting.course_title} and its linked batch data?`
          }
          close={() => setDeleting(null)}
          action={async () => {
            await rpc(
              deleting.scope === "global"
                ? "delete_global_course"
                : "remove_batch_course",
              deleting.scope === "global"
                ? { target_course: deleting.id }
                : { target_offering: deleting.offering_id },
            );
            await refresh();
          }}
        />
      )}
    </>
  );
}
function People({
  ctx,
  open,
  refresh,
}: {
  ctx: Context;
  open: (a: Action) => void;
  refresh: () => Promise<void>;
}) {
  const [search, setSearch] = useState(""),
    [error, setError] = useState<unknown>(null);
  const users = useInfiniteQuery({
    queryKey: [ctx.user, "owner-people", search],
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      rpc<Row[]>("owner_people", {
        query_text: search,
        result_offset: pageParam,
      }),
    getNextPageParam: (last, pages) =>
      last.length === 50 ? pages.length * 50 : undefined,
  });
  return (
    <>
      <label className="search">
        <Search size={18} />
        <input
          placeholder="Search all users"
          aria-label="Search all users"
          value={search}
          maxLength={100}
          onChange={(e) => setSearch(e.target.value)}
        />
      </label>
      <ErrorBox error={error || users.error} />
      {users.isPending ? (
        <Skeleton />
      ) : (
        users.data?.pages.flat().map((p) => (
          <div className="card person" key={p.profile_id}>
            <Avatar url={p.avatar_url} name={p.full_name} />
            <span>
              <strong>{p.full_name}</strong>
              <small>
                {p.student_id || p.role} ·{" "}
                {p.batch_name || p.verification_status}
              </small>
            </span>
            {p.role === "student" && p.verification_status === "active" && (
              <label className="cr-toggle">
                CR
                <input
                  type="checkbox"
                  checked={p.is_cr}
                  onChange={async () => {
                    try {
                      await rpc(
                        p.is_cr ? "revoke_cr" : "assign_cr",
                        p.is_cr
                          ? { target_profile: p.profile_id }
                          : {
                              target_profile: p.profile_id,
                              target_batch: p.batch_id,
                              valid_until: null,
                            },
                      );
                      await refresh();
                    } catch (e) {
                      setError(e);
                    }
                  }}
                />
              </label>
            )}
            {p.profile_source === "manual" &&
              ["pending", "rejected"].includes(p.verification_status) && (
                <button
                  onClick={() =>
                    open({
                      title: "Approve student",
                      name: "approve_student_profile",
                      args: { target_profile: p.profile_id },
                      fields: [
                        {
                          name: "corrected_student_id",
                          label: "Student ID",
                          required: true,
                          value: p.student_id,
                        },
                        {
                          name: "target_batch",
                          label: "Batch",
                          required: true,
                          value: ctx.batch,
                          options: [
                            {
                              value: ctx.batch,
                              label: `Current batch ${ctx.batchInfo.batch_number}`,
                            },
                          ],
                        },
                        {
                          name: "corrected_session",
                          label: "Academic session (e.g. 24-25)",
                          required: true,
                          value: academicSession(
                            ctx.batchInfo.academic_session,
                          ),
                        },
                      ],
                    })
                  }
                >
                  Approve
                </button>
              )}
            {p.profile_source === "manual" &&
              p.verification_status === "pending" && (
                <button
                  onClick={() =>
                    open({
                      title: "Reject profile",
                      name: "reject_student_profile",
                      args: { target_profile: p.profile_id },
                      fields: [
                        { name: "reason", label: "Reason", required: true },
                      ],
                    })
                  }
                >
                  Reject
                </button>
              )}
          </div>
        ))
      )}
      {users.hasNextPage && (
        <button onClick={() => users.fetchNextPage()}>Load 50 more</button>
      )}
    </>
  );
}
function Health({
  data,
  loading,
  refresh,
  retry,
}: {
  data?: Row;
  loading: boolean;
  refresh: () => void;
  retry: (id: string) => Promise<void>;
}) {
  const [error, setError] = useState<unknown>(null);
  if (loading) return <Skeleton />;
  if (!data) return null;
  return (
    <>
      <div className="section-heading">
        <h2>System health</h2>
        <button onClick={refresh}>Refresh</button>
      </div>
      <small>Observed {new Date(data.observed_at).toLocaleString()}</small>
      <div className="health-grid">
        {[
          ["Pending deliveries", data.pending_jobs],
          [
            "Oldest pending notice",
            `${Math.round(data.oldest_pending_notice_seconds)} seconds`,
          ],
          ["Successful sends", data.accepted_sends],
          ["Failures", data.failed_jobs],
          [
            "Database size",
            `${(data.database_bytes / 1024 / 1024).toFixed(1)} MB`,
          ],
        ].map(([label, value]) => (
          <div className="card" key={String(label)}>
            <small>{label}</small>
            <h2>{value}</h2>
          </div>
        ))}
      </div>
      {data.oldest_pending_notice_seconds > 300 && (
        <div className="error">Queue age exceeds five minutes.</div>
      )}
      <div className="card">
        <h3>Hosting & usage snapshot</h3>
        {data.hosting && (
          <>
            <p>
              {Date.now() - new Date(data.hosting.observed_at).getTime() >
              86400000
                ? "Stale provider metrics — refresh before capacity decisions."
                : "Timestamped provider metrics."}
            </p>
            {data.hosting.database_limit_bytes &&
              data.database_bytes / data.hosting.database_limit_bytes > 0.8 && (
                <p className="error">
                  Database usage exceeds 80% of the verified allowance.
                </p>
              )}
            {data.hosting.egress_limit_bytes &&
              data.hosting.egress_bytes / data.hosting.egress_limit_bytes >
                0.8 && (
                <p className="error">
                  Bandwidth usage exceeds 80% of the verified allowance.
                </p>
              )}
          </>
        )}
        {data.hosting ? (
          <pre>{JSON.stringify(data.hosting, null, 2)}</pre>
        ) : (
          <p>Provider metrics unavailable. No verified quota information.</p>
        )}
        <h3>Client receipt states</h3>
        <pre>{JSON.stringify(data.client_states, null, 2)}</pre>
        <h3>Largest tables</h3>
        {data.largest_tables?.map((t: Row) => (
          <p key={t.name}>
            {t.name} · {(t.bytes / 1024 / 1024).toFixed(2)} MB
          </p>
        ))}
      </div>
      <ErrorBox error={error} />
      {data.failed_events?.map((e: Row) => (
        <div className="card person" key={e.id}>
          <span>
            {e.kind} · {e.devices} failed deliveries
          </span>
          <button onClick={() => retry(e.id).catch(setError)}>
            Retry failed deliveries
          </button>
        </div>
      ))}
    </>
  );
}

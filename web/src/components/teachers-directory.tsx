"use client";
import { useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Search, ExternalLink, Trash2, GraduationCap, Mail, Phone, Plus, Pencil } from "lucide-react";
import { rpc, type Row } from "@/lib/api";
import type { Context } from "./app";
import { Avatar, Empty, ErrorBox, Skeleton, Modal, Form, Confirm } from "./ui";

export function TeachersDirectory({ ctx }: { ctx: Context }) {
  const [search, setSearch] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Row | null>(null);
  const [deleting, setDeleting] = useState<Row | null>(null);

  const qc = useQueryClient();
  const canManage = Boolean(
    ctx.owner ||
    ctx.profile.role === "admin" ||
    ctx.profile.is_cr
  );

  const directory = useQuery({
    queryKey: [ctx.user, ctx.batch, "batch-teacher-directory"],
    queryFn: () => rpc<Row[]>("batch_teachers_directory", { target_batch: ctx.batch }),
    enabled: !!ctx.batch,
  });

  const filtered = useMemo(() => (directory.data || []).filter(t =>
    `${t.full_name} ${t.department_name} ${t.department_code} ${t.designation} ${t.email} ${(t.courses || []).join(" ")}`.toLowerCase().includes(search.toLowerCase().trim())
  ), [directory.data, search]);

  const refreshAll = async () => {
    await Promise.all([
      qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch, "batch-teacher-directory"] }),
      qc.invalidateQueries({ queryKey: [ctx.user, "teachers"] }),
      qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch] }),
    ]);
  };

  return (
    <div className="teachers-directory-page">
      <div className="page-heading">
        <div>
          <span className="eyebrow">YOUR BATCH FACULTY</span>
          <h1>Teachers directory</h1>
          <p>Faculty members and course instructors for this batch.</p>
        </div>
        <div style={{ display: "flex", gap: "10px", alignItems: "center" }}>
          {canManage && (
            <button className="primary" onClick={() => setAdding(true)}>
              <Plus size={16} />
              Add Teacher
            </button>
          )}
          <GraduationCap size={25} />
        </div>
      </div>

      <label className="teachers-directory-search">
        <Search size={18}/>
        <input
          value={search}
          onChange={e => setSearch(e.target.value)}
          placeholder="Search teachers or departments"
          aria-label="Search teachers"
        />
      </label>

      {!ctx.batch ? (
        <Empty title="No batch selected" body="A batch is needed to show the teachers assigned to its classes."/>
      ) : null}

      <ErrorBox error={error || directory.error} retry={() => directory.refetch()}/>

      {ctx.batch && (
        directory.isPending ? (
          <Skeleton/>
        ) : filtered.length ? (
          <div className="teachers-directory-grid">
            {filtered.map(t => (
              <article className="card teacher-directory-card" key={t.id}>
                <Avatar name={t.full_name}/>
                <div className="teacher-directory-info">
                  <strong>{t.full_name}</strong>
                  <span>{t.designation || "Faculty member"}</span>
                  <small>{t.department_name} ({String(t.department_code || "").toUpperCase()})</small>
                  {(t.courses || []).length > 0 && <small>Courses: {t.courses.join(" · ")}</small>}
                  {t.email && <a href={`mailto:${t.email}`}><Mail size={14}/>{t.email}</a>}
                  {t.phone && <a href={`tel:${t.phone}`}><Phone size={14}/>{t.phone}</a>}
                  {t.profile_url && (
                    <a href={t.profile_url} target="_blank" rel="noreferrer">
                      <ExternalLink size={14}/>Official profile
                    </a>
                  )}
                </div>
                {canManage && (
                  <div style={{ display: "flex", gap: "4px", alignSelf: "flex-start" }}>
                    <button
                      className="icon"
                      aria-label={`Edit ${t.full_name}`}
                      title="Edit teacher"
                      onClick={() => setEditing(t)}
                    >
                      <Pencil size={16}/>
                    </button>
                    <button
                      className="icon danger"
                      disabled={busy === t.id}
                      aria-label={`Delete ${t.full_name}`}
                      title="Delete teacher"
                      onClick={() => setDeleting(t)}
                    >
                      <Trash2 size={16}/>
                    </button>
                  </div>
                )}
              </article>
            ))}
          </div>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: "14px" }}>
            <Empty
              title="No teachers found"
              body={
                search
                  ? "No faculty members match your search query."
                  : "No teachers configured yet. Add faculty members to link them to your courses."
              }
            />
            {canManage && !search && (
              <button className="primary" onClick={() => setAdding(true)}>
                <Plus size={16} />
                Add First Teacher
              </button>
            )}
          </div>
        )
      )}

      {adding && (
        <Modal title="Add Teacher to Directory" close={() => setAdding(false)}>
          <Form
            fields={[
              {
                name: "target_name",
                label: "Teacher name",
                required: true,
                placeholder: "e.g. Dr. Shams M. Galib",
              },
              {
                name: "target_email",
                label: "Teacher email (University or personal)",
                type: "email",
                placeholder: "e.g. faculty@mbstu.ac.bd",
              },
            ]}
            label="Add Teacher"
            submit={async (d) => {
              try {
                setBusy("add");
                setError(null);
                const deptId = ctx.batchInfo.department_id || ctx.profile.department_id;
                await rpc("owner_create_teacher", {
                  target_department: deptId,
                  target_name: d.target_name,
                  target_email: d.target_email || null,
                });
                await refreshAll();
                setAdding(false);
              } catch (e) {
                setError(e);
              } finally {
                setBusy(null);
              }
            }}
          />
        </Modal>
      )}

      {editing && (
        <Modal title={`Edit ${editing.full_name}`} close={() => setEditing(null)}>
          <Form
            fields={[
              {
                name: "target_name",
                label: "Teacher name",
                required: true,
                value: editing.full_name,
              },
              {
                name: "target_email",
                label: "Teacher email",
                type: "email",
                value: editing.email || "",
              },
            ]}
            label="Save Changes"
            submit={async (d) => {
              try {
                setBusy(editing.id);
                setError(null);
                await rpc("owner_save_teacher", {
                  target_record: editing.id,
                  target_name: d.target_name,
                  target_email: d.target_email || null,
                });
                await refreshAll();
                setEditing(null);
              } catch (e) {
                setError(e);
              } finally {
                setBusy(null);
              }
            }}
          />
        </Modal>
      )}

      {deleting && (
        <Confirm
          title="Delete teacher?"
          body={`Delete ${deleting.full_name} from the teacher directory? Linked courses will become unassigned.`}
          close={() => setDeleting(null)}
          action={async () => {
            try {
              setBusy(deleting.id);
              setError(null);
              await rpc("owner_delete_teacher", { target_record: deleting.id });
              await refreshAll();
              setDeleting(null);
            } catch (e) {
              setError(e);
            } finally {
              setBusy(null);
            }
          }}
        />
      )}
    </div>
  );
}

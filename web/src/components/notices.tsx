"use client";
import { useEffect, useRef, useState, type RefObject } from "react";
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  Bell,
  BellRing,
  CalendarClock,
  FileText,
  Heart,
  MessageCircle,
  MessageSquare,
  MoreVertical,
  Plus,
  Search,
  ExternalLink,
  Copy,
  Check,
  Languages,
  LoaderCircle,
  ArrowLeft,
  X,
  Image as ImageIcon,
  Palmtree,
  CalendarDays,
  Share2,
  BarChart2,
  ChevronLeft,
  ChevronRight,
  Menu,
} from "lucide-react";
import { WhatsAppText } from "@/lib/whatsapp-formatter";
import { edge, rpc, supabase, mutation, openFile, type Row } from "@/lib/api";
import { readAcademic, saveAcademic } from "@/lib/cache";
import { dhakaToday, formatStamp, parseDate, dateKey } from "@/lib/calendar";
import type { Context } from "./app";
import { Avatar, Confirm, Empty, ErrorBox, Form, Modal, Skeleton } from "./ui";
import { Comments } from "./notice-comments";

export interface NoticeAttachmentItem {
  id: string;
  url: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  storageKey?: string;
}

type Page = {
  entries: Row[];
  details: Row;
  attachments?: Record<string, { id: string; url: string; fileName: string; sizeBytes: number }>;
  attachmentsList?: Record<string, NoticeAttachmentItem[]>;
  hasMore: boolean;
};
export function Notices({
  ctx,
  scrollContainer,
}: {
  ctx: Context;
  scrollContainer?: RefObject<HTMLDivElement | null>;
}) {
  const qc = useQueryClient(),
    [search, setSearch] = useState(""),
    [searchOpen, setSearchOpen] = useState(false),
    [composer, setComposer] = useState(false),
    [editing, setEditing] = useState<Row | null>(null),
    [deleting, setDeleting] = useState<Row | null>(null),
    [readers, setReaders] = useState<{ notice: Row; triggerRef?: React.RefObject<HTMLElement | null> } | null>(null),
    [comments, setComments] = useState<Row | null>(null),
    [detailNotice, setDetailNotice] = useState<Row | null>(null),
    [error, setError] = useState<unknown>(null),
    [reminder, setReminder] = useState<Row | null>(null),
    [noticeAssessmentId, setNoticeAssessmentId] = useState<string | null>(null);

  const noticeAssessmentQuery = useQuery({
    queryKey: [ctx.user, ctx.batch, "notice-assessment-detail", noticeAssessmentId],
    enabled: !!noticeAssessmentId,
    queryFn: async () => {
      const { data } = await supabase()
        .schema("classmate")
        .from("assessments")
        .select("*")
        .eq("id", noticeAssessmentId!)
        .maybeSingle();
      return data;
    },
  });
  const receiptsEnabled = useQuery({
    queryKey: [ctx.user, "owner-preferences"],
    enabled: ctx.owner,
    queryFn: async () => {
      const res = await rpc<{ read_receipts_enabled?: boolean }>("owner_preferences");
      return res?.read_receipts_enabled ?? true;
    },
  });
  const end = useRef<HTMLDivElement>(null);
  const scrolled = useRef(false);
  useEffect(() => {
    const update = () => {
      scrolled.current = true;
    };
    const root = scrollContainer?.current;
    if (root) {
      root.addEventListener("scroll", update, { passive: true });
      return () => {
        root.removeEventListener("scroll", update);
      };
    } else {
      window.addEventListener("scroll", update, { passive: true });
      return () => {
        window.removeEventListener("scroll", update);
      };
    }
  }, [scrollContainer]);
  const permissions = useQuery({
    queryKey: [
      ctx.user,
      ctx.batch,
      "post-permissions",
      ctx.courses.map((c) => c.offering_id).join(","),
    ],
    queryFn: async () => {
      const general = await rpc<boolean>("can_post", {
        target_batch: ctx.batch,
        target_course: null,
      });
      const courses = await Promise.all(
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
      );
      return { general, courses: Object.fromEntries(courses) };
    },
  });
  const feed = useInfiniteQuery({
    queryKey: [ctx.user, ctx.batch, "notices"],
    networkMode: "always",
    refetchInterval: ctx.active === false ? false : 30_000,
    initialPageParam: null as Row | null,
    queryFn: async ({ pageParam }): Promise<Page> => {
      if (!navigator.onLine) {
        const cached = readAcademic(ctx.user, ctx.batch, "notices")?.data as
          | Page[]
          | undefined;
        if (!cached) throw new Error("Connect once to sync your notices.");
        const index = pageParam
          ? cached.findIndex((p) =>
              p.entries.some((n) => n.id === pageParam.id),
            ) + 1
          : 0;
        return cached[index]
          ? { ...cached[index], hasMore: index < cached.length - 1 }
          : { entries: [], details: {}, hasMore: false };
      }
      let query = supabase()
        .schema("classmate")
        .from("notices")
        .select(
          "id,title,body,published_at,semester_course_id,author_id,resource_id,class_change_id,poll_id",
        )
        .eq("batch_id", ctx.batch)
        .order("published_at", { ascending: false })
        .order("id", { ascending: false })
        .limit(10);
      if (ctx.profile.role === "teacher") query = query.eq("author_id", ctx.user);
      if (pageParam)
        query = query.or(
          `published_at.lt.${pageParam.published_at},and(published_at.eq.${pageParam.published_at},id.lt.${pageParam.id})`,
        );
      const { data, error } = await query;
      if (error) throw error;
      const entries = data || [],
        noticeIds = entries.map((n) => n.id);

      const [details, attachmentsData] = await Promise.all([
        noticeIds.length
          ? rpc("notice_feed_details", {
              target_ids: noticeIds,
            }).catch((err) => {
              console.warn("notice_feed_details error:", err);
              return {};
            })
          : {},
        noticeIds.length
          ? (async () => {
              try {
                // First try default schema (which now has classmate.notice_attachments view)
                const res = await supabase()
                  .from("notice_attachments")
                  .select("id,notice_id,storage_key,file_name,mime_type,size_bytes")
                  .in("notice_id", noticeIds);
                if (res.data && res.data.length > 0) return res.data as any[];
                // Fallback to explicit public schema
                const resPublic = await supabase()
                  .schema("public")
                  .from("notice_attachments")
                  .select("id,notice_id,storage_key,file_name,mime_type,size_bytes")
                  .in("notice_id", noticeIds);
                return (resPublic.data || []) as any[];
              } catch {
                return [] as any[];
              }
            })()
          : Promise.resolve([]),
      ]);

      const allAtts = [
        ...((details as any)?.attachments || []),
        ...((attachmentsData as any[]) || []),
      ];

      const seenAttIds = new Set<string>();
      const attachmentsListMap: Record<string, NoticeAttachmentItem[]> = {};
      const attachmentMap: Record<
        string,
        { id: string; url: string; fileName: string; sizeBytes: number }
      > = {};

      for (const a of allAtts) {
        if (!a || !a.id || seenAttIds.has(a.id) || !a.notice_id || !a.storage_key) continue;
        seenAttIds.add(a.id);
        const { data: urlData } = supabase().storage.from("notice-images").getPublicUrl(a.storage_key);
        const url = urlData?.publicUrl || "";
        if (!url) continue;

        const item: NoticeAttachmentItem = {
          id: a.id,
          url,
          fileName: a.file_name || "Attachment",
          mimeType: a.mime_type || "image/jpeg",
          sizeBytes: Number(a.size_bytes || 0),
          storageKey: a.storage_key,
        };

        const nid = String(a.notice_id).trim();
        const keys = Array.from(new Set([nid, nid.toLowerCase(), nid.toUpperCase()]));
        for (const k of keys) {
          if (!attachmentsListMap[k]) {
            attachmentsListMap[k] = [];
          }
          if (!attachmentsListMap[k].some((existing) => existing.id === item.id)) {
            attachmentsListMap[k].push(item);
          }

          if (!attachmentMap[k]) {
            attachmentMap[k] = {
              id: a.id,
              url,
              fileName: item.fileName,
              sizeBytes: item.sizeBytes,
            };
          }
        }
      }

      return {
        entries,
        details,
        attachments: attachmentMap,
        attachmentsList: attachmentsListMap,
        hasMore: entries.length === 10,
      };
    },
    getNextPageParam: (last) =>
      last.hasMore ? last.entries.at(-1) : undefined,
    initialData: () => {
      const cached = readAcademic(ctx.user, ctx.batch, "notices");
      return cached?.data?.length
        ? {
            pages: [
              navigator.onLine
                ? cached.data[0]
                : { ...cached.data[0], hasMore: cached.data.length > 1 },
            ],
            pageParams: [null],
          }
        : undefined;
    },
    initialDataUpdatedAt: () => {
      const cached = readAcademic(ctx.user, ctx.batch, "notices");
      // Force network refresh if cached data does not have attachmentsList
      if (!cached?.data?.[0]?.attachmentsList) return 0;
      return cached?.syncedAt || 0;
    },
  });
  useEffect(() => {
    if (feed.data && navigator.onLine)
      saveAcademic(ctx.user, ctx.batch, "notices", feed.data.pages);
  }, [feed.data, ctx.user, ctx.batch]);
  useEffect(() => {
    if (!end.current) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (
          scrolled.current &&
          !search &&
          entries[0].isIntersecting &&
          feed.hasNextPage &&
          !feed.isFetching &&
          !feed.isFetchNextPageError
        )
          feed.fetchNextPage();
      },
      { rootMargin: "150px", root: scrollContainer?.current || null },
    );
    observer.observe(end.current);
    return () => observer.disconnect();
  }, [
    search,
    feed.hasNextPage,
    feed.isFetching,
    feed.isFetchNextPageError,
    feed.fetchNextPage,
    scrollContainer,
  ]);
  const notices =
    feed.data?.pages
      .flatMap((p) => p.entries)
      .filter((n) => ctx.profile.role !== "teacher" || n.author_id === ctx.user)
      .filter((n, i, a) => a.findIndex((x) => x.id === n.id) === i) || [];
  const details =
    feed.data?.pages.reduce((acc, p) => {
      if (!p?.details) return acc;
      for (const k of Object.keys(p.details)) {
        acc[k] = [...(acc[k] || []), ...(p.details[k] || [])];
      }
      return acc;
    }, {} as Row) || {};
  const attachments =
    feed.data?.pages.reduce((acc, p) => {
      return { ...acc, ...(p.attachments || {}) };
    }, {} as Record<string, { id: string; url: string; fileName: string; sizeBytes: number }>) || {};
  const attachmentsList =
    feed.data?.pages.reduce((acc, p) => {
      return { ...acc, ...(p.attachmentsList || {}) };
    }, {} as Record<string, NoticeAttachmentItem[]>) || {};
  const refresh = () =>
    qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch] });
  async function reaction(n: Row, change: Row) {
    const state =
      details.engagement?.find((x: Row) => x.notice_id === n.id) || {};
    await mutation("notice_reactions", "upsert", {
      profile_id: ctx.user,
      notice_id: n.id,
      liked: state.is_liked || false,
      pinned: state.is_pinned || false,
      reminder_at: state.reminder_at || null,
      ...change,
    });
    await refresh();
  }
  const handleVote = async (pollId: string, optionId: string) => {
    try {
      const allPolls = feed.data?.pages.flatMap((p) => (p.details as any)?.polls || []) || [];
      const targetPoll = allPolls.find((p: Row) => p.poll_id === pollId || p.id === pollId);
      let selectedIds: string[] = [optionId];
      if (targetPoll?.is_multiple_choice) {
        const currentVotes = (targetPoll.options || [])
          .filter((opt: Row) => opt.is_my_vote)
          .map((opt: Row) => opt.id);
        if (currentVotes.includes(optionId)) {
          selectedIds = currentVotes.filter((id: string) => id !== optionId);
        } else {
          selectedIds = [...currentVotes, optionId];
        }
        if (selectedIds.length === 0) return;
      }
      await rpc("cast_poll_vote", {
        target_poll: pollId,
        selected_option_ids: selectedIds,
      });
      qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch, "notices"] });
    } catch (err: any) {
      alert(err?.message || "Failed to submit vote");
    }
  };

  return (
    <div className="notices-screen">
      <div className="page-heading transparent">
        <div className="page-heading-left">
          {ctx.openDrawer && (
            <button
              type="button"
              className="icon hamburger-btn"
              aria-label="Open navigation menu"
              onClick={ctx.openDrawer}
            >
              <Menu size={22} strokeWidth={2.2} />
            </button>
          )}
          <div>
            <span className="eyebrow">STAY IN THE LOOP</span>
            <h1>Notices</h1>
            <p>
              {notices.length}
              {feed.hasNextPage ? "+" : ""} updates ·{" "}
              <button className="inline" onClick={() => feed.refetch()}>
                <span className="notice-desktop-label">Refresh</span>
                <span className="notice-mobile-label">Pull to refresh</span>
              </button>
            </p>
          </div>
        </div>
        <div className="notice-heading-actions">
          <button
            className="icon notice-search-toggle"
            aria-label="Search notices"
            aria-expanded={searchOpen}
            onClick={() => setSearchOpen(!searchOpen)}
          >
            <Search size={22} />
          </button>
          {(permissions.data?.general ||
            Object.values(permissions.data?.courses || {}).some(Boolean)) && (
            <button className="primary" onClick={() => setComposer(true)}>
              <Plus size={18} />
              Post
            </button>
          )}
        </div>
      </div>
      <label
        className={`search notice-search ${searchOpen || search ? "open" : ""}`}
      >
        <Search size={19} />
        <input
          value={search}
          maxLength={100}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search loaded notices"
          aria-label="Search notices"
        />
      </label>
      <ErrorBox error={error || feed.error} retry={() => feed.refetch()} />
      {feed.isPending ? (
        <>
          <Skeleton />
          <Skeleton />
        </>
      ) : null}
      <div className="notice-list">
        {notices
          .filter((n) =>
            `${n.title} ${n.body}`.toLowerCase().includes(search.toLowerCase()),
          )
          .map((n) => (
            <NoticeCard
              key={n.id}
              notice={n}
              ctx={ctx}
              batch={ctx.batch}
              search={search}
              poll={details.polls?.find((x: Row) => x.notice_id === n.id)}
              resource={details.resources?.find((x: Row) => x.notice_id === n.id)}
              onVote={handleVote}
              state={
                details.engagement?.find((x: Row) => x.notice_id === n.id) || {}
              }
              read={details.reads?.find((x: Row) => x.notice_id === n.id) || {}}
              previews={(details.previews || [])
                .filter((x: Row) => x.notice_id === n.id && x.profile_id !== n.author_id)
                .filter(
                  (x: Row) =>
                    !(
                      ctx.owner &&
                      receiptsEnabled.data === false &&
                      x.profile_id === ctx.user
                    ),
                )
                .slice(0, 4)}
              editable={
                ctx.owner ||
                (n.author_id === ctx.user &&
                  (n.semester_course_id
                    ? permissions.data?.courses[n.semester_course_id]
                    : permissions.data?.general))
              }
              attachment={attachments[n.id] || attachments[String(n.id).toLowerCase()]}
              attachmentsList={
                attachmentsList[n.id] ||
                attachmentsList[String(n.id).toLowerCase()] ||
                (attachments[n.id] ? [{ ...attachments[n.id], mimeType: "image/jpeg" }] : [])
              }
              onEdit={() => setEditing(n)}
              onDelete={() => setDeleting(n)}
              onOpenDetail={() => setDetailNotice(n)}
              onOpenAssessment={setNoticeAssessmentId}
              onReaders={(triggerRef) => setReaders({ notice: n, triggerRef })}
              onComments={() => setComments(n)}
              onLike={(liked) =>
                reaction(n, { liked }).catch((e) => {
                  setError(e);
                  throw e;
                })
              }
              onReminder={() => setReminder(n)}
              markRead={() => {
                // Optimistically clear unread badge in unread-activity query
                qc.setQueryData(
                  [ctx.user, ctx.batch, "unread-activity"],
                  (old: any) => {
                    if (!old) return old;
                    return {
                      ...old,
                      notices: false,
                      notice_count: 0,
                    };
                  },
                );
                rpc("mark_notices_read", { target_ids: [n.id] })
                  .then(() => Promise.all([
                    qc.invalidateQueries({queryKey: [ctx.user, ctx.batch, "notices"]}),
                    qc.invalidateQueries({queryKey: [ctx.user, ctx.batch, "unread-activity"]}),
                  ]))
                  .catch(() => {});
              }}
            />
          ))}
      </div>
      {!feed.isPending && !notices.length && (
        <Empty
          title={ctx.profile.role === "teacher" ? "You haven’t posted any notices" : "You’re all caught up"}
          body={ctx.profile.role === "teacher" ? "Try posting your first course notice!" : "Updates for your batch will appear here."}
        />
      )}
      <div ref={end} className="paging">
        {feed.isFetchingNextPage ? (
          <Skeleton />
        ) : feed.hasNextPage ? (
          <button onClick={() => feed.fetchNextPage()}>
            {feed.isFetchNextPageError
              ? "Try loading older notices again"
              : "Load 10 older notices"}
          </button>
        ) : notices.length > 0 ? (
          <small>You’ve reached the beginning.</small>
        ) : null}
      </div>
      {composer && (
        <Modal title="Post a notice" close={() => setComposer(false)}>
          <NoticeComposer
            ctx={ctx}
            allowed={permissions.data}
            done={async (created?: Row) => {
              if (created && created.id) {
                qc.setQueryData(
                  [ctx.user, ctx.batch, "notices"],
                  (old: any) => {
                    if (!old || !old.pages || old.pages.length === 0) return old;
                    const firstPage = old.pages[0];
                    const newEntries = [created, ...firstPage.entries.filter((e: Row) => e.id !== created.id)];
                    const newPages = [
                      { ...firstPage, entries: newEntries },
                      ...old.pages.slice(1),
                    ];
                    return { ...old, pages: newPages };
                  },
                );
              }
              await refresh();
              setComposer(false);
            }}
          />
        </Modal>
      )}
      {editing && (
        <Modal title="Edit notice" close={() => setEditing(null)}>
          <Form
            fields={[
              {
                name: "target_title",
                label: "Title",
                required: true,
                value: editing.title,
              },
              {
                name: "target_body",
                label: "Message",
                type: "textarea",
                value: editing.body,
              },
            ]}
            submit={async (d) => {
              await rpc("edit_notice", { target_id: editing.id, ...d });
              await refresh();
              setEditing(null);
            }}
          />
        </Modal>
      )}
      {deleting && (
        <Confirm
          title="Delete notice?"
          body="This notice will be removed from your batch feed."
          close={() => setDeleting(null)}
          action={async () => {
            await rpc("delete_notice", { target_id: deleting.id });
            await refresh();
          }}
        />
      )}
      {readers && (
        <ReaderList
          notice={readers.notice}
          ctx={ctx}
          receiptsEnabled={receiptsEnabled.data !== false}
          triggerRef={readers.triggerRef}
          close={() => setReaders(null)}
        />
      )}{" "}
      {comments && (
        <Comments notice={comments} ctx={ctx} close={() => setComments(null)} />
      )}{" "}
      {detailNotice && (
        <NoticeDetailModal
          notice={detailNotice}
          resource={details.resources?.find((x: Row) => x.notice_id === detailNotice.id)}
          attachment={attachments[detailNotice.id] || attachments[String(detailNotice.id).toLowerCase()]}
          attachmentsList={
            attachmentsList[detailNotice.id] ||
            attachmentsList[String(detailNotice.id).toLowerCase()] ||
            (attachments[detailNotice.id] ? [{ ...attachments[detailNotice.id], mimeType: "image/jpeg" }] : [])
          }
          close={() => setDetailNotice(null)}
        />
      )}{" "}
      {noticeAssessmentId && (
        <Modal
          title={
            noticeAssessmentQuery.data
              ? `${noticeAssessmentQuery.data.type?.toUpperCase() || "ASSESSMENT"} · ${noticeAssessmentQuery.data.course_code || ""}`
              : "Assessment Details"
          }
          close={() => setNoticeAssessmentId(null)}
        >
          {noticeAssessmentQuery.isLoading ? (
            <div style={{ padding: 24, textAlign: "center" }}>
              <LoaderCircle className="spin" size={24} />
            </div>
          ) : noticeAssessmentQuery.data ? (
            <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
              <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                <span
                  style={{
                    padding: "4px 10px",
                    borderRadius: 9999,
                    fontSize: "0.75rem",
                    fontWeight: 700,
                    textTransform: "uppercase",
                    background: "rgba(99, 102, 241, 0.12)",
                    color: "var(--accent, #6366f1)",
                    border: "1px solid rgba(99, 102, 241, 0.25)",
                  }}
                >
                  {noticeAssessmentQuery.data.type || "Assessment"}
                </span>
                <span style={{ fontSize: "0.85rem", fontWeight: 600, color: "var(--foreground)" }}>
                  {noticeAssessmentQuery.data.course_code}
                </span>
              </div>

              <div>
                <h3 style={{ fontSize: "1.15rem", fontWeight: 700, margin: "0 0 6px 0", color: "var(--foreground)" }}>
                  {noticeAssessmentQuery.data.title}
                </h3>
                <p style={{ margin: 0, fontSize: "0.88rem", color: "var(--muted)" }}>
                  {noticeAssessmentQuery.data.course_title}
                </p>
              </div>

              <div
                style={{
                  display: "grid",
                  gridTemplateColumns: "1fr 1fr",
                  gap: 10,
                  padding: "12px 14px",
                  borderRadius: 12,
                  background: "var(--surface-variant, rgba(0,0,0,0.03))",
                  border: "1px solid var(--border)",
                  fontSize: "0.85rem",
                }}
              >
                <div>
                  <div style={{ color: "var(--muted)", fontSize: "0.75rem", marginBottom: 2 }}>📅 Deadline</div>
                  <div style={{ fontWeight: 600 }}>
                    {noticeAssessmentQuery.data.deadline_date}{" "}
                    {noticeAssessmentQuery.data.deadline_time ? `at ${noticeAssessmentQuery.data.deadline_time}` : ""}
                  </div>
                </div>
                {noticeAssessmentQuery.data.room && (
                  <div>
                    <div style={{ color: "var(--muted)", fontSize: "0.75rem", marginBottom: 2 }}>📍 Venue</div>
                    <div style={{ fontWeight: 600 }}>
                      {/^\d+$/.test(noticeAssessmentQuery.data.room.trim())
                        ? `Room ${noticeAssessmentQuery.data.room.trim()}`
                        : noticeAssessmentQuery.data.room.trim()}
                    </div>
                  </div>
                )}
              </div>

              {noticeAssessmentQuery.data.syllabus && (
                <div>
                  <div style={{ fontWeight: 600, fontSize: "0.85rem", marginBottom: 6, color: "var(--muted)" }}>
                    Syllabus & Instructions
                  </div>
                  <div
                    style={{
                      padding: "12px 14px",
                      borderRadius: 12,
                      background: "var(--surface-variant, rgba(0,0,0,0.02))",
                      border: "1px solid var(--border)",
                      fontSize: "0.9rem",
                      lineHeight: 1.5,
                      whiteSpace: "pre-wrap",
                    }}
                  >
                    {noticeAssessmentQuery.data.syllabus}
                  </div>
                </div>
              )}

              <button
                type="button"
                className="accent"
                style={{
                  width: "100%",
                  marginTop: 8,
                  padding: "10px 16px",
                  borderRadius: 12,
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  gap: 8,
                  fontWeight: 600,
                  cursor: "pointer",
                }}
                onClick={() => {
                  setNoticeAssessmentId(null);
                  window.location.hash = "#upcoming";
                }}
              >
                <CalendarClock size={16} />
                <span>Browse Upcoming Tab</span>
              </button>
            </div>
          ) : (
            <div style={{ padding: 16, textAlign: "center", color: "var(--muted)" }}>
              Assessment details could not be loaded.
            </div>
          )}
        </Modal>
      )}{" "}
      {reminder && (
        <Modal title="Set a reminder" close={() => setReminder(null)}>
          <p>
            Reminders appear while ClassMate Web is open. Browser background
            scheduling is not guaranteed.
          </p>
          <Form
            fields={[
              {
                name: "at",
                label: "Remind me at",
                type: "datetime-local",
                required: true,
                min: new Date(Date.now() + 60_000).toISOString().slice(0, 16),
              },
            ]}
            submit={async (d) => {
              const at = new Date(d.at);
              if (at.getTime() <= Date.now())
                throw new Error("Choose a future time.");
              await reaction(reminder, { reminder_at: at.toISOString() });
              setReminder(null);
            }}
          />
          <button
            className="text-button"
            onClick={async () => {
              try {
                await reaction(reminder, { reminder_at: null });
                setReminder(null);
              } catch (e) {
                setError(e);
              }
            }}
          >
            Remove reminder
          </button>
        </Modal>
      )}
    </div>
  );
}
function NoticeComposer({
  ctx,
  allowed,
  done,
}: {
  ctx: Context;
  allowed?: Row;
  done: (created?: Row) => Promise<void>;
}) {
  const [type, setType] = useState<"general" | "cancelled" | "vacation" | "poll">("general");

  // Image attachment state matching Android PostNoticeActivity
  const [imageFile, setImageFile] = useState<File | null>(null);
  const [imagePreview, setImagePreview] = useState<string | null>(null);
  const [imageError, setImageError] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const handleImageChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setImageError(null);
    const validTypes = ["image/jpeg", "image/png", "image/webp"];
    if (!validTypes.includes(file.type)) {
      setImageError("Only JPG, PNG, or WebP images are supported.");
      return;
    }
    if (file.size > 100 * 1024 * 1024) {
      setImageError("Choose an image smaller than 100 MB.");
      return;
    }
    setImageFile(file);
    setImagePreview(URL.createObjectURL(file));
  };

  const removeImage = () => {
    setImageFile(null);
    if (imagePreview) {
      URL.revokeObjectURL(imagePreview);
      setImagePreview(null);
    }
    if (fileInputRef.current) fileInputRef.current.value = "";
    setImageError(null);
  };

  return (
    <>
      <div className="notice-compose-subtypes">
        <button
          type="button"
          className={type === "general" ? "selected" : ""}
          onClick={() => setType("general")}
        >
          General Notice
        </button>
        <button
          type="button"
          className={type === "cancelled" ? "selected" : ""}
          onClick={() => setType("cancelled")}
        >
          Class Cancellation
        </button>
        <button
          type="button"
          className={type === "vacation" ? "selected" : ""}
          onClick={() => setType("vacation")}
        >
          <Palmtree size={14} style={{ display: "inline", marginRight: "4px" }} /> Vacation
        </button>
        <button
          type="button"
          className={type === "poll" ? "selected" : ""}
          onClick={() => setType("poll")}
        >
          <BarChart2 size={14} style={{ display: "inline", marginRight: "4px" }} /> Poll
        </button>
      </div>

      {type === "poll" ? (
        <PollComposeForm
          ctx={ctx}
          onCancel={() => done()}
          onCreated={(created) => done(created)}
        />
      ) : (
          <Form
            key={type}
            label={type === "vacation" ? "Publish vacation notice" : type === "cancelled" ? "Post cancellation" : "Post notice"}
            fields={
              type === "general"
                ? [
                    ...(!allowed?.general
                      ? [
                          {
                            name: "course",
                            label: "Assigned course",
                            required: true,
                            options: ctx.courses
                              .filter((c) => allowed?.courses[c.offering_id])
                              .map((c) => ({
                                value: c.offering_id,
                                label: c.course_title,
                              })),
                          },
                        ]
                      : []),
                    { name: "title", label: "Title", required: true },
                    {
                      name: "body",
                      label: "Message",
                      type: "textarea",
                      required: true,
                      placeholder: "Start with /silent for a feed-only notice",
                    },
                  ]
                : type === "cancelled"
                ? [
                    {
                      name: "course",
                      label: "Course",
                      required: true,
                      options: ctx.courses
                        .filter((c) => allowed?.courses[c.offering_id])
                        .map((c) => ({
                          value: c.offering_id,
                          label: c.course_title,
                        })),
                    },
                    {
                      name: "date",
                      label: "Which day?",
                      required: true,
                      options: [
                        { value: dhakaToday(), label: "Today" },
                        {
                          value: (() => {
                            const d = parseDate(dhakaToday());
                            d.setDate(d.getDate() + 1);
                            return dateKey(d);
                          })(),
                          label: "Tomorrow",
                        },
                      ],
                    },
                  ]
                : [
                    {
                      name: "title",
                      label: "Vacation / Occasion title",
                      required: true,
                      placeholder: "e.g. Eid-ul-Fitr Vacation, Puja Holidays, Summer Break",
                    },
                    {
                      name: "start_date",
                      label: "Start date",
                      type: "date",
                      required: true,
                      value: dhakaToday(),
                    },
                    {
                      name: "end_date",
                      label: "End date",
                      type: "date",
                      required: true,
                      value: dhakaToday(),
                    },
                    {
                      name: "body",
                      label: "Details & Resumption notes (Optional)",
                      type: "textarea",
                      required: false,
                      placeholder: "Additional guidelines or when classes resume...",
                    },
                  ]
            }
            extra={
              type === "general" ? (
                <div style={{ marginTop: "12px", marginBottom: "14px" }}>
                  <input
                    type="file"
                    ref={fileInputRef}
                    onChange={handleImageChange}
                    accept="image/jpeg,image/png,image/webp"
                    style={{ display: "none" }}
                  />
                  {!imageFile ? (
                    <button
                      type="button"
                      className="secondary"
                      onClick={() => fileInputRef.current?.click()}
                      style={{
                        display: "inline-flex",
                        alignItems: "center",
                        gap: "8px",
                        fontSize: "13px",
                        padding: "8px 14px",
                        borderRadius: "10px",
                        cursor: "pointer",
                      }}
                    >
                      <ImageIcon size={16} /> Attach Image (JPG, PNG, WebP)
                    </button>
                  ) : (
                    <div
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: "12px",
                        padding: "10px 14px",
                        borderRadius: "12px",
                        border: "1px solid var(--border)",
                        background: "var(--card)",
                        maxWidth: "420px",
                      }}
                    >
                      {imagePreview && (
                        <img
                          src={imagePreview}
                          alt="Attachment preview"
                          style={{
                            width: "48px",
                            height: "48px",
                            objectFit: "cover",
                            borderRadius: "8px",
                            border: "1px solid var(--border)",
                          }}
                        />
                      )}
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div
                          style={{
                            fontSize: "13px",
                            fontWeight: 600,
                            overflow: "hidden",
                            textOverflow: "ellipsis",
                            whiteSpace: "nowrap",
                          }}
                        >
                          {imageFile.name}
                        </div>
                        <div style={{ fontSize: "11px", color: "var(--muted)" }}>
                          {(imageFile.size / 1024 / 1024).toFixed(2)} MB
                        </div>
                      </div>
                      <button
                        type="button"
                        onClick={removeImage}
                        className="icon"
                        title="Remove image"
                        style={{ padding: "6px" }}
                      >
                        <X size={16} />
                      </button>
                    </div>
                  )}
                  {imageError && (
                    <div style={{ color: "var(--danger, #ef4444)", fontSize: "12px", marginTop: "6px" }}>
                      {imageError}
                    </div>
                  )}
                  {uploadProgress && (
                    <div
                      style={{
                        color: "var(--primary)",
                        fontSize: "12px",
                        marginTop: "6px",
                        display: "flex",
                        alignItems: "center",
                        gap: "6px",
                      }}
                    >
                      <LoaderCircle size={14} className="spin" /> {uploadProgress}
                    </div>
                  )}
                </div>
              ) : null
            }
            submit={async (d) => {
              let created: Row | null = null;
              if (type === "general") {
                created = await rpc<Row>("post_notice", {
                  target_batch: ctx.batch,
                  target_course: d.course || null,
                  notice_title: d.title,
                  notice_body: d.body,
                });

                if (created && created.id && imageFile) {
                  try {
                    setUploadProgress("Uploading image attachment…");
                    const { data: prep, error: prepError } = await supabase().rpc("prepare_notice_image", {
                      target_notice: created.id,
                      image_file_name: imageFile.name,
                      image_mime_type: imageFile.type,
                      image_size_bytes: imageFile.size,
                    });
                    if (prepError) throw prepError;
                    const storageKey = prep?.storage_key;
                    if (!storageKey) throw new Error("Could not prepare image upload");

                    const { error: upError } = await supabase().storage
                      .from("notice-images")
                      .upload(storageKey, imageFile, {
                        contentType: imageFile.type,
                        upsert: false,
                      });

                    if (upError) {
                      try {
                        await supabase().rpc("discard_failed_notice_upload", { target_notice: created.id });
                      } catch {
                        // ignore discard errors
                      }
                      throw upError;
                    }
                  } catch (err: any) {
                    console.error("Notice image attachment upload failed:", err);
                    throw new Error(`Notice posted, but attachment upload failed: ${err.message || err}`);
                  } finally {
                    setUploadProgress(null);
                  }
                }
              } else if (type === "cancelled") {
                created = await rpc<Row>("post_cancellation_notice", {
                  target_batch: ctx.batch,
                  target_course: d.course,
                  change_date: d.date,
                });
              } else {
                // Vacation Notice
                const vacTitle = `Vacation: ${d.title}`;
                const dateDuration =
                  d.start_date === d.end_date
                    ? `Date: ${d.start_date}`
                    : `Duration: ${d.start_date} to ${d.end_date}`;
                const vacBody = [
                  `🌴 ${vacTitle}`,
                  `📅 ${dateDuration}`,
                  d.body?.trim() ? d.body.trim() : "",
                  "Classes and academic activities will remain suspended during this vacation period.",
                ]
                  .filter(Boolean)
                  .join("\n\n");

                created = await rpc<Row>("post_notice", {
                  target_batch: ctx.batch,
                  target_course: null,
                  notice_title: vacTitle,
                  notice_body: vacBody,
                });

                try {
                  await rpc("save_academic_calendar_event", {
                    target_title: d.title,
                    target_start: d.start_date,
                    target_end: d.end_date,
                    target_scope: "all",
                  });
                } catch (_) {}
              }
              await done(created || undefined);
            }}
          />
      )}
    </>
  );
}
function Highlight({ text, search }: { text: string; search: string }) {
  if (!search) return <>{text}</>;
  const lower = text.toLowerCase(),
    term = search.toLowerCase(),
    parts: React.ReactNode[] = [];
  let pos = 0,
    index = lower.indexOf(term);
  while (index >= 0) {
    parts.push(
      text.slice(pos, index),
      <mark key={index}>{text.slice(index, index + search.length)}</mark>,
    );
    pos = index + search.length;
    index = lower.indexOf(term, pos);
  }
  parts.push(text.slice(pos));
  return <>{parts}</>;
}

export function canViewNoticeReaders(_notice?: Row, _ctx?: Context): boolean {
  return true;
}

const urlRegex =
  /((?:https?:\/\/|www\.)[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-]|\b[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*\.(?:com|org|net|edu|gov|mil|app|dev|io|co|me|bd|ai|tech|xyz|info|biz|tv|cc|live|online|site|page|link|store|cloud|space|uk|us|ca|de|in|eu|au|fr|jp|gg|so|gl|ly|to|mobi|pro|name|asia|int|arpa)(?::[0-9]{1,5})?(?:\/[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-]|(?!\/)))/gi;

const isUrl = (s: string) =>
  /^(?:https?:\/\/|www\.)[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-]$/i.test(s) ||
  /^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*\.(?:com|org|net|edu|gov|mil|app|dev|io|co|me|bd|ai|tech|xyz|info|biz|tv|cc|live|online|site|page|link|store|cloud|space|uk|us|ca|de|in|eu|au|fr|jp|gg|so|gl|ly|to|mobi|pro|name|asia|int|arpa)(?::[0-9]{1,5})?(?:\/[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-])?$/i.test(s);

function truncateSafely(str: string, maxLen: number): string {
  if (str.length <= maxLen) return str;
  let cutoff = maxLen;
  const lastSpace = str.lastIndexOf(" ", cutoff);
  const lastNewline = str.lastIndexOf("\n", cutoff);
  const boundary = Math.max(lastSpace, lastNewline);
  if (boundary > maxLen * 0.6) {
    cutoff = boundary;
  }
  return str.slice(0, cutoff).trimEnd();
}


export function NoticeImageLightbox({
  src,
  fileName,
  images,
  initialIndex = 0,
  onClose,
}: {
  src?: string;
  fileName?: string;
  images?: Array<{ url: string; fileName?: string }>;
  initialIndex?: number;
  onClose: () => void;
}) {
  const allImages = images && images.length > 0 ? images : src ? [{ url: src, fileName }] : [];
  const [index, setIndex] = useState(
    Math.max(0, Math.min(initialIndex, allImages.length - 1))
  );

  const current = allImages[index] || { url: src || "", fileName };

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      else if (e.key === "ArrowLeft") {
        setIndex((prev) => (prev > 0 ? prev - 1 : allImages.length - 1));
      } else if (e.key === "ArrowRight") {
        setIndex((prev) => (prev < allImages.length - 1 ? prev + 1 : 0));
      }
    };
    window.addEventListener("keydown", onKeyDown);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = prevOverflow;
    };
  }, [onClose, allImages.length]);

  return (
    <div
      className="notice-lightbox-overlay"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 99999,
        background: "rgba(0, 0, 0, 0.94)",
        backdropFilter: "blur(12px)",
        WebkitBackdropFilter: "blur(12px)",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        cursor: "zoom-out",
      }}
    >
      <div
        className="notice-lightbox-topbar"
        style={{
          position: "absolute",
          top: 0,
          left: 0,
          right: 0,
          height: "64px",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "0 20px",
          background: "linear-gradient(to bottom, rgba(0, 0, 0, 0.75), transparent)",
          zIndex: 2,
          pointerEvents: "auto",
          cursor: "default",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <span
          style={{
            color: "#ffffff",
            fontSize: "14px",
            fontWeight: 500,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
            maxWidth: "70vw",
          }}
          title={current.fileName}
        >
          {current.fileName || "Notice Attachment"}
          {allImages.length > 1 ? ` (${index + 1} of ${allImages.length})` : ""}
        </span>
        <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
          <a
            href={current.url}
            target="_blank"
            rel="noopener noreferrer"
            title="Open original"
            style={{
              color: "#ffffff",
              background: "rgba(255, 255, 255, 0.16)",
              border: "none",
              borderRadius: "50%",
              width: "38px",
              height: "38px",
              display: "grid",
              placeItems: "center",
              textDecoration: "none",
            }}
          >
            <ExternalLink size={18} />
          </a>
          <button
            type="button"
            onClick={onClose}
            title="Close (Esc)"
            style={{
              color: "#ffffff",
              background: "rgba(255, 255, 255, 0.16)",
              border: "none",
              borderRadius: "50%",
              width: "38px",
              height: "38px",
              display: "grid",
              placeItems: "center",
              cursor: "pointer",
            }}
          >
            <X size={20} />
          </button>
        </div>
      </div>

      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          padding: "68px 16px 24px",
          boxSizing: "border-box",
          position: "relative",
        }}
      >
        {allImages.length > 1 && (
          <>
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                setIndex((prev) => (prev > 0 ? prev - 1 : allImages.length - 1));
              }}
              style={{
                position: "absolute",
                left: "20px",
                top: "50%",
                transform: "translateY(-50%)",
                background: "rgba(255, 255, 255, 0.18)",
                border: "none",
                borderRadius: "50%",
                width: "44px",
                height: "44px",
                color: "white",
                display: "grid",
                placeItems: "center",
                cursor: "pointer",
                zIndex: 3,
                transition: "background 0.2s",
              }}
              title="Previous photo"
            >
              <ChevronLeft size={24} />
            </button>
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                setIndex((prev) => (prev < allImages.length - 1 ? prev + 1 : 0));
              }}
              style={{
                position: "absolute",
                right: "20px",
                top: "50%",
                transform: "translateY(-50%)",
                background: "rgba(255, 255, 255, 0.18)",
                border: "none",
                borderRadius: "50%",
                width: "44px",
                height: "44px",
                color: "white",
                display: "grid",
                placeItems: "center",
                cursor: "pointer",
                zIndex: 3,
                transition: "background 0.2s",
              }}
              title="Next photo"
            >
              <ChevronRight size={24} />
            </button>
          </>
        )}

        <img
          key={current.url}
          src={current.url}
          alt={current.fileName || "Notice attachment"}
          onClick={(e) => e.stopPropagation()}
          style={{
            maxWidth: "96vw",
            maxHeight: "88vh",
            objectFit: "contain",
            borderRadius: "6px",
            boxShadow: "0 10px 40px rgba(0,0,0,0.6)",
            userSelect: "none",
            cursor: "default",
          }}
        />
      </div>
    </div>
  );
}

export function NoticeAttachmentsView({
  attachments,
  onImageClick,
  inModal = false,
}: {
  attachments: NoticeAttachmentItem[];
  onImageClick?: (item: NoticeAttachmentItem, index: number, allImages: NoticeAttachmentItem[]) => void;
  inModal?: boolean;
}) {
  if (!attachments || attachments.length === 0) return null;

  const docs = attachments.filter(
    (a) =>
      a.mimeType === "application/pdf" ||
      a.fileName?.toLowerCase().endsWith(".pdf")
  );
  const images = attachments.filter(
    (a) =>
      a.mimeType?.startsWith("image/") ||
      !a.fileName?.toLowerCase().endsWith(".pdf")
  );

  return (
    <div className="notice-attachments-container" style={{ marginTop: "12px", marginBottom: "12px" }}>
      {/* PDF / Document Attachments */}
      {docs.map((doc) => (
        <div
          key={doc.id}
          className="notice-attachment-doc-card"
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: "12px",
            padding: "10px 14px",
            marginBottom: "8px",
            borderRadius: "12px",
            border: "1px solid var(--border)",
            background: "var(--card)",
            maxWidth: inModal ? "100%" : "520px",
          }}
          onClick={(e) => e.stopPropagation()}
        >
          <div style={{ display: "flex", alignItems: "center", gap: "10px", minWidth: 0 }}>
            <div
              style={{
                width: "36px",
                height: "36px",
                borderRadius: "8px",
                background: "rgba(239, 68, 68, 0.12)",
                color: "#ef4444",
                display: "grid",
                placeItems: "center",
                flexShrink: 0,
                fontWeight: 700,
                fontSize: "11px",
              }}
            >
              PDF
            </div>
            <div style={{ minWidth: 0 }}>
              <div
                style={{
                  fontWeight: 600,
                  fontSize: "13px",
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                  whiteSpace: "nowrap",
                }}
                title={doc.fileName}
              >
                {doc.fileName}
              </div>
              <div style={{ fontSize: "11px", color: "var(--muted)", marginTop: "1px" }}>
                {doc.sizeBytes > 1048576
                  ? `${(doc.sizeBytes / 1048576).toFixed(1)} MB`
                  : `${Math.round(doc.sizeBytes / 1024)} KB`}
              </div>
            </div>
          </div>
          <a
            href={doc.url}
            target="_blank"
            rel="noopener noreferrer"
            className="secondary"
            style={{
              display: "inline-flex",
              alignItems: "center",
              gap: "6px",
              padding: "6px 12px",
              borderRadius: "8px",
              fontSize: "12px",
              textDecoration: "none",
              flexShrink: 0,
            }}
          >
            <span>Open</span>
            <ExternalLink size={13} />
          </a>
        </div>
      ))}

      {/* Image Attachments */}
      {images.length === 1 && (
        <div
          className="notice-attachment-wrapper"
          style={{
            borderRadius: "12px",
            overflow: "hidden",
            border: "1px solid var(--border)",
            background: "rgba(0,0,0,0.06)",
            maxWidth: inModal ? "100%" : "520px",
          }}
          onClick={(e) => e.stopPropagation()}
        >
          <img
            src={images[0].url}
            alt={images[0].fileName || "Notice attachment"}
            style={{
              width: "100%",
              maxHeight: inModal ? "480px" : "360px",
              objectFit: inModal ? "contain" : "cover",
              display: "block",
              cursor: "pointer",
            }}
            onClick={() => onImageClick?.(images[0], 0, images)}
            loading="lazy"
          />
          <div
            style={{
              padding: "8px 12px",
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              fontSize: "12px",
              color: "var(--muted)",
              background: "var(--card)",
              borderTop: "1px solid var(--border)",
            }}
          >
            <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", maxWidth: "260px" }}>
              {images[0].fileName}
            </span>
            <span>
              {images[0].sizeBytes > 1048576
                ? `${(images[0].sizeBytes / 1048576).toFixed(1)} MB`
                : `${Math.round(images[0].sizeBytes / 1024)} KB`}
            </span>
          </div>
        </div>
      )}

      {images.length > 1 && (
        <div
          className="notice-attachment-grid"
          style={{
            display: "grid",
            gridTemplateColumns: images.length === 2 ? "1fr 1fr" : "repeat(auto-fit, minmax(160px, 1fr))",
            gap: "8px",
            maxWidth: inModal ? "100%" : "520px",
            borderRadius: "12px",
            overflow: "hidden",
          }}
          onClick={(e) => e.stopPropagation()}
        >
          {images.map((img, idx) => (
            <div
              key={img.id || idx}
              style={{
                position: "relative",
                height: inModal ? "220px" : "180px",
                borderRadius: "10px",
                overflow: "hidden",
                border: "1px solid var(--border)",
                cursor: "pointer",
                background: "rgba(0,0,0,0.06)",
              }}
              onClick={() => onImageClick?.(img, idx, images)}
            >
              <img
                src={img.url}
                alt={img.fileName || `Attachment ${idx + 1}`}
                style={{
                  width: "100%",
                  height: "100%",
                  objectFit: "cover",
                  display: "block",
                  transition: "transform 0.2s ease",
                }}
                loading="lazy"
              />
              <div
                style={{
                  position: "absolute",
                  bottom: 0,
                  left: 0,
                  right: 0,
                  padding: "4px 8px",
                  background: "linear-gradient(to top, rgba(0,0,0,0.7), transparent)",
                  color: "white",
                  fontSize: "11px",
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                  whiteSpace: "nowrap",
                }}
              >
                {img.fileName}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function NoticeCard({
  notice: n,
  ctx,
  batch,
  state,
  read,
  previews,
  attachment,
  attachmentsList,
  poll,
  resource,
  onVote,
  editable,
  search,
  onLike,
  onReminder,
  onComments,
  onReaders,
  onOpenDetail,
  onOpenAssessment,
  onEdit,
  onDelete,
  markRead,
}: {
  notice: Row;
  ctx: Context;
  batch: string;
  state: Row;
  read: Row;
  previews: Row[];
  attachment?: { id: string; url: string; fileName: string; sizeBytes: number };
  attachmentsList?: NoticeAttachmentItem[];
  poll?: Row;
  resource?: Row;
  onVote?: (pollId: string, optionId: string) => Promise<void>;
  editable: boolean;
  search: string;
  onLike: (liked: boolean) => Promise<void>;
  onReminder: () => void;
  onComments: () => void;
  onReaders: (triggerRef?: React.RefObject<HTMLElement | null>) => void;
  onOpenDetail: () => void;
  onOpenAssessment?: (id: string) => void;
  onEdit: () => void;
  onDelete: () => void;
  markRead: () => void;
}) {
  const [lightboxState, setLightboxState] = useState<{
    images: Array<{ url: string; fileName?: string }>;
    initialIndex: number;
  } | null>(null);
  const [copiedShare, setCopiedShare] = useState(false);
  const [expanded, setExpanded] = useState(false),
    [menu, setMenu] = useState(false),
    [liked, setLiked] = useState(!!state.is_liked),
    [pending, setPending] = useState(false),
    [reminderDue, setReminderDue] = useState(false);
  const [translated, setTranslated] = useState<Row | null>(null),
    [showTranslation, setShowTranslation] = useState(false),
    [translating, setTranslating] = useState(false),
    [translationError, setTranslationError] = useState<unknown>(null);
  const translationRequest = useRef<string | null>(null);
  useEffect(() => {
    setTranslated(null);
    setShowTranslation(false);
    setTranslationError(null);
    translationRequest.current = null;
  }, [n.id, n.title, n.body, batch]);
  const ref = useRef<HTMLElement>(null);
  const seenBtnRef = useRef<HTMLButtonElement>(null);
  const canViewReaders = canViewNoticeReaders(n, ctx);
  const seen = useRef(false);
  useEffect(() => setLiked(!!state.is_liked), [state.is_liked]);
  useEffect(() => {
    if (!ref.current || read.read_by_me || n.author_id === ctx.user) return;
    let timer: ReturnType<typeof setTimeout>;
    const observer = new IntersectionObserver(
      (entries) => {
        clearTimeout(timer);
        if (entries[0].isIntersecting && !seen.current)
          timer = setTimeout(() => {
            if (document.visibilityState === "visible" && navigator.onLine) {
              seen.current = true;
              markRead();
            }
          }, 1200);
      },
      { threshold: 0.5 },
    );
    observer.observe(ref.current);
    return () => {
      clearTimeout(timer);
      observer.disconnect();
    };
  }, [read.read_by_me, markRead, n.author_id, ctx.user]);
  useEffect(() => {
    if (!state.reminder_at) return;
    const due = new Date(state.reminder_at).getTime();
    const timer = setInterval(() => {
      if (Date.now() >= due) {
        setReminderDue(true);
        clearInterval(timer);
      }
    }, 15000);
    return () => clearInterval(timer);
  }, [state.reminder_at]);
  const kind = poll
    ? "poll"
    : n.resource_id
      ? "resource"
      : n.class_change_id
        ? "cancelled"
        : "general";
  const rawBody = String((showTranslation ? translated?.body : n.body) || "");
  const assessmentMatch = rawBody.match(/\[assessment_id:([^\]]+)\]/);
  const assessmentId = assessmentMatch ? assessmentMatch[1] : null;
  const body = rawBody.replace(/\s*\[assessment_id:[^\]]*\]\s*/g, "").trimEnd();
  const rawLines = body.split("\n");
  const hasLineOverflow = rawLines.length > 5;
  const isCharOverflow = body.length > 350;
  const isOverflow = hasLineOverflow || isCharOverflow;
  const text = (() => {
    if (expanded || !isOverflow) return body;
    if (hasLineOverflow) {
      const preview = rawLines.slice(0, 5).join("\n").trimEnd();
      return preview.length > 280 ? truncateSafely(preview, 280) : preview;
    }
    return truncateSafely(body, 280);
  })();

  const handleLinkClick = (e: React.MouseEvent) => {
    e.stopPropagation();
  };

  const handleBodyClick = () => {
    const selection = window.getSelection();
    if (selection && selection.toString().trim().length > 0) {
      return;
    }
    if (isOverflow) {
      setExpanded(!expanded);
    }
  };



  return (
    <article ref={ref} className={`notice-card ${kind}`}>
      <span className="notice-accent" />
      {editable && (
        <div className="notice-menu">
          <button
            className="icon"
            aria-label="Manage notice"
            aria-expanded={menu}
            onClick={() => setMenu(!menu)}
          >
            <MoreVertical size={20} />
          </button>
          {menu && (
            <div className="dropdown">
              <button
                onClick={() => {
                  setMenu(false);
                  onEdit();
                }}
              >
                Edit
              </button>
              <button
                className="danger"
                onClick={() => {
                  setMenu(false);
                  onDelete();
                }}
              >
                Delete
              </button>
            </div>
          )}
        </div>
      )}
      <div className="notice-content">
        <h2
          className={`${isOverflow ? "clickable" : ""} ${
            poll ? "poll-title-unfold" : ""
          } ${
            (n.title?.length || 0) > 100
              ? "notice-title-shrink-lg"
              : (n.title?.length || 0) > 50
              ? "notice-title-shrink-md"
              : ""
          }`}
          onClick={() => {
            if (isOverflow) setExpanded(!expanded);
          }}
          title={isOverflow ? "Click to expand or collapse notice" : undefined}
        >
          <Highlight
            text={showTranslation ? translated?.title || n.title : n.title}
            search={search}
          />
        </h2>
        <div
          className={`notice-body ${expanded ? "expanded" : ""} ${isOverflow ? "clickable" : ""}`}
          onClick={handleBodyClick}
        >
          <WhatsAppText text={text} search={search} />
          {isOverflow && (
            <button
              type="button"
              className="inline accent notice-inline-toggle"
              onClick={(e) => {
                e.stopPropagation();
                setExpanded(!expanded);
              }}
            >
              {expanded ? "  see less" : " … see more"}
            </button>
          )}
        </div>
        {assessmentId && (
          <button
            type="button"
            className="quick-file"
            style={{
              borderColor: "rgba(99, 102, 241, 0.25)",
              color: "var(--accent, #6366f1)",
              display: "flex",
              alignItems: "center",
              gap: 10,
              width: "100%",
              padding: "10px 14px",
              borderRadius: 12,
              marginTop: 10,
              cursor: "pointer",
              background: "rgba(99, 102, 241, 0.05)",
              textAlign: "left",
            }}
            onClick={(e) => {
              e.stopPropagation();
              onOpenAssessment ? onOpenAssessment(assessmentId) : (window.location.hash = `#upcoming?id=${assessmentId}`);
            }}
          >
            <CalendarClock size={20} style={{ flexShrink: 0, color: "var(--accent, #6366f1)" }} />
            <div style={{ display: "flex", flexDirection: "column", alignItems: "flex-start", flex: 1, minWidth: 0 }}>
              <span style={{ fontWeight: 600, fontSize: "0.88rem", color: "var(--foreground)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", width: "100%" }}>
                {n.title?.split("•")?.[1]?.trim() || n.title || "Assessment Details"}
              </span>
              <span style={{ opacity: 0.75, fontSize: "0.75rem", color: "var(--muted)" }}>
                Click to view syllabus & deadline
              </span>
            </div>
            <ExternalLink size={16} style={{ flexShrink: 0, opacity: 0.7 }} />
          </button>
        )}
        {poll && (
          <M3PollView
            poll={poll}
            onVote={(optId) => onVote?.(poll.poll_id || poll.id, optId)}
          />
        )}
        {((attachmentsList && attachmentsList.length > 0) || attachment?.url) && (
          <NoticeAttachmentsView
            attachments={
              attachmentsList && attachmentsList.length > 0
                ? attachmentsList
                : attachment
                ? [{ ...attachment, mimeType: "image/jpeg" }]
                : []
            }
            onImageClick={(item, index, allImages) => {
              setLightboxState({
                images: allImages.map((img) => ({ url: img.url, fileName: img.fileName })),
                initialIndex: index,
              });
            }}
          />
        )}
        {lightboxState && (
          <NoticeImageLightbox
            images={lightboxState.images}
            initialIndex={lightboxState.initialIndex}
            onClose={() => setLightboxState(null)}
          />
        )}
        <div className="timestamp">
          <CalendarClock size={14} />
          {formatStamp(n.published_at)}
          <button
            className="notice-translate"
            disabled={translating}
            onClick={async () => {
              if (translated) {
                setShowTranslation(!showTranslation);
                return;
              }
              translationRequest.current ??= crypto.randomUUID();
              setTranslating(true);
              setTranslationError(null);
              try {
                const r = await edge("classmate-ai", {
                  mode: "translate",
                  request_id: translationRequest.current,
                  batch_id: batch,
                  notice_id: n.id,
                });
                setTranslated(r.result);
                setShowTranslation(true);
              } catch (e) {
                setTranslationError(e);
              } finally {
                setTranslating(false);
              }
            }}
          >
            {translating ? (
              <LoaderCircle className="spin" size={13} />
            ) : (
              <Languages size={13} />
            )}{" "}
            {translating
              ? "Translating…"
              : showTranslation
                ? "Show original"
                : "Translate"}
          </button>
        </div>
        <ErrorBox error={translationError} />
        {n.resource_id && (
          <button
            className="quick-file"
            onClick={() => {
              const bodySlugMatch = n.body ? /https:\/\/classmatebd\.vercel\.app\/r\/([a-zA-Z0-9_\-]+)/.exec(n.body) : null;
              const slug = resource?.slug || (bodySlugMatch ? bodySlugMatch[1] : n.resource_id);
              window.open(`/r/${slug}`, "_blank");
            }}
          >
            <FileText size={18} />
            {resource?.title ? resource.title : "Open resource"} <ExternalLink size={14} />
          </button>
        )}
        {!poll && (
          <img
            className="notice-illustration"
            src={`/notice-${kind}.svg`}
            alt=""
            aria-hidden="true"
          />
        )}
      </div>
      {reminderDue && <p className="reminder-due">Reminder due</p>}
      <footer>
        <button
          className={`icon ${state.reminder_at ? "accent" : ""}`}
          aria-label={state.reminder_at ? "Change reminder" : "Set reminder"}
          onClick={onReminder}
        >
          <span className="notice-mobile-label">
            {state.reminder_at ? <BellRing size={20} /> : <Bell size={20} />}
          </span>
          <span className="notice-desktop-label">
            {state.reminder_at ? <BellRing size={20} /> : <Bell size={20} />}
          </span>
        </button>
        {(() => {
          const authorRead =
            (previews || []).some(
              (x: Row) => x.notice_id === n.id && x.profile_id === n.author_id,
            ) || (read.read_by_me && n.author_id === ctx.user);
          const effectiveSeenCount = Math.max(0, (read.read_count || 0) - (authorRead ? 1 : 0));
          const avatarPreviews = previews.filter((x: Row) => x.profile_id !== n.author_id);

          return canViewReaders ? (
            <button
              ref={seenBtnRef}
              type="button"
              className="seen"
              aria-label="View readers"
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                onReaders(seenBtnRef);
              }}
            >
              <span className="avatars">
                {avatarPreviews.map((p) => (
                  <Avatar
                    small
                    key={p.profile_id}
                    name={p.reader_name}
                    url={p.avatar_url}
                  />
                ))}
              </span>
              <small>{effectiveSeenCount} seen</small>
            </button>
          ) : (
            <div className="seen not-clickable" aria-label="Seen count">
              <span className="avatars">
                {avatarPreviews.map((p) => (
                  <Avatar
                    small
                    key={p.profile_id}
                    name={p.reader_name}
                    url={p.avatar_url}
                  />
                ))}
              </span>
              <small>{effectiveSeenCount} seen</small>
            </div>
          );
        })()}
        <div className="reactions">
          <button
            aria-label={liked ? "Unlike" : "Like"}
            disabled={pending}
            className={liked ? "liked" : ""}
            onClick={async () => {
              setPending(true);
              const before = liked;
              setLiked(!before);
              try {
                await onLike(!before);
              } catch {
                setLiked(before);
              } finally {
                setPending(false);
              }
            }}
          >
            <Heart size={19} fill={liked ? "currentColor" : "none"} />
            <span>
              {Math.max(
                0,
                Number(state.like_count || 0) +
                  (liked !== !!state.is_liked ? (liked ? 1 : -1) : 0),
              )}
            </span>
          </button>
          <button aria-label="View comments" onClick={onComments}>
            <MessageCircle className="notice-desktop-label" size={19} />
            <MessageSquare className="notice-mobile-label" size={19} />
            <span>{state.comment_count || 0}</span>
          </button>
          <button
            aria-label="Share notice"
            title="Share notice"
            onClick={async (e) => {
              e.stopPropagation();
              const bodySlugMatch = n.body ? /https:\/\/classmatebd\.vercel\.app\/r\/([a-zA-Z0-9_\-]+)/.exec(n.body) : null;
              const slug = resource?.slug || (bodySlugMatch ? bodySlugMatch[1] : n.resource_id);
              const shareUrl = n.resource_id
                ? `https://classmatebd.vercel.app/r/${slug}`
                : `https://classmatebd.vercel.app/n/${n.id}`;
              const shareTitle = (n.resource_id && resource?.title) ? resource.title : (n.title || "Notice");
              let shareText = n.resource_id
                ? `${shareTitle}\n${shareUrl}`
                : (n.title || "Notice");

              if (assessmentId) {
                try {
                  const { data: a } = await supabase()
                    .schema("classmate")
                    .from("assessments")
                    .select("*")
                    .eq("id", assessmentId)
                    .maybeSingle();
                  if (a) {
                    shareText = `📝 ${n.title || "Upcoming Assessment"}\n\nTitle: ${a.title}\n📅 Deadline: ${a.deadline_date}${a.deadline_time ? ` at ${a.deadline_time}` : ""}${a.room ? `\n📍 Venue: ${/^\d+$/.test(a.room.trim()) ? `Room ${a.room.trim()}` : a.room.trim()}` : ""}${a.syllabus ? `\n\n📖 Syllabus:\n${a.syllabus.trim()}` : ""}\n\n🔗 View Notice: ${shareUrl}`;
                  }
                } catch {
                  // ignore
                }
              }

              if (typeof navigator !== "undefined" && navigator.share) {
                try {
                  await navigator.share({
                    title: shareTitle,
                    text: shareText,
                    url: shareUrl,
                  });
                  return;
                } catch (err: unknown) {
                  if (err instanceof Error && err.name === "AbortError") {
                    return;
                  }
                  // Fallback to clipboard
                }
              }
              if (typeof navigator !== "undefined" && navigator.clipboard) {
                await navigator.clipboard.writeText(shareUrl);
                setCopiedShare(true);
                setTimeout(() => setCopiedShare(false), 2000);
              }
            }}
          >
            {copiedShare ? (
              <Check size={18} color="#10b981" />
            ) : (
              <Share2 size={18} />
            )}
            <span className="notice-desktop-label">{copiedShare ? "Copied" : "Share"}</span>
          </button>
        </div>
        {copiedShare && (
          <div className="notice-copy-toast" role="status">
            <Check size={14} /> Notice link copied!
          </div>
        )}
      </footer>
    </article>
  );
}
function DoubleCheckIcon({
  size = 15,
  className = "",
}: {
  size?: number;
  className?: string;
}) {
  const width = size;
  const height = Math.round((size * 11) / 16);
  return (
    <svg
      width={width}
      height={height}
      className={`wa-double-check-svg ${className}`}
      viewBox="0 0 20 14"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      style={{
        width: `${width}px`,
        height: `${height}px`,
        maxWidth: `${width}px`,
        maxHeight: `${height}px`,
        flexShrink: 0,
        display: "inline-block",
        verticalAlign: "middle",
      }}
    >
      <path d="M1 7.5L5 11.5L14 2.5" />
      <path d="M6 7.5L10 11.5L19 2.5" />
    </svg>
  );
}

const AVATAR_PALETTE = [
  "#00A884",
  "#3B82F6",
  "#8B5CF6",
  "#EC4899",
  "#F59E0B",
  "#10B981",
  "#06B6D4",
  "#F97316",
];

function getAvatarColor(name: string) {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  return AVATAR_PALETTE[Math.abs(hash) % AVATAR_PALETTE.length];
}

function getInitialLetter(name: string) {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return "C";
  if (parts.length === 1) return parts[0].slice(0, 1).toUpperCase();
  return (parts[0].slice(0, 1) + parts[1].slice(0, 1)).toUpperCase();
}

function ReaderAvatar({
  src,
  name,
}: {
  src?: string | null;
  name: string;
}) {
  const [failed, setFailed] = useState(false);
  const initial = getInitialLetter(name);
  const initialBg = getAvatarColor(name);

  if (!src || !src.startsWith("https://") || failed) {
    return (
      <div
        className="wa-avatar-initial"
        style={{ backgroundColor: initialBg }}
        aria-hidden="true"
      >
        {initial}
      </div>
    );
  }

  return (
    <img
      src={src}
      alt={name}
      className="wa-reader-avatar"
      onError={() => setFailed(true)}
    />
  );
}

function ReaderList({
  notice,
  ctx,
  receiptsEnabled,
  triggerRef,
  close,
}: {
  notice: Row;
  ctx: Context;
  receiptsEnabled: boolean;
  triggerRef?: React.RefObject<HTMLElement | null>;
  close: () => void;
}) {
  const canView = canViewNoticeReaders(notice, ctx);
  const readers = useInfiniteQuery({
    queryKey: [ctx.user, ctx.batch, "readers", notice.id],
    initialPageParam: null as Row | null,
    enabled: canView,
    queryFn: ({ pageParam }) =>
      rpc<Row[]>("notice_readers_page", {
        target_notice: notice.id,
        before_time: pageParam?.read_at || null,
        before_id: pageParam?.profile_id || null,
        page_size: 50,
      }),
    getNextPageParam: (last) => (last.length === 50 ? last.at(-1) : undefined),
  });

  const allReaders = (readers.data?.pages.flat() || []).filter(
    (p) =>
      p.profile_id !== notice.author_id &&
      !(
        ctx.owner &&
        !receiptsEnabled &&
        p.profile_id === ctx.user
      ),
  );

  const seenCount = allReaders.length;

  const bubbleTime = (() => {
    try {
      return formatStamp(notice.published_at || notice.created_at || new Date().toISOString());
    } catch {
      return "—";
    }
  })();

  return (
    <Modal
      className="receipts-modal"
      ariaLabelledBy="receipts-modal-title"
      triggerRef={triggerRef}
      close={close}
    >
      <div className="wa-message-info-shell">
        {/* Header: WhatsApp Message Info */}
        <div className="wa-message-info-header">
          <div className="wa-message-info-header-title">
            <button
              type="button"
              onClick={close}
              className="wa-header-back-btn"
              aria-label="Back"
            >
              <ArrowLeft size={20} />
            </button>
            <span id="receipts-modal-title">Message info</span>
          </div>
          <button
            type="button"
            onClick={close}
            className="wa-header-close-btn"
            aria-label="Close"
          >
            <X size={20} />
          </button>
        </div>

        {/* Pinned Notice Preview Card */}
        <div className="wa-notice-preview-container">
          <div
            className={`wa-notice-preview-card ${
              notice.class_change_id
                ? "cancelled"
                : notice.resource_id
                  ? "resource"
                  : "general"
            }`}
          >
            <div className="wa-notice-preview-tag">
              {notice.class_change_id
                ? "Class Cancellation"
                : notice.resource_id
                  ? "Resource Notice"
                  : "General Notice"}
            </div>
            {notice.title && (
              <div className="wa-notice-preview-title">{notice.title}</div>
            )}
            {notice.body && (
              <div className="wa-notice-preview-body">
                <WhatsAppText
                  text={String(notice.body)
                    .replace(/\s*\[assessment_id:[^\]]*\]\s*/g, "")
                    .trimEnd()}
                />
              </div>
            )}
            <div className="wa-notice-preview-meta">
              <span>{bubbleTime}</span>
              <div className="wa-seen-indicator">
                <DoubleCheckIcon size={15} />
                <span>Seen by {seenCount}</span>
              </div>
            </div>
          </div>
        </div>

        {/* Scrollable Container */}
        <div className="wa-message-info-body">
          {/* "Read by" Section Card */}
          <div className="wa-read-by-section">
            <div className="wa-read-by-header">
              <span>Seen by {seenCount}</span>
              <div className="wa-read-by-badge">
                <DoubleCheckIcon size={15} />
              </div>
            </div>

            <ErrorBox error={readers.error} />

            {readers.isPending ? (
              <div style={{ padding: "20px" }}>
                <Skeleton />
              </div>
            ) : allReaders.length === 0 ? (
              <div className="wa-empty-readers">
                No one has read this notice yet
              </div>
            ) : (
              <div className="wa-readers-list">
                {allReaders.map((p) => {
                  return (
                    <div className="wa-reader-item" key={p.profile_id}>
                      <ReaderAvatar src={p.avatar_url} name={p.reader_name} />
                      <div className="wa-reader-meta">
                        <span className="wa-reader-name truncate" title={p.reader_name}>
                          {p.reader_name}
                        </span>
                        <span className="wa-reader-time">
                          {p.read_at ? formatStamp(p.read_at) : "—"}
                        </span>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}

            {readers.hasNextPage && (
              <div className="wa-load-more">
                <button
                  type="button"
                  disabled={readers.isFetching}
                  onClick={() => readers.fetchNextPage()}
                  className="wa-load-more-btn"
                >
                  {readers.isFetching ? "Loading..." : "Load 50 more"}
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    </Modal>
  );
}


export function NoticeDetailModal({
  notice,
  resource,
  attachment,
  attachmentsList,
  close,
}: {
  notice: Row;
  resource?: Row;
  attachment?: { id: string; url: string; fileName: string; sizeBytes: number };
  attachmentsList?: NoticeAttachmentItem[];
  close: () => void;
}) {
  const [copied, setCopied] = useState(false);
  const [lightboxState, setLightboxState] = useState<{
    images: Array<{ url: string; fileName?: string }>;
    initialIndex: number;
  } | null>(null);

  const copyNotice = async () => {
    let text = `${notice.title || ""}\n\n${notice.body || ""}`.replace(/\s*\[assessment_id:[^\]]*\]\s*/g, "").trim();
    const m = String(notice.body).match(/\[assessment_id:([^\]]+)\]/);
    if (m && m[1]) {
      try {
        const { data: a } = await supabase()
          .schema("classmate")
          .from("assessments")
          .select("*")
          .eq("id", m[1].trim())
          .maybeSingle();
        if (a) {
          text = `📝 ${notice.title || "Upcoming Assessment"}\n\nTitle: ${a.title}\n📅 Deadline: ${a.deadline_date}${a.deadline_time ? ` at ${a.deadline_time}` : ""}${a.room ? `\n📍 Venue: ${/^\d+$/.test(a.room.trim()) ? `Room ${a.room.trim()}` : a.room.trim()}` : ""}${a.syllabus ? `\n\n📖 Syllabus:\n${a.syllabus.trim()}` : ""}\n\n🔗 View Notice: https://classmatebd.vercel.app/n/${notice.id}`;
        }
      } catch {
        // ignore
      }
    }
    navigator.clipboard
      .writeText(text)
      .then(() => {
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
      })
      .catch(() => {});
  };

  return (
    <Modal title={notice.title || "Notice"} close={close}>
      <div className="notice-detail-modal-shell">
        <div className="notice-detail-header">
          <h2 className="notice-detail-title">{notice.title || "Notice"}</h2>
        </div>

        <div className="notice-detail-body-scroll">
          {notice.body && (() => {
            const raw = String(notice.body);
            const m = raw.match(/\[assessment_id:([^\]]+)\]/);
            const aId = m ? m[1] : null;
            const cleanText = raw.replace(/\s*\[assessment_id:[^\]]*\]\s*/g, "").trimEnd();
            return (
              <div className="notice-detail-content">
                <WhatsAppText text={cleanText} />
                {aId && (
                  <div style={{ marginTop: 12 }}>
                    <button
                      type="button"
                      className="inline accent"
                      style={{
                        display: "inline-flex",
                        alignItems: "center",
                        gap: 6,
                        padding: "6px 14px",
                        borderRadius: 9999,
                        background: "rgba(99, 102, 241, 0.08)",
                        border: "1px solid rgba(99, 102, 241, 0.2)",
                        fontSize: "0.85rem",
                        fontWeight: 600,
                        cursor: "pointer",
                      }}
                      onClick={() => {
                        close();
                        window.location.hash = `#upcoming?id=${aId}`;
                      }}
                    >
                      <CalendarClock size={16} />
                      <span>View Assessment in Upcoming &rarr;</span>
                    </button>
                  </div>
                )}
              </div>
            );
          })()}

          {((attachmentsList && attachmentsList.length > 0) || attachment?.url) && (
            <NoticeAttachmentsView
              attachments={
                attachmentsList && attachmentsList.length > 0
                  ? attachmentsList
                  : attachment
                  ? [{ ...attachment, mimeType: "image/jpeg" }]
                  : []
              }
              inModal
              onImageClick={(item, index, allImages) => {
                setLightboxState({
                  images: allImages.map((img) => ({ url: img.url, fileName: img.fileName })),
                  initialIndex: index,
                });
              }}
            />
          )}

          {notice.resource_id && (
            <div className="notice-detail-resource-card">
              <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                <FileText size={22} style={{ color: "var(--primary)" }} />
                <div>
                  <div style={{ fontWeight: 600, fontSize: "0.9rem" }}>
                    {resource?.title || "Attached Resource"}
                  </div>
                  <small style={{ color: "var(--muted)", fontSize: "0.75rem" }}>
                    {resource?.course_code ? `${resource.course_code} · ` : ""}
                    {resource?.file_type ? resource.file_type.toUpperCase() : "Resource"}
                    {resource?.size_bytes ? ` · ${Math.ceil(resource.size_bytes / 1024)} KB` : ""}
                  </small>
                </div>
              </div>
              <button
                type="button"
                className="secondary"
                style={{ padding: "6px 14px", fontSize: "0.8rem" }}
                onClick={() => {
                  const bodySlugMatch = notice.body ? /https:\/\/classmatebd\.vercel\.app\/r\/([a-zA-Z0-9_\-]+)/.exec(notice.body) : null;
                  const slug = resource?.slug || (bodySlugMatch ? bodySlugMatch[1] : notice.resource_id);
                  window.open(`/r/${slug}`, "_blank");
                }}
              >
                View Resource <ExternalLink size={14} style={{ marginLeft: 4 }} />
              </button>
            </div>
          )}
        </div>

        <div className="notice-detail-footer">
          <button
            type="button"
            className="notice-detail-dismiss-btn"
            onClick={close}
          >
            Close
          </button>
          <button
            type="button"
            className="notice-detail-copy-btn"
            onClick={async () => {
              const bodySlugMatch = notice.body ? /https:\/\/classmatebd\.vercel\.app\/r\/([a-zA-Z0-9_\-]+)/.exec(notice.body) : null;
              const slug = resource?.slug || (bodySlugMatch ? bodySlugMatch[1] : notice.resource_id);
              const shareUrl = notice.resource_id
                ? `https://classmatebd.vercel.app/r/${slug}`
                : `https://classmatebd.vercel.app/n/${notice.id}`;
              const shareTitle = (notice.resource_id && resource?.title) ? resource.title : (notice.title || "Notice");
              let shareText = notice.resource_id
                ? `${shareTitle}\n${shareUrl}`
                : (notice.title || "Notice");

              const aMatch = String(notice.body).match(/\[assessment_id:([^\]]+)\]/);
              if (aMatch && aMatch[1]) {
                try {
                  const { data: a } = await supabase()
                    .schema("classmate")
                    .from("assessments")
                    .select("*")
                    .eq("id", aMatch[1].trim())
                    .maybeSingle();
                  if (a) {
                    shareText = `📝 ${notice.title || "Upcoming Assessment"}\n\nTitle: ${a.title}\n📅 Deadline: ${a.deadline_date}${a.deadline_time ? ` at ${a.deadline_time}` : ""}${a.room ? `\n📍 Venue: ${/^\d+$/.test(a.room.trim()) ? `Room ${a.room.trim()}` : a.room.trim()}` : ""}${a.syllabus ? `\n\n📖 Syllabus:\n${a.syllabus.trim()}` : ""}\n\n🔗 View Notice: ${shareUrl}`;
                  }
                } catch {
                  // ignore
                }
              }

              if (typeof navigator !== "undefined" && navigator.share) {
                try {
                  await navigator.share({
                    title: shareTitle,
                    text: shareText,
                    url: shareUrl,
                  });
                  return;
                } catch (err: unknown) {
                  if (err instanceof Error && err.name === "AbortError") {
                    return;
                  }
                  // Fallback
                }
              }
              if (typeof navigator !== "undefined" && navigator.clipboard) {
                await navigator.clipboard.writeText(shareUrl);
                setCopied(true);
                setTimeout(() => setCopied(false), 2000);
              }
            }}
          >
            {copied ? <Check size={16} /> : <Share2 size={16} />}
            <span>{copied ? "Link Copied!" : "Share Link"}</span>
          </button>
          <button
            type="button"
            className={`notice-detail-copy-btn ${copied ? "copied" : ""}`}
            onClick={copyNotice}
          >
            {copied ? <Check size={16} /> : <Copy size={16} />}
            <span>{copied ? "Copied!" : "Copy Text"}</span>
          </button>
        </div>
      </div>
      {lightboxState && (
        <NoticeImageLightbox
          images={lightboxState.images}
          initialIndex={lightboxState.initialIndex}
          onClose={() => setLightboxState(null)}
        />
      )}
    </Modal>
  );
}


/* ==========================================================================
   Material 3 Expressive Poll Component & Composer
   ========================================================================== */

function M3PollView({
  poll,
  onVote,
}: {
  poll: Row;
  onVote: (optionId: string) => Promise<void> | void;
}) {
  const [optimisticPoll, setOptimisticPoll] = useState<Row | null>(null);
  const activePoll = optimisticPoll || poll;

  useEffect(() => {
    setOptimisticPoll(null);
  }, [poll]);

  const totalVotes = Number(activePoll.total_votes || 0);
  const totalVoters = Number(activePoll.total_voters || 0);
  const isClosed = Boolean(activePoll.is_closed);
  const isMultiple = Boolean(activePoll.is_multiple_choice);
  const isAnonymous = Boolean(activePoll.is_anonymous);

  const handleOptionClick = async (optionId: string) => {
    if (isClosed) return;

    const currentOptions: Row[] = activePoll.options || [];
    const currentVotedIds = currentOptions.filter((o) => o.is_my_vote).map((o) => o.id);
    const newSelectedIds = isMultiple
      ? currentVotedIds.includes(optionId)
        ? currentVotedIds.filter((id) => id !== optionId)
        : [...currentVotedIds, optionId]
      : [optionId];

    if (!isMultiple && currentVotedIds.length === 1 && currentVotedIds[0] === optionId) {
      return;
    }

    let optTotalVotes = 0;
    const newOptions = currentOptions.map((opt) => {
      const wasVoted = Boolean(opt.is_my_vote);
      const isNowVoted = newSelectedIds.includes(opt.id);
      let count = Number(opt.votes_count || 0);
      if (!wasVoted && isNowVoted) count += 1;
      else if (wasVoted && !isNowVoted) count = Math.max(0, count - 1);
      optTotalVotes += count;
      return { ...opt, is_my_vote: isNowVoted, votes_count: count };
    });

    let optTotalVoters = Number(activePoll.total_voters || 0);
    const hadVotes = currentVotedIds.length > 0;
    const hasVotes = newSelectedIds.length > 0;
    if (!hadVotes && hasVotes) optTotalVoters += 1;
    else if (hadVotes && !hasVotes) optTotalVoters = Math.max(0, optTotalVoters - 1);

    setOptimisticPoll({
      ...activePoll,
      options: newOptions,
      total_votes: optTotalVotes,
      total_voters: optTotalVoters,
    });

    try {
      await onVote(optionId);
    } catch {
      setOptimisticPoll(null);
    }
  };

  const formatCountdown = (expiresAtStr?: string) => {
    if (!expiresAtStr) return "";
    const diff = new Date(expiresAtStr).getTime() - Date.now();
    if (diff <= 0) return "Closed";
    const hours = Math.floor(diff / (1000 * 60 * 60));
    if (hours < 24) return `Closes in ${hours}h`;
    const days = Math.floor(hours / 24);
    return `Closes in ${days}d`;
  };

  return (
    <div className="m3-poll-container">
      <div className="m3-poll-badge-row">
        <span className="m3-poll-chip">
          <BarChart2 size={12} /> POLL
        </span>
        <span className="m3-poll-subtext">
          {isMultiple ? "Multiple choice" : "Single choice"} • {isAnonymous ? "Anonymous" : "Public"}
        </span>
      </div>

      <div className="m3-poll-options">
        {(activePoll.options || []).map((opt: Row) => {
          const votesCount = Number(opt.votes_count || 0);
          const pct = totalVotes > 0 ? Math.round((votesCount / totalVotes) * 100) : 0;
          const isSelected = Boolean(opt.is_my_vote);

          return (
            <button
              key={opt.id}
              type="button"
              className={`m3-poll-option-btn ${isSelected ? "selected" : ""}`}
              disabled={isClosed}
              onClick={() => handleOptionClick(opt.id)}
            >
              <div className="m3-poll-track" style={{ width: `${pct}%` }} />
              <div className="m3-poll-content-inner">
                <div className={`m3-poll-indicator ${isMultiple ? "square" : ""}`}>
                  {isMultiple ? (
                    isSelected ? <Check size={13} strokeWidth={3} /> : null
                  ) : (
                    isSelected ? <div className="m3-poll-dot" /> : null
                  )}
                </div>
                <span className="m3-poll-text">{opt.option_text}</span>
                <div className="m3-poll-stat">
                  <span className="m3-poll-percentage">{pct}%</span>
                  <span className="m3-poll-count">
                    {votesCount} {votesCount === 1 ? "vote" : "votes"}
                  </span>
                </div>
              </div>
            </button>
          );
        })}
      </div>

      <div className="m3-poll-footer-row">
        <div className="m3-poll-voter-summary">
          <span>
            {totalVoters} {totalVoters === 1 ? "person voted" : "people voted"}
          </span>
          {totalVotes > totalVoters && (
            <span>({totalVotes} total votes)</span>
          )}
        </div>
        <div>
          {isClosed ? (
            <span className="m3-poll-closed-chip">Final Results</span>
          ) : poll.expires_at ? (
            <span>{formatCountdown(poll.expires_at)}</span>
          ) : (
            <span>Open</span>
          )}
        </div>
      </div>
    </div>
  );
}

function PollComposeForm({
  ctx,
  onCancel,
  onCreated,
}: {
  ctx: Context;
  onCancel: () => void;
  onCreated: (created?: Row) => void;
}) {
  const [question, setQuestion] = useState("");
  const [description, setDescription] = useState("");
  const [options, setOptions] = useState<string[]>(["", ""]);
  const [isMultipleChoice, setIsMultipleChoice] = useState(false);
  const [isAnonymous, setIsAnonymous] = useState(false);
  const [durationHours, setDurationHours] = useState(72);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleOptionChange = (index: number, val: string) => {
    const next = [...options];
    next[index] = val;
    setOptions(next);
  };

  const addOption = () => {
    if (options.length < 10) {
      setOptions([...options, ""]);
    }
  };

  const removeOption = (index: number) => {
    if (options.length > 2) {
      setOptions(options.filter((_, i) => i !== index));
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    const cleanQ = question.trim();
    if (cleanQ.length < 2) {
      setError("Please enter a question for the poll.");
      return;
    }
    const cleanOpts = options.map((o) => o.trim()).filter(Boolean);
    if (cleanOpts.length < 2) {
      setError("Please provide at least 2 options.");
      return;
    }

    setBusy(true);
    setError(null);
    try {
      const res = await rpc<Row>("publish_batch_poll", {
        target_batch: ctx.batch,
        poll_question: cleanQ,
        poll_options: cleanOpts,
        poll_description: description.trim(),
        is_multiple_choice: isMultipleChoice,
        is_anonymous: isAnonymous,
        duration_hours: durationHours > 0 ? durationHours : null,
      });
      onCreated(
        res
          ? {
              ...res,
              id: res.notice_id || res.id,
              title: res.question || cleanQ,
              body: description.trim(),
              type: "poll",
              created_at: new Date().toISOString(),
              author_id: ctx.user,
            }
          : undefined,
      );
    } catch (err: any) {
      setError(err?.message || "Failed to publish poll.");
      setBusy(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} style={{ display: "flex", flexDirection: "column", gap: "12px", marginTop: "12px" }}>
      <label>
        Poll Question
        <input
          type="text"
          required
          placeholder="e.g. Which date works best for our makeup lab class?"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          maxLength={300}
        />
      </label>

      <label>
        Additional Guidelines / Description (Optional)
        <textarea
          placeholder="Add context, instructions, or notes about this poll..."
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          rows={2}
          maxLength={2000}
        />
      </label>

      <div>
        <label style={{ marginBottom: "6px", display: "block" }}>Options</label>
        <div className="poll-compose-options-list">
          {options.map((opt, idx) => (
            <div key={idx} className="poll-compose-option-row">
              <input
                type="text"
                placeholder={`Option ${idx + 1}`}
                value={opt}
                required
                onChange={(e) => handleOptionChange(idx, e.target.value)}
                maxLength={200}
              />
              {options.length > 2 && (
                <button
                  type="button"
                  className="icon"
                  title="Remove option"
                  onClick={() => removeOption(idx)}
                  style={{ padding: "6px" }}
                >
                  <X size={16} />
                </button>
              )}
            </div>
          ))}
        </div>
        {options.length < 10 && (
          <button
            type="button"
            className="secondary"
            onClick={addOption}
            style={{ display: "inline-flex", alignItems: "center", gap: "6px", fontSize: "13px", padding: "6px 12px", borderRadius: "10px" }}
          >
            <Plus size={14} /> Add option
          </button>
        )}
      </div>

      <div className="poll-compose-switches">
        <label className="poll-switch-label">
          <span>Allow multiple answers</span>
          <input
            type="checkbox"
            checked={isMultipleChoice}
            onChange={(e) => setIsMultipleChoice(e.target.checked)}
          />
        </label>
        <label className="poll-switch-label">
          <span>Anonymous voting (hide voter names)</span>
          <input
            type="checkbox"
            checked={isAnonymous}
            onChange={(e) => setIsAnonymous(e.target.checked)}
          />
        </label>
        <label style={{ display: "flex", alignItems: "center", justifyContent: "space-between", fontSize: "13px" }}>
          <span>Duration</span>
          <select
            value={durationHours}
            onChange={(e) => setDurationHours(Number(e.target.value))}
            style={{ width: "auto", minWidth: "140px" }}
          >
            <option value={24}>24 hours</option>
            <option value={72}>3 days (default)</option>
            <option value={168}>7 days (1 week)</option>
            <option value={0}>No expiration</option>
          </select>
        </label>
      </div>

      {error && <div style={{ color: "var(--danger, #ef4444)", fontSize: "12px" }}>{error}</div>}

      <div style={{ display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "8px" }}>
        <button type="button" className="secondary" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
        <button type="submit" className="primary" disabled={busy}>
          {busy ? "Publishing poll…" : "Publish Poll"}
        </button>
      </div>
    </form>
  );
}

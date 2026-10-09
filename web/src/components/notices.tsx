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
  Sparkles,
  LoaderCircle,
  ArrowLeft,
  X,
} from "lucide-react";
import { edge, rpc, supabase, mutation, openFile, type Row } from "@/lib/api";
import { readAcademic, saveAcademic } from "@/lib/cache";
import { dhakaToday, formatStamp, parseDate, dateKey } from "@/lib/calendar";
import type { Context } from "./app";
import { Avatar, Confirm, Empty, ErrorBox, Form, Modal, Skeleton } from "./ui";
import { Comments } from "./notice-comments";
import { AICompose } from "./classmate-ai";
type Page = { entries: Row[]; details: Row; hasMore: boolean };
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
    [reminder, setReminder] = useState<Row | null>(null);
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
    window.addEventListener("scroll", update, { passive: true });
    root?.addEventListener("scroll", update, { passive: true });
    return () => {
      window.removeEventListener("scroll", update);
      root?.removeEventListener("scroll", update);
    };
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
          "id,title,body,published_at,semester_course_id,author_id,resource_id,class_change_id",
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
        details = entries.length
          ? await rpc("notice_feed_details", {
              target_ids: entries.map((n) => n.id),
            })
          : {};
      return { entries, details, hasMore: entries.length === 10 };
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
    initialDataUpdatedAt: () =>
      readAcademic(ctx.user, ctx.batch, "notices")?.syncedAt || 0,
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
      for (const k of ["engagement", "reads", "authors", "previews"])
        acc[k] = [...(acc[k] || []), ...(p.details[k] || [])];
      return acc;
    }, {} as Row) || {};
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
  return (
    <div className="notices-screen">
      <div className="page-heading transparent">
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
              onEdit={() => setEditing(n)}
              onDelete={() => setDeleting(n)}
              onOpenDetail={() => setDetailNotice(n)}
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
          close={() => setDetailNotice(null)}
        />
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
  const aiAllowed =
    ctx.owner || (ctx.profile.is_cr && ctx.profile.cr_batch_id === ctx.batch);
  const [type, setType] = useState(aiAllowed ? "ai" : "general");
  return (
    <>
      <div className="segment notice-compose-tabs">
        {aiAllowed && (
          <button
            className={type === "ai" ? "selected" : ""}
            onClick={() => setType("ai")}
          >
            <Sparkles size={16} /> AI compose
          </button>
        )}
        <button
          className={type === "general" ? "selected" : ""}
          onClick={() => setType("general")}
        >
          General notice
        </button>
        <button
          className={type === "cancelled" ? "selected" : ""}
          onClick={() => setType("cancelled")}
        >
          Class cancellation
        </button>
      </div>
      {type === "ai" ? (
        <AICompose ctx={ctx} done={done} />
      ) : (
        <Form
          key={type}
          label="Post notice"
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
              : [
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
          }
          submit={async (d) => {
            let created: Row | null = null;
            if (type === "general")
              created = await rpc<Row>("post_notice", {
                target_batch: ctx.batch,
                target_course: d.course || null,
                notice_title: d.title,
                notice_body: d.body,
              });
            else
              created = await rpc<Row>("post_cancellation_notice", {
                target_batch: ctx.batch,
                target_course: d.course,
                change_date: d.date,
              });
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

export function canViewNoticeReaders(notice: Row, ctx: Context): boolean {
  if (ctx.owner) return true;
  if (ctx.profile.role === "admin" || ctx.profile.role === "teacher") return true;
  if (ctx.profile.is_cr) return true;
  if (notice.author_id === ctx.user) return true;
  return false;
}

function NoticeCard({
  notice: n,
  ctx,
  batch,
  state,
  read,
  previews,
  editable,
  search,
  onLike,
  onReminder,
  onComments,
  onReaders,
  onOpenDetail,
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
  editable: boolean;
  search: string;
  onLike: (liked: boolean) => Promise<void>;
  onReminder: () => void;
  onComments: () => void;
  onReaders: (triggerRef?: React.RefObject<HTMLElement | null>) => void;
  onOpenDetail: () => void;
  onEdit: () => void;
  onDelete: () => void;
  markRead: () => void;
}) {
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
  const kind = n.resource_id
    ? "resource"
    : n.class_change_id
      ? "cancelled"
      : "general";
  const body = String((showTranslation ? translated?.body : n.body) || "");
  const rawLines = body.split("\n");
  const hasLineOverflow = rawLines.length > 6;
  const isCharOverflow = body.length > 180;
  const isOverflow = hasLineOverflow || isCharOverflow;
  const text = (() => {
    if (expanded || !isOverflow) return body;
    if (hasLineOverflow) {
      const preview = rawLines.slice(0, 6).join("\n");
      return preview.length > 200 ? preview.slice(0, 200) + "…" : preview + "…";
    }
    return body.slice(0, 200) + "…";
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

  const urlRegex =
    /((?:https?:\/\/|www\.)[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-]|\b[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*\.(?:com|org|net|edu|gov|mil|app|dev|io|co|me|bd|ai|tech|xyz|info|biz|tv|cc|live|online|site|page|link|store|cloud|space|uk|us|ca|de|in|eu|au|fr|jp|gg|so|gl|ly|to|mobi|pro|name|asia|int|arpa)(?::[0-9]{1,5})?(?:\/[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-]|(?!\/)))/gi;

  const isUrl = (s: string) =>
    /^(?:https?:\/\/|www\.)[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-]$/i.test(s) ||
    /^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*\.(?:com|org|net|edu|gov|mil|app|dev|io|co|me|bd|ai|tech|xyz|info|biz|tv|cc|live|online|site|page|link|store|cloud|space|uk|us|ca|de|in|eu|au|fr|jp|gg|so|gl|ly|to|mobi|pro|name|asia|int|arpa)(?::[0-9]{1,5})?(?:\/[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-])?$/i.test(s);

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
          className={isOverflow ? "clickable" : ""}
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
          {text.split(urlRegex).map((part, i) =>
            isUrl(part) ? (
              <a
                key={i}
                href={
                  part.startsWith("http://") || part.startsWith("https://")
                    ? part
                    : `https://${part}`
                }
                target="_blank"
                rel="noopener noreferrer"
                onClick={handleLinkClick}
              >
                <Highlight text={part} search={search} />
              </a>
            ) : (
              <Highlight key={i} text={part} search={search} />
            ),
          )}
        </div>
        {isOverflow && (
          <button
            type="button"
            className="inline accent notice-inline-toggle"
            onClick={() => setExpanded(!expanded)}
          >
            {expanded ? "see less" : "… see more"}
          </button>
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
            onClick={() =>
              openFile(n.resource_id).catch(() =>
                alert("This file is unavailable or your access has changed."),
              )
            }
          >
            <FileText size={18} />
            Open resource <ExternalLink size={14} />
          </button>
        )}
        <img
          className="notice-illustration"
          src={`/notice-${kind}.svg`}
          alt=""
          aria-hidden="true"
        />
      </div>
      {reminderDue && <p className="reminder-due">Reminder due</p>}
      <footer>
        <button
          className={`icon ${state.reminder_at ? "accent" : ""}`}
          aria-label={state.reminder_at ? "Change reminder" : "Set reminder"}
          onClick={onReminder}
        >
          <span className="notice-mobile-label">
            <BellRing size={20} />
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
        </div>
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
              <div className="wa-notice-preview-body">{notice.body}</div>
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
  close,
}: {
  notice: Row;
  close: () => void;
}) {
  const [copied, setCopied] = useState(false);

  const copyNotice = () => {
    const text = `${notice.title || ""}\n\n${notice.body || ""}`.trim();
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
          {notice.body && (
            <div className="notice-detail-content">
              {notice.body.split(/((?:https?:\/\/|www\.)[a-zA-Z0-9+&@#/%?=~_|!:,.;]*[a-zA-Z0-9+&@#/%=~_|])/g).map((part: string, i: number) =>
                /^(?:https?:\/\/|www\.)/.test(part) ? (
                  <a
                    key={i}
                    href={part.startsWith("www.") ? `https://${part}` : part}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    {part}
                  </a>
                ) : (
                  part
                ),
              )}
            </div>
          )}

          {notice.resource_id && (
            <div className="notice-detail-resource-card">
              <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                <FileText size={22} style={{ color: "var(--primary)" }} />
                <div>
                  <div style={{ fontWeight: 600, fontSize: "0.9rem" }}>
                    Attached Resource
                  </div>
                  <small style={{ color: "var(--muted)", fontSize: "0.75rem" }}>
                    Available in batch library
                  </small>
                </div>
              </div>
              <button
                type="button"
                className="secondary"
                style={{ padding: "6px 14px", fontSize: "0.8rem" }}
                onClick={() =>
                  openFile(notice.resource_id).catch(() =>
                    alert("This file is unavailable or your access has changed."),
                  )
                }
              >
                Open <ExternalLink size={14} style={{ marginLeft: 4 }} />
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
            className={`notice-detail-copy-btn ${copied ? "copied" : ""}`}
            onClick={copyNotice}
          >
            {copied ? <Check size={16} /> : <Copy size={16} />}
            <span>{copied ? "Copied!" : "Copy"}</span>
          </button>
        </div>
      </div>
    </Modal>
  );
}

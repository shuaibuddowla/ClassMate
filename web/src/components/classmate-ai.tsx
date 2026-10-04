"use client";
import { useEffect, useRef, useState } from "react";
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  LoaderCircle,
  ArrowUp,
  Menu,
  Sparkles,
  Check,
  Plus,
  MessageSquare,
  Trash2,
  Pencil,
} from "lucide-react";
import { edge, rpc, type Row } from "@/lib/api";
import type { Context } from "./app";
import { ErrorBox, Modal, Skeleton } from "./ui";
type Message = { role: "user" | "assistant"; text: string };

export default function ClassMateAI({ ctx }: { ctx: Context }) {
  const qc = useQueryClient();
  const [conversation, setConversation] = useState<string | null>(null),
    [text, setText] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null);
  const [editing, setEditing] = useState<Row | null>(null),
    [title, setTitle] = useState(""),
    [deleting, setDeleting] = useState<Row | null>(null),
    [saving, setSaving] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false),
    [showSuggestions, setShowSuggestions] = useState(false),
    [submitted, setSubmitted] = useState("");
  const request = useRef<string | null>(null),
    lock = useRef(false),
    transcript = useRef<HTMLDivElement>(null),
    entered = useRef(false),
    scrollRequest = useRef<string | null>(null);
  const key = [ctx.user, ctx.batch, "ai"];
  const permission = useQuery({
    queryKey: [...key, "write"],
    queryFn: () => rpc<boolean>("ai_can_write", { target_batch: ctx.batch }),
    enabled: ctx.active !== false,
    staleTime: 0,
  });
  const canWrite = permission.data === true;
  const chats = useInfiniteQuery({
    queryKey: [...key, "chats"],
    initialPageParam: null as Row | null,
    queryFn: ({ pageParam }) =>
      rpc<Row[]>("ai_conversation_page", {
        target_batch: ctx.batch,
        before_at: pageParam?.updated_at || null,
        before_uuid: pageParam?.id || null,
      }),
    getNextPageParam: (last) => (last.length === 30 ? last.at(-1) : undefined),
    enabled: ctx.active !== false,
  });
  const messages = useInfiniteQuery({
    queryKey: [...key, "messages", conversation],
    initialPageParam: null as number | null,
    queryFn: ({ pageParam }) =>
      rpc<Row[]>("ai_message_page", {
        target_conversation: conversation,
        before_id: pageParam,
      }),
    getNextPageParam: (last) => (last.length === 50 ? last[0].id : undefined),
    enabled: !!conversation && ctx.active !== false,
  });
  const allMessages = messages.data?.pages.slice().reverse().flat() || [],
    allChats = chats.data?.pages.flat() || [];
  const latestMessage = allMessages.at(-1)?.id;
  useEffect(() => {
    const active = ctx.active !== false;
    if (active && !entered.current && !lock.current) {
      setConversation(null);
      setText("");
      setError(null);
      setSubmitted("");
      request.current = null;
      scrollRequest.current = null;
      const seen = `classmate:ai-welcome:${ctx.user}`;
      setShowSuggestions(localStorage.getItem(seen) !== "1");
      localStorage.setItem(seen, "1");
    }
    if (!active) setShowSuggestions(false);
    entered.current = active;
  }, [ctx.active, ctx.user]);
  useEffect(() => {
    const list = transcript.current;
    if (!list || ctx.active === false) return;
    const anchor = scrollRequest.current
      ? Array.from(
          list.querySelectorAll<HTMLElement>('[data-role="user"]'),
        ).find((el) => el.dataset.request === scrollRequest.current)
      : null;
    if (anchor)
      list.scrollTop +=
        anchor.getBoundingClientRect().top - list.getBoundingClientRect().top;
    else if (!scrollRequest.current) list.scrollTop = 0;
  }, [conversation, latestMessage, busy, ctx.active]);
  const choose = (id: string | null) => {
    if (lock.current) return;
    setConversation(id);
    setText("");
    setError(null);
    setHistoryOpen(false);
    setShowSuggestions(false);
    setSubmitted("");
    scrollRequest.current = null;
    request.current = null;
  };
  async function send() {
    if (lock.current || !text.trim()) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    request.current ??= crypto.randomUUID();
    scrollRequest.current = request.current;
    setSubmitted(text.trim());
    setShowSuggestions(false);
    try {
      let id = conversation;
      if (!id) {
        id = crypto.randomUUID();
        await rpc("manage_ai_conversation", {
          target_id: id,
          target_batch: ctx.batch,
          operation: "create",
        });
        setConversation(id);
      }
      await edge("classmate-ai", {
        mode: "agent",
        request_id: request.current,
        conversation_id: id,
        batch_id: ctx.batch,
        text: text.trim(),
      });
      setText("");
      request.current = null;
    } catch (e) {
      setError(e);
    } finally {
      await qc.invalidateQueries({ queryKey: key });
      setBusy(false);
      lock.current = false;
      setSubmitted("");
    }
  }
  async function changeChat(operation: string, chat: Row) {
    setSaving(true);
    setError(null);
    try {
      await rpc("manage_ai_conversation", {
        target_id: chat.id,
        target_batch: ctx.batch,
        operation,
        target_title: title,
      });
      if (operation === "delete" && conversation === chat.id) choose(null);
      setEditing(null);
      setDeleting(null);
      await qc.invalidateQueries({ queryKey: key });
    } catch (e) {
      setError(e);
    } finally {
      setSaving(false);
    }
  }
  const historyPanel = (
    <>
      <div className="ai-history-heading">
        <h2>Your chats</h2>
        <button disabled={busy} onClick={() => choose(null)}>
          <Plus size={16} /> New chat
        </button>
      </div>
      {chats.isPending ? (
        <Skeleton />
      ) : allChats.length === 0 ? (
        <p>No saved chats yet.</p>
      ) : (
        allChats.map((c) => (
          <div
            className={`ai-history-row ${c.id === conversation ? "selected" : ""}`}
            key={c.id}
          >
            <button
              disabled={busy}
              onClick={() => choose(c.id)}
              aria-current={c.id === conversation ? "true" : undefined}
            >
              <MessageSquare size={16} />
              <span>{c.title}</span>
            </button>
            <button
              className="icon"
              disabled={busy}
              aria-label={`Rename ${c.title}`}
              onClick={() => {
                setHistoryOpen(false);
                setEditing(c);
                setTitle(c.title);
              }}
            >
              <Pencil size={14} />
            </button>
            <button
              className="icon"
              disabled={busy}
              aria-label={`Delete ${c.title}`}
              onClick={() => {
                setHistoryOpen(false);
                setDeleting(c);
              }}
            >
              <Trash2 size={14} />
            </button>
          </div>
        ))
      )}
      {chats.hasNextPage && (
        <button
          disabled={chats.isFetchingNextPage}
          onClick={() => chats.fetchNextPage()}
        >
          Older chats
        </button>
      )}
      <ErrorBox error={chats.error} retry={() => chats.refetch()} />
    </>
  );
  return (
    <div className="ai-page">
      <header className="ai-page-heading">
        <button
          className="ai-history-toggle"
          aria-label="Open chat history"
          onClick={() => setHistoryOpen(true)}
          aria-expanded={historyOpen}
        >
          <Menu size={22} />
        </button>
        <div>
          <h1>
            <Sparkles size={27} /> ClassMate AI
          </h1>
          <p>Your academic assistant</p>
        </div>
        <button
          className="secondary"
          disabled={busy}
          onClick={() => choose(null)}
        >
          <Plus size={18} /> New chat
        </button>
      </header>
      <div className="ai-workspace">
        <aside className="ai-history" aria-label="Your conversations">
          {historyPanel}
        </aside>
        <section className="ai-agent" aria-label="AI conversation">
          {!conversation && !showSuggestions && !busy && (
            <div className="ai-new-chat">
              <Sparkles size={30} />
              <h2>Start a conversation</h2>
            </div>
          )}
          {!conversation && showSuggestions && (
            <div className="ai-welcome">
              <Sparkles size={32} />
              <h2>What can I help you find?</h2>
              <p>
                Answers from your batch’s timetable, notices, library and
                calendar, plus university buses.
              </p>
              <div className="ai-suggestions">
                {[
                  "What is on my timetable today?",
                  "When is the next campus bus?",
                  canWrite
                    ? "Help me compose a notice"
                    : "What notices have I missed?",
                ].map((s) => (
                  <button
                    key={s}
                    onClick={() => {
                      setText(s);
                      request.current = null;
                    }}
                  >
                    {s}
                  </button>
                ))}
              </div>
            </div>
          )}
          {conversation && messages.isPending && <Skeleton />}
          {messages.hasNextPage && (
            <button
              disabled={messages.isFetchingNextPage}
              onClick={() => messages.fetchNextPage()}
            >
              Load earlier messages
            </button>
          )}
          <div
            className="ai-messages"
            ref={transcript}
            role="log"
            aria-live="polite"
          >
            {allMessages.map((m) => (
              <div
                className={`ai-message ${m.role}`}
                key={m.id}
                data-role={m.role}
                data-request={m.request_id}
              >
                <span className="sr-only">
                  {m.role === "user" ? "You" : "ClassMate AI"}:{" "}
                </span>
                {m.content}
                {m.role === "assistant" && m.plan?.actions?.length > 0 && (
                  <ActionPlan
                    key={m.request_id}
                    ctx={ctx}
                    canWrite={canWrite}
                    plan={{ ...m.plan, request_id: m.request_id }}
                    completed={m.status === "complete"}
                  />
                )}
              </div>
            ))}
            {busy &&
              submitted &&
              !allMessages.some(
                (m) =>
                  m.request_id === scrollRequest.current && m.role === "user",
              ) && (
                <div
                  className="ai-message user"
                  data-role="user"
                  data-request={scrollRequest.current || ""}
                >
                  {submitted}
                </div>
              )}
            {busy && (
              <p className="ai-thinking">
                <LoaderCircle size={17} className="spin" />
                Checking your academic data…
              </p>
            )}
          </div>
          <ErrorBox error={error || messages.error} />
          <form
            className="ai-input"
            onSubmit={(e) => {
              e.preventDefault();
              void send();
            }}
          >
            <label>
              <span className="sr-only">Ask ClassMate AI</span>
              <textarea
                rows={2}
                value={text}
                disabled={busy}
                maxLength={8000}
                placeholder={
                  canWrite
                    ? "Ask a question or describe a change…"
                    : "Ask about your academic day…"
                }
                onKeyDown={(e) => {
                  if (
                    e.key === "Enter" &&
                    !e.shiftKey &&
                    !e.nativeEvent.isComposing &&
                    matchMedia("(hover: hover) and (pointer: fine)").matches
                  ) {
                    e.preventDefault();
                    void send();
                  }
                }}
                onChange={(e) => {
                  setText(e.target.value);
                  request.current = null;
                }}
              />
            </label>
            <button
              className="primary"
              disabled={!text.trim() || busy}
              aria-label="Send to ClassMate AI"
            >
              {busy ? (
                <LoaderCircle size={20} className="spin" />
              ) : (
                <ArrowUp size={22} strokeWidth={2.5} />
              )}
            </button>
          </form>
          <p className="ai-footnote">
            {canWrite
              ? "Changes require your review. Bus schedules are shared university-wide."
              : "Academic answers only. Your account cannot change database records."}
          </p>
        </section>
      </div>
      {historyOpen && (
        <Modal title="Chat history" close={() => setHistoryOpen(false)}>
          <div className="ai-history-drawer">{historyPanel}</div>
        </Modal>
      )}
      {editing && (
        <Modal title="Rename chat" close={() => !saving && setEditing(null)}>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void changeChat("rename", editing);
            }}
          >
            <label>
              Chat title
              <input
                autoFocus
                required
                maxLength={100}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
              />
            </label>
            <ErrorBox error={error} />
            <button className="primary" disabled={saving || !title.trim()}>
              Save title
            </button>
          </form>
        </Modal>
      )}
      {deleting && (
        <Modal
          title="Delete this chat?"
          close={() => !saving && setDeleting(null)}
        >
          <p>This removes its messages from your chat history.</p>
          <ErrorBox error={error} />
          <button
            className="danger"
            disabled={saving}
            onClick={() => changeChat("delete", deleting)}
          >
            Delete chat
          </button>
        </Modal>
      )}
    </div>
  );
}
function ActionPlan({
  ctx,
  plan,
  completed,
  canWrite,
}: {
  ctx: Context;
  plan: Row;
  completed: boolean;
  canWrite: boolean;
}) {
  const qc = useQueryClient(),
    [applying, setApplying] = useState(false),
    [applied, setApplied] = useState(completed),
    [error, setError] = useState<unknown>(null);
  return (
    <>
      {plan && plan.actions?.length > 0 && (
        <div className="ai-plan">
          <h3>
            {plan.actions.length} proposed{" "}
            {plan.actions.length === 1 ? "change" : "changes"}
          </h3>
          {plan.actions.some(
            (a: Row) => a.name === "save_student_bus_schedule",
          ) && (
            <p className="ai-shared-warning">
              Bus changes apply university-wide.
            </p>
          )}
          <ol>
            {plan.actions.map((a: Row, i: number) => (
              <li key={i}>
                <strong>
                  {a.name.replace(/^ai_/, "").replaceAll("_", " ")}
                </strong>
                {a.summary && <p>{a.summary}</p>}
                <dl>
                  {Object.entries(a.args)
                    .filter(
                      ([k, v]) =>
                        v !== null &&
                        !k.endsWith("_id") &&
                        ![
                          "target_batch",
                          "target_course",
                          "target_semester_course",
                          "target_profile",
                          "target_resource",
                          "target_offering",
                          "target_teacher_record",
                          "target_record",
                          "target_department",
                          "target_catalog_course",
                          "target_semester",
                          "target_teacher",
                          "from_id",
                          "to_id",
                        ].includes(k),
                    )
                    .map(([k, v]) => (
                      <div key={k}>
                        <dt>
                          {k
                            .replace(/^target_|^notice_/, "")
                            .replaceAll("_", " ")}
                        </dt>
                        <dd>{String(v)}</dd>
                      </div>
                    ))}
                </dl>
              </li>
            ))}
          </ol>
          <button
            className="primary wide"
            disabled={applying || applied || !canWrite}
            onClick={async () => {
              setApplying(true);
              setError(null);
              try {
                await edge("classmate-ai", {
                  mode: "execute",
                  request_id: plan.request_id,
                });
                await qc.invalidateQueries({ queryKey: [ctx.user] });
                setApplied(true);
                await qc.invalidateQueries({
                  queryKey: [ctx.user, ctx.batch, "ai"],
                });
              } catch (e) {
                setError(e);
              } finally {
                setApplying(false);
              }
            }}
          >
            {applied ? (
              <Check size={18} />
            ) : applying ? (
              <LoaderCircle size={18} className="spin" />
            ) : null}
            {applied ? "Saved" : "Apply changes"}
          </button>
        </div>
      )}
      <ErrorBox error={error} />
    </>
  );
}
export function AICompose({
  ctx,
  done,
}: {
  ctx: Context;
  done: () => Promise<void>;
}) {
  const [text, setText] = useState(""),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState(""),
    [error, setError] = useState<unknown>(null);
  const request = useRef<string | null>(null),
    lock = useRef(false),
    history = useRef<Message[]>([]);
  return (
    <form
      className="ai-compose"
      onSubmit={async (e) => {
        e.preventDefault();
        if (lock.current || !text.trim()) return;
        lock.current = true;
        setBusy(true);
        setError(null);
        request.current ??= crypto.randomUUID();
        try {
          const r = await edge("classmate-ai", {
            mode: "compose",
            request_id: request.current,
            batch_id: ctx.batch,
            text,
            history: history.current,
          });
          if (r.result?.actions?.length) {
            await done();
            return;
          }
          const reply =
            r.plan?.message ||
            r.result?.message ||
            "Please add the missing details.";
          setMessage(reply);
          history.current = [
            ...history.current,
            { role: "user", text },
            { role: "assistant", text: reply },
          ].slice(-8) as Message[];
          request.current = null;
        } catch (e) {
          setError(e);
        } finally {
          setBusy(false);
          lock.current = false;
        }
      }}
    >
      <p>
        Paste your rough notice in English or Bengali. AI will organize it and
        post in English. Include the course and today/tomorrow for
        cancellations.
      </p>
      <label>
        Your notice
        <textarea
          rows={6}
          value={text}
          maxLength={8000}
          required
          disabled={busy}
          onChange={(e) => {
            setText(e.target.value);
            request.current = null;
          }}
          placeholder="Write naturally. Start with /silent for a feed-only notice."
        />
      </label>
      {message && (
        <p className="ai-clarification" role="status">
          {message}
        </p>
      )}
      <ErrorBox error={error} />
      <button className="primary wide" disabled={busy || !text.trim()}>
        {busy ? (
          <LoaderCircle size={18} className="spin" />
        ) : (
          <Sparkles size={18} />
        )}{" "}
        {busy ? "Composing…" : "Compose & post"}
      </button>
    </form>
  );
}

"use client";
import { useRef, useState } from "react";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { Heart, MoreHorizontal, Send, X } from "lucide-react";
import { rpc, type Row } from "@/lib/api";
import { formatStamp } from "@/lib/calendar";
import type { Context } from "./app";
import { Avatar, Confirm, Empty, ErrorBox, Modal, Skeleton } from "./ui";

function commentTime(stamp: string) {
  const minutes = Math.max(
    0,
    Math.floor((Date.now() - Date.parse(stamp)) / 60_000),
  );
  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes}m`;
  if (minutes < 1440) return `${Math.floor(minutes / 60)}h`;
  if (minutes < 10080) return `${Math.floor(minutes / 1440)}d`;
  return new Date(stamp).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    timeZone: "Asia/Dhaka",
  });
}

export function Comments({
  notice,
  ctx,
  close,
}: {
  notice: Row;
  ctx: Context;
  close: () => void;
}) {
  return (
    <Modal title="Comments" close={close}>
      <div className="social-comments">
        <p className="comment-notice-title">{notice.title}</p>
        <CommentThread notice={notice} ctx={ctx} />
      </div>
    </Modal>
  );
}
function CommentThread({
  notice,
  ctx,
  parent = null,
}: {
  notice: Row;
  ctx: Context;
  parent?: Row | null;
}) {
  const qc = useQueryClient();
  const refresh = async () => {
    await Promise.all([
      qc.invalidateQueries({
        queryKey: [ctx.user, ctx.batch, "comments", notice.id],
      }),
      qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch, "notices"] }),
    ]);
  };
  const comments = useInfiniteQuery({
    queryKey: [
      ctx.user,
      ctx.batch,
      "comments",
      notice.id,
      parent?.id || "root",
    ],
    initialPageParam: null as Row | null,
    refetchInterval: 15000,
    queryFn: ({ pageParam }) =>
      rpc<Row[]>("comment_page", {
        target_notice: notice.id,
        target_parent: parent?.id || null,
        before_time: pageParam?.created_at || null,
        before_id: pageParam?.id || null,
      }),
    getNextPageParam: (last) => (last.length === 50 ? last.at(-1) : undefined),
  });
  return (
    <div className={parent ? "reply-thread" : "comment-thread"}>
      <ErrorBox error={comments.error} retry={() => comments.refetch()} />
      {comments.isPending ? (
        <Skeleton />
      ) : (
        comments.data?.pages
          .flat()
          .map((c) => (
            <Comment
              key={c.id}
              c={c}
              ctx={ctx}
              notice={notice}
              root={parent}
              refresh={refresh}
            />
          ))
      )}
      {!parent &&
        !comments.isPending &&
        !comments.data?.pages.flat().length && (
          <Empty
            title="Start the conversation"
            body="Share a thought or ask a question."
          />
        )}
      {comments.hasNextPage && (
        <button
          className="text-button"
          disabled={comments.isFetchingNextPage}
          onClick={() => comments.fetchNextPage()}
        >
          {comments.isFetchingNextPage ? "Loading…" : "View older comments"}
        </button>
      )}
      <CommentInput ctx={ctx} notice={notice} parent={parent} done={refresh} />
    </div>
  );
}
function Comment({
  c,
  ctx,
  notice,
  root,
  refresh,
}: {
  c: Row;
  ctx: Context;
  notice: Row;
  root: Row | null;
  refresh: () => Promise<void>;
}) {
  const [replies, setReplies] = useState(false),
    [menu, setMenu] = useState(false),
    [editing, setEditing] = useState(false),
    [deleting, setDeleting] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null);
  const [optimistic, setOptimistic] = useState<boolean | null>(null);
  const liked = optimistic ?? !!c.liked_by_me;
  return (
    <article className="social-comment">
      <Avatar small url={c.avatar_url} name={c.author_name} />
      <div className="comment-main">
        {editing ? (
          <CommentInput
            ctx={ctx}
            notice={notice}
            parent={root}
            editing={c}
            done={async () => {
              await refresh();
              setEditing(false);
            }}
            cancel={() => setEditing(false)}
          />
        ) : (
          <div className="comment-bubble">
            <strong>{c.author_name || "ClassMate member"}</strong>
            <p>{c.body}</p>
          </div>
        )}
        <div className="comment-meta">
          <span title={formatStamp(c.created_at)}>
            {commentTime(c.created_at)}
            {c.updated_at ? " · Edited" : ""}
          </span>
          <button
            className={liked ? "liked" : ""}
            disabled={busy}
            aria-label={liked ? "Unlike comment" : "Like comment"}
            onClick={async () => {
              setBusy(true);
              setError(null);
              setOptimistic(!liked);
              try {
                await rpc("set_comment_like", {
                  target_id: c.id,
                  target_liked: !liked,
                });
                await refresh();
              } catch (e) {
                setError(e);
              } finally {
                setOptimistic(null);
                setBusy(false);
              }
            }}
          >
            {liked ? "Liked" : "Like"}
          </button>
          {!root && (
            <button
              onClick={() => setReplies(!replies)}
              aria-expanded={replies}
            >
              Reply
            </button>
          )}
          {Number(c.like_count) +
            (optimistic === null
              ? 0
              : optimistic !== !!c.liked_by_me
                ? optimistic
                  ? 1
                  : -1
                : 0) >
            0 && (
            <span className="comment-likes">
              <Heart size={12} fill="currentColor" />
              {Math.max(
                0,
                Number(c.like_count) +
                  (optimistic === null
                    ? 0
                    : optimistic !== !!c.liked_by_me
                      ? optimistic
                        ? 1
                        : -1
                      : 0),
              )}
            </span>
          )}
        </div>
        <ErrorBox error={error} />
        {!root && Number(c.reply_count) > 0 && !replies && (
          <button className="comment-replies" onClick={() => setReplies(true)}>
            View {c.reply_count}{" "}
            {Number(c.reply_count) === 1 ? "reply" : "replies"}
          </button>
        )}
        {replies && !root && (
          <CommentThread notice={notice} ctx={ctx} parent={c} />
        )}
      </div>
      {(c.can_edit || c.can_delete) && (
        <div className="comment-menu">
          <button
            className="icon"
            aria-label="Comment options"
            aria-expanded={menu}
            onClick={() => setMenu(!menu)}
          >
            <MoreHorizontal size={18} />
          </button>
          {menu && (
            <div className="dropdown">
              {c.can_edit && (
                <button
                  onClick={() => {
                    setMenu(false);
                    setEditing(true);
                  }}
                >
                  Edit
                </button>
              )}
              {c.can_delete && (
                <button
                  className="danger"
                  onClick={() => {
                    setMenu(false);
                    setDeleting(true);
                  }}
                >
                  Delete
                </button>
              )}
            </div>
          )}
        </div>
      )}
      {deleting && (
        <Confirm
          title="Delete comment?"
          body={
            c.reply_count
              ? "This comment and its replies will be removed."
              : "This comment will be removed."
          }
          close={() => setDeleting(false)}
          action={async () => {
            await rpc("delete_notice_comment", { target_id: c.id });
            await refresh();
          }}
        />
      )}
    </article>
  );
}
function CommentInput({
  ctx,
  notice,
  parent = null,
  editing,
  done,
  cancel,
}: {
  ctx: Context;
  notice: Row;
  parent?: Row | null;
  editing?: Row;
  done: () => Promise<void>;
  cancel?: () => void;
}) {
  const [text, setText] = useState(editing?.body || ""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null);
  const request = useRef<string | null>(null),
    lock = useRef(false);
  async function send() {
    if (lock.current || !text.trim()) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    request.current ??= crypto.randomUUID();
    try {
      await rpc("save_notice_comment", {
        target_notice: notice.id,
        target_body: text.trim(),
        target_parent: parent?.id || null,
        target_id: editing?.id || null,
        target_request: request.current,
      });
      setText("");
      request.current = null;
      await done();
    } catch (e) {
      setError(e);
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  return (
    <form
      className="comment-composer"
      onSubmit={(e) => {
        e.preventDefault();
        void send();
      }}
    >
      {!editing && (
        <Avatar
          small
          name={ctx.profile.full_name}
          url={ctx.profile.avatar_url}
        />
      )}
      <label>
        <span className="sr-only">
          {editing
            ? "Edit comment"
            : parent
              ? "Write a reply"
              : "Write a comment"}
        </span>
        <textarea
          value={text}
          rows={1}
          maxLength={2000}
          disabled={busy}
          placeholder={
            parent
              ? `Reply to ${parent.author_name?.split(" ")[0] || "this comment"}…`
              : "Write a comment…"
          }
          onChange={(e) => {
            setText(e.target.value);
            request.current = null;
          }}
          onKeyDown={(e) => {
            if (
              e.key === "Enter" &&
              !e.shiftKey &&
              !e.nativeEvent.isComposing
            ) {
              e.preventDefault();
              void send();
            }
          }}
        />
      </label>
      <button
        className="icon accent"
        aria-label={editing ? "Save comment" : "Send comment"}
        disabled={busy || !text.trim()}
      >
        <Send size={19} />
      </button>
      {cancel && (
        <button
          type="button"
          className="icon"
          aria-label="Cancel editing"
          onClick={cancel}
        >
          <X size={18} />
        </button>
      )}
      <ErrorBox error={error} />
    </form>
  );
}

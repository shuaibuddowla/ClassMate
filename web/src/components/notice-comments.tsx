"use client";
import { useEffect, useRef, useState } from "react";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { Heart, MessageSquare, MoreHorizontal, Send, X, CornerDownRight } from "lucide-react";
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
  const qc = useQueryClient();
  const [replyingTo, setReplyingTo] = useState<Row | null>(null);
  const [editingComment, setEditingComment] = useState<Row | null>(null);
  const [composerText, setComposerText] = useState("");
  const [busy, setBusy] = useState(false);
  const [composerError, setComposerError] = useState<unknown>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const listBottomRef = useRef<HTMLDivElement>(null);
  const requestRef = useRef<string | null>(null);

  const refresh = async () => {
    await Promise.all([
      qc.invalidateQueries({
        queryKey: [ctx.user, ctx.batch, "comments", notice.id],
      }),
      qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch, "notices"] }),
    ]);
  };

  const commentsQuery = useInfiniteQuery({
    queryKey: [ctx.user, ctx.batch, "comments", notice.id, "root"],
    initialPageParam: null as Row | null,
    refetchInterval: 15000,
    queryFn: ({ pageParam }) =>
      rpc<Row[]>("comment_page", {
        target_notice: notice.id,
        target_parent: null,
        before_time: pageParam?.created_at || null,
        before_id: pageParam?.id || null,
      }),
    getNextPageParam: (last) => (last.length === 50 ? last.at(-1) : undefined),
  });

  const allComments = commentsQuery.data?.pages.flat() || [];
  const totalComments = allComments.reduce(
    (acc, curr) => acc + 1 + Number(curr.reply_count || 0),
    0,
  );

  const handleStartReply = (comment: Row) => {
    setEditingComment(null);
    setReplyingTo(comment);
    textareaRef.current?.focus();
  };

  const handleStartEdit = (comment: Row) => {
    setReplyingTo(null);
    setEditingComment(comment);
    setComposerText(comment.body);
    textareaRef.current?.focus();
  };

  const handleCancelComposerAction = () => {
    setReplyingTo(null);
    setEditingComment(null);
    setComposerText("");
    setComposerError(null);
  };

  const handleSend = async () => {
    const text = composerText.trim();
    if (!text || busy) return;
    setBusy(true);
    setComposerError(null);
    requestRef.current ??= crypto.randomUUID();

    try {
      await rpc("save_notice_comment", {
        target_notice: notice.id,
        target_body: text,
        target_parent: editingComment ? null : (replyingTo?.id || null),
        target_id: editingComment?.id || null,
        target_request: requestRef.current,
      });
      setComposerText("");
      setReplyingTo(null);
      setEditingComment(null);
      requestRef.current = null;
      await refresh();
      listBottomRef.current?.scrollIntoView({ behavior: "smooth" });
    } catch (e) {
      setComposerError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title="" close={close}>
      <div className="comments-modal-shell">
        {/* Facebook-style clean, sticky header */}
        <div className="comments-header-card">
          <div className="comments-header-info">
            <h3 className="comments-header-title">
              <span>Discussion</span>
              <span className="comments-count-pill">{totalComments}</span>
            </h3>
            <span className="comments-notice-meta" title={notice.title}>
              {notice.title}
            </span>
          </div>
        </div>

        {/* Scrollable comments viewport */}
        <div className="comments-body-scroll">
          <ErrorBox error={commentsQuery.error} retry={() => commentsQuery.refetch()} />

          {commentsQuery.isPending ? (
            <Skeleton />
          ) : allComments.length === 0 ? (
            <Empty
              title="No comments yet"
              body="Be the first to start the discussion for this notice."
            />
          ) : (
            allComments.map((c) => (
              <FacebookCommentItem
                key={c.id}
                comment={c}
                notice={notice}
                ctx={ctx}
                onReply={handleStartReply}
                onEdit={handleStartEdit}
                refresh={refresh}
              />
            ))
          )}

          {commentsQuery.hasNextPage && (
            <button
              className="text-button"
              style={{ marginTop: 8 }}
              disabled={commentsQuery.isFetchingNextPage}
              onClick={() => commentsQuery.fetchNextPage()}
            >
              {commentsQuery.isFetchingNextPage ? "Loading older…" : "Load older comments"}
            </button>
          )}
          <div ref={listBottomRef} />
        </div>

        {/* Facebook-style ideal, compact, sticky bottom composer */}
        <div className="comment-sticky-footer">
          {replyingTo && (
            <div className="comment-reply-banner">
              <span style={{ display: "flex", alignItems: "center", gap: 6 }}>
                <CornerDownRight size={14} />
                Replying to <strong>{replyingTo.author_name}</strong>
              </span>
              <button aria-label="Cancel reply" onClick={handleCancelComposerAction}>
                <X size={15} />
              </button>
            </div>
          )}

          {editingComment && (
            <div className="comment-reply-banner" style={{ background: "color-mix(in srgb, #f59e0b 12%, var(--surface))", color: "#d97706" }}>
              <span>Editing your comment</span>
              <button aria-label="Cancel editing" onClick={handleCancelComposerAction}>
                <X size={15} />
              </button>
            </div>
          )}

          <div className="comment-composer">
            <Avatar small name={ctx.profile.full_name} url={ctx.profile.avatar_url} />
            <label>
              <span className="sr-only">Write a comment</span>
              <textarea
                ref={textareaRef}
                value={composerText}
                rows={1}
                maxLength={2000}
                disabled={busy}
                placeholder={
                  replyingTo
                    ? `Reply to ${replyingTo.author_name?.split(" ")[0]}…`
                    : editingComment
                      ? "Edit your comment…"
                      : "Write a comment…"
                }
                onChange={(e) => {
                  setComposerText(e.target.value);
                  requestRef.current = null;
                }}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault();
                    void handleSend();
                  }
                }}
              />
              <button
                type="button"
                className="comment-composer-send"
                disabled={busy || !composerText.trim()}
                aria-label="Send comment"
                onClick={handleSend}
              >
                <Send size={15} />
              </button>
            </label>
          </div>
          <ErrorBox error={composerError} />
        </div>
      </div>
    </Modal>
  );
}

function FacebookCommentItem({
  comment: c,
  notice,
  ctx,
  isReply = false,
  onReply,
  onEdit,
  refresh,
}: {
  comment: Row;
  notice: Row;
  ctx: Context;
  isReply?: boolean;
  onReply: (comment: Row) => void;
  onEdit: (comment: Row) => void;
  refresh: () => Promise<void>;
}) {
  const [repliesOpen, setRepliesOpen] = useState(false);
  const [menu, setMenu] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [busy, setBusy] = useState(false);
  const [optimisticLiked, setOptimisticLiked] = useState<boolean | null>(null);

  const liked = optimisticLiked ?? !!c.liked_by_me;
  const isAuthor = notice.author_id === c.author_id;

  const repliesQuery = useInfiniteQuery({
    queryKey: [ctx.user, ctx.batch, "comments", notice.id, c.id],
    enabled: repliesOpen && !isReply,
    initialPageParam: null as Row | null,
    queryFn: ({ pageParam }) =>
      rpc<Row[]>("comment_page", {
        target_notice: notice.id,
        target_parent: c.id,
        before_time: pageParam?.created_at || null,
        before_id: pageParam?.id || null,
      }),
    getNextPageParam: (last) => (last.length === 50 ? last.at(-1) : undefined),
  });

  const repliesList = repliesQuery.data?.pages.flat() || [];

  const handleLikeToggle = async () => {
    if (busy) return;
    setBusy(true);
    const nextState = !liked;
    setOptimisticLiked(nextState);
    try {
      await rpc("set_comment_like", {
        target_id: c.id,
        target_liked: nextState,
      });
      await refresh();
    } catch {
      setOptimisticLiked(!nextState);
    } finally {
      setOptimisticLiked(null);
      setBusy(false);
    }
  };

  const calculatedLikes = Math.max(
    0,
    Number(c.like_count || 0) +
      (optimisticLiked === null
        ? 0
        : optimisticLiked !== !!c.liked_by_me
          ? optimisticLiked
            ? 1
            : -1
          : 0),
  );

  return (
    <article className="social-comment">
      <Avatar small url={c.avatar_url} name={c.author_name} />
      <div className="comment-main">
        {/* Facebook-style bubble */}
        <div className="comment-bubble">
          <div className="comment-author-row">
            <span className="comment-author-name">{c.author_name || "ClassMate member"}</span>
            {isAuthor && <span className="comment-role-tag author">Author</span>}
          </div>
          <p>{c.body}</p>
        </div>

        {/* Action & Metadata bar */}
        <div className="comment-meta">
          <span title={formatStamp(c.created_at)}>
            {commentTime(c.created_at)}
            {c.updated_at ? " · Edited" : ""}
          </span>
          <button
            className={liked ? "liked" : ""}
            disabled={busy}
            aria-label={liked ? "Unlike comment" : "Like comment"}
            onClick={handleLikeToggle}
          >
            {liked ? "Liked" : "Like"}
          </button>
          <button onClick={() => onReply(c)}>
            Reply
          </button>

          {calculatedLikes > 0 && (
            <span className="comment-likes">
              <Heart size={11} fill="currentColor" />
              <span>{calculatedLikes}</span>
            </span>
          )}
        </div>

        {/* Collapsible replies indicator */}
        {!isReply && Number(c.reply_count || 0) > 0 && (
          <div>
            {!repliesOpen ? (
              <button
                className="comment-replies"
                onClick={() => setRepliesOpen(true)}
              >
                <CornerDownRight size={13} />
                View {c.reply_count} {Number(c.reply_count) === 1 ? "reply" : "replies"}
              </button>
            ) : (
              <button
                className="comment-replies"
                style={{ color: "var(--muted)" }}
                onClick={() => setRepliesOpen(false)}
              >
                Hide replies
              </button>
            )}
          </div>
        )}

        {/* Indented reply tree */}
        {repliesOpen && !isReply && (
          <div className="reply-thread">
            {repliesQuery.isPending ? (
              <Skeleton />
            ) : (
              repliesList.map((reply) => (
                <FacebookCommentItem
                  key={reply.id}
                  comment={reply}
                  notice={notice}
                  ctx={ctx}
                  isReply={true}
                  onReply={onReply}
                  onEdit={onEdit}
                  refresh={refresh}
                />
              ))
            )}
          </div>
        )}
      </div>

      {/* 3-dot options menu */}
      {(c.can_edit || c.can_delete) && (
        <div className="comment-menu">
          <button
            className="icon"
            aria-label="Comment options"
            aria-expanded={menu}
            onClick={() => setMenu(!menu)}
          >
            <MoreHorizontal size={16} />
          </button>
          {menu && (
            <div className="dropdown">
              {c.can_edit && (
                <button
                  onClick={() => {
                    setMenu(false);
                    onEdit(c);
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
          body="Are you sure you want to delete this comment? Its replies will also be removed."
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


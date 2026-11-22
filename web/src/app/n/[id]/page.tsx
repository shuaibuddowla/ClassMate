import { Metadata } from "next";
import { notFound } from "next/navigation";
import { createClient } from "@supabase/supabase-js";
import {
  Bell,
  Calendar,
  AlertTriangle,
  User,
  BookOpen,
  FileText,
  ExternalLink,
  Smartphone,
  Globe,
  Share2,
  CalendarClock,
  MapPin,
} from "lucide-react";

export const dynamic = "force-dynamic";

export interface PublicNoticeAttachment {
  id: string;
  url: string;
  file_name: string;
  mime_type: string;
  size_bytes: number;
  storage_key?: string;
}

export interface PublicPollOption {
  id: string;
  option_text: string;
  position: number;
  votes_count: number;
}

export interface PublicPoll {
  id: string;
  question: string;
  is_multiple_choice: boolean;
  is_anonymous: boolean;
  expires_at?: string;
  is_closed: boolean;
  total_voters: number;
  total_votes: number;
  options: PublicPollOption[];
}

export interface PublicAssessment {
  id: string;
  title: string;
  type: string;
  course_code?: string;
  course_title?: string;
  deadline_date: string;
  deadline_time?: string;
  room?: string;
  syllabus?: string;
  posted_by?: string;
}

interface PublicNotice {
  id: string;
  title: string;
  body: string;
  published_at: string;
  department_id?: string;
  department_name?: string;
  department_code?: string;
  batch_id?: string;
  batch_number?: number;
  academic_session?: number;
  author_id?: string;
  author_name?: string;
  author_role?: string;
  author_is_cr?: boolean;
  author_avatar_url?: string;
  course_title?: string;
  course_code?: string;
  is_cancelled?: boolean;
  class_change_kind?: string;
  class_change_date?: string;
  class_change_details?: string;
  resource_id?: string;
  resource_slug?: string;
  resource_title?: string;
  resource_file_type?: string;
  resource_size_bytes?: number;
  resource_mime_type?: string;
  resource_link_url?: string;
  resource_is_link?: boolean;
  attachments?: PublicNoticeAttachment[];
  poll?: PublicPoll;
  assessment?: PublicAssessment;
}

function getSupabase() {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || "https://cbskpifgbdbgdmnnxfrh.supabase.co";
  const key = process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || "";
  return createClient(url, key, { auth: { persistSession: false } });
}

async function fetchPublicNotice(id: string): Promise<PublicNotice | null> {
  if (!id || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
    return null;
  }
  const db = getSupabase();
  let notice: PublicNotice | null = null;
  const { data, error } = await db.schema("classmate").rpc("get_public_notice", { target_identifier: id });
  if (error || !data) {
    const { data: fallbackData } = await db.rpc("get_public_notice", { target_identifier: id });
    if (fallbackData) notice = fallbackData as PublicNotice;
  } else {
    notice = data as PublicNotice;
  }

  if (notice && (!notice.attachments || notice.attachments.length === 0)) {
    try {
      const { data: atts } = await db
        .schema("public")
        .from("notice_attachments")
        .select("id,storage_key,file_name,mime_type,size_bytes")
        .eq("notice_id", notice.id);
      if (atts && atts.length > 0) {
        notice.attachments = atts.map((a: any) => ({
          id: a.id,
          url: db.storage.from("notice-images").getPublicUrl(a.storage_key).data.publicUrl,
          file_name: a.file_name,
          mime_type: a.mime_type,
          size_bytes: a.size_bytes,
          storage_key: a.storage_key,
        }));
      }
    } catch {
      // ignore
    }
  }

  if (notice && !notice.poll) {
    try {
      const { data: pollRow } = await db
        .schema("classmate")
        .from("polls")
        .select("id,question,is_multiple_choice,is_anonymous,expires_at")
        .eq("notice_id", notice.id)
        .maybeSingle();

      if (pollRow) {
        const { data: opts } = await db
          .schema("classmate")
          .from("poll_options")
          .select("id,option_text,position,votes_count")
          .eq("poll_id", pollRow.id)
          .order("position", { ascending: true });

        const options = (opts || []).map((o: any) => ({
          id: o.id,
          option_text: o.option_text,
          position: o.position,
          votes_count: o.votes_count || 0,
        }));

        const totalVotes = options.reduce((sum, o) => sum + o.votes_count, 0);

        notice.poll = {
          id: pollRow.id,
          question: pollRow.question,
          is_multiple_choice: pollRow.is_multiple_choice,
          is_anonymous: pollRow.is_anonymous,
          expires_at: pollRow.expires_at,
          is_closed: pollRow.expires_at ? new Date(pollRow.expires_at).getTime() < Date.now() : false,
          total_voters: totalVotes,
          total_votes: totalVotes,
          options,
        };
      }
    } catch {
      // ignore
    }
  }

  if (notice) {
    if (!notice.assessment) {
      try {
        const match = notice.body ? notice.body.match(/\[assessment_id:([^\]]+)\]/) : null;
        const targetAssessmentId = match ? match[1].trim() : null;
        let assessmentRow = null;

        if (targetAssessmentId) {
          const { data } = await db
            .schema("classmate")
            .from("assessments")
            .select("*")
            .eq("id", targetAssessmentId)
            .maybeSingle();
          assessmentRow = data;
        }

        if (!assessmentRow) {
          const { data } = await db
            .schema("classmate")
            .from("assessments")
            .select("*")
            .eq("notice_id", notice.id)
            .maybeSingle();
          assessmentRow = data;
        }

        if (assessmentRow) {
          notice.assessment = {
            id: assessmentRow.id,
            title: assessmentRow.title,
            type: assessmentRow.type,
            course_code: assessmentRow.course_code,
            course_title: assessmentRow.course_title,
            deadline_date: assessmentRow.deadline_date,
            deadline_time: assessmentRow.deadline_time,
            room: assessmentRow.room,
            syllabus: assessmentRow.syllabus,
            posted_by: assessmentRow.posted_by,
          };
        }
      } catch {
        // ignore
      }
    }

    // Always strip assessment_id from viewable body
    notice.body = notice.body.replace(/\s*\[assessment_id:[^\]]*\]\s*/g, "").trim();
  }

  return notice;
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ id: string }>;
}): Promise<Metadata> {
  const { id } = await params;
  const notice = await fetchPublicNotice(id);
  if (!notice) {
    return {
      title: "Notice Not Found · ClassMate",
      robots: {
        index: false,
        follow: false,
      },
    };
  }

  const deptBatch = `${notice.department_code ? notice.department_code.toUpperCase() : "MBSTU"} · Batch ${notice.batch_number || "CSE"}`;
  const author = notice.author_name ? `By ${notice.author_name}` : "ClassMate";
  const snippet = notice.body.slice(0, 150).replace(/\s+/g, " ").trim();
  const description = `${author} (${deptBatch}): ${snippet || notice.title}`;
  const pageUrl = `https://classmatebd.vercel.app/n/${notice.id}`;
  const firstImage = notice.attachments?.find(
    (a) => a.mime_type?.startsWith("image/") || !a.file_name?.toLowerCase().endsWith(".pdf")
  );
  const ogImageUrl = firstImage?.url || `https://classmatebd.vercel.app/n/${notice.id}/opengraph-image`;

  const isThinOrCancelled =
    notice.is_cancelled ||
    (notice.body.trim().length < 80 && (!notice.attachments || notice.attachments.length === 0));

  return {
    title: `${notice.title} · ClassMate Notice`,
    description,
    robots: {
      index: !isThinOrCancelled,
      follow: true,
    },
    alternates: {
      canonical: pageUrl,
    },
    openGraph: {
      title: notice.title,
      description,
      url: pageUrl,
      siteName: "ClassMate",
      type: "article",
      publishedTime: notice.published_at,
      authors: notice.author_name ? [notice.author_name] : undefined,
      images: [
        {
          url: ogImageUrl,
          width: 1200,
          height: 630,
          alt: notice.title,
        },
      ],
    },
    twitter: {
      card: "summary_large_image",
      title: notice.title,
      description,
      images: [ogImageUrl],
    },
  };
}

export default async function PublicNoticePage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const notice = await fetchPublicNotice(id);

  if (!notice) {
    notFound();
  }

  const publishedDate = notice.published_at
    ? new Date(notice.published_at).toLocaleDateString("en-US", {
        weekday: "short",
        month: "short",
        day: "numeric",
        year: "numeric",
        hour: "numeric",
        minute: "2-digit",
        hour12: true,
      })
    : "";

  const appIntentUrl = `intent://classmatebd.vercel.app/n/${notice.id}#Intent;scheme=https;package=com.shuaib.classmate;end`;

  const firstImage = notice.attachments?.find(
    (a) => a.mime_type?.startsWith("image/") || !a.file_name?.toLowerCase().endsWith(".pdf")
  );
  const ogImageUrl = firstImage?.url || `https://classmatebd.vercel.app/n/${notice.id}/opengraph-image`;

  const noticeJsonLd = {
    "@context": "https://schema.org",
    "@graph": [
      {
        "@type": "Article",
        "@id": `https://classmatebd.vercel.app/n/${notice.id}#article`,
        headline: notice.title,
        description: notice.body ? notice.body.slice(0, 150).replace(/\s+/g, " ").trim() : notice.title,
        datePublished: notice.published_at,
        dateModified: notice.published_at,
        mainEntityOfPage: `https://classmatebd.vercel.app/n/${notice.id}`,
        image: ogImageUrl,
        author: {
          "@type": "Person",
          name: notice.author_name || "Academic Department",
        },
        publisher: {
          "@type": "EducationalOrganization",
          name: "ClassMate",
          url: "https://classmatebd.vercel.app",
          logo: "https://classmatebd.vercel.app/logo.png",
        },
        articleBody: notice.body || notice.title,
      },
      {
        "@type": "BreadcrumbList",
        itemListElement: [
          {
            "@type": "ListItem",
            position: 1,
            name: "Home",
            item: "https://classmatebd.vercel.app",
          },
          {
            "@type": "ListItem",
            position: 2,
            name: "Notices",
            item: "https://classmatebd.vercel.app/#notices",
          },
          {
            "@type": "ListItem",
            position: 3,
            name: notice.title,
            item: `https://classmatebd.vercel.app/n/${notice.id}`,
          },
        ],
      },
    ],
  };

  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(noticeJsonLd) }}
      />
      <main className="public-notice-page">
      <div className="public-notice-wrapper">
        {/* App Link Floating Banner for Mobile Users */}
        <div className="mobile-app-banner">
          <div className="mobile-app-info">
            <Smartphone size={18} className="mobile-app-icon" />
            <span>Have the ClassMate Android App?</span>
          </div>
          <a href={appIntentUrl} className="mobile-app-link">
            Open in App
          </a>
        </div>

        {/* Main Notice Card (Zero-Login Presentation) */}
        <article className="public-notice-card">
          {/* Header Tag / Cancellation Banner */}
          {notice.is_cancelled ? (
            <div className="notice-cancel-banner">
              <AlertTriangle size={18} />
              <div>
                <strong>Class Cancelled</strong>
                {notice.class_change_date && (
                  <span> for {new Date(notice.class_change_date).toLocaleDateString("en-US", { month: "short", day: "numeric" })}</span>
                )}
                {notice.class_change_details ? ` · ${notice.class_change_details}` : ""}
              </div>
            </div>
          ) : notice.course_title ? (
            <div className="notice-course-badge">
              <BookOpen size={15} />
              <span>{notice.course_title}</span>
              {notice.course_code && <span className="course-code-tag">{notice.course_code}</span>}
            </div>
          ) : (
            <div className="notice-general-badge">
              <Bell size={15} />
              <span>Official Academic Notice</span>
            </div>
          )}

          {/* Title */}
          <h1 className="notice-title">{notice.title}</h1>

          {/* Author & Timestamp Bar */}
          <div className="notice-meta-bar">
            <div className="author-info">
              {notice.author_avatar_url ? (
                <img
                  src={notice.author_avatar_url}
                  alt={notice.author_name || "Author"}
                  className="author-avatar"
                />
              ) : (
                <div className="author-avatar-fallback">
                  <User size={16} />
                </div>
              )}
              <div className="author-details">
                <span className="author-name">
                  {notice.author_name || "ClassMate Member"}
                  {notice.author_is_cr && <span className="cr-badge">CR</span>}
                </span>
                <span className="notice-batch-text">
                  {notice.department_code ? notice.department_code.toUpperCase() : "MBSTU"} · Batch {notice.batch_number || ""}
                </span>
              </div>
            </div>

            <div className="notice-time-info">
              <Calendar size={14} />
              <time dateTime={notice.published_at}>{publishedDate}</time>
            </div>
          </div>

          <div className="notice-divider" />

          {/* Notice Body */}
          <div className="notice-body-content">
            {notice.body}
          </div>

          {/* Assessment Card (if notice is an assessment announcement) */}
          {notice.assessment && (
            <div
              style={{
                marginTop: 20,
                borderRadius: 16,
                padding: "18px 20px",
                background: "color-mix(in srgb, var(--primary, #6366f1) 6%, transparent)",
                border: "1.5px solid color-mix(in srgb, var(--primary, #6366f1) 20%, transparent)",
                display: "flex",
                flexDirection: "column",
                gap: 14,
              }}
            >
              <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 8 }}>
                <span
                  style={{
                    padding: "4px 12px",
                    borderRadius: 9999,
                    fontSize: "0.75rem",
                    fontWeight: 700,
                    textTransform: "uppercase",
                    letterSpacing: "0.5px",
                    background: "color-mix(in srgb, var(--primary, #6366f1) 15%, transparent)",
                    color: "var(--primary, #6366f1)",
                    border: "1px solid color-mix(in srgb, var(--primary, #6366f1) 30%, transparent)",
                  }}
                >
                  📝 {notice.assessment.type || "Assessment"}
                </span>
                {notice.assessment.course_code && (
                  <span style={{ fontSize: "0.85rem", fontWeight: 700, color: "var(--text-secondary, #64748b)" }}>
                    {notice.assessment.course_code}
                  </span>
                )}
              </div>

              <div>
                <h2 style={{ fontSize: "1.2rem", fontWeight: 700, margin: "0 0 4px 0", color: "var(--text-primary, #0f172a)" }}>
                  {notice.assessment.title}
                </h2>
                {notice.assessment.course_title && (
                  <p style={{ margin: 0, fontSize: "0.9rem", color: "var(--text-secondary, #64748b)" }}>
                    {notice.assessment.course_title}
                  </p>
                )}
              </div>

              <div
                style={{
                  display: "grid",
                  gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
                  gap: 10,
                  padding: "12px 14px",
                  borderRadius: 12,
                  background: "var(--card-bg, #ffffff)",
                  border: "1px solid var(--border-color, rgba(0,0,0,0.08))",
                  fontSize: "0.88rem",
                }}
              >
                <div>
                  <div style={{ color: "var(--text-muted, #64748b)", fontSize: "0.75rem", marginBottom: 2 }}>📅 Deadline</div>
                  <div style={{ fontWeight: 600, color: "var(--text-primary, #0f172a)" }}>
                    {notice.assessment.deadline_date}{" "}
                    {notice.assessment.deadline_time ? `at ${notice.assessment.deadline_time}` : ""}
                  </div>
                </div>
                {notice.assessment.room && (
                  <div>
                    <div style={{ color: "var(--text-muted, #64748b)", fontSize: "0.75rem", marginBottom: 2 }}>📍 Venue</div>
                    <div style={{ fontWeight: 600, color: "var(--text-primary, #0f172a)" }}>
                      {/^\d+$/.test(notice.assessment.room.trim())
                        ? `Room ${notice.assessment.room.trim()}`
                        : notice.assessment.room.trim()}
                    </div>
                  </div>
                )}
              </div>

              {notice.assessment.syllabus && (
                <div>
                  <div style={{ fontWeight: 600, fontSize: "0.82rem", marginBottom: 6, color: "var(--text-muted, #64748b)" }}>
                    📖 Syllabus & Instructions
                  </div>
                  <div
                    style={{
                      padding: "12px 14px",
                      borderRadius: 12,
                      background: "var(--card-bg, #ffffff)",
                      border: "1px solid var(--border-color, rgba(0,0,0,0.08))",
                      fontSize: "0.9rem",
                      lineHeight: 1.6,
                      whiteSpace: "pre-wrap",
                      color: "var(--text-primary, #0f172a)",
                    }}
                  >
                    {notice.assessment.syllabus}
                  </div>
                </div>
              )}

              <a
                href={`https://classmatebd.vercel.app/#upcoming?id=${notice.assessment.id}`}
                style={{
                  marginTop: 4,
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  gap: 8,
                  padding: "10px 18px",
                  borderRadius: 12,
                  background: "var(--primary, #6366f1)",
                  color: "#ffffff",
                  textDecoration: "none",
                  fontWeight: 600,
                  fontSize: "0.88rem",
                }}
              >
                <CalendarClock size={16} />
                <span>Open in Upcoming Assessments &rarr;</span>
              </a>
            </div>
          )}

          {/* Poll Card (if notice is a poll) */}
          {notice.poll && (
            <div className="notice-public-poll-container">
              <div className="notice-poll-header">
                <div className="poll-badge-row">
                  <span className="poll-tag">📊 Poll</span>
                  <span className="poll-meta-badge">
                    {notice.poll.is_multiple_choice ? "Multiple choice" : "Single choice"}
                  </span>
                  {notice.poll.is_closed ? (
                    <span className="poll-closed-badge">Closed</span>
                  ) : (
                    <span className="poll-active-badge">Active</span>
                  )}
                </div>
                <h3 className="notice-poll-question">{notice.poll.question}</h3>
              </div>

              <div className="notice-poll-options-list">
                {notice.poll.options.map((opt) => {
                  const percent =
                    notice.poll!.total_votes > 0
                      ? Math.round((opt.votes_count / notice.poll!.total_votes) * 100)
                      : 0;
                  return (
                    <div key={opt.id} className="notice-poll-option-row">
                      <div
                        className="poll-option-bar-fill"
                        style={{ width: `${percent}%` }}
                      />
                      <div className="poll-option-content">
                        <span className="poll-option-label">{opt.option_text}</span>
                        <span className="poll-option-votes">
                          {opt.votes_count} ({percent}%)
                        </span>
                      </div>
                    </div>
                  );
                })}
              </div>

              <div className="notice-poll-footer">
                <span>
                  {notice.poll.total_voters} {notice.poll.total_voters === 1 ? "voter" : "voters"} ·{" "}
                  {notice.poll.total_votes} {notice.poll.total_votes === 1 ? "vote" : "votes"}
                </span>
                {notice.poll.expires_at && (
                  <span>
                    Ends {new Date(notice.poll.expires_at).toLocaleDateString("en-US", { month: "short", day: "numeric" })}
                  </span>
                )}
              </div>
            </div>
          )}

          {/* Notice Attachments (Documents & Images) */}
          {notice.attachments && notice.attachments.length > 0 && (
            <div className="notice-public-attachments-section">
              {/* PDF Documents */}
              {notice.attachments
                .filter(
                  (a) =>
                    a.mime_type === "application/pdf" ||
                    a.file_name?.toLowerCase().endsWith(".pdf"),
                )
                .map((doc) => (
                  <div key={doc.id} className="notice-attachment-box">
                    <div className="attachment-left">
                      <div className="attachment-icon-pill doc-pill">
                        <FileText size={20} />
                      </div>
                      <div className="attachment-meta">
                        <span className="attachment-title">{doc.file_name}</span>
                        <span className="attachment-sub">
                          PDF Document
                          {doc.size_bytes && doc.size_bytes > 0
                            ? ` · ${
                                doc.size_bytes > 1048576
                                  ? `${(doc.size_bytes / 1048576).toFixed(1)} MB`
                                  : `${Math.round(doc.size_bytes / 1024)} KB`
                              }`
                            : ""}
                        </span>
                      </div>
                    </div>
                    <a
                      href={doc.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="attachment-open-btn"
                    >
                      <span>Open PDF</span>
                      <ExternalLink size={14} />
                    </a>
                  </div>
                ))}

              {/* Images */}
              {(() => {
                const images = notice.attachments.filter(
                  (a) =>
                    a.mime_type?.startsWith("image/") ||
                    !a.file_name?.toLowerCase().endsWith(".pdf"),
                );
                if (images.length === 0) return null;

                if (images.length === 1) {
                  return (
                    <div className="notice-public-single-image-wrapper">
                      <a
                        href={images[0].url}
                        target="_blank"
                        rel="noopener noreferrer"
                        title="Click to view full resolution"
                      >
                        <img
                          src={images[0].url}
                          alt={images[0].file_name || notice.title}
                          className="notice-public-image"
                          loading="lazy"
                        />
                      </a>
                      <div className="notice-public-image-footer">
                        <span>{images[0].file_name}</span>
                        {images[0].size_bytes ? (
                          <span>
                            {images[0].size_bytes > 1048576
                              ? `${(images[0].size_bytes / 1048576).toFixed(1)} MB`
                              : `${Math.round(images[0].size_bytes / 1024)} KB`}
                          </span>
                        ) : null}
                      </div>
                    </div>
                  );
                }

                return (
                  <div className={`notice-public-images-grid grid-${Math.min(images.length, 3)}`}>
                    {images.map((img, idx) => (
                      <a
                        key={img.id || idx}
                        href={img.url}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="notice-public-grid-item"
                        title={`View ${img.file_name || "full image"}`}
                      >
                        <img
                          src={img.url}
                          alt={img.file_name || `Attachment ${idx + 1}`}
                          className="notice-public-image-thumbnail"
                          loading="lazy"
                        />
                        <div className="notice-public-grid-overlay">
                          <span className="notice-public-grid-name">{img.file_name}</span>
                        </div>
                      </a>
                    ))}
                  </div>
                );
              })()}
            </div>
          )}

          {/* Resource / File Attachment if any */}
          {notice.resource_id && (
            <div className="notice-attachment-box">
              <div className="attachment-left">
                <div className="attachment-icon-pill">
                  <FileText size={20} />
                </div>
                <div className="attachment-meta">
                  <span className="attachment-title">
                    {notice.resource_title || "Attached Learning Resource"}
                  </span>
                  <span className="attachment-sub">
                    {notice.resource_file_type ? notice.resource_file_type.toUpperCase() : "FILE"}
                    {notice.resource_size_bytes && notice.resource_size_bytes > 0
                      ? ` · ${Math.ceil(notice.resource_size_bytes / 1024)} KB`
                      : ""}
                  </span>
                </div>
              </div>
              <a
                href={notice.resource_slug ? `/r/${notice.resource_slug}` : (notice.resource_link_url || `/r/${notice.resource_id}`)}
                target="_blank"
                rel="noopener noreferrer"
                className="attachment-open-btn"
              >
                <span>Open File</span>
                <ExternalLink size={14} />
              </a>
            </div>
          )}
        </article>

        {/* Bottom Gateway Section: Two Distinctive Action Buttons */}
        <section className="gateway-section">
          <div className="gateway-header">
            <h3>Get the best ClassMate experience</h3>
            <p>Access your timetable, real-time notices, bus schedules, and batch library.</p>
          </div>

          <div className="gateway-buttons-grid">
            <a
              href="/download/android"
              className="gateway-btn btn-android"
              target="_blank"
              rel="noopener noreferrer"
            >
              <Smartphone size={20} />
              <div className="btn-text-col">
                <span className="btn-label-sm">Download App</span>
                <span className="btn-label-lg">ClassMate Android</span>
              </div>
            </a>

            <a
              href="/"
              className="gateway-btn btn-web"
            >
              <Globe size={20} />
              <div className="btn-text-col">
                <span className="btn-label-sm">Web Dashboard</span>
                <span className="btn-label-lg">Continue to Web</span>
              </div>
            </a>
          </div>
        </section>

        {/* Minimal ClassMate Branding Footer */}
        <footer className="public-brand-footer">
          <span className="brand-subtext">Shared via</span>
          <a href="https://classmatebd.vercel.app" className="brand-logo-text">
            classmate<span className="brand-dot">.</span>
          </a>
        </footer>
      </div>

      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@600;700;800&family=Inter:wght@400;500;600;700&display=swap');

        :root {
          --page-bg: #f8fafc;
          --card-bg: #ffffff;
          --card-border: rgba(226, 232, 240, 0.9);
          --card-shadow: 0 16px 40px -12px rgba(15, 23, 42, 0.08);
          --text-main: #0f172a;
          --text-secondary: #475569;
          --text-muted: #64748b;
          --accent-blue: #2563eb;
          --accent-blue-bg: rgba(37, 99, 235, 0.08);
          --accent-blue-border: rgba(37, 99, 235, 0.18);
          --cancel-bg: #fef2f2;
          --cancel-border: #fecaca;
          --cancel-text: #b91c1c;
          --divider: #f1f5f9;
          --attachment-bg: #f8fafc;
          --attachment-border: #e2e8f0;
          --gateway-bg: #ffffff;
          --gateway-border: #e2e8f0;
          --btn-android-bg: #0f172a;
          --btn-android-text: #ffffff;
          --btn-web-bg: #ffffff;
          --btn-web-text: #0f172a;
          --btn-web-border: #cbd5e1;
        }

        @media (prefers-color-scheme: dark) {
          :root {
            --page-bg: #0b0f19;
            --card-bg: #111827;
            --card-border: rgba(255, 255, 255, 0.08);
            --card-shadow: 0 24px 48px -12px rgba(0, 0, 0, 0.6);
            --text-main: #f8fafc;
            --text-secondary: #cbd5e1;
            --text-muted: #94a3b8;
            --accent-blue: #60a5fa;
            --accent-blue-bg: rgba(59, 130, 246, 0.15);
            --accent-blue-border: rgba(59, 130, 246, 0.25);
            --cancel-bg: rgba(127, 29, 29, 0.25);
            --cancel-border: rgba(239, 68, 68, 0.3);
            --cancel-text: #fca5a5;
            --divider: rgba(255, 255, 255, 0.06);
            --attachment-bg: rgba(17, 24, 39, 0.7);
            --attachment-border: rgba(255, 255, 255, 0.09);
            --gateway-bg: #111827;
            --gateway-border: rgba(255, 255, 255, 0.08);
            --btn-android-bg: #1e293b;
            --btn-android-text: #ffffff;
            --btn-web-bg: rgba(255, 255, 255, 0.05);
            --btn-web-text: #f8fafc;
            --btn-web-border: rgba(255, 255, 255, 0.12);
          }
        }

        .public-notice-page {
          min-height: 100vh;
          display: flex;
          align-items: center;
          justify-content: center;
          padding: 32px 16px;
          background: var(--page-bg);
          font-family: 'Inter', -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          color: var(--text-main);
          box-sizing: border-box;
        }

        .public-notice-wrapper {
          width: 100%;
          max-width: 640px;
          display: flex;
          flex-direction: column;
          gap: 20px;
        }

        .mobile-app-banner {
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding: 10px 16px;
          background: var(--accent-blue-bg);
          border: 1px solid var(--accent-blue-border);
          border-radius: 12px;
          font-size: 13px;
          font-weight: 500;
          color: var(--accent-blue);
        }

        .mobile-app-info {
          display: flex;
          align-items: center;
          gap: 8px;
        }

        .mobile-app-link {
          font-weight: 700;
          color: var(--accent-blue);
          text-decoration: underline;
          text-underline-offset: 3px;
        }

        .public-notice-card {
          background: var(--card-bg);
          border: 1px solid var(--card-border);
          border-radius: 20px;
          padding: 28px;
          box-shadow: var(--card-shadow);
          box-sizing: border-box;
        }

        .notice-cancel-banner {
          display: flex;
          align-items: center;
          gap: 10px;
          padding: 12px 16px;
          background: var(--cancel-bg);
          border: 1px solid var(--cancel-border);
          border-radius: 12px;
          color: var(--cancel-text);
          font-size: 14px;
          margin-bottom: 20px;
        }

        .notice-course-badge,
        .notice-general-badge {
          display: inline-flex;
          align-items: center;
          gap: 8px;
          padding: 6px 12px;
          border-radius: 8px;
          background: var(--accent-blue-bg);
          border: 1px solid var(--accent-blue-border);
          color: var(--accent-blue);
          font-size: 13px;
          font-weight: 600;
          margin-bottom: 16px;
        }

        .course-code-tag {
          font-size: 11px;
          font-weight: 700;
          background: rgba(37, 99, 235, 0.14);
          padding: 2px 6px;
          border-radius: 4px;
        }

        .notice-title {
          font-family: 'Plus Jakarta Sans', sans-serif;
          font-size: 24px;
          font-weight: 800;
          line-height: 1.35;
          letter-spacing: -0.02em;
          color: var(--text-main);
          margin: 0 0 18px 0;
          word-break: break-word;
        }

        .notice-meta-bar {
          display: flex;
          align-items: center;
          justify-content: space-between;
          flex-wrap: wrap;
          gap: 12px;
        }

        .author-info {
          display: flex;
          align-items: center;
          gap: 10px;
        }

        .author-avatar,
        .author-avatar-fallback {
          width: 38px;
          height: 38px;
          border-radius: 50%;
          object-fit: cover;
          display: flex;
          align-items: center;
          justify-content: center;
          background: rgba(148, 163, 184, 0.15);
          color: var(--text-muted);
          flex-shrink: 0;
        }

        .author-details {
          display: flex;
          flex-direction: column;
          line-height: 1.3;
        }

        .author-name {
          font-size: 14px;
          font-weight: 700;
          color: var(--text-main);
          display: flex;
          align-items: center;
          gap: 6px;
        }

        .cr-badge {
          font-size: 10px;
          font-weight: 800;
          padding: 1px 5px;
          background: #3b82f6;
          color: #ffffff;
          border-radius: 4px;
        }

        .notice-batch-text {
          font-size: 12px;
          color: var(--text-muted);
        }

        .notice-time-info {
          display: flex;
          align-items: center;
          gap: 6px;
          font-size: 12px;
          color: var(--text-muted);
        }

        .notice-divider {
          height: 1px;
          background: var(--divider);
          margin: 20px 0;
        }

        .notice-body-content {
          font-size: 15px;
          line-height: 1.7;
          color: var(--text-secondary);
          white-space: pre-wrap;
          word-break: break-word;
        }

        /* Public Poll Component */
        .notice-public-poll-container {
          margin-top: 24px;
          padding: 20px;
          border-radius: 16px;
          background: var(--attachment-bg);
          border: 1px solid var(--attachment-border);
        }

        .poll-badge-row {
          display: flex;
          align-items: center;
          gap: 8px;
          margin-bottom: 10px;
        }

        .poll-tag {
          font-size: 11px;
          font-weight: 700;
          text-transform: uppercase;
          letter-spacing: 0.5px;
          padding: 3px 8px;
          border-radius: 6px;
          background: rgba(14, 165, 233, 0.15);
          color: #0284c7;
        }

        .poll-meta-badge {
          font-size: 11px;
          font-weight: 500;
          color: var(--text-muted);
        }

        .poll-active-badge {
          margin-left: auto;
          font-size: 11px;
          font-weight: 600;
          padding: 2px 7px;
          border-radius: 10px;
          background: rgba(34, 197, 94, 0.15);
          color: #16a34a;
        }

        .poll-closed-badge {
          margin-left: auto;
          font-size: 11px;
          font-weight: 600;
          padding: 2px 7px;
          border-radius: 10px;
          background: rgba(156, 163, 175, 0.15);
          color: var(--text-muted);
        }

        .notice-poll-question {
          font-size: 16px;
          font-weight: 700;
          color: var(--text-main);
          margin: 0 0 16px 0;
          line-height: 1.4;
        }

        .notice-poll-options-list {
          display: flex;
          flex-direction: column;
          gap: 10px;
        }

        .notice-poll-option-row {
          position: relative;
          padding: 12px 14px;
          border-radius: 10px;
          border: 1px solid var(--attachment-border);
          background: var(--card-bg);
          overflow: hidden;
        }

        .poll-option-bar-fill {
          position: absolute;
          top: 0;
          left: 0;
          bottom: 0;
          background: rgba(14, 165, 233, 0.12);
          transition: width 0.3s ease;
          border-radius: 10px 0 0 10px;
        }

        .poll-option-content {
          position: relative;
          display: flex;
          justify-content: space-between;
          align-items: center;
          gap: 12px;
          z-index: 1;
        }

        .poll-option-label {
          font-size: 14px;
          font-weight: 500;
          color: var(--text-main);
          word-break: break-word;
        }

        .poll-option-votes {
          font-size: 12px;
          font-weight: 600;
          color: var(--text-muted);
          white-space: nowrap;
        }

        .notice-poll-footer {
          display: flex;
          justify-content: space-between;
          align-items: center;
          margin-top: 14px;
          font-size: 12px;
          color: var(--text-muted);
          padding-top: 10px;
          border-top: 1px dashed var(--attachment-border);
        }

        .notice-attachment-box {
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding: 14px 16px;
          background: var(--attachment-bg);
          border: 1px solid var(--attachment-border);
          border-radius: 14px;
          margin-top: 24px;
          gap: 12px;
        }

        .attachment-left {
          display: flex;
          align-items: center;
          gap: 12px;
          min-width: 0;
        }

        .attachment-icon-pill {
          width: 40px;
          height: 40px;
          border-radius: 10px;
          background: var(--accent-blue-bg);
          color: var(--accent-blue);
          display: flex;
          align-items: center;
          justify-content: center;
          flex-shrink: 0;
        }

        .attachment-meta {
          display: flex;
          flex-direction: column;
          min-width: 0;
        }

        .attachment-title {
          font-size: 13px;
          font-weight: 600;
          color: var(--text-main);
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }

        .attachment-sub {
          font-size: 11px;
          color: var(--text-muted);
          margin-top: 2px;
        }

        .attachment-open-btn {
          display: inline-flex;
          align-items: center;
          gap: 6px;
          padding: 8px 14px;
          border-radius: 8px;
          background: var(--accent-blue);
          color: #ffffff;
          font-size: 12px;
          font-weight: 600;
          text-decoration: none;
          flex-shrink: 0;
          transition: transform 0.15s ease, opacity 0.15s ease;
        }

        .attachment-open-btn:hover {
          opacity: 0.9;
          transform: translateY(-1px);
        }

        .notice-public-attachments-section {
          margin-top: 18px;
        }

        .doc-pill {
          background: rgba(239, 68, 68, 0.12) !important;
          color: #ef4444 !important;
        }

        .notice-public-single-image-wrapper {
          margin-top: 16px;
          border-radius: 14px;
          overflow: hidden;
          border: 1px solid var(--attachment-border);
          background: var(--attachment-bg);
        }

        .notice-public-image {
          width: 100%;
          max-height: 480px;
          object-fit: contain;
          display: block;
          background: rgba(0, 0, 0, 0.04);
          transition: transform 0.2s ease;
        }

        .notice-public-single-image-wrapper:hover .notice-public-image {
          transform: scale(1.008);
        }

        .notice-public-image-footer {
          padding: 8px 14px;
          display: flex;
          justify-content: space-between;
          font-size: 12px;
          color: var(--text-muted);
          background: var(--card-bg);
          border-top: 1px solid var(--attachment-border);
        }

        .notice-public-images-grid {
          display: grid;
          gap: 12px;
          margin-top: 16px;
          border-radius: 14px;
        }

        .notice-public-images-grid.grid-2 {
          grid-template-columns: repeat(2, 1fr);
        }

        .notice-public-images-grid.grid-3 {
          grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
        }

        .notice-public-grid-item {
          position: relative;
          display: block;
          border-radius: 12px;
          overflow: hidden;
          border: 1px solid var(--attachment-border);
          aspect-ratio: 4 / 3;
          background: var(--attachment-bg);
          text-decoration: none;
        }

        .notice-public-image-thumbnail {
          width: 100%;
          height: 100%;
          object-fit: cover;
          display: block;
          transition: transform 0.25s ease;
        }

        .notice-public-grid-item:hover .notice-public-image-thumbnail {
          transform: scale(1.04);
        }

        .notice-public-grid-overlay {
          position: absolute;
          bottom: 0;
          left: 0;
          right: 0;
          padding: 6px 10px;
          background: linear-gradient(to top, rgba(0, 0, 0, 0.75), transparent);
          color: #ffffff;
          font-size: 11px;
          display: flex;
          align-items: center;
        }

        .notice-public-grid-name {
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .gateway-section {
          background: var(--gateway-bg);
          border: 1px solid var(--gateway-border);
          border-radius: 20px;
          padding: 22px 24px;
          box-shadow: 0 10px 25px -8px rgba(15, 23, 42, 0.05);
        }

        .gateway-header h3 {
          font-family: 'Plus Jakarta Sans', sans-serif;
          font-size: 16px;
          font-weight: 700;
          margin: 0 0 4px 0;
          color: var(--text-main);
        }

        .gateway-header p {
          font-size: 13px;
          color: var(--text-muted);
          margin: 0 0 18px 0;
        }

        .gateway-buttons-grid {
          display: grid;
          grid-template-columns: 1fr 1fr;
          gap: 12px;
        }

        @media (max-width: 520px) {
          .gateway-buttons-grid {
            grid-template-columns: 1fr;
          }
        }

        .gateway-btn {
          display: flex;
          align-items: center;
          gap: 12px;
          padding: 12px 16px;
          border-radius: 12px;
          text-decoration: none;
          transition: transform 0.15s ease, box-shadow 0.15s ease;
        }

        .gateway-btn:hover {
          transform: translateY(-1px);
        }

        .btn-android {
          background: var(--btn-android-bg);
          color: var(--btn-android-text);
        }

        .btn-web {
          background: var(--btn-web-bg);
          color: var(--btn-web-text);
          border: 1px solid var(--btn-web-border);
        }

        .btn-text-col {
          display: flex;
          flex-direction: column;
          line-height: 1.25;
        }

        .btn-label-sm {
          font-size: 10px;
          text-transform: uppercase;
          letter-spacing: 0.05em;
          opacity: 0.75;
        }

        .btn-label-lg {
          font-size: 13px;
          font-weight: 700;
        }

        .public-brand-footer {
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 6px;
          padding-top: 8px;
        }

        .brand-subtext {
          font-size: 13px;
          color: var(--text-muted);
        }

        .brand-logo-text {
          font-family: 'Plus Jakarta Sans', sans-serif;
          font-size: 16px;
          font-weight: 800;
          color: var(--text-main);
          text-decoration: none;
          letter-spacing: -0.03em;
        }

        .brand-dot {
          color: #2563eb;
        }

        .not-found-card {
          text-align: center;
          padding: 48px 24px;
        }

        .not-found-icon {
          font-size: 40px;
          margin-bottom: 16px;
        }

        .not-found-title {
          font-size: 20px;
          font-weight: 700;
          margin: 0 0 8px 0;
        }

        .not-found-text {
          font-size: 14px;
          color: var(--text-muted);
          margin: 0 0 24px 0;
        }

        .primary-action-btn {
          display: inline-block;
          padding: 10px 20px;
          background: var(--accent-blue);
          color: #ffffff;
          border-radius: 10px;
          font-size: 14px;
          font-weight: 600;
          text-decoration: none;
        }
      `}</style>
    </main>
    </>
  );
}

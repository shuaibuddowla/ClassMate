import { Metadata } from "next";
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
} from "lucide-react";

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
  resource_title?: string;
  resource_file_type?: string;
  resource_size_bytes?: number;
  resource_mime_type?: string;
  resource_link_url?: string;
  resource_is_link?: boolean;
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
  const { data, error } = await db.schema("classmate").rpc("get_public_notice", { target_identifier: id });
  if (error || !data) {
    const { data: fallbackData } = await db.rpc("get_public_notice", { target_identifier: id });
    if (!fallbackData) return null;
    return fallbackData as PublicNotice;
  }
  return data as PublicNotice;
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ id: string }>;
}): Promise<Metadata> {
  const { id } = await params;
  const notice = await fetchPublicNotice(id);
  if (!notice) {
    return { title: "Notice Not Found · ClassMate" };
  }

  const deptBatch = `${notice.department_code ? notice.department_code.toUpperCase() : "MBSTU"} · Batch ${notice.batch_number || "CSE"}`;
  const author = notice.author_name ? `By ${notice.author_name}` : "ClassMate";
  const snippet = notice.body.slice(0, 150).replace(/\s+/g, " ").trim();
  const description = `${author} (${deptBatch}): ${snippet || notice.title}`;
  const pageUrl = `https://classmatebd.vercel.app/n/${notice.id}`;
  const ogImageUrl = `https://classmatebd.vercel.app/n/${notice.id}/opengraph-image`;

  return {
    title: `${notice.title} · ClassMate Notice`,
    description,
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
    return (
      <main className="public-notice-page">
        <div className="public-notice-wrapper">
          <div className="public-notice-card not-found-card">
            <div className="not-found-icon">📢</div>
            <h1 className="not-found-title">Notice Not Found</h1>
            <p className="not-found-text">
              This notice link is either invalid, deleted, or no longer available.
            </p>
            <a href="https://classmatebd.vercel.app" className="primary-action-btn">
              <span>Go to ClassMate Home</span>
            </a>
          </div>
        </div>
      </main>
    );
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

  return (
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
                href={notice.resource_link_url || `/r/${notice.resource_id}`}
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
  );
}

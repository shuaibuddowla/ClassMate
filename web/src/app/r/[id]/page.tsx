import { Metadata } from "next";
import { createClient } from "@supabase/supabase-js";
import { Download, ExternalLink } from "lucide-react";

interface PublicResource {
  id: string;
  slug?: string;
  title: string;
  file_type?: string;
  size_bytes?: number;
  mime_type?: string;
  link_url?: string;
  is_link: boolean;
  course_title?: string;
  course_code?: string;
  batch_number?: number;
  created_at?: string;
}

function getSupabase() {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || "https://cbskpifgbdbgdmnnxfrh.supabase.co";
  const key = process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || "";
  return createClient(url, key, { auth: { persistSession: false } });
}

async function fetchPublicResource(id: string): Promise<PublicResource | null> {
  if (!id || !/^[a-z0-9_-]{1,128}$/i.test(id)) return null;
  const db = getSupabase();
  const { data, error } = await db.schema("classmate").rpc("get_public_resource", { target_identifier: id });
  if (error || !data) return null;
  return data as PublicResource;
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ id: string }>;
}): Promise<Metadata> {
  const { id } = await params;
  const res = await fetchPublicResource(id);
  if (!res) {
    return { title: "Resource Not Found · ClassMate" };
  }
  const course = res.course_title ? `${res.course_title}${res.course_code ? ` (${res.course_code})` : ""}` : "Academic Resource";
  const slugPath = res.slug || res.id;
  const ogImageUrl = `https://classmatebd.vercel.app/r/${slugPath}/opengraph-image?v=light3`;
  return {
    title: res.title,
    description: `Course: ${course} · Shared with Batch ${res.batch_number || 22}`,
    openGraph: {
      title: res.title,
      description: `Course: ${course}`,
      url: `https://classmatebd.vercel.app/r/${slugPath}`,
      siteName: "ClassMate",
      type: "website",
      images: [
        {
          url: ogImageUrl,
          width: 1200,
          height: 630,
          alt: res.title,
        },
      ],
    },
    twitter: {
      card: "summary_large_image",
      title: res.title,
      description: `Course: ${course}`,
      images: [ogImageUrl],
    },
  };
}

function GoogleDriveIcon({ size = 44 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 87.3 78" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path d="m6.6 66.85 3.85 6.65c.8 1.4 1.95 2.5 3.3 3.3l13.75-23.8H0c0 1.55.4 3.1 1.2 4.5z" fill="#0066DA" />
      <path d="M43.65 25 29.9 1.2c-1.35.8-2.5 1.9-3.3 3.3l-25.4 44C.4 50 0 51.55 0 53.1h27.5z" fill="#00AC47" />
      <path d="M73.55 76.8c1.35-.8 2.5-1.9 3.3-3.3l1.6-2.75 7.65-13.25c.8-1.4 1.2-2.95 1.2-4.5H59.8l5.85 10.15z" fill="#EA4335" />
      <path d="M43.65 25 57.4 1.2C56.05.4 54.5 0 52.95 0H34.35c-1.55 0-3.1.4-4.45 1.2z" fill="#00832D" />
      <path d="M59.8 53.1H27.5L13.75 76.9c1.35.75 2.9 1.1 4.45 1.1h50.9c1.55 0 3.1-.35 4.45-1.1z" fill="#2684FC" />
      <path d="M73.4 26.5 60.7 4.5c-.8-1.4-1.95-2.5-3.3-3.3L43.65 25l16.15 28.1h27.5c0-1.55-.4-3.1-1.2-4.5z" fill="#FFBA00" />
    </svg>
  );
}

function PremiumPdfIcon() {
  return (
    <svg className="pdf-doc-svg" viewBox="0 0 56 60" fill="none" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id="pdfGrad" x1="0" y1="0" x2="56" y2="60" gradientUnits="userSpaceOnUse">
          <stop stopColor="#FF4D4D"/>
          <stop offset="1" stopColor="#DC2626"/>
        </linearGradient>
        <linearGradient id="foldGrad" x1="42" y1="0" x2="56" y2="14" gradientUnits="userSpaceOnUse">
          <stop stopColor="#B91C1C"/>
          <stop offset="1" stopColor="#991B1B"/>
        </linearGradient>
      </defs>
      <path d="M8 0C3.58 0 0 3.58 0 8v44c0 4.42 3.58 8 8 8h40c4.42 0 8-3.58 8-8V14L42 0H8z" fill="url(#pdfGrad)"/>
      <path d="M42 0v10c0 2.21 1.79 4 4 4h10L42 0z" fill="url(#foldGrad)" opacity="0.85"/>
      <rect x="12" y="18" width="22" height="3" rx="1.5" fill="#FFFFFF" fillOpacity="0.45"/>
      <rect x="12" y="24" width="32" height="3" rx="1.5" fill="#FFFFFF" fillOpacity="0.45"/>
      <rect x="10" y="34" width="36" height="18" rx="5" fill="#FFFFFF"/>
      <text x="28" y="47.5" fontFamily="'Plus Jakarta Sans', system-ui, sans-serif" fontSize="11" fontWeight="900" fill="#DC2626" textAnchor="middle" letterSpacing="0.5">PDF</text>
    </svg>
  );
}

export default async function PublicResourcePage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const res = await fetchPublicResource(id);

  if (!res) {
    return (
      <main className="public-resource-page">
        <div className="public-resource-wrapper">
          <div className="public-card not-found-card">
            <div className="not-found-icon">🔍</div>
            <h1 className="not-found-title">Resource Not Found</h1>
            <p className="not-found-text">
              This link is either invalid, deleted, or no longer available for public access.
            </p>
            <a href="https://classmatebd.vercel.app" className="action-btn open-btn">
              <span>Go to ClassMate</span>
            </a>
          </div>
        </div>
      </main>
    );
  }

  const isDrive = Boolean(
    res.is_link &&
      res.link_url &&
      /drive\.google\.com|docs\.google\.com|drive\.link/i.test(res.link_url)
  );

  const formattedDate = res.created_at
    ? new Date(res.created_at).toLocaleDateString("en-US", {
        month: "short",
        day: "numeric",
        year: "numeric",
      })
    : "";

  const formattedSize =
    res.size_bytes && res.size_bytes > 0
      ? `${Math.ceil(res.size_bytes / 1024)} KB`
      : "Link";

  const slugIdentifier = res.slug || res.id;
  const openUrl = res.is_link
    ? res.link_url || `/api/public-download/${slugIdentifier}?mode=inline`
    : `/api/public-download/${slugIdentifier}?mode=inline`;

  const downloadUrl = `/api/public-download/${slugIdentifier}?mode=download`;
  const appIntentUrl = `intent://classmatebd.vercel.app/r/${res.id}#Intent;scheme=https;package=com.shuaib.classmate;S.browser_fallback_url=https%3A%2F%2Fclassmatebd.vercel.app;end`;

  return (
    <main className="public-resource-page">
      <div className="public-resource-wrapper">
        {/* Main Card (Self-Contained) */}
        <section className="public-card">
          {/* Course Banner */}
          {res.course_title && (
            <div className="public-course-banner">
              <svg className="course-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24" width="16" height="16">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253"/>
              </svg>
              <span className="course-title-text">{res.course_title}</span>
              {res.course_code && (
                <span className="course-code-badge">{res.course_code}</span>
              )}
            </div>
          )}

          {/* Hero Section: Icon + Title */}
          <div className="public-title-area">
            <div className="file-icon-box">
              {isDrive ? (
                <GoogleDriveIcon size={46} />
              ) : res.is_link ? (
                <div className="link-icon-badge">
                  <ExternalLink size={26} />
                </div>
              ) : (
                <PremiumPdfIcon />
              )}
            </div>
            <div className="public-title-text">
              <h1 className="resource-display-title">{res.title}</h1>
              <p className="resource-subtitle">
                {isDrive ? "Google Drive Resource" : res.is_link ? "Web Resource Link" : "Official Course Curriculum"}
              </p>
            </div>
          </div>

          {/* Structured Bento Metadata Row */}
          <div className="metadata-bento-grid">
            <div className="bento-chip">
              <span className="bento-label">Size</span>
              <span className="bento-value">{formattedSize}</span>
            </div>
            <div className="bento-chip">
              <span className="bento-label">Batch</span>
              <span className="bento-value">{res.batch_number ? `CSE ${res.batch_number}` : "All"}</span>
            </div>
            <div className="bento-chip date-chip">
              <span className="bento-label">Shared</span>
              <span className="bento-value date-value">{formattedDate || "Recent"}</span>
            </div>
          </div>

          {/* Dual Action Buttons: Side by Side, Perfectly Balanced */}
          <div className="dual-actions-grid">
            {/* 1: Open Button */}
            <a
              href={openUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="action-btn open-btn"
            >
              <ExternalLink size={17} />
              <span>Open</span>
            </a>

            {/* 2: Download Button */}
            <a
              href={downloadUrl}
              target={res.is_link ? "_blank" : undefined}
              rel="noopener noreferrer"
              className="action-btn download-btn"
              download={!res.is_link}
            >
              <Download size={17} />
              <span>Download</span>
            </a>
          </div>

          {/* Under Layout Branding Footer: 2 Centered Lines with App Deep Link */}
          <div className="public-brand-footer">
            <span className="from-label">from</span>
            <a
              href={appIntentUrl}
              className="timetable-brand-title"
              title="Open in ClassMate App"
            >
              classmate<span className="brand-dot">.</span>
            </a>
          </div>
        </section>
      </div>

      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@700;800&family=Inter:wght@400;500;600;700&display=swap');

        :root {
          --page-bg: #f8fafc;
          --card-bg: #ffffff;
          --card-border: rgba(226, 232, 240, 0.9);
          --card-shadow: 0 20px 45px -15px rgba(15, 23, 42, 0.08);
          --title-color: #0f172a;
          --subtitle-color: #64748b;
          --bento-bg: #f8fafc;
          --bento-border: #e2e8f0;
          --chip-bg: #ffffff;
          --chip-border: #e2e8f0;
          --chip-label: #94a3b8;
          --chip-val: #1e293b;
          --banner-bg: rgba(37, 99, 235, 0.08);
          --banner-border: rgba(37, 99, 235, 0.16);
          --banner-text: #1d4ed8;
          --btn-download-bg: #0f172a;
          --btn-download-text: #ffffff;
          --btn-download-border: #0f172a;
          --footer-border: #f1f5f9;
          --footer-from: #94a3b8;
          --footer-brand: #0f172a;
        }

        @media (prefers-color-scheme: dark) {
          :root {
            --page-bg: #0a0f1d;
            --card-bg: #111827;
            --card-border: rgba(255, 255, 255, 0.09);
            --card-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.6);
            --title-color: #f8fafc;
            --subtitle-color: #94a3b8;
            --bento-bg: rgba(15, 23, 42, 0.5);
            --bento-border: rgba(255, 255, 255, 0.06);
            --chip-bg: #1e293b;
            --chip-border: rgba(255, 255, 255, 0.08);
            --chip-label: #64748b;
            --chip-val: #f1f5f9;
            --banner-bg: rgba(37, 99, 235, 0.15);
            --banner-border: rgba(37, 99, 235, 0.25);
            --banner-text: #93c5fd;
            --btn-download-bg: #1e293b;
            --btn-download-text: #ffffff;
            --btn-download-border: rgba(255, 255, 255, 0.12);
            --footer-border: rgba(255, 255, 255, 0.08);
            --footer-from: #64748b;
            --footer-brand: #f8fafc;
          }
        }

        .public-resource-page {
          min-height: 100vh;
          display: flex;
          align-items: center;
          justify-content: center;
          padding: 24px 16px;
          background: var(--page-bg);
          font-family: 'Inter', -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        }
        .public-resource-wrapper {
          width: 100%;
          max-width: 440px;
        }
        .public-card {
          background: var(--card-bg);
          border: 1px solid var(--card-border);
          border-radius: 28px;
          padding: 28px 24px;
          box-shadow: var(--card-shadow);
          display: flex;
          flex-direction: column;
          gap: 20px;
          position: relative;
        }
        .public-course-banner {
          display: inline-flex;
          align-items: center;
          gap: 8px;
          background: var(--banner-bg);
          border: 1px solid var(--banner-border);
          padding: 6px 12px;
          border-radius: 12px;
          color: var(--banner-text);
          font-size: 13px;
          font-weight: 600;
          width: 100%;
          box-sizing: border-box;
        }
        .course-title-text {
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
          flex: 1;
        }
        .course-code-badge {
          background: #2563eb;
          color: #ffffff;
          padding: 2px 7px;
          border-radius: 6px;
          font-size: 11px;
          font-weight: 700;
          flex-shrink: 0;
        }
        .public-title-area {
          display: flex;
          align-items: flex-start;
          gap: 16px;
        }
        .file-icon-box {
          width: 56px;
          height: 60px;
          flex-shrink: 0;
          display: flex;
          align-items: center;
          justify-content: center;
        }
        .pdf-doc-svg {
          width: 56px;
          height: 60px;
          filter: drop-shadow(0 4px 10px rgba(220, 38, 38, 0.25));
        }
        .link-icon-badge {
          width: 52px;
          height: 52px;
          border-radius: 16px;
          background: rgba(37, 99, 235, 0.1);
          color: #2563eb;
          display: flex;
          align-items: center;
          justify-content: center;
        }
        .public-title-text {
          flex: 1;
          min-width: 0;
        }
        .resource-display-title {
          font-size: 20px;
          font-weight: 800;
          line-height: 1.3;
          margin: 0;
          word-break: break-word;
          color: var(--title-color);
          letter-spacing: -0.02em;
        }
        .resource-subtitle {
          font-size: 12.5px;
          font-weight: 500;
          color: var(--subtitle-color);
          margin: 4px 0 0 0;
        }
        .metadata-bento-grid {
          display: grid;
          grid-template-columns: 1fr 1fr 1.35fr;
          gap: 8px;
          background: var(--bento-bg);
          border: 1px solid var(--bento-border);
          padding: 8px;
          border-radius: 18px;
        }
        .bento-chip {
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          padding: 6px 4px;
          background: var(--chip-bg);
          border: 1px solid var(--chip-border);
          border-radius: 12px;
          min-width: 0;
        }
        .bento-label {
          font-size: 10px;
          font-weight: 700;
          text-transform: uppercase;
          letter-spacing: 0.05em;
          color: var(--chip-label);
        }
        .bento-value {
          font-size: 12px;
          font-weight: 700;
          color: var(--chip-val);
          margin-top: 2px;
          white-space: nowrap;
        }
        .date-value {
          font-size: 11px;
          white-space: nowrap;
        }
        .dual-actions-grid {
          display: grid;
          grid-template-columns: 1fr 1fr;
          gap: 12px;
        }
        .action-btn {
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 8px;
          height: 50px;
          border-radius: 14px;
          font-size: 15px;
          font-weight: 700;
          text-decoration: none;
          transition: all 0.15s ease;
          cursor: pointer;
          user-select: none;
        }
        .action-btn:active {
          transform: scale(0.98);
        }
        .open-btn {
          background: #2563eb;
          color: #ffffff;
          box-shadow: 0 4px 14px rgba(37, 99, 235, 0.35);
        }
        .open-btn:hover {
          background: #1d4ed8;
        }
        .download-btn {
          background: var(--btn-download-bg);
          color: var(--btn-download-text);
          border: 1px solid var(--btn-download-border);
          box-shadow: 0 4px 14px rgba(0, 0, 0, 0.15);
        }
        .download-btn:hover {
          opacity: 0.92;
        }
        .public-brand-footer {
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          padding-top: 18px;
          border-top: 1px solid var(--footer-border);
        }
        .from-label {
          font-size: 12px;
          font-weight: 500;
          color: var(--footer-from);
          text-transform: lowercase;
          letter-spacing: -0.01em;
          line-height: 1;
          margin-bottom: 4px;
        }
        .timetable-brand-title {
          font-family: 'Plus Jakarta Sans', system-ui, -apple-system, sans-serif;
          font-size: 24px;
          font-weight: 800;
          letter-spacing: -0.035em;
          color: var(--footer-brand);
          text-transform: lowercase;
          line-height: 1;
          text-decoration: none;
          transition: opacity 0.15s ease, transform 0.15s ease;
          display: inline-block;
        }
        .timetable-brand-title:hover {
          opacity: 0.85;
        }
        .timetable-brand-title:active {
          transform: scale(0.96);
        }
        .brand-dot {
          color: #2563eb;
        }
      `}</style>
    </main>
  );
}

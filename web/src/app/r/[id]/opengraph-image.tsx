import { ImageResponse } from "next/og";
import { createClient } from "@supabase/supabase-js";

export const runtime = "nodejs";
export const alt = "ClassMate Resource Preview";
export const size = {
  width: 1200,
  height: 630,
};
export const contentType = "image/png";

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

function formatBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return "N/A";
  const k = 1024;
  const sizes = ["Bytes", "KB", "MB", "GB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

export default async function Image({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const res = await fetchPublicResource(id);

  const title = res?.title || "Academic Resource";
  const isDrive = Boolean(
    res?.is_link &&
      res?.link_url &&
      /drive\.google\.com|docs\.google\.com|drive\.link/i.test(res.link_url)
  );
  const subtitle = isDrive
    ? "Google Drive Resource"
    : res?.is_link
    ? "Web Resource Link"
    : "Official Course Curriculum";

  const courseTitle = res?.course_title || "Academic Course Resource";
  const courseCode = res?.course_code || null;
  const sizeFormatted = res?.is_link ? "Google Drive" : formatBytes(res?.size_bytes);
  const batchText = res?.batch_number ? `CSE ${res.batch_number}` : "CSE 22";
  const dateFormatted = res?.created_at
    ? new Date(res.created_at).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" })
    : "Recent";

  return new ImageResponse(
    (
      <div
        style={{
          height: "100%",
          width: "100%",
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          backgroundColor: "#f8fafc",
          padding: "30px 50px",
          fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
        }}
      >
        {/* Main Floating Card - Matches web page design exactly */}
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            width: "1060px",
            backgroundColor: "#ffffff",
            borderRadius: "32px",
            border: "2px solid #e2e8f0",
            boxShadow: "0 25px 50px -12px rgba(15, 23, 42, 0.12)",
            padding: "32px 42px 26px 42px",
          }}
        >
          {/* 1: Course Banner */}
          <div
            style={{
              display: "flex",
              alignItems: "center",
              backgroundColor: "rgba(37, 99, 235, 0.08)",
              border: "1px solid rgba(37, 99, 235, 0.18)",
              borderRadius: "14px",
              padding: "10px 18px",
              marginBottom: "22px",
              gap: "12px",
            }}
          >
            <svg
              width="22"
              height="22"
              viewBox="0 0 24 24"
              fill="none"
              stroke="#2563eb"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" />
            </svg>
            <span
              style={{
                fontSize: "17px",
                fontWeight: 700,
                color: "#1d4ed8",
                flex: 1,
              }}
            >
              {courseTitle}
            </span>
            {courseCode && (
              <span
                style={{
                  backgroundColor: "#2563eb",
                  color: "#ffffff",
                  fontSize: "13px",
                  fontWeight: 800,
                  padding: "4px 10px",
                  borderRadius: "8px",
                  letterSpacing: "0.5px",
                }}
              >
                {courseCode}
              </span>
            )}
          </div>

          {/* 2: Hero Section - PDF/Drive Icon + Title & Subtitle side by side */}
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: "24px",
              marginBottom: "24px",
            }}
          >
            {/* Icon */}
            <div
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                flexShrink: 0,
              }}
            >
              {isDrive ? (
                <svg width="72" height="64" viewBox="0 0 87.3 78" fill="none">
                  <path d="m6.6 66.85 3.85 6.65c.8 1.4 1.95 2.5 3.3 3.3l13.75-23.8H0c0 1.55.4 3.1 1.2 4.5z" fill="#0066DA" />
                  <path d="M43.65 25 29.9 1.2c-1.35.8-2.5 1.9-3.3 3.3l-25.4 44C.4 50 0 51.55 0 53.1h27.5z" fill="#00AC47" />
                  <path d="M73.55 76.8c1.35-.8 2.5-1.9 3.3-3.3l1.6-2.75 7.65-13.25c.8-1.4 1.2-2.95 1.2-4.5H59.8l5.85 10.15z" fill="#EA4335" />
                  <path d="M43.65 25 57.4 1.2C56.05.4 54.5 0 52.95 0H34.35c-1.55 0-3.1.4-4.45 1.2z" fill="#00832D" />
                  <path d="M59.8 53.1H27.5L13.75 76.9c1.35.75 2.9 1.1 4.45 1.1h50.9c1.55 0 3.1-.35 4.45-1.1z" fill="#2684FC" />
                  <path d="M73.4 26.5 60.7 4.5c-.8-1.4-1.95-2.5-3.3-3.3L43.65 25l16.15 28.1h27.5c0-1.55-.4-3.1-1.2-4.5z" fill="#FFBA00" />
                </svg>
              ) : (
                /* Premium 3D Folded PDF Icon */
                <svg width="72" height="78" viewBox="0 0 56 60" fill="none">
                  <defs>
                    <linearGradient id="pdfGrad" x1="0" y1="0" x2="56" y2="60" gradientUnits="userSpaceOnUse">
                      <stop stopColor="#FF4D4D" />
                      <stop offset="1" stopColor="#DC2626" />
                    </linearGradient>
                    <linearGradient id="foldGrad" x1="42" y1="0" x2="56" y2="14" gradientUnits="userSpaceOnUse">
                      <stop stopColor="#B91C1C" />
                      <stop offset="1" stopColor="#991B1B" />
                    </linearGradient>
                  </defs>
                  <path d="M8 0C3.58 0 0 3.58 0 8v44c0 4.42 3.58 8 8 8h40c4.42 0 8-3.58 8-8V14L42 0H8z" fill="url(#pdfGrad)" />
                  <path d="M42 0v10c0 2.21 1.79 4 4 4h10L42 0z" fill="url(#foldGrad)" opacity="0.85" />
                  <rect x="12" y="18" width="22" height="3" rx="1.5" fill="#FFFFFF" fillOpacity="0.45" />
                  <rect x="12" y="24" width="32" height="3" rx="1.5" fill="#FFFFFF" fillOpacity="0.45" />
                  <rect x="10" y="34" width="36" height="18" rx="5" fill="#FFFFFF" />
                  <text x="28" y="47.5" fontFamily="system-ui, sans-serif" fontSize="11" fontWeight="900" fill="#DC2626" textAnchor="middle" letterSpacing="0.5">PDF</text>
                </svg>
              )}
            </div>

            {/* Title & Subtitle */}
            <div
              style={{
                display: "flex",
                flexDirection: "column",
                flex: 1,
                minWidth: 0,
              }}
            >
              <div
                style={{
                  fontSize: "34px",
                  fontWeight: 800,
                  color: "#0f172a",
                  lineHeight: 1.25,
                  letterSpacing: "-0.5px",
                  whiteSpace: "nowrap",
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                }}
              >
                {title}
              </div>
              <div
                style={{
                  fontSize: "17px",
                  fontWeight: 500,
                  color: "#64748b",
                  marginTop: "6px",
                }}
              >
                {subtitle}
              </div>
            </div>
          </div>

          {/* 3: Bento Metadata Row */}
          <div
            style={{
              display: "flex",
              backgroundColor: "#f8fafc",
              border: "1px solid #e2e8f0",
              borderRadius: "18px",
              padding: "10px 14px",
              gap: "14px",
              marginBottom: "24px",
            }}
          >
            {/* Size */}
            <div
              style={{
                display: "flex",
                flexDirection: "column",
                alignItems: "center",
                flex: 1,
                backgroundColor: "#ffffff",
                borderRadius: "12px",
                padding: "10px 16px",
                border: "1px solid #e2e8f0",
                boxShadow: "0 1px 3px rgba(0,0,0,0.03)",
              }}
            >
              <span style={{ fontSize: "12px", fontWeight: 800, color: "#94a3b8", letterSpacing: "0.8px" }}>
                SIZE
              </span>
              <span style={{ fontSize: "18px", fontWeight: 700, color: "#0f172a", marginTop: "2px" }}>
                {sizeFormatted}
              </span>
            </div>

            {/* Batch */}
            <div
              style={{
                display: "flex",
                flexDirection: "column",
                alignItems: "center",
                flex: 1,
                backgroundColor: "#ffffff",
                borderRadius: "12px",
                padding: "10px 16px",
                border: "1px solid #e2e8f0",
                boxShadow: "0 1px 3px rgba(0,0,0,0.03)",
              }}
            >
              <span style={{ fontSize: "12px", fontWeight: 800, color: "#94a3b8", letterSpacing: "0.8px" }}>
                BATCH
              </span>
              <span style={{ fontSize: "18px", fontWeight: 700, color: "#0f172a", marginTop: "2px" }}>
                {batchText}
              </span>
            </div>

            {/* Shared Date */}
            <div
              style={{
                display: "flex",
                flexDirection: "column",
                alignItems: "center",
                flex: 1.2,
                backgroundColor: "#ffffff",
                borderRadius: "12px",
                padding: "10px 16px",
                border: "1px solid #e2e8f0",
                boxShadow: "0 1px 3px rgba(0,0,0,0.03)",
              }}
            >
              <span style={{ fontSize: "12px", fontWeight: 800, color: "#94a3b8", letterSpacing: "0.8px" }}>
                SHARED
              </span>
              <span style={{ fontSize: "18px", fontWeight: 700, color: "#0f172a", marginTop: "2px" }}>
                {dateFormatted}
              </span>
            </div>
          </div>

          {/* 4: Dual Action Buttons */}
          <div
            style={{
              display: "flex",
              gap: "16px",
              marginBottom: "22px",
            }}
          >
            {/* Open Button */}
            <div
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                gap: "10px",
                flex: 1,
                height: "52px",
                backgroundColor: "#2563eb",
                borderRadius: "14px",
                color: "#ffffff",
                fontSize: "18px",
                fontWeight: 700,
                boxShadow: "0 8px 18px rgba(37, 99, 235, 0.28)",
              }}
            >
              <svg
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="#ffffff"
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
                <polyline points="15 3 21 3 21 9" />
                <line x1="10" y1="14" x2="21" y2="3" />
              </svg>
              <span>Open</span>
            </div>

            {/* Download Button */}
            <div
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                gap: "10px",
                flex: 1,
                height: "52px",
                backgroundColor: "#0f172a",
                borderRadius: "14px",
                color: "#ffffff",
                fontSize: "18px",
                fontWeight: 700,
                border: "1px solid #0f172a",
                boxShadow: "0 6px 14px rgba(15, 23, 42, 0.15)",
              }}
            >
              <svg
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="#ffffff"
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="7 10 12 15 17 10" />
                <line x1="12" y1="15" x2="12" y2="3" />
              </svg>
              <span>Download</span>
            </div>
          </div>

          {/* 5: Brand Footer */}
          <div
            style={{
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              borderTop: "1px solid #f1f5f9",
              paddingTop: "14px",
              width: "100%",
            }}
          >
            <span
              style={{
                fontSize: "13px",
                color: "#94a3b8",
                fontWeight: 500,
                marginBottom: "2px",
                letterSpacing: "0.5px",
              }}
            >
              from
            </span>
            <div
              style={{
                display: "flex",
                alignItems: "baseline",
                fontSize: "26px",
                fontWeight: 800,
                color: "#0f172a",
                letterSpacing: "-0.5px",
              }}
            >
              classmate<span style={{ color: "#2563eb" }}>.</span>
            </div>
          </div>
        </div>
      </div>
    ),
    {
      ...size,
    }
  );
}

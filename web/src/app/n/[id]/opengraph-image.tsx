import { ImageResponse } from "next/og";
import { createClient } from "@supabase/supabase-js";

export const runtime = "nodejs";
export const alt = "ClassMate Notice Preview";
export const size = {
  width: 1200,
  height: 630,
};
export const contentType = "image/png";

interface PublicNotice {
  id: string;
  title: string;
  body: string;
  published_at: string;
  department_name?: string;
  department_code?: string;
  batch_number?: number;
  author_name?: string;
  author_is_cr?: boolean;
  course_title?: string;
  course_code?: string;
  is_cancelled?: boolean;
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

export default async function Image({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const notice = await fetchPublicNotice(id);

  const title = notice?.title || "Notice";
  const bodySnippet = notice?.body
    ? notice.body.slice(0, 140).replace(/\s+/g, " ").trim()
    : "Academic announcement on ClassMate";
  const deptCode = notice?.department_code ? notice.department_code.toUpperCase() : "MBSTU";
  const batchNum = notice?.batch_number ? `Batch ${notice.batch_number}` : "";
  const author = notice?.author_name || "ClassMate Member";
  const isCancelled = Boolean(notice?.is_cancelled);
  const course = notice?.course_title
    ? `${notice.course_title}${notice.course_code ? ` (${notice.course_code})` : ""}`
    : null;

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
          padding: "36px 48px",
          fontFamily: "system-ui, -apple-system, sans-serif",
        }}
      >
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            width: "1080px",
            height: "540px",
            backgroundColor: "#ffffff",
            borderRadius: "28px",
            border: "2px solid #e2e8f0",
            boxShadow: "0 25px 50px -12px rgba(15, 23, 42, 0.12)",
            padding: "36px 44px",
            justifyContent: "space-between",
          }}
        >
          {/* Top Tag Row */}
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
              {isCancelled ? (
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    backgroundColor: "#fef2f2",
                    border: "1px solid #fecaca",
                    color: "#dc2626",
                    padding: "6px 14px",
                    borderRadius: "10px",
                    fontSize: "16px",
                    fontWeight: 700,
                  }}
                >
                  CLASS CANCELLED
                </div>
              ) : course ? (
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    backgroundColor: "rgba(37, 99, 235, 0.08)",
                    border: "1px solid rgba(37, 99, 235, 0.2)",
                    color: "#2563eb",
                    padding: "6px 14px",
                    borderRadius: "10px",
                    fontSize: "16px",
                    fontWeight: 700,
                  }}
                >
                  {course}
                </div>
              ) : (
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    backgroundColor: "rgba(37, 99, 235, 0.08)",
                    border: "1px solid rgba(37, 99, 235, 0.2)",
                    color: "#2563eb",
                    padding: "6px 14px",
                    borderRadius: "10px",
                    fontSize: "16px",
                    fontWeight: 700,
                  }}
                >
                  OFFICIAL NOTICE
                </div>
              )}
            </div>

            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: "8px",
                fontSize: "16px",
                fontWeight: 600,
                color: "#64748b",
              }}
            >
              <span>{deptCode}</span>
              {batchNum && <span>· {batchNum}</span>}
            </div>
          </div>

          {/* Middle: Title & Body snippet */}
          <div style={{ display: "flex", flexDirection: "column", gap: "14px", margin: "16px 0" }}>
            <div
              style={{
                fontSize: "36px",
                fontWeight: 800,
                color: "#0f172a",
                lineHeight: 1.25,
                maxHeight: "90px",
                overflow: "hidden",
              }}
            >
              {title}
            </div>
            <div
              style={{
                fontSize: "20px",
                fontWeight: 400,
                color: "#475569",
                lineHeight: 1.5,
                maxHeight: "65px",
                overflow: "hidden",
              }}
            >
              {bodySnippet}
            </div>
          </div>

          {/* Bottom Bar: Author info & ClassMate Brand */}
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              borderTop: "1px solid #f1f5f9",
              paddingTop: "20px",
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: "12px" }}>
              <div
                style={{
                  width: "42px",
                  height: "42px",
                  borderRadius: "50%",
                  backgroundColor: "#2563eb",
                  color: "#ffffff",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  fontWeight: 800,
                  fontSize: "18px",
                }}
              >
                {author.charAt(0).toUpperCase()}
              </div>
              <div style={{ display: "flex", flexDirection: "column" }}>
                <span style={{ fontSize: "17px", fontWeight: 700, color: "#0f172a" }}>
                  {author}
                </span>
                <span style={{ fontSize: "14px", color: "#64748b" }}>
                  Posted on ClassMate
                </span>
              </div>
            </div>

            <div style={{ display: "flex", alignItems: "center", gap: "6px" }}>
              <span style={{ fontSize: "16px", color: "#94a3b8" }}>view on</span>
              <span style={{ fontSize: "22px", fontWeight: 800, color: "#0f172a", letterSpacing: "-0.5px" }}>
                classmate<span style={{ color: "#2563eb" }}>.</span>
              </span>
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

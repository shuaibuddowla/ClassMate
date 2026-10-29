import { ImageResponse } from "next/og";

export const runtime = "nodejs";
export const alt = "ClassMate — Smart University & Attendance Platform";
export const size = {
  width: 1200,
  height: 630,
};
export const contentType = "image/png";

export default function Image() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
          padding: "64px",
          background: "linear-gradient(135deg, #090d16 0%, #0f172a 50%, #1e1b4b 100%)",
          fontFamily: "system-ui, -apple-system, sans-serif",
          color: "#ffffff",
        }}
      >
        {/* Top badge */}
        <div style={{ display: "flex", alignItems: "center", gap: "16px" }}>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              padding: "8px 20px",
              borderRadius: "999px",
              background: "rgba(37, 99, 235, 0.25)",
              border: "1px solid rgba(59, 130, 246, 0.4)",
              color: "#93c5fd",
              fontSize: "20px",
              fontWeight: 600,
              letterSpacing: "0.05em",
            }}
          >
            🎓 MBSTU SMART CAMPUS PLATFORM
          </div>
        </div>

        {/* Hero title & description */}
        <div style={{ display: "flex", flexDirection: "column", gap: "20px" }}>
          <div
            style={{
              fontSize: "68px",
              fontWeight: 900,
              letterSpacing: "-0.03em",
              lineHeight: 1.1,
              background: "linear-gradient(to right, #ffffff, #93c5fd, #60a5fa)",
              backgroundClip: "text",
              color: "transparent",
            }}
          >
            ClassMate
          </div>
          <div
            style={{
              fontSize: "30px",
              color: "#94a3b8",
              lineHeight: 1.35,
              maxWidth: "960px",
            }}
          >
            Real-time Class Routines · RFID Attendance Tracking · Bus Schedules · Academic Notices &amp; Past Papers
          </div>
        </div>

        {/* Bottom bar */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            paddingTop: "32px",
            borderTop: "1px solid rgba(255, 255, 255, 0.1)",
          }}
        >
          <div style={{ display: "flex", gap: "28px", color: "#cbd5e1", fontSize: "22px" }}>
            <span>📱 Native Android APK</span>
            <span>⚡ Progressive Web App</span>
            <span>🩸 Blood Donor Network</span>
          </div>
          <div
            style={{
              fontSize: "22px",
              color: "#60a5fa",
              fontWeight: 700,
            }}
          >
            classmatebd.vercel.app
          </div>
        </div>
      </div>
    ),
    {
      ...size,
    }
  );
}

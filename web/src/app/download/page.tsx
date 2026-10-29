import type { Metadata } from "next";
import Link from "next/link";
import PublicPage from "@/components/public-page";

export const metadata: Metadata = {
  title: "Download ClassMate App for Android & Web | Official APK",
  description:
    "Download the official ClassMate Android APK (direct download) or install as a Progressive Web App. Real-time class routines, RFID attendance tracking, notices, and bus schedules for MBSTU.",
  keywords: [
    "ClassMate download",
    "ClassMate APK",
    "ClassMate Android App",
    "MBSTU ClassMate APK",
    "Download ClassMate",
    "ClassMate app download",
    "MBSTU routine app",
  ],
  alternates: {
    canonical: "https://classmatebd.vercel.app/download",
  },
  openGraph: {
    title: "Download ClassMate - Official Android APK & Web App",
    description:
      "Get ClassMate for Android. Automated RFID attendance, live class timetables, bus schedules, and academic notices for MBSTU.",
    url: "https://classmatebd.vercel.app/download",
    siteName: "ClassMate",
    images: [{ url: "/logo.png", width: 512, height: 512, alt: "ClassMate App Icon" }],
  },
  twitter: {
    card: "summary",
    title: "Download ClassMate - Official Android APK & Web App",
    description:
      "Get ClassMate for Android. Automated RFID attendance, live class timetables, bus schedules, and academic notices for MBSTU.",
    images: ["/logo.png"],
  },
};

export default function DownloadPage() {
  return (
    <PublicPage
      title="Download ClassMate for Android"
      lead="Install the native Android app for instant attendance updates, live routine alerts, bus tracking, and offline notices."
    >
      <section style={{ textAlign: "center", margin: "24px 0 36px" }}>
        <a
          href="/download/android"
          className="public-cta"
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: "10px",
            fontSize: "1.15rem",
            padding: "16px 32px",
            borderRadius: "14px",
            textDecoration: "none",
            boxShadow: "0 8px 24px rgba(37, 99, 235, 0.25)",
          }}
        >
          <span>📥</span>
          <strong>Download Latest Android APK</strong>
        </a>
        <p style={{ marginTop: "12px", color: "var(--text-muted, #64748b)", fontSize: "0.9rem" }}>
          Direct download · Safe, virus-free, signed by developer · Version 1.1.26+
        </p>
      </section>

      <section>
        <h2>How to Install the APK on Your Android Phone</h2>
        <ol style={{ paddingLeft: "20px", lineHeight: "1.8" }}>
          <li>
            Tap the <strong>Download Latest Android APK</strong> button above.
          </li>
          <li>
            When downloading completes, tap the notification in your status bar or open your device’s{" "}
            <strong>Downloads</strong> folder.
          </li>
          <li>
            If Android shows a prompt saying <em>&quot;File might be harmful&quot;</em> or asks for permission to{" "}
            <em>&quot;Install unknown apps&quot;</em>, select <strong>Download anyway</strong> and grant permission for your
            browser.
          </li>
          <li>
            Tap <strong>Install</strong>, then open ClassMate and sign in with your official university Google account.
          </li>
        </ol>
      </section>

      <div className="public-features">
        <section>
          <h2>⚡ Real-Time Attendance</h2>
          <p>
            Tap your university RFID card on attendance readers or check your daily attendance logs and percentages instantly.
          </p>
        </section>
        <section>
          <h2>📅 Timetable & Routine Alerts</h2>
          <p>
            Stay on schedule with day-by-day class timetables, room numbers, and real-time class cancellation alerts from CRs and teachers.
          </p>
        </section>
        <section>
          <h2>🚌 University Bus Schedules</h2>
          <p>
            Accurate departure times and bus stop routes for all university transport routes right in your pocket.
          </p>
        </section>
        <section>
          <h2>🩸 Emergency Blood Network</h2>
          <p>
            Direct campus blood donor directory matching compatible blood groups within seconds during emergencies.
          </p>
        </section>
      </div>

      <section style={{ marginTop: "32px" }}>
        <h2>Using iOS or a Laptop? Use the Web App</h2>
        <p>
          You don’t have to install the APK to use ClassMate. The full web application works directly on all modern browsers (Chrome, Safari, Edge) on phones, tablets, and computers.
        </p>
        <p>
          <Link href="/" className="public-cta" style={{ display: "inline-block", marginTop: "8px" }}>
            Open Web App Now →
          </Link>
        </p>
      </section>
    </PublicPage>
  );
}

"use client";

import dynamic from "next/dynamic";

const Transit = dynamic(() => import("../../components/transit").then((m) => m.Transit), {
  ssr: false,
  loading: () => (
    <div
      style={{
        height: "100dvh",
        width: "100%",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        background: "radial-gradient(ellipse at 50% 45%, #0f172a 0%, #090d16 100%)",
        color: "#f8fafc",
        padding: "24px 20px",
        textAlign: "center",
        boxSizing: "border-box"
      }}
    >
      <div
        style={{
          width: 52,
          height: 52,
          borderRadius: "50%",
          background: "linear-gradient(135deg, #0284c7 0%, #0369a1 100%)",
          border: "2px solid #ffffff",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          fontSize: 24,
          boxShadow: "0 0 24px rgba(2, 132, 199, 0.75)",
          marginBottom: 16
        }}
      >
        🚌
      </div>
      <div style={{ fontSize: 16, fontWeight: 800, marginBottom: 6, color: "#f8fafc" }}>
        মাভাবিপ্রবি বাস ট্র্যাকার লোড হচ্ছে…
      </div>
      <div style={{ fontSize: 12, color: "#94a3b8", marginBottom: 16 }}>
        স্যাটেলাইট স্তর ও করিডোর স্টপেজ প্রস্তুত হচ্ছে
      </div>
      <div style={{ display: "flex", gap: 6, flexWrap: "wrap", justifyContent: "center" }}>
        <span
          style={{
            background: "rgba(30, 41, 59, 0.7)",
            border: "1px solid rgba(255, 255, 255, 0.08)",
            color: "#cbd5e1",
            fontSize: 10.5,
            fontWeight: 700,
            padding: "4px 10px",
            borderRadius: 20
          }}
        >
          ৮০টি রুট পয়েন্ট
        </span>
        <span
          style={{
            background: "rgba(30, 41, 59, 0.7)",
            border: "1px solid rgba(255, 255, 255, 0.08)",
            color: "#cbd5e1",
            fontSize: 10.5,
            fontWeight: 700,
            padding: "4px 10px",
            borderRadius: 20
          }}
        >
          ২৮টি বাস স্টপ
        </span>
      </div>
      {/* Crawlable fallback content for search engines & accessibility */}
      <div style={{ marginTop: 24, maxWidth: 480, fontSize: 13, lineHeight: 1.6, color: "#94a3b8" }}>
        <p style={{ margin: "0 0 8px 0" }}>
          মওলানা ভাসানী বিজ্ঞান ও প্রযুক্তি বিশ্ববিদ্যালয় (MBSTU) ক্যাম্পাস ও টাঙ্গাইল শহরের মধ্যে চলাচলকারী বাসসমূহের লাইভ জিপিএস লোকেশন ও শিডিউল।
        </p>
        <p style={{ margin: 0, fontSize: 11.5, color: "#64748b" }}>
          প্রধান স্টপেজ: ক্যাম্পাস গেট · সন্তোষ · নতুন বাস স্ট্যান্ড · পুরাতন বাস স্ট্যান্ড · জেলা প্রশাসকের কার্যালয় · নিরালা মোড় · সাবালিয়া · করটিয়া।
        </p>
      </div>
    </div>
  )
});

export default function TransitPreviewPage() {
  return (
    <main style={{ height: "100dvh", width: "100%" }}>
      <Transit />
    </main>
  );
}

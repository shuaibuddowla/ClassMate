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

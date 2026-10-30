"use client";

import dynamic from "next/dynamic";

const Transit = dynamic(() => import("../../components/transit").then((m) => m.Transit), {
  ssr: false,
  loading: () => (
    <div style={{ height: "100%", width: "100%", display: "flex", alignItems: "center", justifyContent: "center", background: "#0b0f19", color: "#38bdf8" }}>
      Loading MBSTU Bus Tracker...
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

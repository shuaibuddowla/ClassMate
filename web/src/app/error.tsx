"use client";

import { useEffect } from "react";
import Link from "next/link";

export default function ErrorBoundary({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("Next.js App Router Error:", error);
  }, [error]);

  return (
    <main
      style={{
        minHeight: "100vh",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        padding: "2rem",
        textAlign: "center",
        fontFamily: "system-ui, -apple-system, sans-serif",
        background: "var(--bg, #0a0a0c)",
        color: "var(--fg, #f4f4f6)",
      }}
    >
      <h1 style={{ fontSize: "2.5rem", margin: "0 0 1rem", fontWeight: 700 }}>
        Something went wrong
      </h1>
      <p style={{ maxWidth: "460px", margin: "0 0 2rem", opacity: 0.7, lineHeight: 1.5 }}>
        An unexpected error occurred while loading this page. Please try again or return to the main dashboard.
      </p>
      <div style={{ display: "flex", gap: "1rem" }}>
        <button
          onClick={() => reset()}
          style={{
            padding: "0.75rem 1.5rem",
            background: "var(--primary, #6366f1)",
            color: "#ffffff",
            border: "none",
            borderRadius: "8px",
            fontWeight: 600,
            cursor: "pointer",
          }}
        >
          Try Again
        </button>
        <Link
          href="/"
          style={{
            display: "inline-flex",
            alignItems: "center",
            padding: "0.75rem 1.5rem",
            background: "rgba(255, 255, 255, 0.1)",
            color: "inherit",
            borderRadius: "8px",
            textDecoration: "none",
            fontWeight: 600,
          }}
        >
          Home
        </Link>
      </div>
    </main>
  );
}

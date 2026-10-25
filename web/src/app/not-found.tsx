import Link from "next/link";

export default function NotFound() {
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
      <h1 style={{ fontSize: "3rem", margin: "0 0 1rem", fontWeight: 700 }}>404</h1>
      <h2 style={{ fontSize: "1.5rem", margin: "0 0 1rem", opacity: 0.9 }}>
        Page Not Found
      </h2>
      <p style={{ maxWidth: "420px", margin: "0 0 2rem", opacity: 0.7, lineHeight: 1.5 }}>
        The page or academic resource you are looking for does not exist or may have been moved.
      </p>
      <Link
        href="/"
        style={{
          display: "inline-flex",
          alignItems: "center",
          gap: "0.5rem",
          padding: "0.75rem 1.5rem",
          background: "var(--primary, #6366f1)",
          color: "#ffffff",
          borderRadius: "8px",
          textDecoration: "none",
          fontWeight: 600,
        }}
      >
        Return to Home
      </Link>
    </main>
  );
}

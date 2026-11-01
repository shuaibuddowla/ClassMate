"use client";
import { useEffect, useState, useRef } from "react";
import { supabase } from "@/lib/api";

export default function Callback() {
  const [error, setError] = useState("");
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;

    async function handleAuth() {
      try {
        const client = supabase();

        // 1. Check if we already have an active session (e.g. concurrent exchange or existing valid session)
        const { data: sessionData } = await client.auth.getSession();
        if (sessionData?.session) {
          history.replaceState({}, "", location.pathname);
          location.replace("/?signedin=1");
          return;
        }

        const url = new URL(location.href);
        const code = url.searchParams.get("code");
        const flowId = url.searchParams.get("sb_flow_id");
        const providerError = url.searchParams.get("error_description") || url.searchParams.get("error");

        const signInHelp = sessionStorage.getItem("classmate:sign-in-mode") === "teacher"
          ? "Teacher sign-in could not finish. Ask your administrator to approve this Google email and assign a course, then try again."
          : "Google sign-in could not finish. Try your university account or an admin-approved teacher Gmail.";

        if (!code) {
          history.replaceState({}, "", location.pathname);
          setError(providerError ? `${signInHelp} (${providerError})` : "Sign-in was cancelled.");
          return;
        }

        // 2. Exchange code for session while retaining full URL query context for PKCE
        const { error: exchangeError } = await client.auth.exchangeCodeForSession(
          code,
          flowId ? { flowId } : undefined
        );

        if (exchangeError) {
          console.error("Exchange code for session failed:", exchangeError);
          // Check if session was actually established despite error return
          const { data: fallbackSession } = await client.auth.getSession();
          if (fallbackSession?.session) {
            history.replaceState({}, "", location.pathname);
            location.replace("/?signedin=1");
            return;
          }
          history.replaceState({}, "", location.pathname);
          setError(exchangeError.message || "Could not finish sign-in. Please try again.");
          return;
        }

        // Clean up URL parameters after successful exchange
        history.replaceState({}, "", location.pathname);
        location.replace("/?signedin=1");
      } catch (err: any) {
        console.error("Auth callback exception:", err);
        history.replaceState({}, "", location.pathname);
        setError(err?.message || "Could not finish sign-in. Please try again.");
      }
    }

    handleAuth();
  }, []);

  return (
    <main className="boot">
      <img src="/logo.png" alt="ClassMate" width="64" />
      <h1>{error ? "Sign-in needs another try" : "Welcome to ClassMate"}</h1>
      <p>{error || "Finishing your Google sign-in…"}</p>
      {error && (
        <a
          href="/"
          onClick={() => {
            try {
              sessionStorage.removeItem("classmate:sign-in-mode");
            } catch (_) {}
          }}
        >
          Back to sign in
        </a>
      )}
    </main>
  );
}

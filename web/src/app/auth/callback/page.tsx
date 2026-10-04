"use client";
import { useEffect, useState, useRef } from "react";
import { supabase } from "@/lib/api";
export default function Callback() {
  const [error, setError] = useState("");
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const url = new URL(location.href);
    const code = url.searchParams.get("code");
    // Provider errors can include authorization codes. Keep these out of
    // rendered text and remove callback query parameters from browser history.
    history.replaceState({}, "", location.pathname);
    if (!code) {
      setError(
        url.searchParams.has("error")
          ? "Google sign-in could not finish. Please start again with your university account."
          : "Sign-in was cancelled.",
      );
      return;
    }
    supabase()
      .auth.exchangeCodeForSession(code)
      .then(({ error }) => {
        if (error) setError("Could not finish sign-in. Please try again.");
        else location.replace("/?signedin=1");
      })
      .catch(() => setError("Could not finish sign-in. Please try again."));
  }, []);
  return (
    <main className="boot">
      <img src="/logo.png" alt="ClassMate" width="64" />
      <h1>{error ? "Sign-in needs another try" : "Welcome to ClassMate"}</h1>
      <p>{error || "Finishing your university sign-in…"}</p>
      {error && <a href="/">Back to sign in</a>}
    </main>
  );
}

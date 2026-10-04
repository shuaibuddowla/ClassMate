"use client";
import { useEffect, useState } from "react";

/** Offer a refresh after activation, without discarding a user's unfinished form. */
export function AppUpdate() {
  const [available, setAvailable] = useState(false);
  useEffect(() => {
    if (!("serviceWorker" in navigator)) return;
    const workers = navigator.serviceWorker;
    let hadController = !!workers.controller;
    let registration: ServiceWorkerRegistration | undefined;
    let live = true;
    const changed = () => {
      if (hadController) setAvailable(true);
      hadController = true;
    };
    const check = () => {
      if (document.visibilityState === "visible" && navigator.onLine)
        void registration?.update().catch(() => {});
    };
    workers.addEventListener("controllerchange", changed);
    document.addEventListener("visibilitychange", check);
    window.addEventListener("online", check);
    void workers.register("/sw.js", { updateViaCache: "none" }).then((r) => {
      if (!live) return;
      registration = r;
      check();
    }).catch(() => {});
    return () => {
      live = false;
      workers.removeEventListener("controllerchange", changed);
      document.removeEventListener("visibilitychange", check);
      window.removeEventListener("online", check);
    };
  }, []);
  if (!available) return null;
  return (
    <div className="push-feedback" role="status">
      <span>A new version of ClassMate is ready.</span>
      <button className="text-button" onClick={() => location.reload()}>
        Refresh app
      </button>
    </div>
  );
}

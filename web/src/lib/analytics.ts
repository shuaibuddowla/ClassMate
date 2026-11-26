import { initializeApp, getApps, getApp, FirebaseApp } from "firebase/app";
import type { Analytics } from "firebase/analytics";

let analyticsInstance: Analytics | null = null;
let initPromise: Promise<Analytics | null> | null = null;

/**
 * Initializes Firebase Analytics safely in browser environment.
 * Reuses existing Firebase app instance initialized by FCM push notifications or creates a new one.
 */
export async function getAnalyticsInstance(): Promise<Analytics | null> {
  if (typeof window === "undefined") return null;
  if (analyticsInstance) return analyticsInstance;
  if (initPromise) return initPromise;

  initPromise = (async () => {
    try {
      const configStr = process.env.NEXT_PUBLIC_FIREBASE_CONFIG;
      if (!configStr) {
        if (process.env.NODE_ENV === "development") {
          console.debug("[Analytics] NEXT_PUBLIC_FIREBASE_CONFIG not configured.");
        }
        return null;
      }

      const { isSupported, getAnalytics } = await import("firebase/analytics");
      const supported = await isSupported().catch(() => false);
      if (!supported) {
        if (process.env.NODE_ENV === "development") {
          console.debug("[Analytics] Firebase Analytics is not supported in this environment (e.g. cookies disabled or ad-blocker).");
        }
        return null;
      }

      const firebaseConfig = JSON.parse(configStr);
      const app: FirebaseApp = getApps().length > 0 ? getApps()[0] : initializeApp(firebaseConfig);

      analyticsInstance = getAnalytics(app);
      return analyticsInstance;
    } catch (err) {
      if (process.env.NODE_ENV === "development") {
        console.warn("[Analytics] Initialization error:", err);
      }
      return null;
    } finally {
      initPromise = null;
    }
  })();

  return initPromise;
}

/**
 * Log a custom or recommended event to Firebase Analytics.
 */
export function trackEvent(eventName: string, params?: Record<string, any>): void {
  if (typeof window === "undefined") return;
  getAnalyticsInstance().then(async (analytics) => {
    if (!analytics) return;
    try {
      const { logEvent } = await import("firebase/analytics");
      // Sanitize params: ensure primitive types or strings
      const sanitized: Record<string, any> = {};
      if (params) {
        for (const [k, v] of Object.entries(params)) {
          if (v !== undefined && v !== null) {
            sanitized[k] = typeof v === "object" ? JSON.stringify(v) : v;
          }
        }
      }
      logEvent(analytics, eventName, sanitized);
    } catch (e) {
      // Non-blocking
    }
  }).catch(() => {});
}

/**
 * Associate events with a verified user ID.
 */
export function trackUser(userId: string | null): void {
  if (typeof window === "undefined") return;
  getAnalyticsInstance().then(async (analytics) => {
    if (!analytics) return;
    try {
      const { setUserId } = await import("firebase/analytics");
      setUserId(analytics, userId);
    } catch (e) {
      // Non-blocking
    }
  }).catch(() => {});
}

/**
 * Set user properties (e.g., student, teacher, batch, department).
 */
export function trackUserProperties(properties: Record<string, string | number | boolean>): void {
  if (typeof window === "undefined") return;
  getAnalyticsInstance().then(async (analytics) => {
    if (!analytics) return;
    try {
      const { setUserProperties } = await import("firebase/analytics");
      setUserProperties(analytics, properties);
    } catch (e) {
      // Non-blocking
    }
  }).catch(() => {});
}

/**
 * Log a page view event.
 */
export function trackPageView(pageTitle?: string, pagePath?: string): void {
  if (typeof window === "undefined") return;
  trackEvent("page_view", {
    page_title: pageTitle || document.title,
    page_location: window.location.href,
    page_path: pagePath || window.location.pathname,
  });
}

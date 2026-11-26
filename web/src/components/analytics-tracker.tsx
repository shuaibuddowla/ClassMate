"use client";

import { useEffect } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import { getAnalyticsInstance, trackPageView } from "@/lib/analytics";

/**
 * Automatically initializes Firebase Analytics on the client
 * and logs page views on route transitions.
 */
export function AnalyticsTracker() {
  const pathname = usePathname();
  const searchParams = useSearchParams();

  useEffect(() => {
    // Warm up Firebase Analytics instance
    getAnalyticsInstance();
  }, []);

  useEffect(() => {
    const url = pathname + (searchParams?.toString() ? `?${searchParams.toString()}` : "");
    trackPageView(document.title, url);
  }, [pathname, searchParams]);

  return null;
}

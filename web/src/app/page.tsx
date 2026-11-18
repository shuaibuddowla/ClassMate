import type { Metadata } from "next";
import { Suspense } from "react";
import App from "@/components/app";

export const metadata: Metadata = {
  alternates: {
    canonical: "https://classmatebd.vercel.app",
  },
};

export default function Page() {
  return (
    <Suspense fallback={<div className="boot">Opening ClassMate…</div>}>
      <App />
    </Suspense>
  );
}

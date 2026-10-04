import { Suspense } from "react";
import App from "@/components/app";
export default function Page() {
  return (
    <Suspense fallback={<div className="boot">Opening ClassMate…</div>}>
      <App />
    </Suspense>
  );
}

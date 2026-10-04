import { latestApk } from "@/lib/android-release";

export async function GET() {
  try {
    const response = await fetch(
      "https://api.github.com/repos/shuaibuddowla/ClassMate/releases/latest",
      {
        headers: {
          Accept: "application/vnd.github+json",
          "User-Agent": "ClassMate-Web",
        },
        next: { revalidate: 60 },
        signal: AbortSignal.timeout(10_000),
      },
    );
    if (!response.ok) throw new Error("Release lookup failed");
    return new Response(null, {
      status: 302,
      headers: {
        Location: latestApk(await response.json()),
        "Cache-Control": "no-store",
      },
    });
  } catch {
    return new Response(
      `<!doctype html><html lang="en"><meta name="viewport" content="width=device-width,initial-scale=1"><title>ClassMate Android download</title><body style="font-family:system-ui;background:#f4f7fc;color:#0f172a;padding:32px;max-width:440px;margin:auto"><h1>Try again in a moment</h1><p>We couldn't fetch the latest APK. Your download link still points to the latest release.</p><p><a href="/download/android">Retry download</a></p><a href="https://github.com/shuaibuddowla/ClassMate/releases/latest">View latest release</a></body></html>`,
      {
        status: 503,
        headers: {
          "Content-Type": "text/html; charset=utf-8",
          "Cache-Control": "no-store",
          "Retry-After": "60",
        },
      },
    );
  }
}

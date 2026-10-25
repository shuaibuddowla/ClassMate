import { latestApk, parseUpdateMetadata } from "@/lib/android-release";

export const dynamic = "force-dynamic";
export const revalidate = 0;

async function resolveLatestRelease(): Promise<{ apkUrl: string; versionName?: string }> {
  // Strategy 1: Fetch update.json directly from GitHub Releases latest CDN asset.
  // This bypasses GitHub API 60 req/hr rate limits and is always 100% up-to-date with releases.
  try {
    const updateRes = await fetch(
      "https://github.com/shuaibuddowla/ClassMate/releases/latest/download/update.json",
      {
        headers: { "User-Agent": "ClassMate-Web" },
        cache: "no-store",
        signal: AbortSignal.timeout(6000),
      },
    );
    if (updateRes.ok) {
      const data = await updateRes.json();
      return parseUpdateMetadata(data);
    }
  } catch {
    // Fall back to GitHub REST API if CDN asset is temporarily unreachable
  }

  // Strategy 2: GitHub Releases REST API lookup
  const apiRes = await fetch(
    "https://api.github.com/repos/shuaibuddowla/ClassMate/releases/latest",
    {
      headers: {
        Accept: "application/vnd.github+json",
        "User-Agent": "ClassMate-Web",
      },
      cache: "no-store",
      signal: AbortSignal.timeout(6000),
    },
  );
  if (!apiRes.ok) throw new Error("Release lookup failed");
  const releaseData = await apiRes.json();
  const apkUrl = latestApk(releaseData);
  const versionName =
    typeof releaseData.tag_name === "string"
      ? releaseData.tag_name.replace(/^v/, "")
      : undefined;

  return { apkUrl, versionName };
}

export async function GET(request: Request) {
  const url = new URL(request.url);
  const wantsJson =
    url.searchParams.get("format") === "json" ||
    request.headers.get("accept")?.includes("application/json");

  try {
    const { apkUrl, versionName } = await resolveLatestRelease();

    if (wantsJson) {
      return Response.json(
        { versionName, apkUrl },
        {
          headers: {
            "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
          },
        },
      );
    }

    return new Response(null, {
      status: 302,
      headers: {
        Location: apkUrl,
        "Cache-Control": "no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0",
        Pragma: "no-cache",
        Expires: "0",
      },
    });
  } catch {
    if (wantsJson) {
      return Response.json(
        { error: "Could not fetch latest release" },
        { status: 503, headers: { "Cache-Control": "no-store" } },
      );
    }

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

export function latestApk(release: unknown): string {
  const data = release as {
    draft?: boolean;
    prerelease?: boolean;
    assets?: { name?: string; browser_download_url?: string; state?: string }[];
  };
  if (!data || data.draft || data.prerelease)
    throw new Error("No stable Android release is available.");
  const asset = data.assets?.find(
    (a) => a.name?.toLowerCase().endsWith(".apk") && a.state === "uploaded",
  );
  const url = asset?.browser_download_url;
  if (
    !url?.startsWith(
      "https://github.com/shuaibuddowla/ClassMate/releases/download/",
    )
  )
    throw new Error("The latest release does not contain an Android APK.");
  return url;
}

export function parseUpdateMetadata(metadata: unknown): {
  apkUrl: string;
  versionName?: string;
} {
  const data = metadata as {
    apkUrl?: string;
    versionName?: string;
  };
  if (
    !data ||
    typeof data.apkUrl !== "string" ||
    !data.apkUrl.startsWith(
      "https://github.com/shuaibuddowla/ClassMate/releases/download/",
    ) ||
    !data.apkUrl.toLowerCase().endsWith(".apk")
  ) {
    throw new Error("Invalid or untrusted APK update metadata.");
  }
  return {
    apkUrl: data.apkUrl,
    versionName: typeof data.versionName === "string" ? data.versionName : undefined,
  };
}

import { test } from "node:test";
import assert from "node:assert/strict";
import { latestApk } from "../src/lib/android-release";

test("download follows the versioned APK of each new stable release", () => {
  for (const version of ["1.1.20", "1.1.21"]) {
    const url = `https://github.com/shuaibuddowla/ClassMate/releases/download/v${version}/classmate-${version}.apk`;
    assert.equal(
      latestApk({
        assets: [
          { name: "update.json", state: "uploaded" },
          {
            name: `classmate-${version}.apk`,
            state: "uploaded",
            browser_download_url: url,
          },
        ],
      }),
      url,
    );
  }
});
test("download rejects drafts, missing APKs and external redirects", () => {
  for (const release of [
    null,
    { draft: true },
    { prerelease: true },
    { assets: [] },
    {
      assets: [
        {
          name: "app.apk",
          state: "uploaded",
          browser_download_url: "https://evil.example/app.apk",
        },
      ],
    },
  ])
    assert.throws(() => latestApk(release));
});

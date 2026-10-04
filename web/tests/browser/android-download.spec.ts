import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";

test("sidebar offers a themed QR download prompt without navigating away", async ({
  page,
}) => {
  await openSavedAccount(page, 1440);
  await page.getByRole("button", { name: "Try Android app" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(
    dialog.getByText("ClassMate for Android", { exact: true }),
  ).toBeVisible();
  await expect(dialog.locator(".android-download-qr svg")).toBeVisible();
  for (const height of [844, 600, 480]) {
    await page.setViewportSize({ width: 1440, height });
    expect(await dialog.evaluate((el) => el.scrollHeight <= el.clientHeight)).toBeTruthy();
    const bounds = (await dialog.boundingBox())!;
    expect(bounds.width).toBeLessThanOrEqual(360);
    expect(bounds.y + bounds.height).toBeLessThanOrEqual(height);
  }
  await page.setViewportSize({ width: 1440, height: 844 });
  await expect(
    dialog.getByRole("button", { name: "Copy download link" }),
  ).toBeVisible();
  await page.screenshot({ path: "../build/web-android-download.png" });
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(page.locator(".schedule-row")).toBeVisible();
});

test("stable phone URL redirects straight to the current APK", async ({
  request,
}) => {
  const response = await request.get("/download/android", { maxRedirects: 0 });
  expect(response.status()).toBe(302);
  expect(response.headers().location).toMatch(
    /^https:\/\/github.com\/shuaibuddowla\/ClassMate\/releases\/download\/[^/]+\/[^/]+\.apk$/,
  );
  expect(response.headers()["cache-control"]).toBe("no-store");
});

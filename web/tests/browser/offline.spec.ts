import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";
test("offline reopening loads the complete app, timetable and paginated last-synced notices; logout clears private data", async ({
  page,
  context,
}) => {
  await openSavedAccount(page, 390);
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
    if (!navigator.serviceWorker.controller)
      await new Promise<void>((resolve) =>
        navigator.serviceWorker.addEventListener(
          "controllerchange",
          () => resolve(),
          { once: true },
        ),
      );
  });
  const cacheState = await page.evaluate(async () => {
    const names = (await caches.keys()).filter((n) =>
      n.startsWith("classmate-shell-"),
    );
    const keys = await (await caches.open(names[0])).keys();
    return keys.map((r) => new URL(r.url).pathname);
  });
  expect(cacheState).toContain("/");
  expect(
    cacheState.some((p) => p.startsWith("/_next/static/") && p.endsWith(".js")),
  ).toBeTruthy();
  expect(
    cacheState.some(
      (p) => p.startsWith("/_next/static/") && p.endsWith(".css"),
    ),
  ).toBeTruthy();
  await context.setOffline(true);
  await page.reload();
  await expect(page.locator(".schedule-row")).toContainText("Room 301");
  await expect(page.locator(".offline")).toContainText("Last sync");
  await page
    .locator(".bottom-nav")
    .getByRole("button", { name: "Notices", exact: true })
    .click();
  await expect(page.locator(".notice-card")).toHaveCount(10);
  await page.locator(".paging").scrollIntoViewIfNeeded();
  await expect(page.locator(".notice-card")).toHaveCount(20);
  await page
    .locator(".bottom-nav")
    .getByRole("button", { name: "Profile", exact: true })
    .click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("button", { name: /Continue with Google/ }),
  ).toBeVisible();
  expect(
    await page.evaluate(() =>
      Object.keys(localStorage).some(
        (k) =>
          k.startsWith("classmate:academic:") ||
          k.startsWith("classmate:identity:"),
      ),
    ),
  ).toBeFalsy();
});

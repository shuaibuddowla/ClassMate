import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";

test("mobile notices use Android header, real summary and compact cards; search remains functional", async ({ page }) => {
  await openSavedAccount(page, 384);
  await page.evaluate(() => {
    const key = "classmate:academic:v1:mobile-layout-test:layout-batch:notices";
    const saved = JSON.parse(localStorage.getItem(key)!);
    for (const p of saved.data) p.details.authors = p.entries.map((n: any) => ({ notice_id: n.id, author_name: "Actual author", avatar_url: null }));
    localStorage.setItem(key, JSON.stringify(saved));
  });
  await page.reload();
  await page.locator(".bottom-nav").getByRole("button", { name: "Notices", exact: true }).click();
  await expect(page.locator(".notice-author-summary")).toContainText("Actual author");
  await expect(page.getByRole("textbox", { name: "Search notices" })).toBeHidden();
  await page.getByRole("button", { name: "Search notices", exact: true }).click();
  await page.getByRole("textbox", { name: "Search notices" }).fill("notice 1");
  await expect(page.locator(".notice-card mark").first()).toBeVisible();
  await page.getByRole("textbox", { name: "Search notices" }).fill("");
  await page.getByRole("button", { name: "Search notices", exact: true }).click();
  for (const theme of ["dark", "light"]) {
    await page.evaluate((t) => { document.documentElement.dataset.theme = t; }, theme);
    for (const width of [320, 384, 430]) {
      await page.setViewportSize({ width, height:844 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
      const card = page.locator(".notice-card").first();
      const title = (await card.locator("h2").boundingBox())!;
      const art = (await card.locator(".notice-illustration").boundingBox())!;
      expect(Math.abs(art.y - title.y)).toBeLessThan(3);
      expect((await card.boundingBox())!.height).toBeLessThan(220);
    }
    await page.setViewportSize({ width:384, height:844 });
    await page.screenshot({ path:`../build/web-android-notices-${theme}.png` });
  }
});

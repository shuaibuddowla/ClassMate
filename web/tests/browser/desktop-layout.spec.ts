import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";
test("desktop notices stay beside academic pages, scroll independently, and become the mobile Notices tab", async ({
  page,
}) => {
  await openSavedAccount(page, 1280);
  const panel = page.getByRole("complementary", { name: "Batch notices" });
  await expect(panel).toBeVisible();
  await expect(
    page
      .locator(".sidebar")
      .getByRole("button", { name: "Notices", exact: true }),
  ).toBeHidden();
  await expect(panel.locator(".notice-card")).toHaveCount(10);
  const layout = await page.evaluate(() => {
    const main = document
        .querySelector(".primary-pane")!
        .getBoundingClientRect(),
      rail = document.querySelector(".notice-pane")!.getBoundingClientRect();
    return { mainRight: main.right, railLeft: rail.left, width: main.width };
  });
  expect(layout.mainRight).toBeLessThan(layout.railLeft);
  expect(layout.width).toBeGreaterThan(400);
  await panel.evaluate((el) => {
    el.scrollTop = el.scrollHeight;
  });
  await expect(panel.locator(".notice-card")).toHaveCount(20);
  await page
    .locator(".sidebar")
    .getByRole("button", { name: "Library", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Library", exact: true }),
  ).toBeVisible();
  await expect(panel).toBeVisible();
  await panel
    .getByRole("textbox", { name: "Search notices" })
    .fill("Saved notice 12");
  await expect(panel.locator(".notice-card")).toHaveCount(1);
  await page
    .locator(".sidebar")
    .getByRole("button", { name: "Timetable", exact: true })
    .click();
  await expect(
    panel.getByRole("textbox", { name: "Search notices" }),
  ).toHaveValue("Saved notice 12");
  await panel.getByRole("textbox", { name: "Search notices" }).fill("");
  await panel.evaluate((el) => (el.scrollTop = 0));
  for (const width of [1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBeTruthy();
  }
  await page.screenshot({
    path: "../build/web-desktop-notice-rail.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(panel).toBeHidden();
  await expect(page.locator(".schedule-row")).toBeVisible();
  await page
    .locator(".bottom-nav")
    .getByRole("button", { name: "Notices", exact: true })
    .click();
  await expect(panel).toBeVisible();
  await expect(page.locator(".schedule-row")).toBeHidden();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  await page.setViewportSize({ width: 1280, height: 844 });
  await expect(panel).toBeVisible();
  await expect(page.locator(".schedule-row")).toBeVisible();
});

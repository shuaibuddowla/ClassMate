import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";

test("mobile header saves space; timetable shows teacher and room; Profile switches theme in one tap", async ({
  page,
}) => {
  await openSavedAccount(page, 390);
  await expect(page.locator(".topbar")).toHaveCount(0);
  await expect(page.locator(".schedule-row")).toContainText("Room 301");
  await expect(page.locator(".schedule-row")).toContainText("Assigned Teacher");
  expect(
    await page
      .locator(".schedule-row")
      .evaluate((el: any) => el.getBoundingClientRect().height),
  ).toBeLessThan(135);
  await page.locator(".modes .selected").hover();
  expect(
    await page
      .locator(".modes .selected")
      .evaluate((el: any) => getComputedStyle(el).backgroundImage),
  ).toContain("linear-gradient");
  await page.screenshot({
    path: "../build/web-mobile-timetable.png",
    fullPage: true,
  });
  await page
    .locator(".bottom-nav")
    .getByRole("button", { name: "Profile", exact: true })
    .click();
  const toggle = page.getByRole("switch", { name: "Dark appearance" });
  await toggle.click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await toggle.click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await expect(
    page.getByRole("combobox", { name: "Profile batch" }),
  ).toBeVisible();
  await expect(page.getByRole("switch", { name: "Push notifications", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Send a test notification" })).toBeHidden();
  await expect(
    page.getByRole("link", { name: /Get ClassMate for Android/ }),
  ).toHaveAttribute(
    "href",
    "https://github.com/shuaibuddowla/ClassMate/releases/latest",
  );
  await page.screenshot({
    path: "../build/web-mobile-profile.png",
    fullPage: true,
  });
  for (const width of [320, 390, 700]) {
    await page.setViewportSize({ width, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBeTruthy();
  }
});
test("desktop toggle switches straight between light and dark; library filters hide scrollbars", async ({
  page,
}) => {
  await openSavedAccount(page, 1280);
  await expect(page.locator(".topbar")).toHaveCount(0);
  await page
    .locator(".sidebar")
    .getByRole("button", { name: "Profile", exact: true })
    .click();
  const toggle = page.getByRole("switch", { name: "Dark appearance" });
  await toggle.click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await toggle.click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await page
    .locator(".sidebar")
    .getByRole("button", { name: "Library", exact: true })
    .click();
  expect(
    await page
      .locator(".category-list")
      .evaluate((el: any) => getComputedStyle(el).scrollbarWidth),
  ).toBe("none");
  await page.locator(".segment .selected:visible").hover();
  expect(
    await page
      .locator(".segment .selected:visible")
      .evaluate((el: any) => getComputedStyle(el).backgroundColor),
  ).not.toBe("rgba(0, 0, 0, 0)");
});

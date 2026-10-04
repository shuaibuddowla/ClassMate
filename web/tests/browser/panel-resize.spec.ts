import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";
test("desktop panels resize by dragging and keyboard, persist, and calendar stays centered", async ({
  page,
}) => {
  await openSavedAccount(page, 1440);
  const sidebar = page.locator(".sidebar"),
    notices = page.locator(".notice-pane");
  await expect(sidebar).toHaveCSS("width", "228px");
  await expect(notices).toHaveCSS("width", "380px");
  const left = page.getByRole("separator", {
    name: "Resize navigation sidebar",
  });
  let box = (await left.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, 200);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 30, 200, { steps: 5 });
  await page.mouse.up();
  await expect(sidebar).toHaveCSS("width", "258px");
  const right = page.getByRole("separator", { name: "Resize notices panel" });
  box = (await right.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, 200);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 - 50, 200, { steps: 5 });
  await page.mouse.up();
  await expect(notices).toHaveCSS("width", "430px");
  await page.reload();
  await expect(notices).toHaveCSS("width", "430px");
  await expect(sidebar).toHaveCSS("width", "258px");
  await right.focus();
  await right.press("ArrowLeft");
  await expect(notices).toHaveCSS("width", "446px");
  await right.dblclick();
  await expect(notices).toHaveCSS("width", "380px");
  await page.getByRole("button", { name: "Calendar", exact: true }).click();
  await page.setViewportSize({ width: 1920, height: 1080 });
  const gaps = await page.evaluate(() => {
    const left = document.querySelector(".sidebar")!.getBoundingClientRect(),
      calendar = document
        .querySelector(".calendar-surface")!
        .getBoundingClientRect(),
      right = document.querySelector(".notice-pane")!.getBoundingClientRect();
    return {
      left: calendar.left - left.right,
      right: right.left - calendar.right,
    };
  });
  expect(Math.abs(gaps.left - gaps.right)).toBeLessThan(2);
  await page.screenshot({
    path: "../build/web-resizable-calendar.png",
    fullPage: true,
  });
  for (const width of [1024, 390]) {
    await page.setViewportSize({ width, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBeTruthy();
  }
  await expect(left).toBeHidden();
  await expect(right).toBeHidden();
});

import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";

test("mobile Profile keeps granted push enabled without a token after reload", async ({
  page,
}) => {
  await page.addInitScript(() => {
    Object.defineProperty(window, "Notification", {
      value: { permission: "granted" },
      configurable: true,
    });
    localStorage.setItem("classmate:push-disabled:mobile-layout-test", "0");
    localStorage.removeItem("classmate:push-token");
  });
  await openSavedAccount(page, 390);
  await page
    .locator(".bottom-nav")
    .getByRole("button", { name: "Profile", exact: true })
    .click();
  await expect(
    page.getByRole("switch", { name: "Push notifications" }),
  ).toHaveAttribute("aria-checked", "true");
  await expect(page.getByText("Connecting when online…")).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("switch", { name: "Push notifications" }),
  ).toHaveAttribute("aria-checked", "true");
  await page.evaluate(() => {
    localStorage.setItem("classmate:push-disabled:mobile-layout-test", "1");
    window.dispatchEvent(new Event("classmate:push-state"));
  });
  await expect(
    page.getByRole("switch", { name: "Push notifications" }),
  ).toHaveAttribute("aria-checked", "false");
});

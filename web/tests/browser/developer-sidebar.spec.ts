import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";

test("sidebar developer profile links and sign out", async ({ page }) => {
  await openSavedAccount(page, 1440);
  await page.route("**/rest/v1/rpc/developer_profile", route => route.fulfill({
    status: 200, contentType: "application/json",
    body: JSON.stringify({full_name: "Mohammad Shuaib-Ud-Dowla"}),
  }));
  await page.evaluate(() => {
    Object.defineProperty(navigator, "onLine", {get: () => true, configurable: true});
    window.dispatchEvent(new Event("online"));
  });
  await page.locator(".sidebar-account-actions").getByRole("button", {name: "About developer"}).click();
  await expect(page.locator(".developer-hero h3")).toHaveText("Mohammad Shuaib-Ud-Dowla");
  for (const url of ["facebook.com/shuaibuddowla", "github.com/shuaibuddowla", "shuaibuddowla.github.io"]) {
    await expect(page.locator(`.developer-links a[href="https://${url}"]`)).toBeVisible();
  }
  await page.screenshot({path: "test-results/developer-panel.png"});
  await page.keyboard.press("Escape");
  await page.locator(".sidebar-account-actions").getByRole("button", {name: "Sign out", exact: true}).click();
  await expect(page.getByRole("button", {name: "Continue with Google"})).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("classmate:last-account"))).toBeNull();
});

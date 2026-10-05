import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";
test("blood requests fit phones and desktops, preserve consent and volunteer actions", async ({
  page,
}) => {
  await openSavedAccount(page, 390);
  const request = {
    id: "b1000000-0000-4000-8000-000000000021",
    blood_group: "A+",
    hospital: "Tangail General Hospital",
    units: 2,
    needed_by: new Date(Date.now() + 86400000).toISOString(),
    audience: "university",
    status: "open",
    can_donate: true,
    can_verify: false,
    my_response: null as string | null,
    volunteers: 0,
  };
  let enabled = false;
  let responseCalls = 0;
  await page.route("**/rest/v1/rpc/**", async (route) => {
    const name = route.request().url().split("/").at(-1),
      args = route.request().postDataJSON();
    let data: unknown = [];
    if (name === "blood_request_feed") data = [request];
    if (name === "blood_preferences")
      data = { opted_in: enabled, last_donation: null, cooldown_days: 120 };
    if (name === "save_blood_preferences") {
      enabled = args.target_enabled;
      data = { opted_in: enabled };
    }
    if (name === "blood_request_details")
      data = {
        ...request,
        attendant_phone: "+8801700000099",
        can_manage: false,
        volunteers: [],
      };
    if (name === "respond_blood_request") {
      responseCalls++;
      request.my_response = args.target_response;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(data),
    });
  });
  await page.evaluate(() => {
    Object.defineProperty(navigator, "onLine", {
      get: () => true,
      configurable: true,
    });
    window.dispatchEvent(new Event("online"));
  });
  await page
    .locator(".bottom-nav")
    .getByRole("button", { name: "Friends", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Blood requests", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Blood requests", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".blood-request")).toContainText(
    "Tangail General Hospital",
  );
  await page.getByRole("button", { name: "Donor settings" }).click();
  await page.getByLabel("Matching blood alerts").selectOption("yes");
  await page.getByRole("button", { name: "Save preferences" }).click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  expect(enabled).toBe(true);
  await page.locator(".blood-request").click();
  await expect(
    page.getByRole("link", { name: "Call attendant" }),
  ).toHaveAttribute("href", "tel:+8801700000099");
  await page.getByRole("button", { name: "I can donate", exact: true }).click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  expect(responseCalls).toBe(1);
  for (const width of [320, 390, 1440]) {
    await page.setViewportSize({ width, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: "../build/blood-network-mobile.png" });
  await page.getByRole("button", { name: "Request", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText(
    "Submit for verification",
  );
  await page.getByRole("button", { name: "Close", exact: true }).click();
  await page.goto("/#friends/blood/" + request.id);
  // Reloading a cached account still keeps blood data online-only.
  await expect(page.locator(".bottom-nav")).toBeVisible();
});

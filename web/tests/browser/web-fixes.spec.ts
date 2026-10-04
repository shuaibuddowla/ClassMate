import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";

test("profile displays the shared academic session range", async ({ page }) => {
  await openSavedAccount(page, 390);
  await page.locator(".bottom-nav").getByRole("button", {name: "Profile", exact: true}).click();
  await expect(page.locator(".profile-identity")).toContainText("23-24");
});

test("Manage arrow and browser back return to Profile", async ({ page }) => {
  await openSavedAccount(page, 390);
  await page.evaluate(() => {
    const key = "classmate:identity:mobile-layout-test";
    const p = JSON.parse(localStorage.getItem(key)!);
    p.is_cr = true;
    localStorage.setItem(key, JSON.stringify(p));
  });
  await page.reload();
  await page.route("**/rest/v1/rpc/can_manage_batch_catalog", route => route.fulfill({status:200,contentType:"application/json",body:"true"}));
  await page.evaluate(() => { Object.defineProperty(navigator, "onLine", {get:()=>true, configurable:true}); window.dispatchEvent(new Event("online")); });
  await page
    .locator(".bottom-nav")
    .getByRole("button", { name: "Profile", exact: true })
    .click();
  const open = () => page.locator(".profile-manage").click();
  await open();
  await expect(page).toHaveURL(/#manage$/);
  await page
    .locator(".primary-pane .text-button")
    .filter({ hasText: "Profile" })
    .click();
  await expect(page.locator(".profile-header")).toBeVisible();
  await open();
  await page.goBack();
  await expect(page).toHaveURL(/#profile$/);
  await expect(page.locator(".profile-header")).toBeVisible();
});
test("Syllabus clears conflicting filters and shows all its results below selector", async ({
  page,
}) => {
  await openSavedAccount(page, 390);
  let queries: string[] = [];
  await page.route("**/*.supabase.co/rest/v1/file_metadata**", (route) => {
    const url = new URL(route.request().url());
    queries.push(url.search);
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(
        Array.from({ length: 6 }, (_, i) => ({
          id: `syllabus-${i}`,
          title: `OOPC syllabus ${i}`,
          category: "syllabus",
          size_bytes: 20000,
          created_at: new Date().toISOString(),
        })),
      ),
    });
  });
  await page.evaluate(() => window.dispatchEvent(new Event("online")));
  await page
    .locator(".bottom-nav")
    .getByRole("button", { name: "Library", exact: true })
    .click();
  await page
    .getByRole("textbox", { name: "Search library" })
    .fill("missing term");
  await page
    .locator(".library-courses")
    .getByRole("button", { name: "Syllabus", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "Search library" }),
  ).toHaveValue("");
  await expect(page.locator(".library-files .file-row")).toHaveCount(6);
  expect(
    queries.some(
      (q) =>
        q.includes("category=eq.syllabus") &&
        !q.includes("semester_course_id="),
    ),
  ).toBeTruthy();
  const selector = (await page.locator(".library-courses").boundingBox())!;
  const results = (await page.locator(".library-files").boundingBox())!;
  expect(results.y).toBeGreaterThanOrEqual(selector.y + selector.height);
});
test("signed-out welcome matches Android and follows device theme", async ({
  page,
}) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: /Continue with Google/ }),
  ).toBeVisible();
  await expect(page.locator(".login h2")).toContainText("ClassMate.");
  await expect(page.locator(".signin-brand")).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.emulateMedia({ colorScheme: "light" });
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
});

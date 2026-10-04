import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";

test("mobile library puts recent files above grouped courses and retains All files access", async ({
  page,
}) => {
  await openSavedAccount(page, 384);
  await page.route("**/*.supabase.co/rest/v1/file_metadata**", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          id: "test-file",
          title: "OOP Syllabus",
          category: "syllabus",
          size_bytes: 199680,
          semester_course_id: "layout-course",
          created_at: new Date().toISOString(),
        },
      ]),
    }),
  );
  await page.evaluate(() => window.dispatchEvent(new Event("online")));
  await page
    .locator(".bottom-nav")
    .getByRole("button", { name: "Library", exact: true })
    .click();
  await expect(page.locator(".library-files .file-row")).toContainText(
    "OOP Syllabus",
  );
  const files = (await page.locator(".library-files").boundingBox())!;
  const courses = (await page.locator(".library-courses").boundingBox())!;
  expect(files.y + files.height).toBeLessThanOrEqual(courses.y);
  expect((await page.locator(".library-screen .search").boundingBox())!.height).toBeLessThan(55);
  await page.getByRole("button", { name: "All files", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("OOP Syllabus");
  await page.keyboard.press("Escape");
  for (const theme of ["dark", "light"]) {
    await page.evaluate((t) => {
      document.documentElement.dataset.theme = t;
    }, theme);
    await page.screenshot({
      path: `../build/web-android-library-${theme}.png`,
    });
    await page.setViewportSize({ width: 320, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBeTruthy();
    await page.setViewportSize({ width: 384, height: 844 });
  }
});

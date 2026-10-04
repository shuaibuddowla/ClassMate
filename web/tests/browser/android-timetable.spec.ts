import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";

test("mobile timetable matches Android structure in both themes and stays within narrow screens", async ({
  page,
}) => {
  await openSavedAccount(page, 384);
  await page.evaluate(() => {
    const key = "classmate:academic:v1:mobile-layout-test:layout-batch:routine";
    const saved = JSON.parse(localStorage.getItem(key)!);
    saved.data = ["Computer Org. & Arc.", "DSA", "DSA LAB", "OOP"].map(
      (title, i) => ({
        ...saved.data[0],
        id: `slot-${i}`,
        start_time: ["09:50", "12:20", "13:40", "15:10"][i],
        end_time: ["10:35", "13:05", "15:05", "15:50"][i],
        type: i === 2 ? "lab" : "class",
        room: i === 2 ? "Lab-02" : "Room no. 338",
        course: { ...saved.data[0].course, course_title: title },
      }),
    );
    localStorage.setItem(key, JSON.stringify(saved));
    const identityKey = "classmate:identity:mobile-layout-test";
    const profile = JSON.parse(localStorage.getItem(identityKey)!);
    profile.full_name = "Mohammad Shuaib";
    localStorage.setItem(identityKey, JSON.stringify(profile));
    localStorage.setItem("classmate:theme", "dark");
  });
  await page.reload();
  await page.evaluate(() => {
    document.documentElement.dataset.theme = "dark";
  });
  await expect(page.locator(".routine-row")).toHaveCount(4);
  await expect(page.locator(".period-duration").first()).toHaveText("45 min");
  await expect(page.locator(".period-course").first()).toContainText(
    "Room no. 338",
  );
  await expect(page.locator(".period-course").first()).not.toContainText(
    "Room Room",
  );
  const week = (await page.locator(".week").boundingBox())!;
  const modes = (await page.locator(".modes").boundingBox())!;
  expect(week.y + week.height).toBeLessThan(modes.y);
  await page.screenshot({ path: "../build/web-android-timetable-dark.png" });
  for (const theme of ["light", "dark"]) {
    await page.evaluate((theme) => {
      document.documentElement.dataset.theme = theme;
    }, theme);
    for (const width of [320, 384, 430]) {
      await page.setViewportSize({ width, height: 844 });
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBeTruthy();
      const days = await page
        .locator(".day")
        .evaluateAll((elements) =>
          elements.map((el) => el.getBoundingClientRect().width),
        );
      expect(Math.max(...days) - Math.min(...days)).toBeLessThan(1);
    }
  }
});

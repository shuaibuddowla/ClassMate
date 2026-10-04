import { test, expect } from "@playwright/test";
import fs from "node:fs";
const fixture = JSON.parse(
  fs.readFileSync("../build/web-fixtures-private.json", "utf8"),
);
async function login(page: any, role = "student") {
  const user = fixture.users.find((u: any) => u.role === role);
  await page.addInitScript(
    ({ user, ref }: any) => {
      localStorage.setItem(
        `sb-${ref}-auth-token`,
        JSON.stringify({
          access_token: user.token,
          refresh_token: "synthetic-test-refresh-token",
          token_type: "bearer",
          expires_in: 7200,
          expires_at: Math.floor(Date.now() / 1000) + 7200,
          user: {
            id: user.id,
            aud: "authenticated",
            role: "authenticated",
            email: user.email,
            user_metadata: { full_name: "Web Test" },
            app_metadata: { provider: "google", providers: ["google"] },
            identities: [],
            created_at: new Date().toISOString(),
          },
        }),
      );
    },
    { user, ref: fixture.ref },
  );
  await page.goto("/");
  await expect(page.getByRole("heading", { name: /Welcome,/ })).toBeVisible();
}
test("landing is responsive, clear and themed", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ colorScheme: "light" });
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: /Continue with Google/ }),
  ).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
});
test("student notices paginate, read receipts work, and footer fits a narrow phone", async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await login(page);
  await page
    .locator(".bottom-nav")
    .getByRole("button", { name: "Notices", exact: true })
    .click();
  await expect(page.locator(".notice-card")).toHaveCount(10);
  await expect(
    page
      .locator(".notice-card")
      .first()
      .getByRole("button", { name: "Manage notice" }),
  ).toHaveCount(0);
  await page.locator(".notice-card").first().locator(".seen").click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("button", { name: "Close", exact: true }).click();
  await page.locator(".paging").scrollIntoViewIfNeeded();
  await expect(page.locator(".notice-card")).toHaveCount(20);
  await page.screenshot({
    path: "../build/web-notices-mobile.png",
    fullPage: false,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
});
test("calendar, bus, library and Friends render using real staging APIs", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1366, height: 900 });
  await login(page);
  await page.getByRole("button", { name: "Calendar", exact: true }).click();
  await expect(page.locator(".calendar-grid")).toBeVisible();
  await page.getByRole("button", { name: "Next month" }).click();
  await page.getByRole("button", { name: "Previous month" }).click();
  await page.getByRole("button", { name: "Today", exact: true }).click();
  await page.screenshot({ path: "../build/web-calendar-desktop.png" });
  await page.getByRole("button", { name: "Bus Schedule", exact: true }).click();
  await expect(page.locator(".schedule-row").first()).toBeVisible();
  await page
    .locator(".sidebar")
    .getByRole("button", { name: "Library", exact: true })
    .click();
  await expect(
    page.getByPlaceholder("Search notes, slides and past questions"),
  ).toBeVisible();
  await page.getByRole("button", { name: "Labs", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Labs", exact: true }),
  ).toHaveClass(/selected/);
  await page
    .locator(".sidebar")
    .getByRole("button", { name: "Friends", exact: true })
    .click();
  await expect(page.locator(".person")).toHaveCount(3);
  await page.locator(".person").first().click();
  await expect(page.getByRole("dialog")).toContainText("Student ID");
  await expect(page.getByRole("dialog")).toContainText("Not shared");
});
test("CR configuration and posting use real forms with enforced permissions", async ({
  page,
}) => {
  await login(page, "cr");
  await page
    .locator(".sidebar")
    .getByRole("button", { name: "Profile", exact: true })
    .click();
  await page.getByRole("button", { name: /Manage/ }).click();
  await expect(
    page.getByRole("heading", { name: "Manage", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "System health", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Add course", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("Teacher name");
  await page.getByRole("button", { name: "Close", exact: true }).click();
  await page
    .locator(".sidebar")
    .getByRole("button", { name: "Notices", exact: true })
    .click();
  await page.getByRole("button", { name: "Post", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("General notice");
  await page
    .getByRole("button", { name: "Class cancellation", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText("Which day?");
  await page
    .getByRole("button", { name: "General notice", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByLabel("Title", { exact: true })
    .fill("Browser CRUD notice");
  await page
    .getByRole("dialog")
    .getByLabel("Message", { exact: true })
    .fill("/silent Isolated browser posting test.");
  await page.getByRole("button", { name: "Post notice", exact: true }).click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  const card = page
    .locator(".notice-card")
    .filter({ hasText: "Browser CRUD notice" });
  await expect(card).toBeVisible();
  await card.getByRole("button", { name: "Manage notice" }).click();
  await card.getByRole("button", { name: "Delete", exact: true }).click();
  await page
    .getByRole("button", { name: "Confirm deletion", exact: true })
    .click();
  await expect(card).not.toBeVisible();
});
test("offline academic content survives reload, and sign-out clears private caches", async ({
  page,
  context,
}) => {
  await login(page);
  await expect
    .poll(async () =>
      page.evaluate(
        () =>
          Object.keys(localStorage).filter((k) =>
            k.startsWith("classmate:academic"),
          ).length,
      ),
    )
    .toBeGreaterThan(0);
  await page
    .locator(".sidebar")
    .getByRole("button", { name: "Notices", exact: true })
    .click();
  await expect(page.locator(".notice-card")).toHaveCount(10);
  await page.waitForFunction(() => navigator.serviceWorker.controller);
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByText(/Offline · saved/)).toBeVisible();
  await page
    .locator(".sidebar")
    .getByRole("button", { name: "Notices", exact: true })
    .click();
  await expect(page.locator(".notice-card")).toHaveCount(10);
  await context.setOffline(false);
  await page
    .locator(".sidebar")
    .getByRole("button", { name: "Profile", exact: true })
    .click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("button", { name: /Continue with Google/ }),
  ).toBeVisible();
  expect(
    await page.evaluate(() =>
      Object.keys(localStorage).some((k) => k.startsWith("classmate:academic")),
    ),
  ).toBeFalsy();
});

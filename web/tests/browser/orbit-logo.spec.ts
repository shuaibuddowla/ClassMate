import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";

test("refresh keeps only the orbit logo while restoring an account", async ({
  page,
}) => {
  const config = readFileSync(".env.local", "utf8");
  const ref = /NEXT_PUBLIC_SUPABASE_URL=["']?https:\/\/([^.]+)/.exec(
    config,
  )![1];
  await page.setViewportSize({ width: 390, height: 844 });
  let release!: () => void;
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/*.supabase.co/**", async (route) => {
    await pending;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: "[]",
    });
  });
  await page.addInitScript((ref) => {
    const exp = Math.floor(Date.now() / 1000) + 3600;
    const user = {
      id: "orbit-layout-test",
      email: "ce25045@mbstu.ac.bd",
      user_metadata: {},
    };
    const token = [
      btoa(JSON.stringify({ alg: "HS256", typ: "JWT" })),
      btoa(JSON.stringify({ sub: user.id, exp, aud: "authenticated" })),
      "fixture",
    ].join(".");
    localStorage.setItem(
      `sb-${ref}-auth-token`,
      JSON.stringify({
        access_token: token,
        refresh_token: "fixture",
        expires_at: exp,
        token_type: "bearer",
        user,
      }),
    );
  }, ref);
  await page.goto("/", { waitUntil: "domcontentloaded" });
  await expect(
    page.getByRole("status", { name: "Loading ClassMate" }),
  ).toBeVisible();
  await expect(page.locator(".boot-orbit .signin-orbit img")).toBeVisible();
  await expect(
    page.locator(".boot-orbit .skeleton, .boot-orbit h2"),
  ).toHaveCount(0);
  await page.screenshot({ path: "test-results/refresh-orbit.png" });
  release();
});

test("refresh artwork and sign-in share moving electrons", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.route("**/*.supabase.co/**", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: "[]" }),
  );
  await page.goto("/", { waitUntil: "commit" });
  await expect(page.locator(".signin-orbit")).toBeVisible();
  await expect(page.locator("animateMotion")).toHaveCount(3);
  await expect(
    page.getByRole("button", { name: /Continue with Google/ }),
  ).toBeVisible();
  const position = () =>
    page
      .locator(".logo-electron")
      .first()
      .evaluate((node: SVGGraphicsElement) => {
        const matrix = node.getCTM()!;
        return { x: matrix.e, y: matrix.f };
      });
  const before = await position();
  await expect.poll(position).not.toEqual(before);
  await page.screenshot({ path: "test-results/signin-orbit.png" });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await expect(page.locator("animateMotion")).toHaveCount(0);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
});

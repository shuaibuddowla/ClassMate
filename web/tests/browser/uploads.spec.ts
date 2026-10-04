import { test, expect } from "@playwright/test";
import fs from "node:fs";
const fixture = JSON.parse(
  fs.readFileSync("../build/web-fixtures-private.json", "utf8"),
);
test("CR uploads through browser CORS, edits metadata and permanently deletes the file", async ({
  page,
}) => {
  const user = fixture.users.find((u: any) => u.role === "cr");
  await page.addInitScript(
    ({ user, ref }: any) =>
      localStorage.setItem(
        `sb-${ref}-auth-token`,
        JSON.stringify({
          access_token: user.token,
          refresh_token: "test-refresh",
          token_type: "bearer",
          expires_in: 7200,
          expires_at: Math.floor(Date.now() / 1000) + 7200,
          user: {
            id: user.id,
            email: user.email,
            aud: "authenticated",
            role: "authenticated",
            user_metadata: {},
            app_metadata: { provider: "google" },
          },
        }),
      ),
    { user, ref: fixture.ref },
  );
  await page.goto("/");
  await expect(page.getByRole("heading", { name: /Welcome,/ })).toBeVisible();
  await page
    .locator(".sidebar")
    .getByRole("button", { name: "Library", exact: true })
    .click();
  await page.getByRole("button", { name: "Add file", exact: true }).click();
  await page
    .getByRole("dialog")
    .getByLabel("File name")
    .fill("Tagged web upload test");
  await page
    .getByRole("dialog")
    .locator("input[type=file]")
    .setInputFiles({
      name: "web-test.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("Isolated ClassMate web staging test.\n"),
    });
  await page.getByRole("button", { name: "Upload file", exact: true }).click();
  await expect(page.getByRole("dialog")).not.toBeVisible({ timeout: 30_000 });
  await expect(
    page.getByText("Tagged web upload test", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Edit file", exact: true }).click();
  await page
    .getByRole("dialog")
    .getByLabel("File name")
    .fill("Tagged web upload edited");
  await page
    .getByRole("dialog")
    .getByLabel("Subject")
    .selectOption(user.offering);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Save", exact: true })
    .click();
  await expect(
    page.getByText("Tagged web upload edited", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Edit file", exact: true }).click();
  await page.getByRole("button", { name: "Delete permanently" }).click();
  await page.getByRole("button", { name: "Confirm deletion" }).click();
  await expect(
    page.getByText("Tagged web upload edited", { exact: true }),
  ).not.toBeVisible({ timeout: 30_000 });
});

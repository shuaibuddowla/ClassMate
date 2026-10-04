import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";
test("Authorized Profile shortcut opens AI with persistent private chats", async ({
  page,
}) => {
  await openSavedAccount(page, 1440);
  const chats: any[] = [],
    messages: any[] = [];
  await page.route("**/rest/v1/rpc/**", async (route) => {
    const name = route.request().url().split("/").at(-1),
      a = route.request().postDataJSON();
    let data: any = [];
    if (name === "ai_can_write") data = true;
    if (name === "ai_conversation_page") data = chats;
    if (name === "manage_ai_conversation") {
      if (a.operation === "create") {
        data = {
          id: a.target_id,
          title: "New chat",
          updated_at: new Date().toISOString(),
        };
        chats.unshift(data);
      }
      if (a.operation === "rename") {
        data = chats.find((c) => c.id === a.target_id);
        data.title = a.target_title;
      }
      if (a.operation === "delete") {
        chats.splice(
          chats.findIndex((c) => c.id === a.target_id),
          1,
        );
        for (let i = messages.length - 1; i >= 0; i--)
          if (messages[i].conversation_id === a.target_id)
            messages.splice(i, 1);
      }
    }
    if (name === "ai_message_page")
      data = messages.filter(
        (m) => m.conversation_id === a.target_conversation,
      );
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(data),
    });
  });
  await page.route("**/functions/v1/classmate-ai", async (route) => {
    const a = route.request().postDataJSON();
    expect(a.history).toBeUndefined();
    chats.find((c) => c.id === a.conversation_id).title = a.text;
    const answer = "Your batch has four classes today.\n" + "More academic details from this batch.\n".repeat(70);
    messages.push(
      {
        id: messages.length + 1,
        role: "user",
        content: a.text,
        request_id: a.request_id,
        conversation_id: a.conversation_id,
      },
      {
        id: messages.length + 2,
        role: "assistant",
        content: answer,
        request_id: a.request_id,
        conversation_id: a.conversation_id,
        status: "complete",
        plan: { message: answer, actions: [] },
      },
    );
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        plan: { message: "Your batch has four classes today.", actions: [] },
      }),
    });
  });
  await page.evaluate(() =>
    Object.defineProperty(navigator, "onLine", {
      get: () => true,
      configurable: true,
    }),
  );
  await page
    .locator(".sidebar nav")
    .getByRole("button", { name: "Profile", exact: true })
    .click();
  await page.locator(".profile-screen").getByRole("button", {name:/ClassMate AI/}).click();
  await expect(page.locator(".primary-pane .ai-page")).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.locator(".ai-suggestions button")).toHaveCount(3);
  await page
    .getByRole("textbox", { name: "Ask ClassMate AI" })
    .fill("What classes do I have today?");
  await page.getByRole("textbox", {name:"Ask ClassMate AI"}).press("Enter");
  await expect(page.locator(".ai-message.assistant")).toContainText(
    "four classes",
  );
  const reading = await page.evaluate(()=>{
    const list=document.querySelector('.ai-messages')!.getBoundingClientRect(),prompt=document.querySelector('.ai-message.user')!.getBoundingClientRect();
    return {list:list.top,prompt:prompt.top};
  });
  expect(Math.abs(reading.prompt-reading.list)).toBeLessThan(25);
  await page.locator('.sidebar nav').getByRole('button',{name:'Library',exact:true}).click();
  await page.locator('.sidebar nav').getByRole('button',{name:'Profile',exact:true}).click();
  await page.locator('.profile-screen').getByRole('button',{name:/ClassMate AI/}).click();
  await expect(page.locator('.ai-suggestions')).toHaveCount(0);
  await expect(page.locator('.ai-new-chat')).toBeVisible();
  await page.reload();
  await expect(page.locator(".profile-screen")).toBeVisible();
  await page.evaluate(() => {
    Object.defineProperty(navigator, "onLine", {
      get: () => true,
      configurable: true,
    });
    window.dispatchEvent(new Event("online"));
  });
  await expect(page.locator(".primary-pane .ai-page")).toBeVisible();
  await page
    .locator(".ai-history-row")
    .getByRole("button", { name: "What classes do I have today?", exact: true })
    .click();
  await expect(page.locator(".ai-message.user")).toContainText("What classes");
  await expect(page.locator(".ai-message.assistant")).toContainText(
    "four classes",
  );
  await page
    .getByRole("button", { name: "Rename What classes do I have today?" })
    .click();
  await page.getByRole("textbox", { name: "Chat title" }).fill("Today");
  await page.getByRole("button", { name: "Save title" }).click();
  await expect(page.locator(".ai-history-row")).toContainText("Today");
  await page.setViewportSize({ width: 360, height: 800 });
  await expect(page.locator('.ai-workspace>.ai-history')).toBeHidden();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  await page.screenshot({ path: "../build/ai-workspace-mobile.png" });
  await page.getByRole('button',{name:'Open chat history'}).click();
  await page.getByRole("button", { name: "Delete Today", exact: true }).click();
  await page.getByRole("button", { name: "Delete chat", exact: true }).click();
  await expect(page.locator(".ai-history-row")).toHaveCount(0);
});
test("web installation uses the browser prompt and has a menu fallback", async ({
  page,
}) => {
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "Install web app", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Install web app", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText("browser’s app menu");
  await page.getByRole("button", { name: "Got it" }).click();
  await page.evaluate(() => {
    const event = new Event("beforeinstallprompt");
    Object.assign(event, {
      prompt: async () => {
        (window as any).installPrompted = true;
      },
      userChoice: Promise.resolve({ outcome: "accepted" }),
    });
    window.dispatchEvent(event);
  });
  await page
    .getByRole("button", { name: "Install web app", exact: true })
    .click();
  expect(
    await page.evaluate(() => (window as any).installPrompted),
  ).toBeTruthy();
  await page.evaluate(() => window.dispatchEvent(new Event("appinstalled")));
  await expect(page.getByText("Web app installed")).toBeVisible();
});

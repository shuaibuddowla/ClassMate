import { test, expect } from "@playwright/test";
import { openSavedAccount } from "./helpers/offline-fixtures";

async function ready(page: any) {
  await openSavedAccount(page, 390);
  await page.evaluate(() => {
    const key = "classmate:identity:mobile-layout-test";
    const p = JSON.parse(localStorage.getItem(key)!);
    p.is_cr = true;
    p.cr_batch_id = p.batch_id;
    localStorage.setItem(key, JSON.stringify(p));
  });
  await page.reload();
  await expect(page.locator(".bottom-nav")).toBeVisible();
  const comments: any[] = [];
  const replies: any[] = [];
  const chats: any[] = [],
    messages: any[] = [];
  await page.route("**/rest/v1/rpc/**", async (route: any) => {
    const name = route.request().url().split("/").at(-1),
      args = route.request().postDataJSON();
    let data: any = [];
    if (name === "ai_can_write") data = true;
    if (name === "manage_ai_conversation") {
      data = { id: args.target_id, title: "New chat" };
      chats.push(data);
    }
    if (name === "ai_conversation_page") data = chats;
    if (name === "ai_message_page")
      data = messages.filter(
        (m) => m.conversation_id === args.target_conversation,
      );
    if (name === "can_post" || name === "can_manage_routine") data = true;
    if (name === "comment_page") data = args.target_parent ? replies : comments;
    if (name === "save_notice_comment") {
      const list = args.target_parent ? replies : comments;
      if (args.target_id) {
        const c = list.find((x: any) => x.id === args.target_id);
        c.body = args.target_body;
        c.updated_at = new Date().toISOString();
        data = c;
      } else {
        data = {
          id: crypto.randomUUID(),
          author_id: "mobile-layout-test",
          author_name: "Actual member",
          body: args.target_body,
          parent_id: args.target_parent,
          created_at: new Date().toISOString(),
          can_edit: true,
          can_delete: true,
          like_count: 0,
          liked_by_me: false,
          reply_count: 0,
        };
        list.unshift(data);
        if (args.target_parent)
          comments.find((x: any) => x.id === args.target_parent).reply_count++;
      }
    }
    if (name === "set_comment_like") {
      const c = [...comments, ...replies].find((x) => x.id === args.target_id);
      c.liked_by_me = args.target_liked;
      c.like_count = args.target_liked ? 1 : 0;
    }
    if (name === "delete_notice_comment") {
      const i = comments.findIndex((x) => x.id === args.target_id);
      comments.splice(i, 1);
    }
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(data),
    });
  });
  await page.route("**/functions/v1/classmate-ai", async (route: any) => {
    const args = route.request().postDataJSON();
    let data: any;
    if (args.mode === "translate")
      data = {
        result: { title: "বাংলা নোটিশ", body: "আগামীকাল ক্লাস শুরু হবে।" },
      };
    if (args.mode === "compose")
      data = {
        request_id: args.request_id,
        result: { actions: [{ name: "post_notice", result: { id: "saved" } }] },
      };
    if (args.mode === "agent")
      data = {
        request_id: args.request_id,
        plan: {
          message: "This changes the shared university bus timetable.",
          actions: [
            {
              name: "save_student_bus_schedule",
              args: {
                target_id: null,
                target_kind: "office_open",
                target_campus_departure: "08:00",
                target_city_departure: "08:30",
                target_active: true,
              },
            },
          ],
        },
      };
    if (args.mode === "agent") {
      const chat = chats.find((c) => c.id === args.conversation_id);
      if (chat) chat.title = args.text;
      messages.push(
        {
          id: messages.length + 1,
          role: "user",
          content: args.text,
          conversation_id: args.conversation_id,
          request_id: args.request_id,
        },
        {
          id: messages.length + 2,
          role: "assistant",
          content: data.plan.message,
          conversation_id: args.conversation_id,
          request_id: args.request_id,
          plan: data.plan,
          status: "ready",
        },
      );
    }
    if (args.mode === "execute") {
      data = { result: { message: "Saved" } };
      messages.forEach((m) => {
        if (m.request_id === args.request_id) m.status = "complete";
      });
    }
    return route.fulfill({
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
    .getByRole("button", { name: "Notices", exact: true })
    .click();
  await expect(page.locator(".notice-card").first()).toBeVisible();
}
test("threaded comments create, reply, like, edit and delete in mobile themes", async ({
  page,
}) => {
  await ready(page);
  await page
    .locator(".notice-card")
    .first()
    .getByRole("button", { name: "View comments" })
    .click();
  const dialog = page.getByRole("dialog").first();
  await expect(dialog.getByText("Start the conversation")).toBeVisible();
  await dialog
    .getByRole("textbox", { name: "Write a comment", exact: true })
    .fill("My first comment");
  await dialog
    .getByRole("button", { name: "Send comment", exact: true })
    .click();
  await expect(dialog.locator(".comment-bubble")).toContainText(
    "My first comment",
  );
  await expect(
    dialog.getByRole("textbox", { name: "Write a comment", exact: true }),
  ).toHaveValue("");
  await dialog
    .getByRole("button", { name: "Like comment", exact: true })
    .click();
  await expect(
    dialog.getByRole("button", { name: "Unlike comment", exact: true }),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "Reply", exact: true }).click();
  await dialog
    .getByRole("textbox", { name: "Write a reply", exact: true })
    .fill("A threaded reply");
  await dialog
    .locator(".reply-thread")
    .getByRole("button", { name: "Send comment", exact: true })
    .click();
  await expect(dialog.locator(".reply-thread .comment-bubble")).toContainText(
    "A threaded reply",
  );
  await dialog
    .locator(".social-comment")
    .first()
    .locator(":scope > .comment-menu")
    .getByRole("button", { name: "Comment options" })
    .click();
  await dialog.getByRole("button", { name: "Edit", exact: true }).click();
  await dialog
    .getByRole("textbox", { name: "Edit comment", exact: true })
    .fill("Edited message");
  await dialog
    .getByRole("button", { name: "Save comment", exact: true })
    .click();
  await expect(dialog.locator(".comment-bubble").first()).toContainText(
    "Edited message",
  );
  for (const theme of ["light", "dark"]) {
    await page.evaluate((t) => {
      document.documentElement.dataset.theme = t;
    }, theme);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBeTruthy();
    await page.screenshot({ path: `../build/comments-${theme}.png` });
  }
  await dialog
    .locator(".social-comment")
    .first()
    .locator(":scope > .comment-menu")
    .getByRole("button", { name: "Comment options" })
    .click();
  await dialog.getByRole("button", { name: "Delete", exact: true }).click();
  await page.getByRole("button", { name: "Confirm deletion" }).click();
  await expect(dialog.getByText("Start the conversation")).toBeVisible();
});
test("AI default composer, Bengali toggle and shared-bus plan review", async ({
  page,
}) => {
  await ready(page);
  await page
    .locator(".notice-card")
    .first()
    .getByRole("button", { name: "Translate", exact: true })
    .click();
  await expect(page.locator(".notice-card h2").first()).toHaveText(
    "বাংলা নোটিশ",
  );
  await page
    .locator(".notice-card")
    .first()
    .getByRole("button", { name: "Show original" })
    .click();
  await expect(page.locator(".notice-card h2").first()).toHaveText(
    "Saved notice 1",
  );
  await page.getByRole("button", { name: "Post", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "AI compose", exact: true }),
  ).toHaveClass(/selected/);
  await page
    .getByRole("textbox", { name: "Your notice" })
    .fill("messy exam notice tomorrow at 10");
  await page.getByRole("button", { name: "Compose & post" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page
    .locator(".bottom-nav")
    .getByRole("button", { name: "Profile", exact: true })
    .click();
  await page.getByRole("button", { name: /ClassMate AI/ }).click();
  await page
    .getByRole("textbox", { name: "Ask ClassMate AI" })
    .fill("add bus 8 am and 8:30 am");
  await page.getByRole("button", { name: "Send to ClassMate AI" }).click();
  await expect(page.locator(".ai-shared-warning")).toHaveText(
    "Bus changes apply university-wide.",
  );
  await page.getByRole("button", { name: "Apply changes" }).click();
  await expect(
    page.getByRole("button", { name: "Saved", exact: true }),
  ).toBeDisabled();
});

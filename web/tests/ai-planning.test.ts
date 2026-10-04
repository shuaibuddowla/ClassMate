import test from "node:test";
import assert from "node:assert/strict";
import {
  availableTools,
  validatePlan,
  preserveSilent,
  retryDelay,
} from "../../supabase/functions/classmate-ai/planning";
import { generateJson } from "../../supabase/functions/classmate-ai/provider";
const batch = "00000000-0000-4000-8000-000000000001";
test("CR tools preserve shared buses and prohibit owner management", () => {
  const names = availableTools(false, true, "agent").map((t) => t.name);
  assert.ok(names.includes("save_student_bus_schedule"));
  assert.ok(!names.includes("delete_global_course"));
  assert.equal(availableTools(false, false, "agent").length, 0);
  assert.throws(() =>
    validatePlan(
      {
        message: "x",
        actions: [
          { name: "delete_global_course", args: { target_course: batch } },
        ],
      },
      false,
      true,
      "agent",
    ),
  );
});
test("compose accepts only one notice and rejects invented IDs or fields", () => {
  const action = {
    name: "post_notice",
    args: {
      target_batch: batch,
      target_course: null,
      notice_title: "Exam",
      notice_body: "Tomorrow at 10.",
    },
  };
  assert.equal(
    validatePlan(
      { message: "Ready", actions: [action] },
      false,
      true,
      "compose",
    ).actions.length,
    1,
  );
  assert.throws(() =>
    validatePlan(
      { message: "Ready", actions: [action, action] },
      true,
      true,
      "compose",
    ),
  );
  assert.throws(() =>
    validatePlan(
      {
        message: "Ready",
        actions: [
          { ...action, args: { ...action.args, target_batch: "fake" } },
        ],
      },
      true,
      true,
      "compose",
    ),
  );
  assert.throws(() =>
    validatePlan(
      {
        message: "Ready",
        actions: [{ ...action, args: { ...action.args, sql: "DELETE" } }],
      },
      true,
      true,
      "compose",
    ),
  );
});
test("silent notices and cancellations preserve feed-only intent", () => {
  const p = {
    message: "",
    actions: [
      { name: "post_notice", args: { notice_title: "A" } },
      { name: "ai_post_cancellation_notice", args: { target_silent: false } },
    ],
  };
  preserveSilent(p, " /silent exam postponed");
  assert.equal(p.actions[0].args.notice_title, "/silent A");
  assert.equal(p.actions[1].args.target_silent, true);
});
test("Retry-After handles seconds and dates", () => {
  assert.equal(retryDelay("12", 0), 12000);
  assert.equal(retryDelay("Thu, 01 Jan 1970 00:01:00 GMT", 0), 60000);
});
test("same-project keys do not bypass quota cooldown", async () => {
  let calls = 0;
  const map = new Map<string, number>();
  await assert.rejects(
    () =>
      generateJson(
        [
          { key: "a", group: "same" },
          { key: "b", group: "same" },
          { key: "c", group: "same" },
        ],
        "test",
        "system",
        "input",
        {},
        {
          read: async (g) => map.get(g) || 0,
          write: async (g, t) => {
            map.set(g, t);
          },
        },
        async () => {
          calls++;
          return new Response("", {
            status: 429,
            headers: { "Retry-After": "30" },
          });
        },
      ),
    /busy/,
  );
  assert.equal(calls, 1);
});
test("temporary failure can use another configured key and malformed output never applies", async () => {
  let calls = 0;
  const cooldown = { read: async () => 0, write: async () => {} };
  const result = await generateJson(
    [
      { key: "a", group: "one" },
      { key: "b", group: "two" },
    ],
    "test",
    "system",
    "input",
    {},
    cooldown,
    async () => {
      calls++;
      return calls === 1
        ? new Response("", { status: 503 })
        : Response.json({
            candidates: [
              {
                content: { parts: [{ text: '{"message":"ok","actions":[]}' }] },
              },
            ],
          });
    },
  );
  assert.equal(result.message, "ok");
  assert.equal(calls, 2);
  await assert.rejects(
    () =>
      generateJson(
        [{ key: "a", group: "one" }],
        "test",
        "s",
        "i",
        {},
        cooldown,
        async () => Response.json({ candidates: [] }),
      ),
    /finish/,
  );
});

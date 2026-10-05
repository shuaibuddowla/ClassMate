import { test } from "node:test";
import assert from "node:assert/strict";
import { deliveryPayload } from "../../supabase/functions/dispatch-classmate-notifications/payload";
test("Android keeps its original high-priority data payload", () => {
  assert.deepEqual(deliveryPayload("token", { event_id: "event" }, "android"), {
    message: {
      token: "token",
      android: { priority: "HIGH", ttl: "86400s" },
      data: { event_id: "event" },
    },
  });
});
test("browser delivery is data-only, leaving a single display path", () => {
  const value = deliveryPayload("token", { event_id: "event" }, "web");
  assert.ok("webpush" in value.message);
  assert.ok(!("notification" in value.message));
  assert.ok(!("android" in value.message));
});
test("blood alerts expire at their deadline and stay data-only", () => {
  const oldNow = Date.now;
  Date.now = () => 1_000_000;
  try {
    const data = {
      kind: "blood_request",
      expires_at: new Date(1_120_000).toISOString(),
      event_id: "blood",
    };
    assert.deepEqual(
      deliveryPayload("token", data, "android").message.android,
      { priority: "HIGH", ttl: "120s" },
    );
    assert.equal(
      deliveryPayload("token", data, "web").message.webpush?.headers.TTL,
      "120",
    );
    assert.equal(
      deliveryPayload("token", { ...data, expires_at: "invalid" }, "web")
        .message.webpush?.headers.TTL,
      "0",
    );
    assert.equal(
      deliveryPayload(
        "token",
        { ...data, expires_at: new Date(1).toISOString() },
        "android",
      ).message.android?.ttl,
      "0s",
    );
  } finally {
    Date.now = oldNow;
  }
});

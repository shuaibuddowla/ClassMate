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

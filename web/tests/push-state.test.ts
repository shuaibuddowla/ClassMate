import { test } from "node:test";
import assert from "node:assert/strict";
import {
  pushEnabled,
  savePushPreference,
  setPushRegistrationState,
  pushRegistrationState,
} from "../src/lib/push-state";

test("permission and preference survive missing/rotating tokens and failed registration", () => {
  const cache = new Map<string, string>();
  (globalThis as any).localStorage = {
    getItem: (k: string) => cache.get(k) || null,
    setItem: (k: string, v: string) => cache.set(k, v),
  };
  const notification = { permission: "granted" };
  (globalThis as any).Notification = notification;
  (globalThis as any).window = Object.assign(new EventTarget(), {
    Notification: notification,
  });
  savePushPreference("alice", true);
  assert.equal(cache.get("classmate:push-disabled:alice"), "0");
  assert.equal(pushEnabled("alice"), true); // No token yet, no false switch reset.
  setPushRegistrationState("alice", "error");
  assert.equal(pushEnabled("alice"), true);
  assert.equal(pushRegistrationState("alice"), "error");
  savePushPreference("alice", false);
  assert.equal(pushEnabled("alice"), false);
  assert.equal(pushEnabled("bob"), true); // Preferences are account scoped.
  savePushPreference("alice", true);
  notification.permission = "denied";
  assert.equal(pushEnabled("alice"), false); // A cached preference cannot override the browser.
  notification.permission = "granted";
  assert.equal(pushEnabled("alice"), true);
});

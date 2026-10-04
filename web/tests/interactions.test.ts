import { test } from "node:test";
import assert from "node:assert/strict";
import { openResource } from "../src/lib/file-navigation";
import { requestPushPermission } from "../src/lib/push-permission";

test("desktop opens a safe tab before waiting for the private URL", async () => {
  const actions: string[] = [];
  const tab = {
    opener: {} as any,
    document: { title: "", body: { textContent: "" } },
    location: { replace: (url: string) => actions.push(url) },
    close: () => actions.push("closed"),
  };
  (globalThis as any).window = {
    matchMedia: () => ({ matches: true }),
    open: () => {
      actions.push("opened");
      return tab;
    },
  };
  await openResource(async () => {
    assert.equal(actions[0], "opened");
    assert.equal(tab.opener, null);
    return "https://example.com/private-file";
  });
  assert.deepEqual(actions, ["opened", "https://example.com/private-file"]);
});
test("mobile file stays in the current tab", async () => {
  let assigned = "";
  (globalThis as any).window = {
    matchMedia: () => ({ matches: false }),
    open: () => {
      throw new Error("Unexpected popup");
    },
    location: { assign: (url: string) => (assigned = url) },
  };
  await openResource(async () => "https://example.com/file");
  assert.equal(assigned, "https://example.com/file");
});
test("failed file signing closes its temporary tab and rejects unsafe URLs", async () => {
  let closed = 0;
  (globalThis as any).window = {
    matchMedia: () => ({ matches: true }),
    open: () => ({
      opener: null,
      document: { title: "", body: { textContent: "" } },
      close: () => closed++,
    }),
  };
  await assert.rejects(
    openResource(async () => {
      throw new Error("Access denied");
    }),
    /Access denied/,
  );
  await assert.rejects(
    openResource(async () => "javascript:alert(1)"),
    /Invalid file link/,
  );
  assert.equal(closed, 2);
});
test("blocked desktop popup reports a useful error before signing", async () => {
  (globalThis as any).window = {
    matchMedia: () => ({ matches: true }),
    open: () => null,
  };
  await assert.rejects(
    openResource(async () => {
      throw new Error("Should not request URL");
    }),
    /Allow pop-ups/,
  );
});
test("notification approval closes the prompt immediately; existing permission skips browser request", async () => {
  let closed = 0,
    requested = 0;
  (globalThis as any).Notification = {
    permission: "default",
    requestPermission: async () => {
      requested++;
      return "granted";
    },
  };
  await requestPushPermission(() => closed++);
  assert.equal(closed, 1);
  (globalThis as any).Notification.permission = "granted";
  await requestPushPermission(() => closed++);
  assert.equal(requested, 1);
  assert.equal(closed, 2);
});
test("denied notifications keep the prompt open and report the permission issue", async () => {
  (globalThis as any).Notification = {
    permission: "default",
    requestPermission: async () => "denied",
  };
  await assert.rejects(
    requestPushPermission(() => {
      throw new Error("Must not close");
    }),
    /Notifications are blocked/,
  );
});

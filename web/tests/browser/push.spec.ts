import { test, expect } from "@playwright/test";
test("worker suppresses duplicate events across reloads and rejects another account", async ({
  page,
  context,
}) => {
  await context.grantPermissions(["notifications"]);
  await page.goto("/");
  const setup = () =>
    page.evaluate(async () => {
      const r = await navigator.serviceWorker.ready;
      r.active!.postMessage({
        type: "identity",
        user: "web-worker-test",
        project: "qpryxvunhebwljlwmggk",
      });
    });
  await setup();
  await page.waitForTimeout(200);
  const worker = context.serviceWorkers()[0];
  await worker.evaluate(() => {
    const scope = globalThis as any;
    scope.testDisplays = 0;
    scope.registration.showNotification = async (
      title: string,
      options: any,
    ) => {
      scope.testDisplays++;
      scope.testTitle = title;
      scope.testBody = options.body;
    };
  });
  const event = await page.evaluate(() => crypto.randomUUID());
  const send = (recipient = "web-worker-test") =>
    page.evaluate(
      async ({ event, recipient }) => {
        const r = await navigator.serviceWorker.ready;
        r.active!.postMessage({
          type: "push",
          data: {
            event_id: event,
            recipient_id: recipient,
            project_ref: "qpryxvunhebwljlwmggk",
            title: "Isolated worker test",
            body: "No real FCM send",
          },
        });
      },
      { event, recipient },
    );
  await send();
  await send();
  await expect
    .poll(() => worker.evaluate(() => (globalThis as any).testDisplays))
    .toBe(1);
  expect(await worker.evaluate(() => (globalThis as any).testTitle)).toBe(
    new URL(page.url()).host,
  );
  expect(await worker.evaluate(() => (globalThis as any).testBody)).toContain(
    "No real FCM send",
  );
  await worker.evaluate(() => {
    (globalThis as any).testDisplays = 0;
  });
  await page.reload();
  await send();
  await page.waitForTimeout(300);
  expect(await worker.evaluate(() => (globalThis as any).testDisplays)).toBe(0);
  await send("another-account");
  await page.waitForTimeout(300);
  expect(await worker.evaluate(() => (globalThis as any).testDisplays)).toBe(0);
});

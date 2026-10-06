/// <reference lib="webworker" />
import { initializeApp } from "firebase/app";
import { getMessaging, onBackgroundMessage } from "firebase/messaging/sw";
declare const __FIREBASE_CONFIG__: any;
declare const __SHELL_VERSION__: string;
declare const self: ServiceWorkerGlobalScope;
const SHELL = `classmate-shell-${__SHELL_VERSION__}`;
function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("classmate-push", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("state");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
async function read(key: string) {
  const db = await database();
  return new Promise<any>((resolve, reject) => {
    const tx = db.transaction("state");
    const request = tx.objectStore("state").get(key);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    tx.oncomplete = () => db.close();
  });
}
async function write(key: string, value: any) {
  const db = await database();
  return new Promise<void>((resolve, reject) => {
    const tx = db.transaction("state", "readwrite");
    tx.objectStore("state").put(value, key);
    tx.oncomplete = () => {
      db.close();
      resolve();
    };
    tx.onerror = () => reject(tx.error);
  });
}
async function claim(key: string) {
  const db = await database();
  return new Promise<boolean>((resolve, reject) => {
    const tx = db.transaction("state", "readwrite"),
      store = tx.objectStore("state");
    let accepted = false;
    const request = store.get(key);
    request.onsuccess = () => {
      if (!request.result) {
        store.put(Date.now(), key);
        accepted = true;
      }
    };
    tx.oncomplete = () => {
      db.close();
      resolve(accepted);
    };
    tx.onerror = () => reject(tx.error);
  });
}
async function display(data: Record<string, string> | undefined) {
  if (!data?.event_id) return;
  if(data.kind === "blood_request" && (!data.expires_at || !Number.isFinite(Date.parse(data.expires_at)) || Date.parse(data.expires_at)<=Date.now()))return;
  const identity = await read("identity");
  if (
    !identity?.user ||
    identity.user !== data.recipient_id ||
    identity.project !== data.project_ref
  )
    return;
  if (!(await claim(`event:${data.recipient_id}:${data.event_id}`))) return;
  await self.registration.showNotification(self.location.host, {
    body: [
      data.title && !/^ClassMate(?: update)?$/i.test(data.title)
        ? data.title
        : "",
      data.body || "Your batch has a new update.",
    ]
      .filter(Boolean)
      .join("\n"),
    icon: "/icon-192.png",
    badge: "/icon-192.png",
    tag: data.event_id,
    ...(data.kind === "blood_request" ? { requireInteraction: true, actions: [...(data.blood_match === "true" ? [{action:"donate",title:"I can donate"}] : []),{action:"call",title:"Call attendant"}] } : {}),
    data: {
      kind: data.kind,
      record: data.record_id,
      batch: data.batch_id,
      event: data.event_id,
    },
  });
}
if (__FIREBASE_CONFIG__?.appId) {
  const app = initializeApp(__FIREBASE_CONFIG__);
  onBackgroundMessage(getMessaging(app), (payload) => display(payload.data));
}
async function cacheShell() {
  const cache = await caches.open(SHELL);
  const response = await fetch("/", { cache: "reload" });
  if (!response.ok)
    throw new Error("The offline shell could not be downloaded.");
  const html = await response.clone().text();
  const assets = new Set([
    "/logo.png",
    "/icon-192.png",
    "/icon-512.png",
    "/notice-general.svg",
    "/notice-resource.svg",
    "/notice-cancelled.svg",
  ]);
  for (const match of html.matchAll(/(?:src|href)="([^"\s]+)"/g)) {
    const url = new URL(
      match[1].replaceAll("&amp;", "&"),
      self.location.origin,
    );
    if (
      url.origin === self.location.origin &&
      url.pathname.startsWith("/_next/static/")
    )
      assets.add(url.pathname + url.search);
  }
  // Commit HTML after its scripts/styles are cached, so reopening works offline.
  await cache.addAll([...assets]);
  await cache.put("/", response);
}
self.addEventListener("install", (event) => {
  event.waitUntil(cacheShell().then(() => self.skipWaiting()));
});
self.addEventListener("activate", (event) =>
  event.waitUntil(
    Promise.all([
      self.clients.claim(),
      caches
        .keys()
        .then((keys) =>
          Promise.all(
            keys
              .filter((k) => k.startsWith("classmate-shell-") && k !== SHELL)
              .map((k) => caches.delete(k)),
          ),
        ),
    ]),
  ),
);
self.addEventListener("message", (event) => {
  if (event.data?.type === "identity")
    event.waitUntil(write("identity", event.data));
  if (event.data?.type === "signout") event.waitUntil(write("identity", null));
  if (event.data?.type === "push") event.waitUntil(display(event.data.data));
});
self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (
    event.request.method !== "GET" ||
    url.origin !== self.location.origin ||
    url.pathname.startsWith("/auth") ||
    url.pathname.startsWith("/api") ||
    url.pathname.startsWith("/download/")
  )
    return;
  if (event.request.mode === "navigate") {
    event.respondWith(
      fetch(event.request, { cache: "no-store" }).catch(async () => {
        const response = await caches.match("/");
        return response || Response.error();
      }),
    );
  } else if (
    url.pathname.startsWith("/_next/static/") ||
    /^\/(logo|icon-\d+)\.png$/.test(url.pathname) ||
    /^\/notice-(general|resource|cancelled)\.svg$/.test(url.pathname)
  ) {
    event.respondWith(
      caches.match(event.request).then(async (cached) => {
        if (cached) return cached;
        const response = await fetch(event.request);
        if (response.ok) {
          const cache = await caches.open(SHELL);
          await cache.put(event.request, response.clone());
        }
        return response;
      }),
    );
  }
});
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const kind = event.notification.data?.kind;
  const path =
    kind === "blood_request" ? "friends/blood" : ["release","app_update"].includes(kind) ? "profile" : kind === "file" ? "library" : "notices";
  const target =
    ["release","app_update"].includes(kind)
      ? "/#profile"
      : `/#${path}/${encodeURIComponent(event.notification.data?.record || "")}`;
  event.waitUntil(
    self.clients
      .matchAll({ type: "window", includeUncontrolled: true })
      .then(async (clients) => {
        const client = clients[0] as WindowClient | undefined;
        if (client) {
          await client.navigate(target);
          return client.focus();
        }
        return self.clients.openWindow(target);
      }),
  );
});

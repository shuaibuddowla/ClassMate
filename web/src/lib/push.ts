import { requestPushPermission } from "./push-permission";
import { rpc, mutation, edge } from "./api";
import {
  pushEnabled,
  savePushPreference,
  setPushRegistrationState,
} from "./push-state";
let foregroundStarted = false;
const registrations = new Map<string, Promise<void>>();
const disabledWhileRegistering = new Set<string>();
const removals = new Set<string>();
async function bounded<T>(
  promise: Promise<T>,
  milliseconds: number,
): Promise<T> {
  let timeout: ReturnType<typeof setTimeout>;
  try {
    return await Promise.race([
      promise,
      new Promise<never>((_, reject) => {
        timeout = setTimeout(
          () =>
            reject(
              new Error(
                "Notification connection timed out. Try again when online.",
              ),
            ),
          milliseconds,
        );
      }),
    ]);
  } finally {
    clearTimeout(timeout!);
  }
}
export async function testPush() {
  const token = localStorage.getItem("classmate:push-token");
  if (!token) throw new Error("Enable notifications on this browser first.");
  await edge("test-classmate-web-push", { token });
}
export async function syncPushIdentity(user: string, batch: string) {
  if (!("serviceWorker" in navigator)) return;
  const registration = await navigator.serviceWorker.ready;
  registration.active?.postMessage({
    type: "identity",
    user,
    batch,
    project: new URL(process.env.NEXT_PUBLIC_SUPABASE_URL!).hostname.split(
      ".",
    )[0],
  });
  if (!foregroundStarted && process.env.NEXT_PUBLIC_FIREBASE_CONFIG) {
    foregroundStarted = true;
    const { getMessaging, onMessage, isSupported } = await import(
      "firebase/messaging"
    );
    if (await isSupported()) {
      const { initializeApp, getApps } = await import("firebase/app");
      const app =
        getApps()[0] ||
        initializeApp(JSON.parse(process.env.NEXT_PUBLIC_FIREBASE_CONFIG));
      onMessage(getMessaging(app), (payload) =>
        registration.active?.postMessage({ type: "push", data: payload.data }),
      );
    }
  }
}
export async function enablePush(
  user: string,
  onPermissionGranted?: () => void,
) {
  if (!("Notification" in window) || !("serviceWorker" in navigator))
    throw new Error(
      "This browser does not support notifications. Use a supported browser or the Android app.",
    );
  const config = process.env.NEXT_PUBLIC_FIREBASE_CONFIG,
    key = process.env.NEXT_PUBLIC_FIREBASE_VAPID_KEY;
  if (!config || !key)
    throw new Error(
      "Browser notifications are awaiting Firebase web configuration.",
    );
  await requestPushPermission(() => {
    savePushPreference(user, true);
    disabledWhileRegistering.delete(user);
    onPermissionGranted?.();
  });
  await restorePush(user);
}
export async function restorePush(user: string) {
  if (
    removals.has(user) ||
    !navigator.onLine ||
    !pushEnabled(user) ||
    !("serviceWorker" in navigator)
  )
    return;
  const existing = registrations.get(user);
  if (existing) return existing;
  const task = registerPush(user);
  registrations.set(user, task);
  try {
    await task;
  } finally {
    registrations.delete(user);
  }
}
async function registerPush(user: string) {
  setPushRegistrationState(user, "pending");
  try {
    const config = process.env.NEXT_PUBLIC_FIREBASE_CONFIG,
      key = process.env.NEXT_PUBLIC_FIREBASE_VAPID_KEY;
    if (!config || !key)
      throw new Error(
        "Browser notifications are awaiting Firebase web configuration.",
      );
    const { getMessaging, getToken, isSupported } = await import(
      "firebase/messaging"
    );
    if (!(await isSupported()))
      throw new Error("Push is unavailable in this browser.");
    const { initializeApp, getApps } = await import("firebase/app");
    const app = getApps()[0] || initializeApp(JSON.parse(config));
    const registration = await bounded(navigator.serviceWorker.ready, 15_000);
    const token = await bounded(
      getToken(getMessaging(app), {
        vapidKey: key,
        serviceWorkerRegistration: registration,
      }),
      30_000,
    );
    if (!token) throw new Error("Could not register this browser.");
    if (disabledWhileRegistering.has(user) || !pushEnabled(user)) return;
    await rpc("register_web_device_token", { target_token: token });
    if (disabledWhileRegistering.has(user) || !pushEnabled(user)) {
      await mutation("device_tokens", "delete", { fcm_token: token });
      return;
    }
    localStorage.setItem("classmate:push-token", token);
    localStorage.setItem("classmate:push-token-user", user);
    setPushRegistrationState(user, "ready");
    registration.active?.postMessage({
      type: "identity",
      user,
      project: new URL(process.env.NEXT_PUBLIC_SUPABASE_URL!).hostname.split(
        ".",
      )[0],
    });
  } catch (error) {
    setPushRegistrationState(user, "error");
    throw error;
  }
}
export async function removePush(user?: string, saveDisabled = true) {
  const registrationUser =
    user || localStorage.getItem("classmate:push-token-user");
  if (registrationUser) {
    disabledWhileRegistering.add(registrationUser);
    removals.add(registrationUser);
  }
  if (user && saveDisabled) savePushPreference(user, false);
  try {
    if (registrationUser)
      await registrations.get(registrationUser)?.catch(() => {});
    window.dispatchEvent(new Event("classmate:push-state"));
    const token = localStorage.getItem("classmate:push-token");
    if ("serviceWorker" in navigator) {
      const registration = await bounded(navigator.serviceWorker.ready, 15_000);
      registration.active?.postMessage({ type: "signout" });
    }
    if (token) {
      await mutation("device_tokens", "delete", { fcm_token: token });
      const { getMessaging, deleteToken } = await import("firebase/messaging");
      const { getApps } = await import("firebase/app");
      if (getApps().length) await deleteToken(getMessaging(getApps()[0]));
      localStorage.removeItem("classmate:push-token");
      localStorage.removeItem("classmate:push-token-user");
    }
  } finally {
    if (registrationUser) {
      removals.delete(registrationUser);
      disabledWhileRegistering.delete(registrationUser);
    }
  }
}

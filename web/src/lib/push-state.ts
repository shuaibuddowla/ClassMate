// The user's preference is separate from the asynchronous delivery registration.
// Losing/refreshing an FCM token must not turn their preference off.
export function pushEnabled(user: string) {
  return (
    "Notification" in window &&
    Notification.permission === "granted" &&
    localStorage.getItem(`classmate:push-disabled:${user}`) !== "1"
  );
}
export function savePushPreference(user: string, enabled: boolean) {
  localStorage.setItem(`classmate:push-disabled:${user}`, enabled ? "0" : "1");
  window.dispatchEvent(new Event("classmate:push-state"));
}
export type PushRegistrationState = "pending" | "ready" | "error";
const registrationStates = new Map<string, PushRegistrationState>();
export function pushRegistrationState(user: string) {
  return registrationStates.get(user) || "pending";
}
export function setPushRegistrationState(
  user: string,
  state: PushRegistrationState,
) {
  registrationStates.set(user, state);
  window.dispatchEvent(new Event("classmate:push-state"));
}

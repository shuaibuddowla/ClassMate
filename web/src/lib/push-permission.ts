export async function requestPushPermission(onGranted?: () => void) {
  const permission =
    Notification.permission === "granted"
      ? "granted"
      : await Notification.requestPermission();
  if (permission !== "granted")
    throw new Error(
      "Notifications are blocked. You can enable them in browser settings.",
    );
  onGranted?.();
}

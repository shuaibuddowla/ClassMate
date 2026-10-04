import type { Row } from "./api";
const PREFIX = "classmate:academic:v1:";
const allowed = new Set([
  "routine",
  "notices",
  "calendar",
  "buses",
  "courses",
  "batches",
  "timetable-details",
]);
export function saveAcademic(
  user: string,
  batch: string,
  kind: string,
  data: unknown,
) {
  if (!allowed.has(kind.split(":")[0]))
    throw new Error("This data must not be cached offline.");
  try {
    localStorage.setItem(
      `${PREFIX}${user}:${batch}:${kind}`,
      JSON.stringify({ data, syncedAt: Date.now() }),
    );
  } catch {
    /* Storage may be disabled or full. */
  }
}
export function readAcademic(
  user: string,
  batch: string,
  kind: string,
): { data: any; syncedAt: number } | null {
  if (!allowed.has(kind.split(":")[0])) return null;
  try {
    return JSON.parse(
      localStorage.getItem(`${PREFIX}${user}:${batch}:${kind}`) || "null",
    );
  } catch {
    return null;
  }
}
export function clearAcademic() {
  for (const key of Object.keys(localStorage))
    if (key.startsWith(PREFIX)) localStorage.removeItem(key);
}
export function stripContacts(profile: Row) {
  const { mobile_number, home_town, blood_group, current_residence, ...safe } =
    profile;
  return safe;
}
export function saveIdentity(profile: Row) {
  localStorage.setItem("classmate:last-account", profile.id);
  localStorage.setItem(
    `classmate:identity:${profile.id}`,
    JSON.stringify(stripContacts(profile)),
  );
}
export function offlineIdentity(): Row | null {
  try {
    const id = localStorage.getItem("classmate:last-account");
    return id
      ? JSON.parse(localStorage.getItem(`classmate:identity:${id}`) || "null")
      : null;
  } catch {
    return null;
  }
}
export function clearIdentity() {
  for (const key of Object.keys(localStorage))
    if (
      key.startsWith("classmate:identity:") ||
      key === "classmate:last-account"
    )
      localStorage.removeItem(key);
}

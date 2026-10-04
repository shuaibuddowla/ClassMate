import type { Row } from "./api";
export function dateKey(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}
export function dhakaToday() {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Dhaka",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
}
export function parseDate(value: string) {
  return new Date(`${value}T12:00:00`);
}
export function scopesFor(date: string, events: Row[]) {
  return new Set(
    events
      .filter((e) => e.start_date <= date && e.end_date >= date)
      .map((e) => e.scope),
  );
}
export function busKind(date: string, events: Row[]) {
  const scopes = scopesFor(date, events);
  if (scopes.has("university")) return "closed";
  if (scopes.has("working_day")) return "office_open";
  return [4, 5].includes(parseDate(date).getDay()) ? "closed" : "office_open";
}
export function classesClosed(date: string, events: Row[]) {
  const scopes = scopesFor(date, events);
  return scopes.has("classes") || busKind(date, events) === "closed";
}
export function monthCells(year: number, month: number) {
  const offset = new Date(year, month, 1).getDay(),
    days = new Date(year, month + 1, 0).getDate();
  return Array.from({ length: Math.ceil((offset + days) / 7) * 7 }, (_, i) =>
    i >= offset && i < offset + days
      ? dateKey(new Date(year, month, i - offset + 1))
      : null,
  );
}
export const formatTime = (value: string) =>
  value
    ? new Date(`2000-01-01T${value}`).toLocaleTimeString("en-US", {
        hour: "2-digit",
        minute: "2-digit",
      })
    : "—";
export const formatStamp = (value: string) =>
  new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Dhaka",
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));

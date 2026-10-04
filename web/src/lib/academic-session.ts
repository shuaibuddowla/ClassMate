/** The database stores the email cohort's ending year, not a display range. */
export function academicSession(value: unknown): string {
  if (value == null || value === "" || value === "null") return "—";
  const text = String(value).trim();
  if (/^\d{2}-\d{2}$/.test(text)) return text;
  const end = Number(text);
  if (!Number.isInteger(end) || end < 0 || end > 99) return "—";
  return `${String((end + 99) % 100).padStart(2, "0")}-${String(end).padStart(2, "0")}`;
}

export function sessionEnd(value: unknown): number {
  const text = String(value).trim();
  const range = /^(\d{2})-(\d{2})$/.exec(text);
  const end = Number(range ? range[2] : text);
  if (
    !/^\d{1,2}$/.test(range ? range[2] : text) ||
    end > 99 ||
    (range && Number(range[1]) !== (end + 99) % 100)
  )
    throw new Error("Enter an academic session such as 24-25.");
  return end;
}

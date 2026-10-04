import { test } from "node:test";
import assert from "node:assert/strict";
import { monthCells, classesClosed, busKind } from "../src/lib/calendar";
test("every month has aligned 4, 5 or 6 rows and each real date exactly once", () => {
  for (let year = 2024; year <= 2032; year++)
    for (let month = 0; month < 12; month++) {
      const cells = monthCells(year, month);
      assert.ok([28, 35, 42].includes(cells.length));
      assert.equal(
        cells.filter(Boolean).length,
        new Date(year, month + 1, 0).getDate(),
      );
      assert.equal(cells.findIndex(Boolean), new Date(year, month, 1).getDay());
    }
});
test("leap day and four/six-row months", () => {
  assert.equal(monthCells(2024, 1).filter(Boolean).length, 29);
  assert.equal(monthCells(2026, 1).length, 28);
  assert.equal(monthCells(2026, 7).length, 42);
});
test("Thursday and Friday closed, Saturday open", () => {
  assert.equal(busKind("2026-10-08", []), "closed");
  assert.equal(busKind("2026-10-09", []), "closed");
  assert.equal(busKind("2026-10-10", []), "office_open");
});
test("class-only holidays do not change bus service", () => {
  const events = [
    { start_date: "2026-10-06", end_date: "2026-10-06", scope: "classes" },
  ];
  assert.equal(classesClosed("2026-10-06", events), true);
  assert.equal(busKind("2026-10-06", events), "office_open");
});
test("university closure overrides working day; working day opens weekends", () => {
  const events = [
    { start_date: "2026-10-09", end_date: "2026-10-09", scope: "working_day" },
  ];
  assert.equal(classesClosed("2026-10-09", events), false);
  assert.equal(
    busKind("2026-10-09", [...events, { ...events[0], scope: "university" }]),
    "closed",
  );
});

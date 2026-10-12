import test from "node:test";
import assert from "node:assert/strict";
import { compareStudentRolls } from "../src/lib/attendance-excel";

test("natural roll comparator sorts rolls in exact numerical order (CE25001... CE25032... CE25060)", () => {
  const input = [
    "CE25060",
    "CE25002",
    "CE25032",
    "CE25001",
    "CE25010",
    "CE25009",
    "CE25005",
  ];

  const sorted = [...input].sort(compareStudentRolls);

  assert.deepEqual(sorted, [
    "CE25001",
    "CE25002",
    "CE25005",
    "CE25009",
    "CE25010",
    "CE25032",
    "CE25060",
  ]);
});

test("natural roll comparator handles single vs double digit rolls and null/empty safely", () => {
  const input = [
    "CE22014",
    null,
    "CE25001",
    "",
    "CE22002",
    "CE25060",
    "CE22001",
  ];

  const sorted = [...input].sort(compareStudentRolls);

  assert.deepEqual(sorted, [
    "CE22001",
    "CE22002",
    "CE22014",
    "CE25001",
    "CE25060",
    null,
    "",
  ]);
});

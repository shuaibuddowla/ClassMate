import { test } from "node:test";
import assert from "node:assert/strict";
import {
  parseRawFundText,
  matchStudentToRoster,
  type RosterStudent,
} from "../src/lib/batch-fund-parser";

const mockRoster: RosterStudent[] = [
  { id: "prof-001", full_name: "Mehedi Hasan", student_id: "IT-21045" },
  { id: "prof-002", full_name: "Sadia Afrin", student_id: "IT-21005" },
  { id: "prof-003", full_name: "Zihadul Islam", student_id: "IT-21012" },
  { id: "prof-004", full_name: "Mosiur Rahman", student_id: "IT-21028" },
  { id: "prof-005", full_name: "Tanvir Ahmed", student_id: "IT-21033" },
  { id: "prof-006", full_name: "Tanvir Hasan", student_id: "IT-21039" },
];

test("batch fund smart parser detects deposits and expenses correctly", () => {
  const sample = `
Jersey Collection:
01. Zihadul - 440
02. Mosiur - 440 tk
Spent:
Market food - 2250
Banner print 350 tk
  `;

  const parsed = parseRawFundText(sample, mockRoster);
  assert.equal(parsed.length, 4);

  // Inflow 1: Zihadul matched to profile
  assert.equal(parsed[0].type, "inflow");
  assert.equal(parsed[0].amount, 440);
  assert.equal(parsed[0].student_name, "Zihadul Islam");
  assert.equal(parsed[0].student_profile_id, "prof-003");
  assert.equal(parsed[0].is_ambiguous, false);

  // Inflow 2: Mosiur matched to profile
  assert.equal(parsed[1].type, "inflow");
  assert.equal(parsed[1].amount, 440);
  assert.equal(parsed[1].student_name, "Mosiur Rahman");
  assert.equal(parsed[1].student_profile_id, "prof-004");
  assert.equal(parsed[1].is_ambiguous, false);

  // Outflow 1
  assert.equal(parsed[2].type, "outflow");
  assert.equal(parsed[2].amount, 2250);
  assert.equal(parsed[2].student_profile_id, undefined);

  // Outflow 2
  assert.equal(parsed[3].type, "outflow");
  assert.equal(parsed[3].amount, 350);
  assert.equal(parsed[3].student_profile_id, undefined);
});

test("ClassMate AI matches informal name 'mehedi-100' directly to student profile", () => {
  const sample = "mehedi-100";
  const parsed = parseRawFundText(sample, mockRoster);

  assert.equal(parsed.length, 1);
  assert.equal(parsed[0].type, "inflow");
  assert.equal(parsed[0].amount, 100);
  assert.equal(parsed[0].student_name, "Mehedi Hasan");
  assert.equal(parsed[0].student_id, "IT-21045");
  assert.equal(parsed[0].student_profile_id, "prof-001");
  assert.equal(parsed[0].is_ambiguous, false);
});

test("ClassMate AI matches roll number 'roll 05 - 500' to student profile", () => {
  const sample = "roll 05 - 500";
  const parsed = parseRawFundText(sample, mockRoster);

  assert.equal(parsed.length, 1);
  assert.equal(parsed[0].type, "inflow");
  assert.equal(parsed[0].amount, 500);
  assert.equal(parsed[0].student_name, "Sadia Afrin");
  assert.equal(parsed[0].student_id, "IT-21005");
  assert.equal(parsed[0].student_profile_id, "prof-002");
  assert.equal(parsed[0].is_ambiguous, false);
});

test("ClassMate AI flags ambiguity when multiple students share a name", () => {
  // Query 'Tanvir' matches both Tanvir Ahmed and Tanvir Hasan
  const sample = "Tanvir - 500";
  const parsed = parseRawFundText(sample, mockRoster);

  assert.equal(parsed.length, 1);
  assert.equal(parsed[0].type, "inflow");
  assert.equal(parsed[0].amount, 500);
  assert.equal(parsed[0].is_ambiguous, true);
  assert.equal(parsed[0].student_profile_id, undefined);
  assert.equal(parsed[0].candidate_matches?.length, 2);
  assert.deepEqual(
    parsed[0].candidate_matches?.map((c) => c.full_name),
    ["Tanvir Ahmed", "Tanvir Hasan"]
  );
});

test("Unknown student name is preserved without linking invalid profile", () => {
  const sample = "Stranger - 200";
  const parsed = parseRawFundText(sample, mockRoster);

  assert.equal(parsed.length, 1);
  assert.equal(parsed[0].type, "inflow");
  assert.equal(parsed[0].amount, 200);
  assert.equal(parsed[0].student_name, "Stranger");
  assert.equal(parsed[0].student_profile_id, undefined);
  assert.equal(parsed[0].is_ambiguous, false);
});

test("ClassMate AI handles Bengali numerals and currency symbol variations (৳500, ১০০, ৫০ টাকা)", () => {
  const sample = `
mehedi - ১০০
Sadia: ৳ 440
roll 05 - ৳500
বাজার: ২৫০০ টাকা
Food - ৳2500
500 tk - Sadia
  `;

  const parsed = parseRawFundText(sample, mockRoster);
  assert.equal(parsed.length, 6);

  // 1. mehedi - ১০০
  assert.equal(parsed[0].type, "inflow");
  assert.equal(parsed[0].amount, 100);
  assert.equal(parsed[0].student_name, "Mehedi Hasan");
  assert.equal(parsed[0].student_profile_id, "prof-001");

  // 2. Sadia: ৳ 440
  assert.equal(parsed[1].type, "inflow");
  assert.equal(parsed[1].amount, 440);
  assert.equal(parsed[1].student_name, "Sadia Afrin");
  assert.equal(parsed[1].student_profile_id, "prof-002");

  // 3. roll 05 - ৳500
  assert.equal(parsed[2].type, "inflow");
  assert.equal(parsed[2].amount, 500);
  assert.equal(parsed[2].student_name, "Sadia Afrin");
  assert.equal(parsed[2].student_profile_id, "prof-002");

  // 4. বাজার: ২৫০০ টাকা (outflow)
  assert.equal(parsed[3].type, "outflow");
  assert.equal(parsed[3].amount, 2500);

  // 5. Food - ৳2500 (outflow)
  assert.equal(parsed[4].type, "outflow");
  assert.equal(parsed[4].amount, 2500);

  // 6. 500 tk - Sadia (leading amount)
  assert.equal(parsed[5].type, "inflow");
  assert.equal(parsed[5].amount, 500);
  assert.equal(parsed[5].student_name, "Sadia Afrin");
  assert.equal(parsed[5].student_profile_id, "prof-002");
});


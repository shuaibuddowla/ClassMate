import { test } from "node:test";
import assert from "node:assert/strict";
import { academicSession, sessionEnd } from "../src/lib/academic-session";
test("email cohort renders the preceding academic year", () => {
  assert.equal(academicSession(25), "24-25");
  assert.equal(academicSession(26), "25-26");
  assert.equal(academicSession(0), "99-00");
  assert.equal(academicSession(9), "08-09");
  assert.equal(academicSession("24-25"), "24-25");
  assert.equal(academicSession(null), "—");
  assert.equal(academicSession("invalid"), "—");
});
test("session forms validate ranges and retain numeric backend compatibility", () => {
  assert.equal(sessionEnd("24-25"), 25);
  assert.equal(sessionEnd("25"), 25);
  assert.throws(() => sessionEnd("23-25"));
  assert.throws(() => sessionEnd(""));
});

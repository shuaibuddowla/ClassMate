import test from "node:test";
import assert from "node:assert/strict";
import { defaultTeacherGreeting } from "../src/components/schedule";

test("teacher greetings accurately return morning, noon, afternoon, and evening by calling sir across all hours", () => {
  // Morning: 5 AM - 11 AM
  for (let h = 5; h <= 11; h++) {
    const g = defaultTeacherGreeting(h);
    assert.equal(g.greeting, "Good morning, Sir");
    assert.equal(g.eyebrow, "TEACHING SCHEDULE");
  }

  // Noon: 12 PM - 2 PM (12 - 14)
  for (let h = 12; h <= 14; h++) {
    const g = defaultTeacherGreeting(h);
    assert.equal(g.greeting, "Good noon, Sir");
    assert.equal(g.eyebrow, "TEACHING SCHEDULE");
  }

  // Afternoon: 3 PM - 5 PM (15 - 17)
  for (let h = 15; h <= 17; h++) {
    const g = defaultTeacherGreeting(h);
    assert.equal(g.greeting, "Good afternoon, Sir");
    assert.equal(g.eyebrow, "TEACHING SCHEDULE");
  }

  // Evening: 6 PM - 10 PM (18 - 22)
  for (let h = 18; h <= 22; h++) {
    const g = defaultTeacherGreeting(h);
    assert.equal(g.greeting, "Good evening, Sir");
    assert.equal(g.eyebrow, "TEACHING SCHEDULE");
  }

  // Night / late hours: 23 - 4
  for (const h of [23, 0, 1, 2, 3, 4]) {
    const g = defaultTeacherGreeting(h);
    assert.equal(g.greeting, "Good evening, Sir");
    assert.equal(g.eyebrow, "TEACHING SCHEDULE");
  }
});

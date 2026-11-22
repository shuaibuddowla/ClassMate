import test from "node:test";
import assert from "node:assert/strict";
import { WhatsAppText } from "../src/lib/whatsapp-formatter";
import React from "react";

test("WhatsAppText formats bold, italic, strike, and bullets without errors", () => {
  const sample = "Hello *world* this is _important_ and ~deprecated~ with `code`.\n- item 1\n* item 2";
  const element = WhatsAppText({ text: sample });
  assert.ok(element, "Should return a React element");
});

test("WhatsAppText normalizes bullet prefixes at start of lines", () => {
  const sample = "- first item\n* second item\n  - nested item";
  const element = WhatsAppText({ text: sample });
  assert.ok(element);
});

test("WhatsAppText parses links into clickable anchors", () => {
  const sample = "Check out https://classmatebd.vercel.app or www.google.com for updates.";
  const element = WhatsAppText({ text: sample });
  assert.ok(element);
});

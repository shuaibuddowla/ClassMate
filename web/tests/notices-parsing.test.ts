import { test } from "node:test";
import assert from "node:assert/strict";

const urlRegex =
  /((?:https?:\/\/|www\.)[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-]|\b[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*\.(?:com|org|net|edu|gov|mil|app|dev|io|co|me|bd|ai|tech|xyz|info|biz|tv|cc|live|online|site|page|link|store|cloud|space|uk|us|ca|de|in|eu|au|fr|jp|gg|so|gl|ly|to|mobi|pro|name|asia|int|arpa)(?::[0-9]{1,5})?(?:\/[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-]|(?!\/)))/gi;

const isUrl = (s: string) =>
  /^(?:https?:\/\/|www\.)[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-]$/i.test(s) ||
  /^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*\.(?:com|org|net|edu|gov|mil|app|dev|io|co|me|bd|ai|tech|xyz|info|biz|tv|cc|live|online|site|page|link|store|cloud|space|uk|us|ca|de|in|eu|au|fr|jp|gg|so|gl|ly|to|mobi|pro|name|asia|int|arpa)(?::[0-9]{1,5})?(?:\/[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-])?$/i.test(s);

const getHref = (part: string) =>
  part.startsWith("http://") || part.startsWith("https://")
    ? part
    : `https://${part}`;

test("notice URL regex splits both http/https and www links correctly", () => {
  const body = "Please visit https://classmate.app and also check www.google.com for more info.";
  const parts = body.split(urlRegex);
  
  assert.equal(parts.length, 5);
  assert.equal(parts[0], "Please visit ");
  assert.equal(parts[1], "https://classmate.app");
  assert.equal(parts[2], " and also check ");
  assert.equal(parts[3], "www.google.com");
  assert.equal(parts[4], " for more info.");

  // Verify href resolution
  assert.equal(getHref("https://classmate.app"), "https://classmate.app");
  assert.equal(getHref("www.google.com"), "https://www.google.com");
});

test("notice URL regex detects naked domains like classmate.vercel.app", () => {
  const body = "Check classmate.vercel.app for notices and join meet.google.com/xyz.";
  const parts = body.split(urlRegex);

  assert.equal(isUrl("classmate.vercel.app"), true);
  assert.equal(getHref("classmate.vercel.app"), "https://classmate.vercel.app");
  assert.equal(isUrl("meet.google.com/xyz"), true);
  assert.equal(getHref("meet.google.com/xyz"), "https://meet.google.com/xyz");
  assert.ok(parts.includes("classmate.vercel.app"));
});

test("notice URL regex strips trailing punctuation like dots and commas and parentheses", () => {
  const body = "See https://classmate.app. Also (www.google.com), check it!";
  const parts = body.split(urlRegex);

  assert.equal(parts[1], "https://classmate.app");
  assert.equal(parts[2], ". Also (");
  assert.equal(parts[3], "www.google.com");
  assert.equal(parts[4], "), check it!");
});

test("notice overflow threshold accurately detects >6 lines or >180 characters", () => {
  const shortBody = "Short notice under six lines.";
  const shortLines = shortBody.split("\n");
  assert.equal(shortLines.length > 6 || shortBody.length > 180, false);

  const multilineBody = "1\n2\n3\n4\n5\n6\n7";
  const multilineLines = multilineBody.split("\n");
  assert.equal(multilineLines.length > 6 || multilineBody.length > 180, true);

  const longBody = "A".repeat(190);
  const longLines = longBody.split("\n");
  assert.equal(longLines.length > 6 || longBody.length > 180, true);
});

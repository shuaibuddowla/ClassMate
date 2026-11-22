"use client";

import React, { ReactNode } from "react";

// Regex for URLs
const URL_REGEX =
  /(?:https?:\/\/|www\.)[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-]|\b[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*\.(?:com|org|net|edu|gov|mil|app|dev|io|co|me|bd|ai|tech|xyz|info|biz|tv|cc|live|online|site|page|link|store|cloud|space|uk|us|ca|de|in|eu|au|fr|jp|gg|so|gl|ly|to|mobi|pro|name|asia|int|arpa)(?::[0-9]{1,5})?(?:\/[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-]|(?!\/))/;

// Search highlighter
export function HighlightText({
  text,
  search,
}: {
  text: string;
  search?: string;
}) {
  if (!search || !search.trim()) return <>{text}</>;
  const query = search.trim();
  const lower = text.toLowerCase();
  const qLower = query.toLowerCase();
  const parts: ReactNode[] = [];
  let lastIdx = 0;
  let idx = lower.indexOf(qLower, lastIdx);

  while (idx !== -1) {
    if (idx > lastIdx) {
      parts.push(text.slice(lastIdx, idx));
    }
    parts.push(
      <mark key={idx} className="highlight">
        {text.slice(idx, idx + query.length)}
      </mark>
    );
    lastIdx = idx + query.length;
    idx = lower.indexOf(qLower, lastIdx);
  }
  if (lastIdx < text.length) {
    parts.push(text.slice(lastIdx));
  }
  return <>{parts}</>;
}

interface MatchResult {
  type: "codeblock" | "inlinecode" | "url" | "bold" | "italic" | "strike";
  rawStart: number;
  rawEnd: number;
  content: string;
  url?: string;
}

function findNextEntity(text: string): MatchResult | null {
  const matches: MatchResult[] = [];

  // 1. Multiline code block: ```code```
  const codeBlockMatch = /```([\s\S]*?)```/.exec(text);
  if (codeBlockMatch) {
    matches.push({
      type: "codeblock",
      rawStart: codeBlockMatch.index,
      rawEnd: codeBlockMatch.index + codeBlockMatch[0].length,
      content: codeBlockMatch[1],
    });
  }

  // 2. Inline code: `code`
  const inlineCodeMatch = /(^|[\s([{"'`])`([^`\n]+)`(?=$|[\s)\]}'",.!?`])/.exec(
    text
  );
  if (inlineCodeMatch) {
    const prefixLen = inlineCodeMatch[1].length;
    matches.push({
      type: "inlinecode",
      rawStart: inlineCodeMatch.index + prefixLen,
      rawEnd: inlineCodeMatch.index + inlineCodeMatch[0].length,
      content: inlineCodeMatch[2],
    });
  }

  // 3. URL
  const urlMatch = URL_REGEX.exec(text);
  if (urlMatch) {
    matches.push({
      type: "url",
      rawStart: urlMatch.index,
      rawEnd: urlMatch.index + urlMatch[0].length,
      content: urlMatch[0],
      url:
        urlMatch[0].startsWith("http://") || urlMatch[0].startsWith("https://")
          ? urlMatch[0]
          : `https://${urlMatch[0]}`,
    });
  }

  // 4. Bold: *bold* or **bold**
  const boldMatch =
    /(^|[\s([{"'`])(?:\*\*|\*)([^\s\*](?:[^\*\n]*?[^\s\*])?)(?:\*\*|\*)(?=$|[\s)\]}'",.!?`])/.exec(
      text
    );
  if (boldMatch) {
    const prefixLen = boldMatch[1].length;
    matches.push({
      type: "bold",
      rawStart: boldMatch.index + prefixLen,
      rawEnd: boldMatch.index + boldMatch[0].length,
      content: boldMatch[2],
    });
  }

  // 5. Italic: _italic_
  const italicMatch =
    /(^|[\s([{"'`])_([^\s_](?:[^_\n]*?[^\s_])?)_(?=$|[\s)\]}'",.!?`])/.exec(
      text
    );
  if (italicMatch) {
    const prefixLen = italicMatch[1].length;
    matches.push({
      type: "italic",
      rawStart: italicMatch.index + prefixLen,
      rawEnd: italicMatch.index + italicMatch[0].length,
      content: italicMatch[2],
    });
  }

  // 6. Strikethrough: ~strike~ or ~~strike~~
  const strikeMatch =
    /(^|[\s([{"'`])(?:~~|~)([^\s~](?:[^~\n]*?[^\s~])?)(?:~~|~)(?=$|[\s)\]}'",.!?`])/.exec(
      text
    );
  if (strikeMatch) {
    const prefixLen = strikeMatch[1].length;
    matches.push({
      type: "strike",
      rawStart: strikeMatch.index + prefixLen,
      rawEnd: strikeMatch.index + strikeMatch[0].length,
      content: strikeMatch[2],
    });
  }

  if (matches.length === 0) return null;

  // Pick the match that starts earliest
  matches.sort((a, b) => a.rawStart - b.rawStart);
  return matches[0];
}

function renderContent(
  text: string,
  search?: string,
  depth = 0
): ReactNode[] {
  if (!text) return [];
  if (depth > 5) return [<HighlightText key="deep" text={text} search={search} />];

  const nodes: ReactNode[] = [];
  let remaining = text;
  let keyCounter = 0;

  while (remaining.length > 0) {
    const next = findNextEntity(remaining);
    if (!next) {
      nodes.push(
        <HighlightText
          key={`txt-${depth}-${keyCounter++}`}
          text={remaining}
          search={search}
        />
      );
      break;
    }

    if (next.rawStart > 0) {
      const leadingText = remaining.slice(0, next.rawStart);
      nodes.push(
        <HighlightText
          key={`txt-${depth}-${keyCounter++}`}
          text={leadingText}
          search={search}
        />
      );
    }

    const key = `ent-${depth}-${keyCounter++}`;
    switch (next.type) {
      case "codeblock":
        nodes.push(
          <pre key={key} className="wa-code-block">
            <code>
              <HighlightText text={next.content} search={search} />
            </code>
          </pre>
        );
        break;
      case "inlinecode":
        nodes.push(
          <code key={key} className="wa-inline-code">
            <HighlightText text={next.content} search={search} />
          </code>
        );
        break;
      case "url":
        nodes.push(
          <a
            key={key}
            href={next.url}
            target="_blank"
            rel="noopener noreferrer"
            className="wa-link"
            onClick={(e) => e.stopPropagation()}
          >
            <HighlightText text={next.content} search={search} />
          </a>
        );
        break;
      case "bold":
        nodes.push(
          <strong key={key} className="wa-bold">
            {renderContent(next.content, search, depth + 1)}
          </strong>
        );
        break;
      case "italic":
        nodes.push(
          <em key={key} className="wa-italic">
            {renderContent(next.content, search, depth + 1)}
          </em>
        );
        break;
      case "strike":
        nodes.push(
          <del key={key} className="wa-strike">
            {renderContent(next.content, search, depth + 1)}
          </del>
        );
        break;
    }

    remaining = remaining.slice(next.rawEnd);
  }

  return nodes;
}

export function WhatsAppText({
  text,
  search,
}: {
  text: string;
  search?: string;
}) {
  if (!text) return null;
  // Normalize bullet list markers (- or * at start of line followed by space) to bullet point •
  const normalized = text.replace(/(^|\n)([ \t]*)[-*][ \t]+/g, "$1$2• ");
  return <>{renderContent(normalized, search, 0)}</>;
}

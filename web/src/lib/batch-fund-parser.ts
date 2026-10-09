export type RosterStudent = {
  id: string;
  full_name: string;
  student_id?: string | null;
};

export type ParsedTransactionItem = {
  type: "inflow" | "outflow";
  amount: number;
  title: string;
  student_name?: string;
  student_id?: string;
  student_profile_id?: string;
  is_ambiguous?: boolean;
  candidate_matches?: RosterStudent[];
  transacted_at: string;
};

const BENGALI_DIGITS = ["০", "১", "২", "৩", "৪", "৫", "৬", "৭", "৮", "৯"];

export function cleanInvisibleChars(str: string): string {
  return str.replace(/[\u200B-\u200D\uFEFF\u200E\u200F\u202A-\u202E\u00A0]/g, " ");
}

export function normalizeBengaliNumerals(str: string): string {
  return str.replace(/[০-৯]/g, (ch) => {
    const idx = BENGALI_DIGITS.indexOf(ch);
    return idx >= 0 ? String(idx) : ch;
  });
}

/**
 * Intelligent roster matching for informal names, nicknames, and roll numbers.
 */
export function matchStudentToRoster(
  rawQuery: string,
  roster: RosterStudent[]
): {
  matched: RosterStudent | null;
  is_ambiguous: boolean;
  candidates: RosterStudent[];
} {
  if (!rawQuery.trim() || !roster.length) {
    return { matched: null, is_ambiguous: false, candidates: [] };
  }

  const clean = cleanInvisibleChars(rawQuery).trim().toLowerCase();

  // 1. Try matching by roll number (e.g. "05", "roll 05", "5")
  const rollMatch = clean.match(/(?:roll\s*#?|^|\s)(\d{1,5})(?:\s|$)/i);
  if (rollMatch) {
    const rollDigits = rollMatch[1];
    const rollPadded = rollDigits.padStart(2, "0");
    const idMatches = roster.filter((s) => {
      if (!s.student_id) return false;
      const sId = s.student_id.toLowerCase();
      return (
        sId === rollDigits ||
        sId.endsWith(rollDigits) ||
        sId.endsWith(rollPadded) ||
        sId.includes(`-${rollDigits}`) ||
        sId.includes(`-${rollPadded}`)
      );
    });

    if (idMatches.length === 1) {
      return { matched: idMatches[0], is_ambiguous: false, candidates: [] };
    }
    if (idMatches.length > 1) {
      return { matched: null, is_ambiguous: true, candidates: idMatches };
    }
  }

  // 2. Exact full name match (case-insensitive)
  const exactNameMatches = roster.filter(
    (s) => s.full_name.trim().toLowerCase() === clean
  );
  if (exactNameMatches.length === 1) {
    return { matched: exactNameMatches[0], is_ambiguous: false, candidates: [] };
  }

  // 3. Name token match (e.g. "mehedi" in "Mehedi Hasan", supports Unicode/Bengali)
  const queryTokens = clean
    .replace(/[^\p{L}\p{M}0-9\s]/gu, " ")
    .split(/\s+/)
    .filter((t) => t.length >= 3 && isNaN(Number(t)));

  if (queryTokens.length > 0) {
    const tokenMatches = roster.filter((s) => {
      const sName = s.full_name.toLowerCase();
      const sTokens = sName.split(/\s+/);
      // Check if all query tokens appear in student name tokens
      return queryTokens.every((qt) =>
        sTokens.some((st) => st.startsWith(qt) || st.includes(qt))
      );
    });

    if (tokenMatches.length === 1) {
      return { matched: tokenMatches[0], is_ambiguous: false, candidates: [] };
    }
    if (tokenMatches.length > 1) {
      return { matched: null, is_ambiguous: true, candidates: tokenMatches };
    }
  }

  return { matched: null, is_ambiguous: false, candidates: [] };
}

/**
 * Robust fallback parser for batch fund pastes, with batch roster intelligence.
 */
export function parseRawFundText(
  text: string,
  roster: RosterStudent[] = []
): ParsedTransactionItem[] {
  const lines = text.split("\n").map((l) => l.trim()).filter(Boolean);
  const results: ParsedTransactionItem[] = [];
  let currentSectionType: "inflow" | "outflow" = "inflow";
  const today = new Date().toISOString().slice(0, 10);

  // Patterns supporting leading or trailing currency signs and separators
  const endAmountPattern = /(?:[-–:]\s*)?(?:(?:tk|taka|টাকা|৳)\s*)?([0-9]+(?:[.,][0-9]{1,2})?)\s*(?:tk|taka|টাকা|৳)?\s*$/i;
  const startAmountPattern = /^\s*(?:[-–:]\s*)?(?:(?:tk|taka|টাকা|৳)\s*)?([0-9]+(?:[.,][0-9]{1,2})?)\s*(?:tk|taka|টাকা|৳)?(?:\s*[-–:]\s*|\s+)/i;

  for (const rawLine of lines) {
    const cleanedLine = cleanInvisibleChars(rawLine).trim();
    const normalizedLine = normalizeBengaliNumerals(cleanedLine);
    const lower = normalizedLine.toLowerCase();

    // Section header detection
    if (
      lower.includes("spent") ||
      lower.includes("expense") ||
      lower.includes("cost") ||
      lower.includes("খরচ") ||
      lower.includes("market") ||
      lower.includes("bazar")
    ) {
      if (!/\d+/.test(normalizedLine)) {
        currentSectionType = "outflow";
        continue;
      }
    } else if (
      lower.includes("deposit") ||
      lower.includes("collection") ||
      lower.includes("paid") ||
      lower.includes("জমা")
    ) {
      if (!/\d+/.test(normalizedLine)) {
        currentSectionType = "inflow";
        continue;
      }
    }

    // Match amount at end first, then at start
    let match = normalizedLine.match(endAmountPattern);
    if (!match || isNaN(parseFloat(match[1]))) {
      match = normalizedLine.match(startAmountPattern);
    }

    if (match) {
      const amountStr = match[1].replace(/,/g, "");
      const amount = parseFloat(amountStr);

      if (!isNaN(amount) && amount > 0) {
        const isNegative = /(?:^|\s)-[0-9]/.test(normalizedLine);
        const isOutflow =
          isNegative ||
          currentSectionType === "outflow" ||
          lower.includes("market") ||
          lower.includes("bazar") ||
          lower.includes("বাজার") ||
          lower.includes("খরচ") ||
          lower.includes("spent") ||
          lower.includes("cost") ||
          lower.includes("expense") ||
          lower.includes("food") ||
          lower.includes("buy") ||
          lower.includes("print");

        let rawDesc = normalizedLine
          .replace(match[0], "")
          .replace(/^[0-9]+[.)\-:]\s*/, "")
          .replace(/^[-–:]+\s*/, "")
          .replace(/[-–:]+$/, "")
          .trim();

        if (!rawDesc) {
          rawDesc = isOutflow ? "General Expense" : "Batch Contribution";
        }

        if (isOutflow) {
          results.push({
            type: "outflow",
            amount,
            title: rawDesc,
            transacted_at: today,
          });
        } else {
          // Inflow: perform roster lookup
          const { matched, is_ambiguous, candidates } = matchStudentToRoster(
            rawDesc,
            roster
          );

          results.push({
            type: "inflow",
            amount,
            title: "Batch contribution",
            student_name: matched ? matched.full_name : rawDesc,
            student_id: matched?.student_id || undefined,
            student_profile_id: matched?.id || undefined,
            is_ambiguous,
            candidate_matches: candidates.length ? candidates : undefined,
            transacted_at: today,
          });
        }
      }
    }
  }

  return results;
}

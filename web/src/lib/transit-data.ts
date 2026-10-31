export interface MbstuBusSchedule {
  busId: string;
  nameBn: string;
  fromCampus: string;
  toCampus: string;
  fromCampusMin: number;
  toCampusMin: number;
  campusToDistrict?: string[];
  districtToCampus?: string[];
  pilotContact?: string;
}

export const KNOWN_BUS_INFO: Record<string, { nameBn: string; nameEn: string; color: string; defaultRoute: string }> = {
  SB0001: { nameBn: "গীতাঞ্জলি", nameEn: "Gitanjali", color: "#6366f1", defaultRoute: "ক্যাম্পাস ⇄ নিরালা (শহর কেন্দ্র)" },
  SB0002: { nameBn: "অগ্নিবীণা", nameEn: "Agnibina", color: "#f97316", defaultRoute: "ক্যাম্পাস ⇄ নিরালা (শহর কেন্দ্র)" },
  SB0003: { nameBn: "সোনারতরী", nameEn: "Sonartori", color: "#0284c7", defaultRoute: "ক্যাম্পাস ⇄ নিরালা (শহর কেন্দ্র)" },
  SB0005: { nameBn: "সংশপ্তক", nameEn: "Shangshaptak", color: "#8b5cf6", defaultRoute: "মাভাবিপ্রবি পরিবহন করিডোর" },
  SB0007: { nameBn: "ধলেশ্বরী", nameEn: "Dhaleshwari", color: "#10b981", defaultRoute: "ক্যাম্পাস ⇄ টাঙ্গাইল পুরাতন বাসস্ট্যান্ড" },
  SB0009: { nameBn: "বনলতা", nameEn: "Banolata", color: "#059669", defaultRoute: "ক্যাম্পাস ⇄ কাগমারী ⇄ নিরালা" },
  SB0010: { nameBn: "রক্তকরবী", nameEn: "Raktakarabi", color: "#ec4899", defaultRoute: "মাভাবিপ্রবি পরিবহন করিডোর" },
  SB5442: { nameBn: "সঞ্চয়িতা", nameEn: "Sanchayita", color: "#0ea5e9", defaultRoute: "ক্যাম্পাস ⇄ নিরালা (শহর কেন্দ্র)" },
  SB5448: { nameBn: "রাখালী", nameEn: "Rakhali", color: "#e11d48", defaultRoute: "ক্যাম্পাস ⇄ নিরালা (শহর কেন্দ্র)" },
  SB5488: { nameBn: "রাখালী", nameEn: "Rakhali", color: "#e11d48", defaultRoute: "ক্যাম্পাস ⇄ নিরালা (শহর কেন্দ্র)" },
};

export const GPS_IMEI_TO_BUS: Record<string, string> = {
  "353701091171641": "SB0001",
  "353701091171104": "SB0002",
  "9175769656": "SB0003",
  "7026087428": "SB0005",
  "7026087352": "SB0009",
  "353701091171799": "SB0010",
  "B20": "SB5442",
  "BUS21": "SB5448",
};

export const MBSTU_BUS_SCHEDULES: Record<string, MbstuBusSchedule> = {
  SB0001: {
    busId: "SB0001",
    nameBn: "গীতাঞ্জলি",
    fromCampus: "8:00 AM",
    toCampus: "8:30 AM",
    fromCampusMin: 8 * 60,
    toCampusMin: 8 * 60 + 30,
    campusToDistrict: ["8:00 AM", "9:00 AM", "1:10 PM", "2:30 PM"],
    districtToCampus: ["8:30 AM", "9:30 AM", "1:40 PM", "3:00 PM"],
    pilotContact: "",
  },
  SB0002: {
    busId: "SB0002",
    nameBn: "অগ্নিবীণা",
    fromCampus: "8:00 AM",
    toCampus: "8:30 AM",
    fromCampusMin: 8 * 60,
    toCampusMin: 8 * 60 + 30,
    campusToDistrict: ["8:00 AM", "9:00 AM", "10:00 AM", "1:10 PM", "2:30 PM"],
    districtToCampus: ["8:30 AM", "9:30 AM", "10:30 AM", "1:40 PM", "3:00 PM"],
    pilotContact: "01721162819",
  },
  SB0003: {
    busId: "SB0003",
    nameBn: "সোনারতরী",
    fromCampus: "4:10 PM",
    toCampus: "4:40 PM",
    fromCampusMin: 16 * 60 + 10,
    toCampusMin: 16 * 60 + 40,
    campusToDistrict: ["4:10 PM", "5:15 PM", "6:20 PM", "8:10 PM"],
    districtToCampus: ["4:40 PM", "5:45 PM", "7:00 PM", "8:50 PM"],
    pilotContact: "01716658738",
  },
  SB0005: {
    busId: "SB0005",
    nameBn: "সংশপ্তক",
    fromCampus: "8:00 AM",
    toCampus: "8:30 AM",
    fromCampusMin: 8 * 60,
    toCampusMin: 8 * 60 + 30,
    campusToDistrict: ["8:00 AM", "9:00 AM", "1:10 PM", "2:30 PM"],
    districtToCampus: ["8:30 AM", "9:30 AM", "1:40 PM", "3:00 PM"],
    pilotContact: "01799148449",
  },
  SB0009: {
    busId: "SB0009",
    nameBn: "বনলতা",
    fromCampus: "4:10 PM",
    toCampus: "4:40 PM",
    fromCampusMin: 16 * 60 + 10,
    toCampusMin: 16 * 60 + 40,
    campusToDistrict: ["4:10 PM", "5:15 PM", "6:20 PM", "8:10 PM"],
    districtToCampus: ["4:40 PM", "5:45 PM", "7:00 PM", "8:50 PM"],
    pilotContact: "01719221530",
  },
  SB0010: {
    busId: "SB0010",
    nameBn: "রক্তকরবী",
    fromCampus: "10:00 AM",
    toCampus: "10:30 AM",
    fromCampusMin: 10 * 60,
    toCampusMin: 10 * 60 + 30,
    campusToDistrict: ["10:00 AM", "4:10 PM"],
    districtToCampus: ["10:30 AM", "4:40 PM"],
    pilotContact: "01688686781",
  },
  SB5442: {
    busId: "SB5442",
    nameBn: "সঞ্চয়িতা",
    fromCampus: "4:10 PM",
    toCampus: "4:40 PM",
    fromCampusMin: 16 * 60 + 10,
    toCampusMin: 16 * 60 + 40,
    campusToDistrict: ["4:10 PM", "5:15 PM", "6:20 PM", "8:10 PM"],
    districtToCampus: ["4:40 PM", "5:45 PM", "7:00 PM", "8:50 PM"],
    pilotContact: "01710802028",
  },
  SB5448: {
    busId: "SB5448",
    nameBn: "রাখালী",
    fromCampus: "2:30 PM",
    toCampus: "3:00 PM",
    fromCampusMin: 14 * 60 + 30,
    toCampusMin: 15 * 60,
    campusToDistrict: ["2:30 PM", "4:10 PM", "5:15 PM", "6:20 PM"],
    districtToCampus: ["3:00 PM", "4:40 PM", "5:45 PM", "7:00 PM"],
    pilotContact: "01710802028",
  },
  SB5488: {
    busId: "SB5488",
    nameBn: "রাখালী",
    fromCampus: "2:30 PM",
    toCampus: "3:00 PM",
    fromCampusMin: 14 * 60 + 30,
    toCampusMin: 15 * 60,
    campusToDistrict: ["2:30 PM", "4:10 PM", "5:15 PM", "6:20 PM"],
    districtToCampus: ["3:00 PM", "4:40 PM", "5:45 PM", "7:00 PM"],
    pilotContact: "01710802028",
  },
};

export function parseScheduleTimeToMinutes(tStr: string): number | null {
  const m = tStr.trim().match(/^(\d{1,2}):(\d{2})\s*(AM|PM)$/i);
  if (!m) return null;
  let h = parseInt(m[1], 10);
  const min = parseInt(m[2], 10);
  const ampm = m[3].toUpperCase();
  if (ampm === "PM" && h < 12) h += 12;
  if (ampm === "AM" && h === 12) h = 0;
  return h * 60 + min;
}

export function computeNextDeparture(times: string[] | undefined, currentMin: number): { str: string; min: number } {
  if (!times || times.length === 0) return { str: "--:--", min: 0 };
  const parsed = times
    .map((t) => ({ str: t, min: parseScheduleTimeToMinutes(t) }))
    .filter((x): x is { str: string; min: number } => x.min !== null);
  parsed.sort((a, b) => a.min - b.min);
  const upcoming = parsed.find((x) => x.min >= currentMin);
  return upcoming || parsed[0] || { str: "--:--", min: 0 };
}

export function getTangailMinutes(nowMs: number = Date.now()): number {
  const d = new Date(nowMs + 6 * 3600 * 1000 + new Date().getTimezoneOffset() * 60 * 1000);
  return d.getHours() * 60 + d.getMinutes();
}

export function toBengaliDigits(num: number | string): string {
  const bnDigits = ["০", "১", "২", "৩", "৪", "৫", "৬", "৭", "৮", "৯"];
  return String(num).replace(/[0-9]/g, (d) => bnDigits[parseInt(d, 10)] || d);
}

export function getScheduleRemaining(targetMin: number, nowMs: number): { text: string; isPast: boolean; diffMin: number } {
  const d = new Date(nowMs + 6 * 3600 * 1000 + new Date().getTimezoneOffset() * 60 * 1000);
  const currentMin = d.getHours() * 60 + d.getMinutes();
  let diffMin = targetMin - currentMin;
  if (diffMin < -720) {
    diffMin += 1440;
  }
  if (diffMin >= 0) {
    const hours = Math.floor(diffMin / 60);
    const mins = diffMin % 60;
    if (hours > 0) {
      return { text: `${toBengaliDigits(hours)}ঘণ্টা ${toBengaliDigits(mins)}মি বাকি`, isPast: false, diffMin };
    }
    return { text: `${toBengaliDigits(mins)}মি বাকি`, isPast: false, diffMin };
  } else {
    const pastMin = Math.abs(diffMin);
    if (pastMin < 60) {
      return { text: `${toBengaliDigits(pastMin)}মি আগে ছেড়েছে`, isPast: true, diffMin };
    }
    return { text: `${toBengaliDigits(Math.floor(pastMin / 60))}ঘণ্টা আগে ছেড়েছে`, isPast: true, diffMin };
  }
}

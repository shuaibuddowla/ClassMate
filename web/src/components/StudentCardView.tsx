import { Metadata } from "next";
import { createClient } from "@supabase/supabase-js";
import {
  CreditCard,
  Phone,
  MessageCircle,
  ShieldCheck,
  AlertCircle,
  GraduationCap,
  Building2,
  Droplet,
  CheckCircle2,
  Download
} from "lucide-react";

export interface StudentCardResult {
  found: boolean;
  card_uid?: string;
  student_id?: string;
  full_name?: string;
  roll?: string;
  phone?: string;
  avatar_url?: string;
  batch_name?: string;
  blood_group?: string;
  department_name?: string;
  attendance_percentage?: number;
}

function getSupabase() {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || "https://cbskpifgbdbgdmnnxfrh.supabase.co";
  const key = process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || "";
  return createClient(url, key, { auth: { persistSession: false } });
}

export async function fetchStudentByCard(cardUid: string): Promise<StudentCardResult | null> {
  const clean = cardUid.trim().toLowerCase();
  if (!clean || !/^[0-9a-f]{4,32}$/i.test(clean)) {
    return null;
  }

  const db = getSupabase();
  try {
    const { data, error } = await db.schema("classmate").rpc("lookup_student_by_card", { p_card_uid: clean });
    if (error || !data) {
      const { data: fallbackData } = await db.rpc("lookup_student_by_card", { p_card_uid: clean });
      if (fallbackData && (fallbackData as any).found) {
        return fallbackData as StudentCardResult;
      }
      return null;
    }
    const res = data as any;
    if (res.found) {
      return res as StudentCardResult;
    }
    return null;
  } catch {
    return null;
  }
}

export async function buildStudentCardMetadata(id: string): Promise<Metadata> {
  const student = await fetchStudentByCard(id);

  if (!student) {
    return {
      title: "ClassMate Smart ID · Unregistered Card",
      description: "ClassMate NFC Student Smart ID card for Mawlana Bhashani Science and Technology University.",
    };
  }

  const name = student.full_name || "MBSTU Student";
  const roll = student.roll ? ` (${student.roll})` : "";
  const dept = student.department_name || "MBSTU";

  return {
    title: `${name}${roll} · ClassMate Smart ID`,
    description: `${dept} · Batch ${student.batch_name || "Campus"} · MBSTU Verified Student Identity Card`,
    openGraph: {
      title: `${name}${roll} · ClassMate Smart ID`,
      description: `${dept} · Batch ${student.batch_name || "Campus"}`,
      url: `https://classmatebd.vercel.app/c/${id}`,
      siteName: "ClassMate",
      type: "profile",
      images: student.avatar_url ? [{ url: student.avatar_url, alt: name }] : [],
    },
  };
}

function getInitials(name?: string, roll?: string): string {
  if (name && name.trim().length > 0) {
    const parts = name.trim().split(/\s+/);
    if (parts.length >= 2) {
      return (parts[0][0] + parts[1][0]).toUpperCase();
    }
    return parts[0].substring(0, 2).toUpperCase();
  }
  return roll?.substring(0, 2).toUpperCase() || "ST";
}

export function StudentCardView({
  id,
  student,
}: {
  id: string;
  student: StudentCardResult | null;
}) {
  const cleanId = id.trim().toUpperCase();

  // Clean phone number for tel: and wa.me links
  const rawPhone = student?.phone?.trim() || "";
  const cleanPhone = rawPhone.replace(/[^0-9+]/g, "");
  const waNumber = cleanPhone.replace(/^\+/, "");
  const hasPhone = cleanPhone.length >= 7 && cleanPhone.toLowerCase() !== "null";

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col items-center justify-between p-4 sm:p-6 md:p-8 font-sans selection:bg-emerald-500/30">
      {/* Background ambient glow */}
      <div className="fixed inset-0 pointer-events-none overflow-hidden z-0">
        <div className="absolute top-1/4 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-emerald-600/10 blur-[130px] rounded-full" />
        <div className="absolute bottom-1/4 left-1/2 -translate-x-1/2 translate-y-1/2 w-[400px] h-[400px] bg-teal-600/10 blur-[120px] rounded-full" />
      </div>

      {/* Top Navbar */}
      <header className="w-full max-w-lg flex items-center justify-between py-3 relative z-10 border-b border-slate-800/80">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-emerald-500 to-teal-400 flex items-center justify-center text-slate-950 font-black text-sm shadow-lg shadow-emerald-500/20">
            CM
          </div>
          <div>
            <span className="font-bold text-sm tracking-wide text-white">ClassMate</span>
            <span className="text-[10px] text-emerald-400 block font-medium uppercase tracking-wider">Smart Campus ID</span>
          </div>
        </div>
        <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-slate-900/90 border border-slate-800 text-xs text-slate-400">
          <CreditCard className="w-3.5 h-3.5 text-emerald-400" />
          <span className="font-mono text-[11px]">••••{cleanId.slice(-4)}</span>
        </div>
      </header>

      {/* Main Content Area */}
      <main className="w-full max-w-md my-auto py-8 relative z-10">
        {student ? (
          /* Registered Student Card */
          <div className="relative rounded-3xl bg-gradient-to-b from-slate-900/90 to-slate-950/95 border border-slate-800/90 shadow-2xl shadow-emerald-950/20 backdrop-blur-xl overflow-hidden p-6 sm:p-8">
            {/* Top decorative accent bar */}
            <div className="absolute top-0 left-0 right-0 h-1.5 bg-gradient-to-r from-emerald-500 via-teal-400 to-cyan-500" />

            {/* University Tag */}
            <div className="text-center mb-6">
              <span className="text-[11px] font-semibold text-emerald-400 tracking-wider uppercase inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-emerald-500/10 border border-emerald-500/20">
                <ShieldCheck className="w-3.5 h-3.5" />
                Verified Student Identity
              </span>
              <p className="text-xs text-slate-400 mt-1 font-medium">Mawlana Bhashani Science & Technology University</p>
            </div>

            {/* Avatar & Core Profile */}
            <div className="flex flex-col items-center text-center">
              <div className="relative mb-4">
                {student.avatar_url && student.avatar_url.startsWith("http") ? (
                  <img
                    src={student.avatar_url}
                    alt={student.full_name || "Student"}
                    className="w-24 h-24 rounded-2xl object-cover border-2 border-emerald-500/40 shadow-xl shadow-emerald-500/10"
                  />
                ) : (
                  <div className="w-24 h-24 rounded-2xl bg-gradient-to-br from-emerald-600 to-teal-700 flex items-center justify-center text-2xl font-black text-white border-2 border-emerald-400/30 shadow-xl">
                    {getInitials(student.full_name, student.roll)}
                  </div>
                )}
                <div className="absolute -bottom-1.5 -right-1.5 bg-emerald-500 text-slate-950 p-1 rounded-full shadow-md">
                  <CheckCircle2 className="w-4 h-4" />
                </div>
              </div>

              <h1 className="text-xl sm:text-2xl font-bold text-white tracking-tight leading-snug">
                {student.full_name || "MBSTU Student"}
              </h1>

              {student.roll && (
                <div className="mt-1.5 inline-block px-3 py-0.5 rounded-lg bg-slate-800/80 border border-slate-700/80 font-mono text-sm font-semibold text-emerald-400 tracking-wider">
                  {student.roll}
                </div>
              )}
            </div>

            {/* Details Pills Grid */}
            <div className="grid grid-cols-2 gap-2.5 mt-6 pt-5 border-t border-slate-800/80">
              {student.department_name && (
                <div className="col-span-2 p-3 rounded-xl bg-slate-900/60 border border-slate-800/70 flex items-center gap-3">
                  <div className="p-2 rounded-lg bg-emerald-500/10 text-emerald-400 shrink-0">
                    <Building2 className="w-4 h-4" />
                  </div>
                  <div className="min-w-0">
                    <span className="text-[10px] text-slate-400 uppercase tracking-wider block font-semibold">Department</span>
                    <span className="text-xs font-semibold text-slate-200 truncate block">{student.department_name}</span>
                  </div>
                </div>
              )}

              {student.batch_name && (
                <div className="p-3 rounded-xl bg-slate-900/60 border border-slate-800/70 flex items-center gap-2.5">
                  <div className="p-2 rounded-lg bg-cyan-500/10 text-cyan-400 shrink-0">
                    <GraduationCap className="w-4 h-4" />
                  </div>
                  <div>
                    <span className="text-[10px] text-slate-400 uppercase tracking-wider block font-semibold">Batch</span>
                    <span className="text-xs font-semibold text-slate-200">{student.batch_name}</span>
                  </div>
                </div>
              )}

              {student.blood_group && (
                <div className="p-3 rounded-xl bg-slate-900/60 border border-slate-800/70 flex items-center gap-2.5">
                  <div className="p-2 rounded-lg bg-rose-500/10 text-rose-400 shrink-0">
                    <Droplet className="w-4 h-4" />
                  </div>
                  <div>
                    <span className="text-[10px] text-slate-400 uppercase tracking-wider block font-semibold">Blood Group</span>
                    <span className="text-xs font-bold text-rose-400">{student.blood_group}</span>
                  </div>
                </div>
              )}

              {typeof student.attendance_percentage === "number" && (
                <div className="col-span-2 p-3 rounded-xl bg-emerald-950/20 border border-emerald-900/30 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
                    <span className="text-xs font-medium text-emerald-200">Verified Attendance</span>
                  </div>
                  <span className="font-mono text-sm font-bold text-emerald-400">
                    {student.attendance_percentage.toFixed(1)}%
                  </span>
                </div>
              )}
            </div>

            {/* Contact Actions (Direct Call & WhatsApp) */}
            <div className="mt-6 pt-5 border-t border-slate-800/80">
              {hasPhone ? (
                <div>
                  <span className="text-[11px] text-slate-400 block mb-2.5 font-medium text-center">
                    Contact the student directly:
                  </span>
                  <div className="grid grid-cols-2 gap-2.5">
                    <a
                      href={`tel:${cleanPhone}`}
                      className="flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-bold text-xs transition shadow-lg shadow-emerald-500/20 active:scale-95"
                    >
                      <Phone className="w-4 h-4" />
                      Call Student
                    </a>
                    <a
                      href={`https://wa.me/${waNumber}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-emerald-400 font-bold text-xs transition active:scale-95"
                    >
                      <MessageCircle className="w-4 h-4 text-emerald-400" />
                      WhatsApp
                    </a>
                  </div>
                  <p className="text-[11px] text-slate-400 text-center mt-3 leading-relaxed">
                    Found this card? Please call or WhatsApp the owner above to return it.
                  </p>
                </div>
              ) : (
                <div className="p-3 rounded-xl bg-slate-900/80 border border-slate-800 text-center">
                  <p className="text-xs text-slate-300 font-medium">No contact phone number listed</p>
                  <p className="text-[11px] text-slate-400 mt-1">
                    If found, please hand this card over to the department office or Class Representative.
                  </p>
                </div>
              )}
            </div>
          </div>
        ) : (
          /* Unregistered Card Screen */
          <div className="rounded-3xl bg-slate-900/90 border border-slate-800/90 shadow-2xl p-6 sm:p-8 text-center backdrop-blur-xl">
            <div className="w-16 h-16 rounded-2xl bg-amber-500/10 border border-amber-500/20 text-amber-400 flex items-center justify-center mx-auto mb-4">
              <AlertCircle className="w-8 h-8" />
            </div>
            <h2 className="text-xl font-bold text-white mb-1.5">Unlinked Smart Card</h2>
            <p className="text-xs text-slate-400 mb-4">
              Card UID: <span className="font-mono text-amber-400 font-semibold">• • • • {cleanId.slice(-4)}</span>
            </p>
            <div className="p-4 rounded-2xl bg-slate-950/60 border border-slate-800/70 text-left text-xs text-slate-300 space-y-2 mb-6 leading-relaxed">
              <p>• This physical NFC card is active, but hasn&apos;t been assigned to a student yet.</p>
              <p>• If you are a Class Representative or Teacher, open the <strong>ClassMate</strong> app to register and link this card to a student.</p>
            </div>
            <a
              href="https://classmatebd.vercel.app/download"
              className="inline-flex items-center justify-center gap-2 w-full px-5 py-3 rounded-xl bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-bold text-xs transition shadow-lg shadow-emerald-500/20"
            >
              <Download className="w-4 h-4" />
              Download ClassMate App
            </a>
          </div>
        )}
      </main>

      {/* Footer */}
      <footer className="w-full max-w-lg text-center py-4 relative z-10 border-t border-slate-900">
        <p className="text-[11px] text-slate-400">
          Powered by <strong className="text-slate-400 font-semibold">ClassMate</strong> · Official MBSTU Campus System
        </p>
        <p className="text-[10px] text-slate-400 mt-1">
          Zero-browser instant NFC authentication · Encrypted & Secure
        </p>
      </footer>
    </div>
  );
}

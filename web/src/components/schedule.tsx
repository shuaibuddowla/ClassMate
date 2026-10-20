"use client";
import { useState, useEffect } from "react";
import dynamic from "next/dynamic";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Plus,
  UserRound,
  Bus,
  ChevronLeft,
  ChevronRight,
  CalendarDays,
  Trash2,
  Clock3,
  Building2,
} from "lucide-react";
import { supabase, rpc, rows, type Row } from "@/lib/api";
import { AttendanceTeacherModal } from "./attendance-teacher-modal";
import StudentAttendanceDashboard from "./student-attendance";
import { useAcademic } from "@/lib/queries";
import {
  busKind,
  classesClosed,
  dateKey,
  dhakaToday,
  formatTime,
  monthCells,
  parseDate,
  scopesFor,
} from "@/lib/calendar";
import type { Context } from "./app";
import { Empty, ErrorBox, Form, Modal, Skeleton, Confirm } from "./ui";

const Lottie = dynamic(
  () => import("lottie-react").then((mod) => mod.Lottie),
  { ssr: false },
);

function HolidayLottie({
  name,
  fallbackEmoji,
}: {
  name: string;
  fallbackEmoji: string;
}) {
  const [data, setData] = useState<object | null>(null);

  useEffect(() => {
    let active = true;
    fetch(`/animations/${name}.json`)
      .then((res) => {
        if (!res.ok) throw new Error("Failed to load animation");
        return res.json();
      })
      .then((json) => {
        if (active) setData(json);
      })
      .catch((err) => {
        console.error("Failed to load Lottie animation", err);
        if (active) setData(null);
      });
    return () => {
      active = false;
    };
  }, [name]);

  if (!data) {
    return (
      <span style={{ fontSize: "3rem", display: "inline-block", lineHeight: 1 }}>
        {fallbackEmoji}
      </span>
    );
  }

  return (
    <div
      style={{
        width: 170,
        height: 170,
        margin: "0 auto",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <Lottie
        src={data}
        autoplay={true}
        loop={true}
        style={{ width: "100%", height: "100%" }}
      />
    </div>
  );
}

function getTimetableAnimation(closureTitle?: string | null, isWeekend?: boolean) {
  const text = (closureTitle || "").toLowerCase();

  if (
    [
      "eid",
      "ramadan",
      "romjan",
      "fitr",
      "azha",
      "adho",
      "barat",
      "meraj",
      "qadr",
      "ashura",
      "hijri",
      "muharram",
      "fateha",
      "milad",
      "ঈদ",
      "রমজান",
      "মিরাজ",
      "বরাত",
      "কদর",
      "আশুরা",
      "ফাতেহা",
      "মিলাদুন্নবী",
      "সোম্বা",
    ].some((k) => text.includes(k))
  ) {
    return { name: "eid", emoji: "🌙" };
  }
  if (
    [
      "puja",
      "pooja",
      "durga",
      "saraswati",
      "kali",
      "diwali",
      "deepavali",
      "janmashtami",
      "dashami",
      "navami",
      "bijoya",
      "shivratri",
      "purnima",
      "buddha",
      "পূজা",
      "দুর্গা",
      "সরস্বতী",
      "জন্মাষ্টমী",
      "লক্ষ্মী",
      "বুদ্ধপূর্ণিমা",
    ].some((k) => text.includes(k))
  ) {
    return { name: "puja", emoji: "🪔" };
  }
  if (
    [
      "vacation",
      "break",
      "recess",
      "summer",
      "winter",
      "autumn",
      "spring",
      "অবকাশ",
      "ছুটি",
    ].some((k) => text.includes(k))
  ) {
    return { name: "vacation", emoji: "🏖️" };
  }
  if (
    [
      "independence",
      "victory",
      "language",
      "ekushey",
      "21st february",
      "21 february",
      "mourning",
      "revolution",
      "bangabandhu",
      "shaheed",
      "national",
      "may day",
      "labor",
      "martyrs",
      "uprising",
      "স্বাধীনতা",
      "বিজয়",
      "মাতৃভাষা",
      "শহিদ",
      "গণঅভ্যুত্থান",
      "ভাসানী",
      "বুদ্ধিজীবী",
    ].some((k) => text.includes(k))
  ) {
    return { name: "national", emoji: "🌺" };
  }
  if (
    ["weekend", "weekly", "শুক্রবার", "শনিবার"].some((k) => text.includes(k)) ||
    isWeekend
  ) {
    return { name: "weekend", emoji: "☕" };
  }
  return { name: "celebration", emoji: "🎉" };
}

const weekdays = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

function dhakaNowTime() {
  return new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Dhaka",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date());
}

export function dhakaHour(): number {
  try {
    const parts = new Intl.DateTimeFormat("en-GB", {
      timeZone: "Asia/Dhaka",
      hour: "numeric",
      hour12: false,
    }).formatToParts(new Date());
    const val = parts.find((p) => p.type === "hour")?.value;
    return val ? parseInt(val, 10) : new Date().getHours();
  } catch {
    return new Date().getHours();
  }
}

export function defaultTeacherGreeting(hour = dhakaHour()): { greeting: string; eyebrow: string } {
  if (hour >= 5 && hour <= 11) return { greeting: "Good morning, Sir", eyebrow: "TEACHING SCHEDULE" };
  if (hour >= 12 && hour <= 14) return { greeting: "Good noon, Sir", eyebrow: "TEACHING SCHEDULE" };
  if (hour >= 15 && hour <= 17) return { greeting: "Good afternoon, Sir", eyebrow: "TEACHING SCHEDULE" };
  return { greeting: "Good evening, Sir", eyebrow: "TEACHING SCHEDULE" };
}

export function Schedule({ ctx }: { ctx: Context }) {
  const [mode, setMode] = useState("routine"),
    [date, setDate] = useState(dhakaToday()),
    [month, setMonth] = useState(() => parseDate(dhakaToday())),
    [currentTime, setCurrentTime] = useState(dhakaNowTime),
    [editing, setEditing] = useState<Row | null>(null),
    [event, setEvent] = useState<Row | null>(null),
    [deleting, setDeleting] = useState<Row | null>(null),
    [teacherAttendance, setTeacherAttendance] = useState<{
      slotId: string;
      sessionId?: string;
      courseTitle: string;
      courseCode?: string;
    } | null>(null);
  const qc = useQueryClient();

  const attendanceSessions = useQuery({
    queryKey: [ctx.user, ctx.batch, "attendance-sessions"],
    queryFn: async () => {
      try {
        const data = await rpc<Array<{
          session_id: string;
          routine_slot_id: string;
          semester_course_id: string;
          started_at: string;
          status: string;
          present_count: number;
        }>>("active_attendance_sessions", { target_batch: ctx.batch });

        const map: Record<string, Row> = {};
        for (const item of data || []) {
          map[item.routine_slot_id] = {
            id: item.session_id,
            routine_slot_id: item.routine_slot_id,
            semester_course_id: item.semester_course_id,
            started_at: item.started_at,
            status: item.status,
            present_count: Number(item.present_count || 0),
          };
        }
        return map;
      } catch {
        const { data } = await supabase()
          .schema("classmate")
          .from("attendance_sessions")
          .select("id, routine_slot_id, started_by, started_at, status")
          .eq("status", "open");
        const map: Record<string, Row> = {};
        for (const item of data || []) {
          map[item.routine_slot_id] = item;
        }
        return map;
      }
    },
    refetchInterval: 3_000,
  });

  const sessionCounts = useQuery({
    queryKey: [ctx.user, ctx.batch, "attendance-counts", attendanceSessions.data],
    enabled: Boolean(
      attendanceSessions.data && Object.keys(attendanceSessions.data).length > 0,
    ),
    queryFn: async () => {
      const counts: Record<string, number> = {};
      for (const sess of Object.values(attendanceSessions.data || {})) {
        if (typeof (sess as any).present_count === "number") {
          counts[(sess as any).id] = (sess as any).present_count;
        }
      }
      return counts;
    },
    refetchInterval: 3_000,
  });

  useEffect(() => {
    const channel = supabase()
      .channel("web:attendance-listener")
      .on(
        "postgres_changes",
        { event: "*", schema: "classmate", table: "attendance_sessions" },
        () => attendanceSessions.refetch(),
      )
      .on(
        "postgres_changes",
        { event: "*", schema: "classmate", table: "attendance_records" },
        () => sessionCounts.refetch(),
      )
      .subscribe();

    return () => {
      supabase().removeChannel(channel);
    };
  }, []);

  useEffect(() => {
    let lastToday = dhakaToday();
    const handleDayRollover = () => {
      const today = dhakaToday();
      if (today !== lastToday) {
        lastToday = today;
        setDate((prev) => (prev <= lastToday ? today : prev));
        setMonth(parseDate(today));
        qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch] });
      }
      setCurrentTime(dhakaNowTime());
    };
    const timer = setInterval(handleDayRollover, 10_000);
    const onVisibility = () => {
      if (document.visibilityState === "visible") {
        handleDayRollover();
      }
    };
    window.addEventListener("focus", handleDayRollover);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      clearInterval(timer);
      window.removeEventListener("focus", handleDayRollover);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [ctx.user, ctx.batch, qc]);
  const calendar = useAcademic<Row[]>(ctx.user, ctx.batch, "calendar", () =>
    rows("academic_calendar_events", {}, "*", "start_date", 1000),
  );
  const events = calendar.data || [];
  const routine = useAcademic<Row[]>(
    ctx.user,
    ctx.batch,
    "routine",
    async () => {
      const catalog = await rpc<Row[]>("batch_timetable_catalog", {
        target_batch: ctx.batch,
      });
      if (!catalog.length) return [];
      const slots = await rows(
        "routine_slots",
        { semester_course_id: catalog.map((c) => c.offering_id) },
        "*",
        "start_time",
      );
      return slots.map((s) => ({
        ...s,
        course: catalog.find((c) => c.offering_id === s.semester_course_id),
      }));
    },
  );
  const buses = useAcademic<Row[]>(ctx.user, ctx.batch, "buses", () =>
    rows("bus_schedules", { active: true }, "*", "departure_time"),
  );
  const details = useAcademic<Row[]>(
    ctx.user,
    ctx.batch,
    "timetable-details",
    () =>
      routine.data?.length
        ? rpc<Row[]>("timetable_details", {
            target_batch: ctx.batch,
            target_date: date,
            target_course_ids: [...new Set(routine.data.map((s) => s.semester_course_id))],
          })
        : Promise.resolve([]),
    [date, routine.data?.map((s) => s.semester_course_id).join(",") || ""],
  );
  const busPermission = useQuery({
    queryKey: [ctx.user, "bus-permission"],
    queryFn: () => rpc<boolean>("can_edit_bus_schedules"),
  });
  const editable = useQuery({
    queryKey: [
      ctx.user,
      ctx.batch,
      "routine-permissions",
      ctx.courses.map((c) => c.offering_id).join(","),
    ],
    queryFn: async () => {
      const permissions = await Promise.all(
        ctx.courses.map(
          async (c) =>
            [
              c.offering_id,
              await rpc<boolean>("can_manage_routine", {
                target_course: c.offering_id,
              }),
            ] as const,
        ),
      );
      return Object.fromEntries(permissions);
    },
  });
  const kind = busKind(date, events),
    day = parseDate(date).getDay(),
    closed = classesClosed(date, events);

  const isTeacherViewer = ctx.profile.role === "teacher";
  const showAttendance = false;
  const teacherGreetingQuery = useQuery({
    queryKey: ["teacher_greeting", isTeacherViewer],
    queryFn: async () => {
      return await rpc<{ greeting: string; eyebrow: string; slot: string }>("get_teacher_greeting", {
        client_hour: dhakaHour(),
      });
    },
    enabled: isTeacherViewer,
    staleTime: 60_000,
  });
  const teacherGreeting = teacherGreetingQuery.data || defaultTeacherGreeting();

  const rawRoutineEntries = (routine.data || []).filter((s) => s.day_of_week === day);
  const teacherAssignedEntries = isTeacherViewer
    ? rawRoutineEntries.filter((s) => Boolean(editable.data?.[s.semester_course_id]))
    : rawRoutineEntries;

  const entries =
    mode === "bus"
      ? (buses.data || []).filter((s) =>
          ["closed", "office_open"].includes(s.schedule_kind)
            ? s.schedule_kind === kind
            : s.weekdays?.includes(day),
        )
      : teacherAssignedEntries;

  const isSlotLive = (s: Row) => {
    if (mode !== "routine" || date !== dhakaToday() || closed) return false;
    const detail = details.data?.find((d) => d.semester_course_id === s.semester_course_id);
    if (detail?.cancelled) return false;
    return Boolean(
      s.start_time &&
        s.end_time &&
        s.start_time.slice(0, 5) <= currentTime &&
        currentTime < s.end_time.slice(0, 5)
    );
  };

  const sortedEntries =
    mode === "routine" && date === dhakaToday() && !closed
      ? [
          ...entries.filter(isSlotLive),
          ...entries.filter((s) => !isSlotLive(s)),
        ]
      : entries;

  const showCalendar = true;
  useEffect(() => {
    if (isTeacherViewer && mode === "bus") {
      setMode("routine");
    }
  }, [mode, isTeacherViewer]);

  const start = parseDate(date);
  start.setDate(start.getDate() - start.getDay());
  const week = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(start);
    d.setDate(d.getDate() + i);
    return dateKey(d);
  });
  const invalidate = () =>
    qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch] });
  return (
    <div className="schedule-screen">
      <div className="schedule-header">
        <div className="page-heading">
          <div>
            <span className="eyebrow">
              {mode === "calendar" ? (
                "YOUR UNIVERSITY, MONTH BY MONTH"
              ) : mode === "bus" ? (
                "STUDENT TRANSPORT"
              ) : isTeacherViewer ? (
                teacherGreeting.eyebrow
              ) : (
                <>
                  <span className="desktop-greeting">
                    MAKE ROOM FOR A GOOD DAY
                  </span>
                  <span className="mobile-greeting">Welcome back.</span>
                </>
              )}
            </span>
            <h1>
              {mode === "calendar" ? (
                "Academic calendar"
              ) : mode === "bus" ? (
                "Campus buses"
              ) : isTeacherViewer ? (
                <>
                  {teacherGreeting.greeting}
                  <span
                    className="greeting-wave"
                    aria-hidden="true"
                  >
                    {" "}
                    👋
                  </span>
                </>
              ) : (
                <>
                  <span className="desktop-greeting">Welcome, </span>
                  {ctx.profile.full_name?.split(" ")[0] || "there"}
                  <span
                    className="mobile-greeting greeting-wave"
                    aria-hidden="true"
                  >
                    {" "}
                    👋
                  </span>
                </>
              )}
            </h1>
          </div>
          {mode !== "calendar" &&
            (mode === "bus"
              ? busPermission.data
              : Object.values(editable.data || {}).some(Boolean)) && (
              <button className="primary" onClick={() => setEditing({})}>
                <Plus size={18} />
                {mode === "bus" ? "Add Bus" : "Add Period"}
              </button>
            )}
        </div>
        <div className="segment modes">
          {[
            ["routine", "Class Routine"],
            ...(!isTeacherViewer ? [["bus", "Bus Schedule"]] : []),
            ["calendar", "Calendar"],
          ].map(([id, label]) => (
            <button
              className={mode === id ? "selected" : ""}
              key={id}
              onClick={() => setMode(id)}
            >
              {label}
            </button>
          ))}
        </div>
        {mode !== "calendar" && (
          <div className="week">
            <button
              className="icon"
              aria-label="Previous week"
              onClick={() => {
                const d = parseDate(date);
                d.setDate(d.getDate() - 7);
                setDate(dateKey(d));
              }}
            >
              <ChevronLeft size={18} />
            </button>
            {week.map((d) => (
              <button
                key={d}
                aria-pressed={d === date}
                className={`day ${d === date ? "selected" : ""} ${classesClosed(d, events) ? "closed" : ""}`}
                onClick={() => setDate(d)}
              >
                <small>{weekdays[parseDate(d).getDay()]}</small>
                <strong>{parseDate(d).getDate()}</strong>
                <i />
              </button>
            ))}
            <button
              className="icon"
              aria-label="Next week"
              onClick={() => {
                const d = parseDate(date);
                d.setDate(d.getDate() + 7);
                setDate(dateKey(d));
              }}
            >
              <ChevronRight size={18} />
            </button>
          </div>
        )}
      </div>
      <ErrorBox error={calendar.error} retry={() => calendar.refetch()} />
      {mode === "calendar" ? (
        <>
          <CalendarPanel
            month={month}
            setMonth={setMonth}
            date={date}
            setDate={setDate}
            events={events}
          />
          <div className="section-heading calendar-events-heading">
            <h2>Holidays & events</h2>
            {ctx.owner && (
              <button
                className="icon"
                aria-label="Add calendar event"
                onClick={() => setEvent({})}
              >
                <Plus />
              </button>
            )}
          </div>
          <div className="event-list">
            {events
              .filter(
                (e) =>
                  e.start_date <=
                    dateKey(
                      new Date(month.getFullYear(), month.getMonth() + 1, 0),
                    ) &&
                  e.end_date >=
                    dateKey(new Date(month.getFullYear(), month.getMonth(), 1)),
              )
              .map((e) => (
                <button
                  key={e.id}
                  className="card event-row"
                  disabled={!ctx.owner}
                  onClick={() => setEvent(e)}
                >
                  <span className={`event-date ${e.scope}`}>
                    {parseDate(e.start_date).toLocaleDateString("en", {
                      month: "short",
                    })}
                    <strong>{parseDate(e.start_date).getDate()}</strong>
                  </span>
                  <span>
                    <strong>{e.title}</strong>
                    <small>
                      {e.start_date}{" "}
                      {e.end_date !== e.start_date ? `— ${e.end_date}` : ""} ·{" "}
                      {e.scope.replace("_", " ")}
                    </small>
                  </span>
                </button>
              ))}
          </div>
        </>
      ) : (
        <>
          <div className="section-heading schedule-section-heading">
            <h3>
              <span className="mobile-schedule-label">
                {weekdays[day] === "Sun"
                  ? "Sunday"
                  : parseDate(date).toLocaleDateString("en", {
                      weekday: "long",
                    })}
                's schedule
              </span>
              <span className="desktop-schedule-label">
                {parseDate(date).toLocaleDateString("en", {
                  weekday: "long",
                  day: "numeric",
                  month: "short",
                })}
              </span>
            </h3>
            <span>
              {sortedEntries.length}{" "}
              {mode === "bus" ? (
                "departures"
              ) : (
                <>
                  <span className="desktop-schedule-label">periods</span>
                  <span className="mobile-schedule-label">classes</span>
                </>
              )}
            </span>
          </div>
          {mode === "routine" && closed ? (
            (() => {
              const holidayTitle =
                events
                  .filter(
                    (e) =>
                      e.start_date <= date &&
                      e.end_date >= date &&
                      ["university", "classes"].includes(e.scope),
                  )
                  .map((e) => e.title)
                  .join(" · ") || "University weekly holiday";
              const isWeekendDay = [4, 5].includes(parseDate(date).getDay());
              const anim = getTimetableAnimation(holidayTitle, isWeekendDay);
              return (
                <Empty
                  illustration={
                    <HolidayLottie
                      name={anim.name}
                      fallbackEmoji={anim.emoji}
                    />
                  }
                  title={
                    isTeacherViewer
                      ? date === dhakaToday()
                        ? "You have no classes today for the selected batch"
                        : "You have no classes on this day for the selected batch"
                      : "No classes today"
                  }
                  body={holidayTitle}
                />
              );
            })()
          ) : (
            <>
              <ErrorBox
                error={mode === "bus" ? buses.error : routine.error}
                retry={() =>
                  mode === "bus" ? buses.refetch() : routine.refetch()
                }
              />
              {(mode === "bus" ? buses.isPending : routine.isPending) ? (
                <>
                  <Skeleton />
                  <Skeleton />
                </>
              ) : sortedEntries.length === 0 ? (
                (() => {
                  const isWeekendDay = [4, 5].includes(parseDate(date).getDay());
                  const anim =
                    mode === "routine"
                      ? getTimetableAnimation(
                          isWeekendDay ? "weekend" : "",
                          isWeekendDay,
                        )
                      : null;
                  return (
                    <Empty
                      illustration={
                        mode === "routine" && anim ? (
                          <HolidayLottie
                            name={anim.name}
                            fallbackEmoji={anim.emoji}
                          />
                        ) : (
                          <span
                            style={{
                              fontSize: "3rem",
                              display: "inline-block",
                              lineHeight: 1,
                            }}
                          >
                            🚌
                          </span>
                        )
                      }
                      title={
                        mode === "routine" && isTeacherViewer
                          ? date === dhakaToday()
                            ? "You have no classes today for the selected batch"
                            : "You have no classes on this day for the selected batch"
                          : "Nothing scheduled"
                      }
                      body={
                        mode === "routine" && isTeacherViewer
                          ? "Your assigned courses have no routine slots scheduled on this day."
                          : mode === "routine"
                          ? "Enjoy your free day"
                          : "Schedules will appear here once configured."
                      }
                    />
                  );
                })()
              ) : (
                <>
                  {mode === "bus" && (
                    <BusHero entries={sortedEntries} date={date} kind={kind} />
                  )}
                  <div className="schedule-list">
                    {sortedEntries.map((s) => {
                      const detail = details.data?.find(
                        (d) => d.semester_course_id === s.semester_course_id,
                      );
                      const canEdit =
                        mode === "bus"
                          ? busPermission.data
                          : editable.data?.[s.semester_course_id];
                      const isToday = date === dhakaToday();
                      const isLive =
                        mode === "routine" &&
                        isToday &&
                        !detail?.cancelled &&
                        !closed &&
                        Boolean(
                          s.start_time &&
                            s.end_time &&
                            s.start_time.slice(0, 5) <= currentTime &&
                            currentTime < s.end_time.slice(0, 5),
                        );

                      const isTeacher =
                        ctx.profile.role === "teacher" &&
                        (Boolean(editable.data?.[s.semester_course_id]) || ctx.owner);
                      const isAdmin = Boolean(ctx.owner || ctx.profile.role === "admin");
                      const isStudent = ctx.profile.role === "student";

                      const slotSession = attendanceSessions.data?.[s.id];
                      const hasOpenSession = Boolean(slotSession);
                      const presentCount = slotSession
                        ? (typeof slotSession.present_count === "number"
                            ? slotSession.present_count
                            : sessionCounts.data?.[slotSession.id] || 0)
                        : 0;

                      if (mode === "bus") {
                        return (
                          <button
                            className="card schedule-row bus-row"
                            disabled={!canEdit}
                            key={s.id}
                            onClick={() => setEditing(s)}
                          >
                            <span className="bus-icon">
                              <Bus />
                            </span>
                            <div>
                              <small>Campus → City</small>
                              <strong>{formatTime(s.departure_time)}</strong>
                            </div>
                            <div>
                              <small>City → Campus</small>
                              <strong>
                                {formatTime(s.city_departure_time)}
                              </strong>
                            </div>
                          </button>
                        );
                      }

                      return (
                        <div
                          className={`card routine-card-container ${detail?.cancelled ? "cancelled" : ""} ${isLive ? "live-pinned-card" : ""}`}
                          key={s.id}
                        >
                          {isLive && (
                            <div className="live-pinned-banner">
                              <span className="pulse-dot" />
                              <span>LIVE NOW · Ongoing Period</span>
                            </div>
                          )}
                          <div
                            role="button"
                            tabIndex={canEdit ? 0 : undefined}
                            className="schedule-row routine-row"
                            onClick={() => canEdit && setEditing(s)}
                          >
                            <div className="period-time">
                              <strong>
                                <Clock3
                                  className="period-start-icon"
                                  size={16}
                                />
                                <span className="desktop-schedule-label">
                                  {formatTime(s.start_time)}
                                </span>
                                <span className="mobile-schedule-label">
                                  {formatTime(s.start_time).replace(
                                    /\s*[AP]M$/,
                                    "",
                                  )}
                                </span>
                              </strong>
                              <small>
                                <i className="period-end-dot" />
                                {formatTime(s.end_time)}
                              </small>
                            </div>
                            <div className="period-course">
                              <h3>{s.course?.course_title || "Course"}</h3>
                              <p>
                                {detail?.teacher_name &&
                                detail.teacher_name !== "null" ? (
                                  <span>
                                    <UserRound size={14} />
                                    {detail.teacher_name}
                                  </span>
                                ) : null}
                                {s.room && s.room !== "null" ? (
                                  <span>
                                    <Building2 size={14} />
                                    {/^[0-9]/.test(s.room)
                                      ? `Room ${s.room}`
                                      : s.room}
                                  </span>
                                ) : null}
                              </p>
                            </div>
                            <div className="period-status">
                              <span
                                className={`period-tag ${detail?.cancelled ? "red" : isLive ? "live" : ""}`}
                              >
                                {detail?.cancelled
                                  ? "Cancelled"
                                  : isLive
                                    ? "Live now"
                                    : s.type === "lab"
                                      ? "Lab"
                                      : "Class"}
                              </span>
                              <small className="period-duration">
                                <Clock3 size={13} />
                                {Math.max(
                                  0,
                                  Number(s.end_time?.slice(0, 2)) * 60 +
                                    Number(s.end_time?.slice(3, 5)) -
                                    (Number(s.start_time?.slice(0, 2)) * 60 +
                                      Number(s.start_time?.slice(3, 5))),
                                )}{" "}
                                min
                              </small>
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </>
              )}
            </>
          )}
        </>
      )}
      {editing && (
        <Modal
          title={
            mode === "bus"
              ? editing.id
                ? "Edit bus"
                : "Add bus"
              : editing.id
                ? "Edit period"
                : "Add period"
          }
          close={() => setEditing(null)}
        >
          {mode === "routine" && !ctx.courses.length ? (
            <>
              <p>Configure your batch’s courses first.</p>
              <button onClick={ctx.manage}>Configure courses</button>
            </>
          ) : (
            <Form
              fields={
                mode === "bus"
                  ? [
                      {
                        name: "campus",
                        label: "From campus",
                        type: "time",
                        required: true,
                        value: editing.departure_time?.slice(0, 5),
                      },
                      {
                        name: "city",
                        label: "From city",
                        type: "time",
                        required: true,
                        value: editing.city_departure_time?.slice(0, 5),
                      },
                    ]
                  : [
                      {
                        name: "course",
                        label: "Course",
                        required: true,
                        value: editing.semester_course_id,
                        options: ctx.courses
                          .filter((c) => editable.data?.[c.offering_id])
                          .map((c) => ({
                            value: c.offering_id,
                            label: c.course_title,
                          })),
                      },
                      {
                        name: "start",
                        label: "Starts",
                        type: "time",
                        required: true,
                        value: editing.start_time?.slice(0, 5),
                      },
                      {
                        name: "end",
                        label: "Ends",
                        type: "time",
                        required: true,
                        value: editing.end_time?.slice(0, 5),
                      },
                      { name: "room", label: "Room", value: editing.room },
                    ]
              }
              submit={async (d) => {
                if (mode === "bus")
                  await rpc("save_student_bus_schedule", {
                    target_id: editing.id || null,
                    target_kind: kind,
                    target_campus_departure: d.campus,
                    target_city_departure: d.city,
                    target_active: true,
                  });
                else {
                  if (d.start >= d.end)
                    throw new Error("End time must be after start time.");
                  await rpc("save_routine_slot", {
                    target_id: editing.id || null,
                    target_semester_course: d.course,
                    target_day: editing.day_of_week ?? day,
                    target_start: d.start,
                    target_end: d.end,
                    target_room: d.room,
                  });
                }
                await invalidate();
                setEditing(null);
              }}
            />
          )}
          {editing.id && (
            <button
              className="text-button danger"
              onClick={() => {
                setDeleting({ ...editing, deleteKind: mode });
                setEditing(null);
              }}
            >
              <Trash2 size={16} />
              Delete
            </button>
          )}
        </Modal>
      )}
      {event && (
        <Modal
          title={event.id ? "Edit event" : "Add calendar event"}
          close={() => setEvent(null)}
        >
          <Form
            fields={[
              {
                name: "target_title",
                label: "Event title",
                required: true,
                value: event.title,
              },
              {
                name: "target_start",
                label: "From",
                type: "date",
                required: true,
                value: event.start_date || date,
              },
              {
                name: "target_end",
                label: "Until",
                type: "date",
                required: true,
                value: event.end_date || date,
              },
              {
                name: "target_scope",
                label: "Calendar effect",
                required: true,
                value: event.scope || "observance",
                options: [
                  {
                    value: "university",
                    label: "University closed (classes + buses)",
                  },
                  { value: "classes", label: "Class holiday" },
                  { value: "observance", label: "Event / observance" },
                  { value: "working_day", label: "Working day override" },
                ],
              },
            ]}
            submit={async (d) => {
              await rpc("save_calendar_event", {
                target_id: event.id || null,
                ...d,
              });
              await invalidate();
              setEvent(null);
            }}
          />
          {event.id && (
            <button
              className="text-button danger"
              onClick={() => {
                setDeleting({ ...event, deleteKind: "calendar" });
                setEvent(null);
              }}
            >
              Delete event
            </button>
          )}
        </Modal>
      )}
      {deleting && (
        <Confirm
          title="Delete this entry?"
          body="This change affects everyone who uses this schedule."
          close={() => setDeleting(null)}
          action={async () => {
            if (deleting.deleteKind === "calendar")
              await rpc("delete_calendar_event", { target_id: deleting.id });
            else if (deleting.deleteKind === "bus")
              await rpc("save_student_bus_schedule", {
                target_id: deleting.id,
                target_kind: deleting.schedule_kind,
                target_campus_departure: deleting.departure_time,
                target_city_departure: deleting.city_departure_time,
                target_active: false,
              });
            else await rpc("delete_routine_slot", { target_id: deleting.id });
            await invalidate();
          }}
        />
      )}
      {teacherAttendance && (
        <AttendanceTeacherModal
          slotId={teacherAttendance.slotId}
          sessionId={teacherAttendance.sessionId}
          courseTitle={teacherAttendance.courseTitle}
          courseCode={teacherAttendance.courseCode}
          onClose={() => {
            setTeacherAttendance(null);
            attendanceSessions.refetch();
          }}
        />
      )}
    </div>
  );
}
function CalendarPanel({
  month,
  setMonth,
  date,
  setDate,
  events,
}: {
  month: Date;
  setMonth: (d: Date) => void;
  date: string;
  setDate: (d: string) => void;
  events: Row[];
}) {
  const count = events.filter(
    (e) =>
      e.start_date <=
        dateKey(new Date(month.getFullYear(), month.getMonth() + 1, 0)) &&
      e.end_date >= dateKey(new Date(month.getFullYear(), month.getMonth(), 1)),
  ).length;
  return (
    <section className="calendar-surface">
      <div className="calendar-heading">
        <div className="scenery" aria-hidden="true">
          <i />
          <b />
          <span />
        </div>
        <span className="eyebrow">{month.getFullYear()}</span>
        <h2>{month.toLocaleDateString("en", { month: "long" })}</h2>
        <p>{count} events this month</p>
        <div className="calendar-controls">
          <button
            className="today"
            onClick={() => {
              setDate(dhakaToday());
              setMonth(parseDate(dhakaToday()));
            }}
          >
            <CalendarDays size={17} />
            Today
          </button>
          <div>
            <button
              className="circle"
              aria-label="Previous month"
              onClick={() =>
                setMonth(new Date(month.getFullYear(), month.getMonth() - 1, 1))
              }
            >
              <ChevronLeft />
            </button>
            <button
              className="circle"
              aria-label="Next month"
              onClick={() =>
                setMonth(new Date(month.getFullYear(), month.getMonth() + 1, 1))
              }
            >
              <ChevronRight />
            </button>
          </div>
        </div>
      </div>
      <div
        className="calendar-grid"
        key={`${month.getFullYear()}-${month.getMonth()}`}
      >
        {weekdays.map((d) => (
          <span key={d} className="weekday">
            {d.toUpperCase()}
          </span>
        ))}
        {monthCells(month.getFullYear(), month.getMonth()).map((d, i) => {
          const scopes = d ? scopesFor(d, events) : new Set();
          return d ? (
            <button
              key={d}
              className={`date-cell ${date === d ? "selected" : classesClosed(d, events) ? "closed" : ""}`}
              aria-pressed={date === d}
              aria-label={`${d}${classesClosed(d, events) ? ", closed" : ""}`}
              onClick={() => setDate(d)}
            >
              <strong>{parseDate(d).getDate()}</strong>
              <span className="dots">
                {classesClosed(d, events) && (
                  <i
                    className={
                      scopes.has("classes") && !scopes.has("university")
                        ? "green"
                        : "red"
                    }
                  />
                )}{" "}
                {scopes.has("observance") && <i />}
              </span>
            </button>
          ) : (
            <span className="date-empty" key={i} />
          );
        })}
      </div>
      <div className="legend">
        <span>
          <i className="red" />
          Closed
        </span>
        <span>
          <i className="green" />
          Class holiday
        </span>
        <span>
          <i />
          Event
        </span>
      </div>
    </section>
  );
}
function BusHero({
  entries,
  date,
  kind,
}: {
  entries: Row[];
  date: string;
  kind: string;
}) {
  const now = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Dhaka",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date());
  const isToday = date === dhakaToday();
  const next = (field: string) =>
    date < dhakaToday()
      ? null
      : entries.find((s) => s[field] && (!isToday || s[field] >= now));
  const a = next("departure_time"),
    b = next("city_departure_time");
  return (
    <div className="bus-hero">
      <span className="eyebrow">
        {isToday ? "NEXT DEPARTURES" : "SELECTED DAY"} ·{" "}
        {kind === "closed" ? "HOLIDAY SERVICE" : "REGULAR SERVICE"}
      </span>
      {!a && !b ? (
        <h2>Service finished for this day</h2>
      ) : (
        <div>
          <section>
            <small>From campus</small>
            <h2>{a ? formatTime(a.departure_time) : "Finished"}</h2>
          </section>
          <section>
            <small>From city</small>
            <h2>{b ? formatTime(b.city_departure_time) : "Finished"}</h2>
          </section>
        </div>
      )}
      <Bus className="hero-bus" aria-hidden="true" />
    </div>
  );
}

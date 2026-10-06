"use client";
import { useState } from "react";
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
import { rpc, rows, type Row } from "@/lib/api";
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
const weekdays = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
export function Schedule({ ctx }: { ctx: Context }) {
  const [mode, setMode] = useState("routine"),
    [date, setDate] = useState(dhakaToday()),
    [month, setMonth] = useState(() => parseDate(dhakaToday())),
    [editing, setEditing] = useState<Row | null>(null),
    [event, setEvent] = useState<Row | null>(null),
    [deleting, setDeleting] = useState<Row | null>(null);
  const qc = useQueryClient();
  const calendar = useAcademic<Row[]>(ctx.user, ctx.batch, "calendar", () =>
    rows("academic_calendar_events", {}, "*", "start_date", 1000),
  );
  const events = calendar.data || [];
  const routine = useAcademic<Row[]>(
    ctx.user,
    ctx.batch,
    "routine",
    async () => {
      const catalog = await rpc<Row[]>("batch_course_catalog", {
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
      ctx.courses.length
        ? rpc<Row[]>("timetable_details", {
            target_batch: ctx.batch,
            target_date: date,
            target_course_ids: ctx.courses.map((c) => c.offering_id),
          })
        : Promise.resolve([]),
    [date, ctx.courses.map((c) => c.offering_id).join(",")],
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
  const entries =
    mode === "bus"
      ? (buses.data || []).filter((s) =>
          ["closed", "office_open"].includes(s.schedule_kind)
            ? s.schedule_kind === kind
            : s.weekdays?.includes(day),
        )
      : (routine.data || []).filter((s) => s.day_of_week === day);
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
            ["bus", "Bus Schedule"],
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
              {entries.length}{" "}
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
            <Empty
              title={ctx.profile.role === "teacher" ? (date === dhakaToday() ? "You have no classes to take today" : "You have no classes to take on this day") : "No classes today"}
              body={
                events
                  .filter(
                    (e) =>
                      e.start_date <= date &&
                      e.end_date >= date &&
                      ["university", "classes"].includes(e.scope),
                  )
                  .map((e) => e.title)
                  .join(" · ") || "University weekly holiday"
              }
            />
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
              ) : entries.length === 0 ? (
                <Empty
                  title={mode === "routine" && ctx.profile.role === "teacher" ? (date === dhakaToday() ? "You have no classes to take today" : "You have no classes to take on this day") : "Nothing scheduled"}
                  body={mode === "routine" && ctx.profile.role === "teacher" ? "Your assigned teaching schedule will appear here." : "Schedules will appear here once configured."}
                />
              ) : (
                <>
                  {mode === "bus" && (
                    <BusHero entries={entries} date={date} kind={kind} />
                  )}
                  <div className="schedule-list">
                    {entries.map((s) => {
                      const detail = details.data?.find(
                        (d) => d.semester_course_id === s.semester_course_id,
                      );
                      const canEdit =
                        mode === "bus"
                          ? busPermission.data
                          : editable.data?.[s.semester_course_id];
                      return (
                        <button
                          className={`card schedule-row ${mode === "routine" ? "routine-row" : "bus-row"} ${detail?.cancelled && mode === "routine" ? "cancelled" : ""}`}
                          disabled={!canEdit}
                          key={s.id}
                          onClick={() => setEditing(s)}
                        >
                          {mode === "bus" ? (
                            <>
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
                            </>
                          ) : (
                            <>
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
                                  className={`period-tag ${detail?.cancelled ? "red" : ""}`}
                                >
                                  {detail?.cancelled
                                    ? "Cancelled"
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
                            </>
                          )}
                        </button>
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

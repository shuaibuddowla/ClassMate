"use client";
import { useState, useEffect } from "react";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { Search, Phone, MessageCircle, ChevronRight, Droplets } from "lucide-react";
import { BloodNetwork } from "./blood-network";
import { AboutDeveloper } from "./about-developer";
import { rpc, type Row } from "@/lib/api";
import type { Context } from "./app";
import { Avatar, Empty, ErrorBox, Modal, Skeleton } from "./ui";
export function Friends({ ctx }: { ctx: Context }) {
  const qc = useQueryClient();
  const [alert, setAlert] = useState<Row | null>(null);
  const [blood, setBlood] = useState(false);
  const [developer, setDeveloper] = useState(false);
  useEffect(() => {
    let live = true;
    setAlert(null);
    if(ctx.profile.role !== "teacher" && ctx.active && !blood && navigator.onLine && !location.hash.startsWith("#friends/blood")) {
      rpc<Row[]>("unread_blood_requests").then(async requests => {
        if(!live || !requests[0]) return;
        const details = await rpc<Row>("blood_request_details", {target_request: requests[0].id});
        if(live) setAlert(details);
      }).catch(() => {});
    }
    return () => { live=false; };
  }, [ctx.active, ctx.user, ctx.batch, blood]);
  useEffect(() => {
    if(!alert || !ctx.active) return;
    rpc("mark_blood_request_read", {target_request: alert.id}).then(() => qc.invalidateQueries({queryKey:[ctx.user,ctx.batch,"unread-activity"]})).catch(() => {});
  }, [alert, ctx.active, ctx.user, ctx.batch, qc]);
  useEffect(() => { const update=()=>{if(location.hash.startsWith("#friends/blood"))setBlood(true)};update();window.addEventListener("hashchange",update);return()=>window.removeEventListener("hashchange",update) },[]);
  const [input, setInput] = useState(""),
    [search, setSearch] = useState(""),
    [person, setPerson] = useState<Row | null>(null),
    [error, setError] = useState<unknown>(null),
    [busy, setBusy] = useState(false),
    [online, setOnline] = useState(true);
  useEffect(() => {
    const update = () => {
      setOnline(navigator.onLine);
      if (!navigator.onLine) { setPerson(null); setAlert(null); }
    };
    update();
    window.addEventListener("online", update);
    window.addEventListener("offline", update);
    return () => {
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
    };
  }, []);
  useEffect(() => {
    const timer = setTimeout(() => setSearch(input), 250);
    return () => clearTimeout(timer);
  }, [input]);
  const friends = useInfiniteQuery({
    queryKey: [ctx.user, ctx.batch, "friends", search],
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      rpc<Row[]>("batch_friends", {
        target_batch: ctx.batch,
        query_text: search,
        result_offset: pageParam,
      }),
    getNextPageParam: (last, pages) =>
      last.length === 100 ? pages.length * 100 : undefined,
  });
  if (!online)
    return (
      <Empty
        title="Friends is online-only"
        body="Connect to view your batchmates and their contact details."
      />
    );
  if (blood) return <BloodNetwork ctx={ctx} close={()=>{setBlood(false);history.replaceState(null,"","#friends")}} />;
  return (
    <>
      {developer && <AboutDeveloper user={ctx.user} close={() => setDeveloper(false)} />}
      {alert && ctx.active && <Modal title={`${alert.blood_group} blood needed`} close={()=>setAlert(null)}>
        <div className="blood-detail"><h3>{alert.hospital}</h3>
          <p>For: {alert.patient_name || alert.requester_name}</p><p>Requested by: {alert.requester_name}</p>
          <p>{alert.units} unit(s) · Needed by {new Date(alert.needed_by).toLocaleString()}</p>
          <p>Attendant: {alert.attendant_phone}</p><p>Can you help, or find someone who can?</p>
          <div className="actions"><a className="secondary" href={`tel:${alert.attendant_phone}`}><Phone size={18}/> Call attendant</a>
          <button className="primary" onClick={()=>{location.hash=`friends/blood/${alert.id}`;setAlert(null);setBlood(true)}}>View request</button></div>
        </div>
      </Modal>}
      <div className="page-heading">
        <div>
          <span className="eyebrow">YOUR BATCH COMMUNITY</span>
          <h1>{ctx.profile.role==="teacher" ? "Students" : "Friends"}</h1>
        </div>
        <button className="blood-entry" onClick={()=>setBlood(true)}><Droplets size={18}/> Blood requests</button>
      </div>
      <label className="search">
        <Search size={18} />
        <input
          aria-label="Search batchmates"
          placeholder="Search name or student ID"
          maxLength={100}
          value={input}
          onChange={(e) => setInput(e.target.value)}
        />
      </label>
      <ErrorBox
        error={error || friends.error}
        retry={() => friends.refetch()}
      />
      {friends.isPending ? (
        <Skeleton />
      ) : (
        <div className="people-grid">
          {friends.data?.pages.flat().filter(p=>ctx.profile.role!=="teacher" || ["student", "admin"].includes(p.role)).map((p) => (
            <button
              disabled={busy}
              className="card person"
              key={p.profile_id}
              onClick={async () => {
                if(p.role === "admin") { setDeveloper(true); return; }
                setBusy(true);
                setError(null);
                try {
                  setPerson(
                    await rpc("batch_friend_details", {
                      target_batch: ctx.batch,
                      target_profile: p.profile_id,
                    }),
                  );
                } catch (e) {
                  setError(e);
                } finally {
                  setBusy(false);
                }
              }}
            >
              <Avatar name={p.full_name} url={p.avatar_url} />
              <span>
                <strong>{p.full_name}</strong>
                {p.role === "admin" && <span className="role">Admin</span>}
                <small>
                  {p.student_id || p.role}
                  {p.is_cr ? " · CR" : ""}
                </small>
              </span>
              <ChevronRight size={17} />
            </button>
          ))}
        </div>
      )}
      {!friends.isPending && !friends.data?.pages.flat().length && (
        <Empty title="No matching batchmates" />
      )}
      {friends.hasNextPage && (
        <button
          disabled={friends.isFetching}
          onClick={() => friends.fetchNextPage()}
        >
          Load more people
        </button>
      )}
      {person && (
        <Modal title="Batchmate profile" close={() => setPerson(null)}>
          <div className="profile-header">
            <Avatar url={person.avatar_url} name={person.full_name} />
            <h3>{person.full_name}</h3>
          </div>
          <dl>
            {[
              ["student_id", "Student ID"],
              ["mobile_number", "Mobile number"],
              ["home_town", "Home town"],
              ["blood_group", "Blood group"],
              ["current_residence", "Current mess / flat"],
            ].map(([key, label]) => (
              <div key={key}>
                <dt>{label}</dt>
                <dd>{person[key] || "Not shared"}</dd>
              </div>
            ))}
          </dl>
          {person.mobile_number && (
            <div className="contact-actions">
              <a
                className="primary"
                href={`tel:${person.mobile_number.replace(/[^+\d]/g, "")}`}
              >
                <Phone size={18} />
                Call
              </a>
              <a
                className="secondary"
                target="_blank"
                rel="noopener noreferrer"
                href={`https://wa.me/${person.mobile_number.replace(/\D/g, "")}`}
              >
                <MessageCircle size={18} />
                WhatsApp
              </a>
            </div>
          )}
        </Modal>
      )}
    </>
  );
}

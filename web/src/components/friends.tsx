"use client";
import { useState, useEffect } from "react";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Search, Phone, MessageCircle, ChevronRight, Droplets, ArrowLeft, X } from "lucide-react";
import { BloodNetwork } from "./blood-network";
import { AboutDeveloper } from "./about-developer";
import { BatchFund } from "./batch-fund";
import { rpc, supabase, type Row } from "@/lib/api";
import type { Context } from "./app";
import { Avatar, Empty, ErrorBox, Modal, Skeleton } from "./ui";
export function Friends({ ctx }: { ctx: Context }) {
  const qc = useQueryClient();
  const [alert, setAlert] = useState<Row | null>(null);
  const [blood, setBlood] = useState(false);
  const [subTab, setSubTab] = useState<"batchmates" | "fund">("batchmates");
  const [developer, setDeveloper] = useState(false);
  const [searchActive, setSearchActive] = useState(false);
  useEffect(() => {
    let live = true;
    setAlert(null);
    if(ctx.profile.role !== "teacher" && ctx.active && !blood && subTab !== "fund" && navigator.onLine && !location.hash.startsWith("#friends/blood") && !location.hash.startsWith("#batch/blood") && !location.hash.startsWith("#friends/fund") && !location.hash.startsWith("#batch/fund")) {
      rpc<Row[]>("unread_blood_requests").then(async requests => {
        if(!live || !requests[0]) return;
        const details = await rpc<Row>("blood_request_details", {target_request: requests[0].id});
        if(live) setAlert(details);
      }).catch(() => {});
    }
    return () => { live=false; };
  }, [ctx.active, ctx.user, ctx.batch, blood, subTab]);
  useEffect(() => {
    if(!alert || !ctx.active) return;
    rpc("mark_blood_request_read", {target_request: alert.id}).then(() => qc.invalidateQueries({queryKey:[ctx.user,ctx.batch,"unread-activity"]})).catch(() => {});
  }, [alert, ctx.active, ctx.user, ctx.batch, qc]);
  useEffect(() => {
    const update=()=>{
      if(location.hash.startsWith("#friends/blood") || location.hash.startsWith("#batch/blood")) setBlood(true);
      if(location.hash.startsWith("#friends/fund") || location.hash.startsWith("#batch/fund")) setSubTab("fund");
    };
    update();
    window.addEventListener("hashchange",update);
    return()=>window.removeEventListener("hashchange",update);
  },[]);
  const [input, setInput] = useState(""),
    [search, setSearch] = useState(""),
    [person, setPerson] = useState<Row | null>(null),
    [error, setError] = useState<unknown>(null),
    [busy, setBusy] = useState(false),
    [online, setOnline] = useState(true),
    [onlineIds, setOnlineIds] = useState<Set<string>>(new Set());
  useEffect(() => {
    if (!ctx.active || !ctx.batch || !navigator.onLine) return;
    rpc("touch_presence").catch(() => {});
    const heartbeat = setInterval(() => {
      rpc("touch_presence").catch(() => {});
    }, 30000);
    const client = supabase();
    const presenceChannel = client.channel(`batch:presence:${ctx.batch}`, {
      config: { presence: { key: ctx.user } },
    });
    presenceChannel
      .on("presence", { event: "sync" }, () => {
        const state = presenceChannel.presenceState();
        setOnlineIds(new Set(Object.keys(state)));
      })
      .subscribe(async (status) => {
        if (status === "SUBSCRIBED") {
          await presenceChannel.track({ online_at: Date.now() });
        }
      });
    return () => {
      clearInterval(heartbeat);
      client.removeChannel(presenceChannel);
    };
  }, [ctx.active, ctx.batch, ctx.user]);
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

  const fundSummary = useQuery({
    queryKey: [ctx.user, ctx.batch, "batch-fund-summary"],
    queryFn: () => rpc<Row>("batch_fund_summary", { target_batch: ctx.batch }),
    enabled: !!ctx.batch && ctx.profile.role !== "teacher",
  });

  if (!online)
    return (
      <Empty
        title="Batch is online-only"
        body="Connect to view your batchmates and their contact details."
      />
    );
  if (blood) return <BloodNetwork ctx={ctx} close={()=>{setBlood(false);history.replaceState(null,"",location.hash.startsWith("#batch") ? "#batch" : "#friends")}} />;

  const fundBalance = Number(fundSummary.data?.current_balance || 0);

  return (
    <div className="batch-screen">
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

      {searchActive ? (
        <div className="batch-search-header">
          <button
            type="button"
            className="icon batch-search-back-btn"
            aria-label="Back to batch"
            onClick={() => {
              setSearchActive(false);
              setInput("");
            }}
          >
            <ArrowLeft size={20} />
          </button>
          <input
            autoFocus
            type="search"
            aria-label="Search batchmates"
            placeholder="Search name, ID, or blood group (e.g. O+)"
            maxLength={100}
            value={input}
            onChange={(e) => setInput(e.target.value)}
          />
          {input ? (
            <button
              type="button"
              className="icon batch-search-clear-btn"
              aria-label="Clear search"
              onClick={() => setInput("")}
            >
              <X size={18} />
            </button>
          ) : null}
        </div>
      ) : (
        <div className="page-heading">
          <div>
            <span className="eyebrow">YOUR BATCH COMMUNITY</span>
            <h1>{ctx.profile.role==="teacher" ? "Students" : "Batch"}</h1>
          </div>
          {subTab === "batchmates" && (
            <button
              type="button"
              className="icon batch-search-trigger-btn"
              aria-label="Search batchmates"
              title="Search batchmates"
              onClick={() => setSearchActive(true)}
            >
              <Search size={20} />
            </button>
          )}
        </div>
      )}

      {ctx.profile.role !== "teacher" && (
        <div className="segment modes batch-screen-toggle">
          <button
            className={subTab === "batchmates" ? "selected" : ""}
            onClick={() => {
              setSubTab("batchmates");
              history.replaceState(null, "", "#batch");
            }}
          >
            Batchmates
          </button>
          <button
            className={subTab === "fund" ? "selected" : ""}
            onClick={() => {
              setSubTab("fund");
              setSearchActive(false);
              history.replaceState(null, "", "#batch/fund");
            }}
          >
            Batch Fund {fundBalance > 0 && <span className="pill-badge">৳ {fundBalance.toLocaleString()}</span>}
          </button>
        </div>
      )}

      {subTab === "fund" ? (
        <BatchFund
          ctx={ctx}
          onBack={() => {
            setSubTab("batchmates");
            history.replaceState(null, "", "#batch");
          }}
        />
      ) : (
        <>
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
                  <Avatar name={p.full_name} url={p.avatar_url} online={Boolean(p.is_online || onlineIds.has(p.profile_id))} />
                  <span>
                    <strong>{p.full_name}</strong>
                    {p.role === "admin" && <span className="role">Admin</span>}
                    <small>
                      {p.student_id || p.role}
                      {p.blood_group && p.blood_group !== "Unknown" && (
                        <span className="blood-group-tag"> · {p.blood_group}</span>
                      )}
                      {p.is_cr && p.role !== "admin" && (
                        <span className="cr-blue-text"> · Class representative</span>
                      )}
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
        </>
      )}
      {person && (
        <Modal title="Batchmate profile" close={() => setPerson(null)}>
          <div className="friend-profile-content">
            <div className="profile-header friend-profile-header">
              <Avatar url={person.avatar_url} name={person.full_name} online={Boolean(person.is_online || onlineIds.has(person.profile_id))} />
              <div>
                <h3>{person.full_name}</h3>
                {person.student_id && <small>{person.student_id}</small>}
              </div>
            </div>
            <dl className="compact-dl">
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
              <div className="contact-actions compact-actions">
                <a
                  className="primary"
                  href={`tel:${person.mobile_number.replace(/[^+\d]/g, "")}`}
                >
                  <Phone size={16} />
                  Call
                </a>
                <a
                  className="secondary"
                  target="_blank"
                  rel="noopener noreferrer"
                  href={`https://wa.me/${person.mobile_number.replace(/\D/g, "")}`}
                >
                  <MessageCircle size={16} />
                  WhatsApp
                </a>
              </div>
            )}
          </div>
        </Modal>
      )}
    </div>
  );
}

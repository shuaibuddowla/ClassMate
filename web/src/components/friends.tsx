"use client";
import { useState, useEffect, useRef } from "react";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Search, Phone, MessageCircle, ChevronRight, Droplets, ArrowLeft, X, MoreVertical, RefreshCw } from "lucide-react";
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
  const canManageBatchmates = ctx.owner || (ctx.profile.is_cr === true && ctx.profile.cr_batch_id === ctx.batch);
  const [pullDistance, setPullDistance] = useState(0);
  const touchStart = useRef<number | null>(null);
  const activeBans = useQuery({
    queryKey: [ctx.user, ctx.batch, "active-batch-bans"],
    queryFn: () => rpc<Row[]>("batch_active_ban_list", { target_batch: ctx.batch }),
    enabled: !!ctx.batch && canManageBatchmates,
    refetchInterval: 30_000,
  });
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
      const isBlood = location.hash.startsWith("#friends/blood") || location.hash.startsWith("#batch/blood");
      const isFund = location.hash.startsWith("#friends/fund") || location.hash.startsWith("#batch/fund");
      setBlood(isBlood);
      setSubTab(isFund ? "fund" : "batchmates");
    };
    update();
    window.addEventListener("hashchange",update);
    window.addEventListener("popstate",update);
    return()=>{
      window.removeEventListener("hashchange",update);
      window.removeEventListener("popstate",update);
    };
  },[]);
  const [input, setInput] = useState(""),
    [search, setSearch] = useState(""),
    [person, setPerson] = useState<Row | null>(null),
    [banTarget, setBanTarget] = useState<Row | null>(null),
    [banDurationMode, setBanDurationMode] = useState<"3" | "7" | "custom" | "permanent">("3"),
    [customDaysInput, setCustomDaysInput] = useState<string>(""),
    [banDays, setBanDays] = useState<number>(3),
    [banReason, setBanReason] = useState<string>(""),
    [error, setError] = useState<unknown>(null),
    [busy, setBusy] = useState(false),
    [online, setOnline] = useState(true),
    [onlineIds, setOnlineIds] = useState<Set<string>>(new Set());
  async function manageStudent(p: Row, action: "appoint" | "transfer" | "ban" | "unban") {
    try {
      setBusy(true);
      if (action === "appoint") {
        await rpc("set_batch_cr", { target_profile: p.profile_id, target_active: !p.is_cr });
        qc.setQueriesData<any>({ queryKey: [ctx.user, ctx.batch, "friends"] }, (old: any) => old && ({ ...old, pages: old.pages.map((page: Row[]) => page.map((row: Row) => row.profile_id === p.profile_id ? { ...row, is_cr: !p.is_cr } : row)) }));
      } else if (action === "transfer") {
        await rpc("transfer_batch_cr", { target_profile: p.profile_id });
        await ctx.refreshProfile(await rpc<Row>("create_or_initialize_profile"));
      } else if (action === "ban") {
        setBanDurationMode("3");
        setBanDays(3);
        setCustomDaysInput("");
        setBanReason("");
        setBanTarget(p);
        document.querySelectorAll<HTMLDetailsElement>(".batchmate-actions[open]").forEach(el => { el.open = false; });
        return;
      } else if (action === "unban") {
        await rpc("manage_student_ban", { target_profile: p.profile_id, target_days: 1, target_reason: "unban", target_lift: true });
        qc.setQueryData<Row[]>([ctx.user, ctx.batch, "active-batch-bans"], old => (old || []).filter(b => b.profile_id !== p.profile_id));
      }
      document.querySelectorAll<HTMLDetailsElement>(".batchmate-actions[open]").forEach(el => { el.open = false; });
      await qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch, "friends"] });
      await activeBans.refetch();
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
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
    <div className="batch-screen" onTouchStart={e => { if(subTab === "batchmates" && window.scrollY <= 2) touchStart.current = e.touches[0]?.clientY ?? null; }} onTouchMove={e => { if(touchStart.current === null || window.scrollY > 2) return; const distance = Math.max(0, Math.min(90, (e.touches[0]?.clientY ?? touchStart.current) - touchStart.current)); setPullDistance(distance); }} onTouchEnd={() => { const shouldRefresh = pullDistance >= 55; touchStart.current = null; setPullDistance(0); if(shouldRefresh) { void friends.refetch(); if(canManageBatchmates) void activeBans.refetch(); } }}>
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
            <span className="eyebrow">{ctx.profile.role === "teacher" ? "ENROLLED STUDENTS" : "YOUR BATCH COMMUNITY"}</span>
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

      {ctx.profile.role === "teacher" && (ctx.batches || []).length > 0 && (
        <div className="segment modes teacher-batch-toggle" style={{ marginBottom: "16px", overflowX: "auto" }}>
          {ctx.batches!.map((b) => {
            const isSelected = ctx.batch === b.id;
            const label = `${b.departments?.code ? String(b.departments.code).toUpperCase() + " " : ""}Batch ${b.batch_number}`;
            return (
              <button
                key={b.id}
                type="button"
                className={isSelected ? "selected" : ""}
                onClick={() => ctx.changeBatch?.(b.id)}
              >
                {label}
              </button>
            );
          })}
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
          <div className="batchmate-refresh-row"><span>{pullDistance >= 55 ? "Release to refresh" : pullDistance > 0 ? "Pull to refresh" : ""}</span><button className="icon" aria-label="Refresh batchmates" title="Refresh batchmates" disabled={friends.isFetching} onClick={() => { void friends.refetch(); if(canManageBatchmates) void activeBans.refetch(); }}><RefreshCw size={17} className={friends.isFetching ? "spin" : undefined}/></button></div>
          {friends.isPending ? (
            <Skeleton />
          ) : (
            <div className="people-grid">
              {friends.data?.pages.flat().filter(p=>ctx.profile.role!=="teacher" || ["student", "admin"].includes(p.role)).map((p) => (
                <div
                  aria-disabled={busy}
                  role="button"
                  tabIndex={0}
                  onKeyDown={(e) => { if ((e.key === "Enter" || e.key === " ") && !busy) { e.preventDefault(); (e.currentTarget as HTMLDivElement).click(); } }}
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
                  {canManageBatchmates && p.role === "student" && p.profile_id !== ctx.user && (
                    <details className="batchmate-actions" onClick={(e) => e.stopPropagation()}>
                      <summary aria-label={`Manage ${p.full_name}`} title="Manage student"><MoreVertical size={18} /></summary>
                      <div className="batchmate-actions-menu">
                        <button disabled={busy} onClick={() => void manageStudent(p,"appoint")}>{p.is_cr ? "Revoke CR access" : "Make CR"}</button>
                        {!ctx.owner && !p.is_cr && <button disabled={busy} onClick={() => { if(window.confirm(`Transfer your CR role to ${p.full_name}?`)) void manageStudent(p,"transfer"); }}>Transfer my CR role</button>}
                        {activeBans.isPending || activeBans.isError ? null : (activeBans.data || []).some(b => b.profile_id === p.profile_id) ? <button disabled={busy} onClick={() => void manageStudent(p,"unban")}>Unban</button> : <button disabled={busy} onClick={() => void manageStudent(p,"ban")}>Ban temporarily</button>}
                      </div>
                    </details>
                  )}
                </div>
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
      {banTarget && (
        <Modal title={`Ban ${banTarget.full_name}`} close={() => setBanTarget(null)}>
          <div className="ban-dialog-body" style={{ display: "flex", flexDirection: "column", gap: "14px" }}>
            <p style={{ margin: 0, fontSize: "14px", color: "var(--muted)" }}>
              The student will be instantly locked out from ClassMate until the ban expires or is lifted by a CR.
            </p>
            <div>
              <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "6px" }}>Ban Duration</label>
              <select
                value={banDurationMode}
                onChange={e => {
                  const val = e.target.value as "3" | "7" | "custom" | "permanent";
                  setBanDurationMode(val);
                  if (val === "3") setBanDays(3);
                  else if (val === "7") setBanDays(7);
                  else if (val === "permanent") setBanDays(-1);
                  else {
                    const parsed = parseInt(customDaysInput, 10);
                    setBanDays(!isNaN(parsed) && parsed > 0 ? parsed : 14);
                  }
                }}
                style={{
                  width: "100%",
                  padding: "9px 12px",
                  borderRadius: "10px",
                  border: "1px solid var(--border)",
                  background: "var(--surface)",
                  color: "var(--foreground)",
                  fontSize: "14px",
                  fontWeight: 600,
                  outline: "none",
                  cursor: "pointer"
                }}
              >
                <option value="3">3 Days</option>
                <option value="7">7 Days</option>
                <option value="custom">Custom Days</option>
                <option value="permanent">Permanent Ban</option>
              </select>
            </div>

            {banDurationMode === "custom" && (
              <div>
                <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "6px" }}>
                  Number of Days
                </label>
                <input
                  type="number"
                  min="1"
                  max="3650"
                  value={customDaysInput}
                  onChange={e => {
                    setCustomDaysInput(e.target.value);
                    const parsed = parseInt(e.target.value, 10);
                    if (!isNaN(parsed) && parsed > 0) {
                      setBanDays(parsed);
                    }
                  }}
                  placeholder="Enter number of days (e.g. 14, 30, 90)"
                  style={{ width: "100%", boxSizing: "border-box" }}
                />
              </div>
            )}

            {banDurationMode === "permanent" && (
              <div style={{
                padding: "10px 12px",
                borderRadius: "10px",
                background: "rgba(239, 68, 68, 0.1)",
                border: "1px solid rgba(239, 68, 68, 0.25)",
                color: "#ef4444",
                fontSize: "12.5px",
                lineHeight: "1.4"
              }}>
                <strong>⚠️ Permanent Restriction:</strong> The student will be permanently locked out from ClassMate across Android and Web until manually unbanned.
              </div>
            )}

            <div>
              <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "6px" }}>Reason for ban</label>
              <input
                type="text"
                value={banReason}
                onChange={e => setBanReason(e.target.value)}
                placeholder="e.g., Disruption in batch group, misconduct"
                style={{ width: "100%", boxSizing: "border-box" }}
              />
            </div>
            <div style={{ display: "flex", gap: "10px", justifyContent: "flex-end", marginTop: "8px" }}>
              <button type="button" className="secondary" onClick={() => setBanTarget(null)}>Cancel</button>
              <button
                type="button"
                className="danger"
                disabled={busy || !banReason.trim() || (banDurationMode === "custom" && (!customDaysInput || parseInt(customDaysInput, 10) <= 0))}
                onClick={async () => {
                  try {
                    setBusy(true);
                    const finalDays = banDurationMode === "permanent" ? -1 : (banDurationMode === "custom" ? (parseInt(customDaysInput, 10) || 1) : banDays);
                    await rpc("manage_student_ban", { target_profile: banTarget.profile_id, target_days: finalDays, target_reason: banReason.trim(), target_lift: false });
                    qc.setQueryData<Row[]>([ctx.user, ctx.batch, "active-batch-bans"], old => [...(old || []).filter(b => b.profile_id !== banTarget.profile_id), { profile_id: banTarget.profile_id }]);
                    setBanTarget(null);
                    await qc.invalidateQueries({ queryKey: [ctx.user, ctx.batch, "friends"] });
                    await activeBans.refetch();
                  } catch(e) { setError(e); } finally { setBusy(false); }
                }}
              >
                {banDurationMode === "permanent" ? "Confirm Permanent Ban" : "Confirm Ban"}
              </button>
            </div>
          </div>
        </Modal>
      )}
      {person && (
        <Modal title={person.role === "teacher" ? "Teacher profile" : "Batchmate profile"} close={() => setPerson(null)}>
          <div className="friend-profile-content">
            <div className="profile-header friend-profile-header">
              <Avatar url={person.avatar_url} name={person.full_name} online={Boolean(person.is_online || onlineIds.has(person.profile_id))} />
              <div>
                <h3>{person.full_name}</h3>
                {person.role === "teacher" ? (
                  <small className="role cr-badge">Teacher</small>
                ) : person.student_id ? (
                  <small>{person.student_id}</small>
                ) : null}
              </div>
            </div>
            {person.role !== "teacher" ? (
              <>
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
              </>
            ) : (
              <div style={{ padding: "10px 0 4px", fontSize: "13px", color: "var(--muted)" }}>
                Faculty Member
              </div>
            )}
          </div>
        </Modal>
      )}
    </div>
  );
}

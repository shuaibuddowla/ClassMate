"use client";
import { useState, useEffect } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Search, Phone, MessageCircle, ChevronRight, Droplets } from "lucide-react";
import { BloodNetwork } from "./blood-network";
import { rpc, type Row } from "@/lib/api";
import type { Context } from "./app";
import { Avatar, Empty, ErrorBox, Modal, Skeleton } from "./ui";
export function Friends({ ctx }: { ctx: Context }) {
  const [blood, setBlood] = useState(false);
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
      if (!navigator.onLine) setPerson(null);
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
      <div className="page-heading">
        <div>
          <span className="eyebrow">YOUR BATCH COMMUNITY</span>
          <h1>Friends</h1>
        </div>
        <button className="text-button" onClick={()=>setBlood(true)}><Droplets size={18}/> Blood requests</button>
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
          {friends.data?.pages.flat().map((p) => (
            <button
              disabled={busy}
              className="card person"
              key={p.profile_id}
              onClick={async () => {
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

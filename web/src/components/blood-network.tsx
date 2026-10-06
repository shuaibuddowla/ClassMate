"use client";
import { useEffect, useState } from "react";
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  ArrowLeft,
  Droplets,
  Phone,
  Plus,
  ShieldCheck,
  HeartHandshake,
} from "lucide-react";
import { rpc, type Row } from "@/lib/api";
import type { Context } from "./app";
import { ErrorBox, Form, Modal, Skeleton, Empty } from "./ui";
const groups = ["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"];
export function BloodNetwork({
  ctx,
  close,
}: {
  ctx: Context;
  close: () => void;
}) {
  const qc = useQueryClient();
  const [create, setCreate] = useState(false),
    [requestKey, setRequestKey] = useState(""),
    [settings, setSettings] = useState(false),
    [selected, setSelected] = useState<Row | null>(null),
    [details, setDetails] = useState<Row | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null);
  const key = [ctx.user, "blood-network"];
  const [confirmStatus, setConfirmStatus] = useState<string | null>(null);
  const feed = useInfiniteQuery({
    queryKey: key,
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      rpc<Row[]>("blood_request_feed", { result_offset: pageParam }),
    getNextPageParam: (last, pages) =>
      last.length === 20 ? pages.length * 20 : undefined,
    refetchInterval: 60_000,
  });
  const prefs = useQuery({
    queryKey: [ctx.user, "blood-preferences"],
    queryFn: () => rpc("blood_preferences"),
  });
  useEffect(() => {
    let live = true;
    const load = () => {
      const match = location.hash.match(
        /^#friends\/blood\/([0-9a-f-]{36})(?:\?|$)/i,
      );
      if (match)
        rpc("blood_request_details", { target_request: match[1] })
          .then((d) => {
            if (live) setSelected(d);
          })
          .catch((e) => {
            if (live) setError(e);
          });
    };
    load();
    window.addEventListener("hashchange", load);
    return () => {
      live = false;
      window.removeEventListener("hashchange", load);
    };
  }, []);
  useEffect(() => {
    let live = true;
    setDetails(null);
    if (selected)
      rpc("blood_request_details", { target_request: selected.id })
        .then((d) => {
          if (live) setDetails(d);
        })
        .catch((e) => {
          if (live) setError(e);
        });
    return () => {
      live = false;
    };
  }, [selected]);
  useEffect(() => {
    if(!details || !selected || details.id !== selected.id || !ctx.active) return;
    rpc("mark_blood_request_read",{target_request:details.id}).then(()=>qc.invalidateQueries({queryKey:[ctx.user,ctx.batch,"unread-activity"]})).catch(()=>{});
  },[details,selected,ctx.active,ctx.user,ctx.batch,qc]);
  async function action(fn: () => Promise<unknown>) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await fn();
      await qc.invalidateQueries({ queryKey: key });
      setSelected(null);
      await qc.invalidateQueries({ queryKey: [ctx.user, "blood-preferences"] });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  async function status(value: string) {
    if (!selected) return;
    setConfirmStatus(value);
  }
  return (
    <section className="blood-network">
      <div className="page-heading">
        <div>
          <button className="text-button" onClick={close}>
            <ArrowLeft size={17} /> Friends
          </button>
          <h1>Blood requests</h1>
          <p>Verified help, when every minute matters.</p>
        </div>
        <button
          className="primary"
          onClick={() => {
            setRequestKey(crypto.randomUUID());
            setCreate(true);
          }}
        >
          <Plus size={18} /> Request
        </button>
      </div>
      <div className="blood-intro">
        <Droplets size={28} />
        <div>
          <strong>Be there for your campus</strong>
          <p>
            {prefs.data?.opted_in
              ? "You’re available to volunteer for matching requests."
              : "Verified requests alert everyone. Join to volunteer."}
          </p>
        </div>
        <button onClick={() => setSettings(true)}>Donor settings</button>
      </div>
      <p className="blood-medical">
        Red-cell donor matching only. The hospital must confirm your blood type,
        eligibility and cross-match. Do not delay emergency care.
      </p>
      <ErrorBox
        error={error || feed.error || prefs.error}
        retry={() => {
          feed.refetch();
          prefs.refetch();
        }}
      />
      {feed.isPending ? (
        <Skeleton />
      ) : !feed.data?.pages.flat().length ? (
        <Empty
          title="No requests right now"
          body="Requests appear here after an admin or CR verifies them."
        />
      ) : (
        <div className="blood-feed">
          {feed.data.pages.flat().map((r) => (
            <button
              key={r.id}
              className="card blood-request"
              onClick={() => {
                setError(null);
                setSelected(r);
              }}
            >
              <span className="blood-group">{r.blood_group}</span>
              <span>
                <strong>{r.hospital}</strong>
                <small>
                  {r.units} unit(s) ·{" "}
                  {r.audience === "university"
                    ? "University-wide"
                    : "Batch only"}
                </small>
                <small>
                  Needed by {new Date(r.needed_by).toLocaleString()}
                </small>
                <small>
                  {r.volunteers} volunteers ·{" "}
                  {r.status === "open" ? "Verified request" : r.status}
                </small>
              </span>
              <ShieldCheck size={20} />
            </button>
          ))}
        </div>
      )}
      {feed.hasNextPage && (
        <button
          disabled={feed.isFetchingNextPage}
          onClick={() => feed.fetchNextPage()}
        >
          {feed.isFetchingNextPage ? "Loading…" : "More requests"}
        </button>
      )}
      {create && (
        <Modal title="Request blood" close={() => setCreate(false)}>
          <p className="blood-medical">
            Confirm these details with the attendant. An admin or your batch CR
            will verify the request before any alerts are sent.
          </p>
          <Form
            label="Submit for verification"
            fields={[
              {
                name: "target_group",
                label: "Blood group needed",
                required: true,
                options: groups.map((value) => ({ value, label: value })),
              },
              {
                name: "target_hospital",
                label: "Hospital / patient location",
                required: true,
              },
              {
                name: "target_units",
                label: "Units (pints) requested",
                type: "number",
                min: "1",
                max: "20",
                value: 1,
                required: true,
              },
              {
                name: "target_deadline",
                label: "Needed by",
                type: "datetime-local",
                required: true,
              },
              {
                name: "target_phone",
                label: "Attendant’s phone",
                type: "tel",
                placeholder: "01XXXXXXXXX",
                required: true,
              },
              {
                name: "target_patient_name", label: "Patient / requested for (optional)",
              },
              {
                name: "matching",
                label: "Donor recruitment",
                required: true,
                options: [
                  { value: "exact", label: "Same blood group" },
                  {
                    value: "compatible",
                    label: "Hospital accepts compatible red-cell donors",
                  },
                ],
              },
            ]}
            submit={async (d) => {
              let phone = String(d.target_phone).replace(/[\s()-]/g, "");
              if (/^01\d{9}$/.test(phone)) phone = "+88" + phone;
              await rpc("create_blood_request", {
                request_key: requestKey,
                target_batch: ctx.batch,
                target_group: d.target_group,
                target_hospital: d.target_hospital,
                target_units: Number(d.target_units),
                target_deadline: new Date(d.target_deadline).toISOString(),
                target_phone: phone,
                target_audience: "university",
                target_patient_name: d.target_patient_name || null,
                allow_compatible: d.matching === "compatible",
              });
              setCreate(false);
              await qc.invalidateQueries({ queryKey: key });
            }}
          />
        </Modal>
      )}
      {settings && (
        <Modal title="Your donor preferences" close={() => setSettings(false)}>
          <p>
            Make yourself available to volunteer. Volunteering shares your name
            and mobile number only with the request’s organizer and verifier.
          </p>
          <p>
            Everyone receives verified blood requests. A recorded donation pauses the matching appeal for 120 days. This does
            not certify medical eligibility. Also enable Push notifications in
            Profile.
          </p>
          <p>
            Last recorded donation:{" "}
            {prefs.data?.last_donation || "Not recorded"}
          </p>
          <Form
            label="Save preferences"
            fields={[
              {
                name: "enabled",
                label: "Available to volunteer",
                required: true,
                value: prefs.data?.opted_in ? "yes" : "no",
                options: [
                  { value: "no", label: "Off" },
                  { value: "yes", label: "On — I consent to participate" },
                ],
              },
              {
                name: "date",
                label: "Record a donation (optional)",
                type: "date",
                max: new Date().toLocaleDateString("en-CA"),
              },
            ]}
            submit={async (d) => {
              await rpc("save_blood_preferences", {
                target_enabled: d.enabled === "yes",
                donation_date: d.date || null,
              });
              setSettings(false);
              await qc.invalidateQueries({
                queryKey: [ctx.user, "blood-preferences"],
              });
              await qc.invalidateQueries({ queryKey: key });
            }}
          />
        </Modal>
      )}
      {selected && (
        <Modal
          title={`${selected.blood_group} blood request`}
          close={() => {
            if (!busy) setSelected(null);
          }}
        >
          <div className="blood-detail">
            <h3>{selected.hospital}</h3>
            <p>
              {selected.units} unit(s) ·{" "}
              {new Date(selected.needed_by).toLocaleString()}
            </p>
            <p>
              {selected.status === "open"
                ? "Verified by an admin or batch CR"
                : selected.status}
            </p>
            <p className="blood-medical">
              Hospital screening and cross-matching are required before
              donation.
            </p>
            {!details ? (
              <Skeleton />
            ) : (
              <>
                <p>For: {details.patient_name || details.requester_name}</p>
                <p>Requested by: {details.requester_name}</p><p>Attendant: {details.attendant_phone}</p>
                <a className="primary" href={`tel:${details.attendant_phone}`}>
                  <Phone size={18} /> Call attendant
                </a>
                {selected.can_donate &&
                  selected.my_response !== "interested" && (
                    <button
                      disabled={busy}
                      className="primary"
                      onClick={() =>
                        action(() =>
                          rpc("respond_blood_request", {
                            target_request: selected.id,
                            target_response: "interested",
                          }),
                        )
                      }
                    >
                      <HeartHandshake size={18} /> I can donate
                    </button>
                  )}
                {selected.my_response === "interested" && (
                  <>
                    <p>
                      You volunteered. Please call the attendant to arrange
                      screening.
                    </p>
                    <button
                      disabled={busy}
                      onClick={() =>
                        action(() =>
                          rpc("respond_blood_request", {
                            target_request: selected.id,
                            target_response: "withdrawn",
                          }),
                        )
                      }
                    >
                      Withdraw response
                    </button>
                  </>
                )}
                {selected.can_verify && selected.status === "pending" && (
                  <>
                    <p>
                      Call the attendant and verify the hospital, blood group,
                      units and deadline before approving.
                    </p>
                    <button
                      disabled={busy}
                      className="primary"
                      onClick={() => status("open")}
                    >
                      Verify & notify university
                    </button>
                    <button disabled={busy} onClick={() => status("rejected")}>
                      Reject request
                    </button>
                  </>
                )}
                {details.can_manage &&
                  ["open", "pending"].includes(selected.status) && (
                    <>
                      <button
                        disabled={busy}
                        onClick={() => status("fulfilled")}
                      >
                        Mark fulfilled
                      </button>
                      <button
                        disabled={busy}
                        onClick={() => status("cancelled")}
                      >
                        Cancel request
                      </button>
                      <h3>Volunteers</h3>
                      {details.volunteers.map((p: Row) => (
                        <div className="blood-volunteer" key={p.id}>
                          <strong>{p.full_name || "Student"}</strong>
                          <a href={`tel:${p.mobile_number}`}>
                            <Phone size={16} /> Call volunteer
                          </a>
                        </div>
                      ))}
                    </>
                  )}
              </>
            )}
          </div>
          <ErrorBox error={error} />
        </Modal>
      )}
      {confirmStatus && selected && (
        <Modal
          title={
            confirmStatus === "open"
              ? "Verify this request?"
              : "Close this request?"
          }
          close={() => setConfirmStatus(null)}
        >
          <p>
            {confirmStatus === "open"
              ? "Confirm you called the attendant and verified all request details. Users across the university will receive an alert."
              : "This closes the request and stops pending blood alerts."}
          </p>
          <button
            disabled={busy}
            className="primary"
            onClick={async () => {
              const value = confirmStatus;
              setConfirmStatus(null);
              await action(() =>
                rpc("set_blood_request_status", {
                  target_request: selected.id,
                  target_status: value,
                }),
              );
            }}
          >
            Confirm
          </button>
        </Modal>
      )}
    </section>
  );
}

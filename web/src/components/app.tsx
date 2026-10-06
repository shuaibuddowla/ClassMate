"use client";
import { academicSession, sessionEnd } from "@/lib/academic-session";
import {
  ResizeHandle,
  usePanelWidths,
  type ResizeHandleProps,
} from "./panel-resize";
import { useEffect, useState, useRef, lazy, Suspense } from "react";
import { AndroidDownload } from "./android-download";
import { AboutDeveloper } from "./about-developer";
import { OrbitLogo } from "./orbit-logo";
import { InstallProvider, InstallWebApp } from "./install-web-app";
import { AppUpdate } from "./app-update";
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  CalendarDays,
  Bell,
  BookOpen,
  Users,
  User,
  LogOut,
  WifiOff,
  ArrowRight,
  Smartphone,
  Info,
} from "lucide-react";
import { supabase, configured, rpc, rows, openFile, type Row } from "@/lib/api";
import {
  clearAcademic,
  clearIdentity,
  offlineIdentity,
  saveIdentity,
  readAcademic,
} from "@/lib/cache";
import { Avatar, ErrorBox, Form, Modal, Skeleton } from "./ui";
import { Schedule } from "./schedule";
import { useAcademic } from "@/lib/queries";
import { Notices } from "./notices";
import { Library } from "./library";
import { Friends } from "./friends";
import { Profile, ProfileForm } from "./profile";
import { enablePush, removePush, restorePush, syncPushIdentity } from "@/lib/push";
const ClassMateAI = lazy(() => import("./classmate-ai"));
const Manage = lazy(() => import("./manage"));
export type Context = {
  user: string;
  profile: Row;
  batch: string;
  batchInfo: Row;
  owner: boolean;
  courses: Row[];
  refreshProfile: (p: Row) => void;
  manage: () => void;
  leaveManage?: () => void;
  active?: boolean;
  ai?: () => void;
  batches?: Row[];
  changeBatch?: (batch: string) => void;
};
const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 60_000, retry: 1, refetchOnWindowFocus: false },
  },
});
export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <InstallProvider>
        <AppUpdate />
        <SessionApp />
      </InstallProvider>
    </QueryClientProvider>
  );
}
function SessionApp() {
  const [user, setUser] = useState<string | null>(null),
    [loading, setLoading] = useState(true),
    [profile, setProfile] = useState<Row | null>(null),
    [error, setError] = useState<unknown>(null),
    [stage, setStage] = useState(""),
    [pushError, setPushError] = useState<unknown>(null),
    [enablingPush, setEnablingPush] = useState(false),
    [theme, setTheme] = useState("system");
  const qc = useQueryClient();
  const activeUser = useRef<string | null>(null);
  useEffect(() => {
    const theme = localStorage.getItem("classmate:theme") || "system";
    setTheme(theme);
    if (!configured()) {
      setLoading(false);
      return;
    }
    const client = supabase();
    let live = true;
    if (!navigator.onLine) {
      const saved = offlineIdentity();
      if (saved) {
        activeUser.current = saved.id;
        setUser(saved.id);
        setProfile(saved);
        setLoading(false);
        return;
      }
    }
    client.auth
      .getSession()
      .then(async ({ data, error }) => {
        if (error) throw error;
        if (data.session && live) {
          await initialize(
            data.session.user.id,
            data.session.user.user_metadata,
          );
        } else if (live) setLoading(false);
      })
      .catch((e) => {
        setError(e);
        setLoading(false);
      });
    const { data } = client.auth.onAuthStateChange((event, session) => {
      if (event === "SIGNED_OUT") {
        qc.clear();
        setProfile(null);
        setUser(null);
        clearAcademic();
        clearIdentity();
      }
      if (event === "SIGNED_IN" && session) {
        if (activeUser.current && activeUser.current !== session.user.id) {
          qc.clear();
          clearAcademic();
          clearIdentity();
          location.reload();
          return;
        }
        setUser(session.user.id);
      }
    });
    async function initialize(id: string, metadata: Row = {}) {
      activeUser.current = id;
      try {
        const existing = await rows(
          "profiles",
          { id },
          "id,profile_completed_at,profile_remind_after",
        );
        let p: Row = {
          ...(await rpc("create_or_initialize_profile")),
          avatar_url: metadata.avatar_url || metadata.picture,
        };
        if (p.role === "student" && !p.department_id) {
          const prefix = /^([a-z]+)[0-9]{2}[0-9]{3,4}@mbstu\.ac\.bd$/.exec(
            String(p.email).toLowerCase(),
          )?.[1];
          if (prefix) {
            const matches = (
              await rows("departments", { is_active: true }, "id,email_prefix")
            ).filter((d) => d.email_prefix === prefix);
            if (matches.length === 1)
              p = {
                ...(await rpc<Row>("complete_student_onboarding", {
                  selected_department: matches[0].id,
                })),
                avatar_url: p.avatar_url,
              };
          }
        }
        if (!live) return;
        setUser(id);
        setProfile(p);
        saveIdentity(p);
        const signed = new URL(location.href).searchParams.has("signedin");
        history.replaceState({}, "", location.pathname + location.hash);
        if (p.verification_status === "active") {
          if (existing.length === 0) setStage("account");
          else if (
            !p.profile_completed_at &&
            (!p.profile_remind_after ||
              new Date(p.profile_remind_after) < new Date())
          )
            setStage("profile");
          else if (
            signed &&
            "Notification" in window &&
            Notification.permission !== "granted"
          )
            setStage("notifications");
        }
      } catch (e) {
        setError(e);
      } finally {
        if (live) setLoading(false);
      }
    }
    return () => {
      live = false;
      data.subscription.unsubscribe();
    };
  }, [qc]);
  useEffect(() => {
    const media = matchMedia("(prefers-color-scheme: dark)");
    const apply = () => {
      const active =
        !user || theme === "system"
          ? media.matches
            ? "dark"
            : "light"
          : theme;
      document.documentElement.dataset.theme = active;
      document
        .querySelectorAll('meta[name="theme-color"]')
        .forEach((meta) =>
          meta.setAttribute(
            "content",
            active === "dark" ? "#040a18" : "#f4f7fc",
          ),
        );
    };
    apply();
    media.addEventListener("change", apply);
    localStorage.setItem("classmate:theme", theme);
    return () => media.removeEventListener("change", apply);
  }, [theme, user]);
  async function signOut() {
    await removePush(user || undefined, false).catch(() => {});
    clearAcademic();
    clearIdentity();
    qc.clear();
    await supabase().auth.signOut({ scope: "local" });
    setUser(null);
    setProfile(null);
    setStage("");
  }
  if (loading)
    return (
      <div
        className="boot boot-orbit"
        role="status"
        aria-label="Loading ClassMate"
      >
        <OrbitLogo />
        <ErrorBox error={error} />
      </div>
    );
  if (!user || !profile)
    return (
      <main className="landing">
        <section className="login card">
          <div className="signin-brand">
            <img src="/logo.png" alt="ClassMate logo" />
            <strong>ClassMate</strong>
          </div>
          <OrbitLogo />
          <h2>
            Welcome to
            <br />
            ClassMate.
          </h2>
          <p>Sign in with your @mbstu.ac.bd university email.</p>
          <ErrorBox error={error} />
          <button
            className="primary wide"
            disabled={!configured()}
            onClick={async () => {
              try {
                const { error } = await supabase().auth.signInWithOAuth({
                  provider: "google",
                  options: {
                    redirectTo: `${location.origin}/auth/callback`,
                    queryParams: {
                      hd: "mbstu.ac.bd",
                      prompt: "select_account",
                    },
                  },
                });
                if (error) throw error;
              } catch (e) {
                setError(e);
              }
            }}
          >
            Continue with Google <ArrowRight size={18} />
          </button>
          <p className="teacher-signin-hint">Teachers: use your admin-approved university email or Gmail.</p>
          <button className="secondary wide" disabled={!configured()} onClick={async()=>{
            try { const {error}=await supabase().auth.signInWithOAuth({provider:"google",options:{redirectTo:`${location.origin}/auth/callback`,queryParams:{prompt:"select_account"}}}); if(error) throw error; } catch(e) {setError(e)}
          }}>Teacher sign-in <ArrowRight size={18}/></button>
          <InstallWebApp />
          {!configured() && (
            <p>Server setup is required before sign-in is available.</p>
          )}
        </section>
      </main>
    );
  if (profile.verification_status !== "active")
    return (
      <main className="boot">
        <h1>
          {!profile.department_id
            ? "Find your department"
            : "Your account is awaiting approval"}
        </h1>
        <p>
          {profile.rejection_reason ||
            "Your university identity determines your batch and access."}
        </p>
        {!profile.department_id && (
          <DepartmentOnboarding
            complete={(p) => {
              setProfile(p);
              if (p.verification_status === "active") setStage("account");
            }}
          />
        )}
        <ErrorBox error={error} />
        <button
          onClick={() =>
            rpc("create_or_initialize_profile").then(setProfile).catch(setError)
          }
        >
          Refresh status
        </button>
        <button onClick={signOut}>Sign out</button>
      </main>
    );
  return (
    <>
      <Home
        user={user}
        profile={profile}
        refreshProfile={setProfile}
        signOut={signOut}
        theme={theme}
        setTheme={setTheme}
      />
      {pushError && (
        <div className="push-feedback" role="status">
          <ErrorBox error={pushError} />
          <button className="text-button" onClick={() => setPushError(null)}>
            Dismiss
          </button>
        </div>
      )}
      {stage === "account" && (
        <Modal title="We found your account" close={() => setStage("profile")}>
          <Avatar name={profile.full_name} url={profile.avatar_url} />
          <h3>{profile.full_name}</h3>
          <dl>
            {(profile.role==="teacher" ? ["role"] : ["student_id", "academic_session", "role"]).map((key) => (
              <div key={key}>
                <dt>{key.replace("_", " ")}</dt>
                <dd>
                  {key === "academic_session"
                    ? academicSession(profile[key])
                    : (profile[key] ?? "—")}
                </dd>
              </div>
            ))}
          </dl>
          <p>
            {profile.role==="teacher" ? "Your teaching access comes from the courses assigned by your administrator." : "Your department and batch are assigned by the university account rules."}
          </p>
          <button className="primary wide" onClick={() => setStage("profile")}>
            Continue
          </button>
        </Modal>
      )}
      {stage === "profile" && (
        <Modal
          title="Complete your profile"
          close={() => {
            rpc("defer_profile_completion").then(setProfile).catch(setError);
            setStage("notifications");
          }}
        >
          <p>Help your batchmates find and contact you.</p>
          <ProfileForm
            profile={profile}
            done={(p) => {
              setProfile(p);
              setStage("notifications");
            }}
          />
          <button
            className="text-button"
            onClick={async () => {
              try {
                setProfile(await rpc("defer_profile_completion"));
                setStage("notifications");
              } catch (e) {
                setError(e);
              }
            }}
          >
            Remind me later
          </button>
        </Modal>
      )}
      {stage === "notifications" && (
        <Modal title="Stay up to date" close={() => setStage("")}>
          <p>
            Get timely notices for your batch, even when ClassMate is closed.
          </p>
          <ErrorBox error={error} />
          <button
            className="primary wide"
            disabled={enablingPush}
            onClick={async () => {
              setEnablingPush(true);
              try {
                setError(null);
                setPushError(null);
                await enablePush(profile.id, () => setStage(""));
              } catch (e) {
                if (
                  "Notification" in window &&
                  Notification.permission === "granted"
                )
                  setPushError(e);
                else setError(e);
              } finally {
                setEnablingPush(false);
              }
            }}
          >
            Enable notifications
          </button>
          <button className="text-button" onClick={() => setStage("")}>
            Not now
          </button>
        </Modal>
      )}
    </>
  );
}
function DepartmentOnboarding({ complete }: { complete: (p: Row) => void }) {
  const departments = useQuery({
    queryKey: ["departments"],
    queryFn: () => rows("departments", { is_active: true }),
  });
  return (
    <Form
      fields={[
        {
          name: "selected_department",
          label: "Department",
          required: true,
          options: (departments.data || []).map((d) => ({
            value: d.id,
            label: d.name,
          })),
        },
        {
          name: "entered_student_id",
          label: "Student ID (if your email cannot be parsed)",
        },
        {
          name: "entered_batch_number",
          label: "Batch (manual profiles only)",
          type: "number",
        },
        {
          name: "entered_session",
          label: "Session (e.g. 24-25; manual profiles only)",
        },
      ]}
      label="Find my batch"
      submit={async (d) =>
        complete(
          await rpc("complete_student_onboarding", {
            selected_department: d.selected_department,
            entered_student_id: d.entered_student_id || null,
            entered_batch_number: d.entered_batch_number
              ? Number(d.entered_batch_number)
              : null,
            entered_session: d.entered_session
              ? sessionEnd(d.entered_session)
              : null,
          }),
        )
      }
    />
  );
}
function TabDeck({
  tab,
  ctx,
  theme,
  setTheme,
  signOut,
  desktop,
  resizeNotices,
}: {
  resizeNotices: ResizeHandleProps;
  desktop: boolean;
  tab: string;
  ctx: Context;
  theme: string;
  setTheme: (s: string) => void;
  signOut: () => Promise<void>;
}) {
  const current =
    tab === "ai" && !ctx.ai
      ? "profile"
      : desktop && tab === "notices"
        ? "timetable"
        : tab;
  const rail = useRef<HTMLDivElement>(null);
  const [visited, setVisited] = useState(() => new Set([current]));
  useEffect(() => {
    setVisited((old) => new Set([...old, current]));
  }, [current]);
  useEffect(() => {
    const restore = () => {
      if (document.visibilityState === "visible") void restorePush(ctx.user).catch(() => {});
    };
    restore();
    window.addEventListener("online", restore);
    window.addEventListener("focus", restore);
    document.addEventListener("visibilitychange", restore);
    return () => {
      window.removeEventListener("online", restore);
      window.removeEventListener("focus", restore);
      document.removeEventListener("visibilitychange", restore);
    };
  }, [ctx.user]);
  const render = (value: string) => {
    const active = { ...ctx, active: value === current };
    if (value === "profile")
      return (
        <Profile
          ctx={active}
          theme={theme}
          setTheme={setTheme}
          signOut={signOut}
        />
      );
    if (value === "manage")
      return (
        <Suspense fallback={<Skeleton />}>
          <Manage
            ctx={active}
            back={() => {
              ctx.leaveManage?.();
            }}
          />
        </Suspense>
      );
    if (!ctx.batch)
      return (
        <div className="card">
          <h2>No running batch available</h2>
          <p>
            Contact your administrator or configure your academic structure.
          </p>
          {ctx.owner && <button onClick={ctx.manage}>Open Manage</button>}
        </div>
      );
    if (value === "ai")
      return (
        <Suspense fallback={<Skeleton />}>
          <ClassMateAI ctx={active} />
        </Suspense>
      );
    return value === "timetable" ? (
      <Schedule ctx={active} />
    ) : value === "notices" ? (
      <Notices ctx={active} />
    ) : value === "library" ? (
      <Library ctx={active} />
    ) : (
      <Friends ctx={active} />
    );
  };
  return (
    <div className="tab-layout">
      <div className="primary-pane">
        {Array.from(new Set([...visited, current]))
          .filter((value) => value !== "notices")
          .map((value) => (
            <section hidden={value !== current} key={value}>
              {render(value)}
            </section>
          ))}
      </div>
      <ResizeHandle {...resizeNotices} className="notices-resizer" />
      <div
        ref={rail}
        className={`notice-pane ${tab === "notices" ? "mobile-active" : ""}`}
        role="complementary"
        tabIndex={desktop ? 0 : -1}
        aria-label="Batch notices"
      >
        {ctx.batch ? (
          <Notices
            ctx={{ ...ctx, active: desktop || tab === "notices" }}
            scrollContainer={desktop ? rail : undefined}
          />
        ) : tab === "notices" ? (
          render("notices")
        ) : null}
      </div>
    </div>
  );
}
function Home({
  user,
  profile,
  refreshProfile,
  signOut,
  theme,
  setTheme,
}: {
  user: string;
  profile: Row;
  refreshProfile: (p: Row) => void;
  signOut: () => Promise<void>;
  theme: string;
  setTheme: (s: string) => void;
}) {
  const panels = usePanelWidths();
  const [androidDownload, setAndroidDownload] = useState(false);
  const [aboutDeveloper, setAboutDeveloper] = useState(false),
    [signingOut, setSigningOut] = useState(false);
  const [desktop, setDesktop] = useState(false);
  useEffect(() => {
    const media = matchMedia("(min-width: 1001px)");
    const update = () => setDesktop(media.matches);
    update();
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);
  const [teacherEntered,setTeacherEntered]=useState(false);
  const [tab, setTab] = useState("timetable"),
    [batch, setBatch] = useState(""),
    [online, setOnline] = useState(true),
    [linked, setLinked] = useState<Row | null>(null),
    [linkError, setLinkError] = useState<unknown>(null);
  const qc = useQueryClient();
  const unread = useQuery({
    queryKey: [user, batch, "unread-activity"],
    queryFn: () => rpc<Row>("unread_activity", { target_batch: batch }),
    enabled: online && !!batch, refetchInterval: 30_000,
  });
  function unreadCount(value: string) { return Number(value === "notices" ? unread.data?.notices : value === "friends" ? unread.data?.blood_requests : 0) || 0; }
  const owner = useQuery({
    queryKey: [user, "owner"],
    queryFn: () => rpc<boolean>("is_owner"),
  });
  const batches = useAcademic<Row[]>(user, "account", "batches", () => rpc<Row[]>("available_batches"));
  const courses = useAcademic<Row[]>(user, batch, "courses", () =>
    rpc<Row[]>("batch_course_catalog", { target_batch: batch }),
  );
  const aiAccess = useQuery({
    queryKey: [user, batch, "ai-access"],
    queryFn: () => rpc<boolean>("ai_can_write", { target_batch: batch }),
    enabled: !!batch,
    staleTime: 0,
  });
  useEffect(() => {
    const data = batches.data || [];
    if (!data.length) return;
    if(profile.role==="teacher" && !teacherEntered) return;
    setBatch((current) =>
      data.some((b) => b.id === current)
        ? current
        : profile.role === "student"
          ? data.find((b) => b.id === profile.batch_id)?.id || ""
          : data.find(
              (b) => b.id === localStorage.getItem(`classmate:batch:${user}`),
            )?.id || data[0].id,
    );
  }, [batches.data, profile, user, teacherEntered]);
  useEffect(() => {
    const update = () => setOnline(navigator.onLine);
    update();
    window.addEventListener("online", update);
    window.addEventListener("offline", update);
    return () => {
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
    };
  }, []);
  useEffect(() => {
    if (!batch) return;
    syncPushIdentity(user, batch);
    localStorage.setItem(`classmate:batch:${user}`, batch);
    const channel = supabase()
      .channel(`web:${user}:${batch}`)
      .on(
        "postgres_changes",
        {
          event: "*",
          schema: "classmate",
          table: "notices",
          filter: `batch_id=eq.${batch}`,
        },
        () => {
          qc.invalidateQueries({ queryKey: [user, batch, "notices"] });
          qc.invalidateQueries({ queryKey: [user, batch, "unread-activity"] });
        },
      )
      .subscribe();
    return () => {
      supabase().removeChannel(channel);
    };
  }, [user, batch, qc]);
  useEffect(() => {
    let live = true;
    const navigate = async () => {
      const [hash, id] = location.hash.slice(1).split("/");
      if (
        [
          "timetable",
          "notices",
          "library",
          "friends",
          "profile",
          "manage",
          "ai",
        ].includes(hash)
      )
        setTab(hash);
      if (!id || !["notices", "library"].includes(hash) || !navigator.onLine)
        return;
      if (!/^[0-9a-f-]{36}$/i.test(id)) return;
      try {
        const records = await rows(
          hash === "library" ? "file_metadata" : "notices",
          { id },
        );
        const record = records[0];
        if (!live) return;
        if (!record)
          throw new Error(
            "This update is unavailable or your access has changed.",
          );
        if (record.batch_id) setBatch(record.batch_id);
        setLinked({ ...record, linkKind: hash });
      } catch (e) {
        if (live) setLinkError(e);
      }
    };
    navigate();
    window.addEventListener("hashchange", navigate);
    window.addEventListener("popstate", navigate);
    return () => {
      live = false;
      window.removeEventListener("hashchange", navigate);
      window.removeEventListener("popstate", navigate);
    };
  }, [user]);
  const info = batches.data?.find((b) => b.id === batch) || {};
  const ctx: Context = {
    user,
    profile,
    batch,
    batchInfo: info,
    batches: (batches.data || []).filter(
      (b) => profile.role !== "student" || b.id === profile.batch_id,
    ),
    changeBatch: setBatch,
    owner: owner.data === true,
    courses: courses.data || [],
    refreshProfile,
    ai: aiAccess.data === true ? () => select("ai") : undefined,
    manage: () => {
      history.replaceState({}, "", "/#profile");
      history.pushState({ classmateManage: true }, "", "/#manage");
      setTab("manage");
    },
    leaveManage: () => {
      if (history.state?.classmateManage) history.back();
      else {
        history.replaceState({}, "", "/#profile");
        setTab("profile");
      }
    },
  };
  const destinations = [
    ["timetable", "Timetable", CalendarDays],
    ["notices", "Notices", Bell],
    ["library", "Library", BookOpen],
    ["friends", profile.role==="teacher" ? "Students" : "Friends", Users],
    ["profile", "Profile", User],
  ] as const;
  const select = (value: string) => {
    if (value === tab) return;
    setTab(value);
    history.replaceState({}, "", `/#${value}`);
  };
  const navigationTab = tab === "ai" ? "profile" : desktop && tab === "notices" ? "timetable" : tab;
  if(profile.role==="teacher" && !teacherEntered) return <main className="teacher-batch-picker"><section className="card">
    <OrbitLogo/><span className="eyebrow">TEACHER WORKSPACE</span><h1>Choose your classroom.</h1><p>Select an assigned batch to open your teaching workspace.</p>
    <ErrorBox error={batches.error} retry={()=>batches.refetch()}/>
    {batches.isPending ? <Skeleton/> : !(batches.data || []).length ? <p>No active courses are assigned yet. Ask your administrator to assign a course, then refresh.</p> : <div className="teacher-batch-list">{batches.data!.map(b=><button className="secondary" key={b.id} onClick={()=>{setBatch(b.id);setTeacherEntered(true)}}><strong>{b.departments?.code?.toUpperCase()} Batch {b.batch_number}</strong><span>Session {academicSession(b.academic_session)} <ArrowRight size={18}/></span></button>)}</div>}
    <div className="actions"><button onClick={()=>batches.refetch()}>Refresh</button><button onClick={()=>signOut()}>Sign out</button></div>
  </section></main>;
  return (
    <div className="app" ref={panels.root} style={panels.style}>
      <aside className="sidebar">
        <a
          className="brand"
          href="/"
          onClick={(e) => {
            e.preventDefault();
            select("timetable");
          }}
        >
          <img src="/logo.png" alt="" />
          <span>
            ClassMate<small>University workspace</small>
          </span>
        </a>
        <span className="sidebar-label">Workspace</span>
        <nav aria-label="Desktop navigation">
          {destinations.map(([value, label, Icon]) => (
            <button
              key={value}
              data-destination={value}
              aria-current={
                navigationTab === value
                  ? "page"
                  : undefined
              }
              className={
                navigationTab === value
                  ? "selected"
                  : ""
              }
              onClick={() => select(value)}
            >
              <span className="nav-icon"><Icon size={21} />{unreadCount(value)>0 && <span className="unread-badge" aria-label={`${unreadCount(value)} unread`}>{unreadCount(value)>99 ? "99+" : unreadCount(value)}</span>}</span>
              <span>{label}</span>
            </button>
          ))}
        </nav>
        <button
          className="sidebar-android"
          onClick={() => setAndroidDownload(true)}
        >
          <Smartphone size={20} />
          <span>Try Android app</span>
        </button>
        <InstallWebApp />
        <div className="sidebar-foot">
          <div className="sidebar-account-actions">
            <button onClick={() => setAboutDeveloper(true)}>
              <Info size={18} />
              <span>About developer</span>
            </button>
            <button
              disabled={signingOut}
              onClick={async () => {
                setSigningOut(true);
                try {
                  await signOut();
                } catch (e) {
                  setLinkError(e);
                  setSigningOut(false);
                }
              }}
            >
              <LogOut size={18} />
              <span>{signingOut ? "Signing out…" : "Sign out"}</span>
            </button>
          </div>
          <span>
            <strong>CLASSMATE WEB</strong>
            <small>Your academic day, organized.</small>
          </span>
        </div>
      </aside>
      <ResizeHandle {...panels.sidebar} className="sidebar-resizer" />
      <div className="workspace">
        {!online && (
          <div className="offline">
            <WifiOff size={15} />
            Offline · saved academic content
            {Math.max(
              ...["routine", "notices", "calendar", "buses"].map(
                (k) => readAcademic(user, batch, k)?.syncedAt || 0,
              ),
            ) > 0 && (
              <span>
                {" "}
                · Last sync{" "}
                {new Date(
                  Math.max(
                    ...["routine", "notices", "calendar", "buses"].map(
                      (k) => readAcademic(user, batch, k)?.syncedAt || 0,
                    ),
                  ),
                ).toLocaleString()}
              </span>
            )}
          </div>
        )}
        <ErrorBox error={batches.error || linkError} />
        <main className="content">
          <TabDeck
            key={batch}
            desktop={desktop}
            resizeNotices={panels.notices}
            tab={tab}
            ctx={ctx}
            theme={theme}
            setTheme={setTheme}
            signOut={signOut}
          />
        </main>
      </div>
      {linked && (
        <Modal title={linked.title} close={() => setLinked(null)}>
          {linked.body && <p className="notice-body">{linked.body}</p>}
          {(linked.linkKind === "library" || linked.resource_id) && (
            <button
              className="primary"
              onClick={() =>
                openFile(linked.resource_id || linked.id).catch(setLinkError)
              }
            >
              Open resource
            </button>
          )}
          {linked.body && (
            <button
              className="secondary"
              onClick={() =>
                navigator.clipboard
                  .writeText(`${linked.title}\n\n${linked.body}`)
                  .catch(setLinkError)
              }
            >
              Copy notice
            </button>
          )}
        </Modal>
      )}
      {androidDownload && (
        <AndroidDownload close={() => setAndroidDownload(false)} />
      )}
      {aboutDeveloper && (
        <AboutDeveloper user={user} close={() => setAboutDeveloper(false)} />
      )}
      <nav className="bottom-nav" aria-label="Main navigation">
        {destinations.map(([value, label, Icon]) => (
          <button
            key={value}
            aria-current={navigationTab === value ? "page" : undefined}
            className={navigationTab === value ? "selected" : ""}
            onClick={() => select(value)}
          >
            <span className="nav-icon"><Icon size={21} />{unreadCount(value)>0 && <span className="unread-badge" aria-label={`${unreadCount(value)} unread`}>{unreadCount(value)>99 ? "99+" : unreadCount(value)}</span>}</span>
            <span>{label}</span>
          </button>
        ))}
      </nav>
    </div>
  );
}

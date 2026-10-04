"use client";
import { academicSession } from "@/lib/academic-session";
import { useState, useEffect } from "react";
import {
  Shield,
  Bell,
  LogOut,
  ChevronRight,
  UserRoundPen,
  Moon,
  Smartphone,
  GraduationCap,
  LayoutGrid,
  Sparkles,
} from "lucide-react";
import { rpc, type Row } from "@/lib/api";
import type { Context } from "./app";
import { Avatar, Form, Modal, ErrorBox } from "./ui";
import { enablePush, removePush, restorePush } from "@/lib/push";
import { pushEnabled as isPushEnabled, pushRegistrationState } from "@/lib/push-state";
import { InstallWebApp } from "./install-web-app";
export function ProfileForm({
  profile,
  done,
}: {
  profile: Row;
  done: (p: Row) => void;
}) {
  return (
    <Form
      fields={[
        {
          name: "target_mobile",
          label: "Mobile number",
          type: "tel",
          required: true,
          value: profile.mobile_number,
          placeholder: "01XXXXXXXXX or +880…",
        },
        {
          name: "target_town",
          label: "Home town",
          required: true,
          value: profile.home_town,
        },
        {
          name: "target_blood",
          label: "Blood group",
          required: true,
          value: profile.blood_group || "Unknown",
          options: [
            "A+",
            "A-",
            "B+",
            "B-",
            "AB+",
            "AB-",
            "O+",
            "O-",
            "Unknown",
          ].map((value) => ({ value, label: value })),
        },
        {
          name: "target_residence",
          label: "Current mess / flat (optional)",
          value: profile.current_residence,
        },
      ]}
      label="Save profile"
      submit={async (d) => done(await rpc("save_profile_details", d))}
    />
  );
}
export function Profile({
  ctx,
  theme,
  setTheme,
  signOut,
}: {
  ctx: Context;
  theme: string;
  setTheme: (s: string) => void;
  signOut: () => Promise<void>;
}) {
  const [editing, setEditing] = useState(false),
    [pushEnabled, setPushEnabled] = useState(false),
    [pushBusy, setPushBusy] = useState(false),
    [pushStatus, setPushStatus] = useState("pending"),
    [error, setError] = useState<unknown>(null),
    [dark, setDark] = useState(false);
  useEffect(() => {
    const update = () => {
      setPushEnabled(isPushEnabled(ctx.user));
      setPushStatus(pushRegistrationState(ctx.user));
    };
    update();
    window.addEventListener("focus", update);
    window.addEventListener("classmate:push-state", update);
    return () => {
      window.removeEventListener("focus", update);
      window.removeEventListener("classmate:push-state", update);
    };
  }, [ctx.user]);
  useEffect(() => {
    const media = matchMedia("(prefers-color-scheme: dark)");
    const update = () =>
      setDark(theme === "system" ? media.matches : theme === "dark");
    update();
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, [theme]);
  const role = ctx.owner
    ? "Owner"
    : ctx.profile.is_cr
      ? "Class representative"
      : ctx.profile.role === "teacher"
        ? "Teacher"
        : "Student";
  return (
    <div className="profile-screen">
      <div className="page-heading">
        <div>
          <span className="eyebrow">YOUR ACCOUNT</span>
          <h1>Profile</h1>
          <p className="profile-mobile">Your account and preferences</p>
        </div>
      </div>
      <section className="card profile-header">
        <Avatar name={ctx.profile.full_name} url={ctx.profile.avatar_url} />
        <div>
          <span className="profile-role">{role}</span>
          <h2>{ctx.profile.full_name}</h2>
          <p>{ctx.profile.email}</p>
          <div className="profile-identity">
            {ctx.profile.student_id && (
              <small className="profile-student-id">
                {ctx.profile.student_id}
              </small>
            )}
            <small>
              {ctx.batchInfo.departments?.code?.toUpperCase()}{" "}
              <span className="profile-mobile">Batch </span>
              {ctx.batchInfo.batch_number}
            </small>
            {ctx.batchInfo.academic_session && (
              <small>
                <span className="profile-mobile">· Session </span>
                {academicSession(ctx.batchInfo.academic_session)}
              </small>
            )}
          </div>
        </div>
      </section>
      <section className="card settings profile-account-actions">
        <button className="profile-edit" onClick={() => setEditing(true)}>
          <UserRoundPen />
          <span>
            Edit information<small>Contact details and personal profile</small>
          </span>
          <ChevronRight />
        </button>
        <label className="setting profile-batch">
          <GraduationCap />
          <span>
            {ctx.profile.role === "student" ? "Your batch" : "Switch batch"}
          </span>
          <select
            aria-label="Profile batch"
            value={ctx.batch}
            disabled={ctx.profile.role === "student"}
            onChange={(e) => ctx.changeBatch?.(e.target.value)}
          >
            {(ctx.batches || [ctx.batchInfo]).map((b) => (
              <option key={b.id} value={b.id}>
                {b.departments?.code?.toUpperCase()} {b.batch_number} |{" "}
                {academicSession(b.academic_session)}
              </option>
            ))}
          </select>
        </label>
        {(ctx.owner || ctx.profile.is_cr || ctx.profile.role === "teacher") && (
          <button className="profile-manage" onClick={ctx.manage}>
            <Shield className="profile-desktop" />
            <LayoutGrid className="profile-mobile" />
            <span>
              Manage<small>Courses, people and batch settings</small>
            </span>
            <ChevronRight />
          </button>
        )}
      </section>
      {ctx.ai && (
        <section className="card settings">
          <button onClick={ctx.ai}>
            <Sparkles />
            <span>
              ClassMate AI
              <small>Ask, compose and organize your academic day</small>
            </span>
            <ChevronRight />
          </button>
        </section>
      )}
      <h3 className="profile-label">Preferences</h3>
      <section className="card settings profile-preferences">
        <div className="setting">
          <Moon />
          <span>
            Dark appearance
            <small>
              {theme === "system"
                ? "Following your device"
                : "A comfortable palette"}
            </small>
          </span>
          <button
            className="theme-switch"
            role="switch"
            aria-label="Dark appearance"
            aria-checked={dark}
            onClick={() => setTheme(dark ? "light" : "dark")}
          >
            <span />
          </button>
        </div>
        {theme !== "system" && (
          <button className="device-theme" onClick={() => setTheme("system")}>
            Use device theme
          </button>
        )}
        <div className="setting push-setting">
          <span>
            Push notifications<small>{pushEnabled && pushStatus !== "ready"
              ? pushStatus === "error" ? "Connection failed — retry below" : "Connecting when online…"
              : "Notices and academic updates"}</small>
          </span>
          <button
            className="theme-switch"
            role="switch"
            aria-label="Push notifications"
            aria-checked={pushEnabled}
            disabled={pushBusy}
            onClick={async () => {
              setPushBusy(true);
              setError(null);
              try {
                if (pushEnabled) {
                  await removePush(ctx.user);
                  setPushEnabled(false);
                } else {
                  await enablePush(ctx.user);
                  setPushEnabled(true);
                }
              } catch (e) {
                setError(e);
              } finally {
                setPushBusy(false);
              }
            }}
          >
            <span />
          </button>
        </div>
        {pushEnabled && pushStatus === "error" && (
          <button className="text-button" disabled={pushBusy} onClick={async () => {
            setPushBusy(true); setError(null);
            try { await restorePush(ctx.user); } catch (e) { setError(e); }
            finally { setPushBusy(false); }
          }}>Retry notification connection</button>
        )}
      </section>
      <h3 className="profile-label">App updates</h3>
      <section className="card settings">
        <InstallWebApp />
      </section>
      <a
        className="card android-promo"
        href="https://github.com/shuaibuddowla/ClassMate/releases/latest"
        target="_blank"
        rel="noopener noreferrer"
      >
        <Smartphone size={27} />
        <span>
          <strong>Get ClassMate for Android</strong>
          <small>Download the latest APK from GitHub Releases</small>
        </span>
        <ChevronRight size={20} />
      </a>
      <button className="danger profile-signout" onClick={signOut}>
        <LogOut size={18} />
        Sign out
      </button>
      <ErrorBox error={error} />
      {editing && (
        <Modal title="Your profile" close={() => setEditing(false)}>
          <ProfileForm
            profile={ctx.profile}
            done={(p) => {
              ctx.refreshProfile(p);
              setEditing(false);
            }}
          />
        </Modal>
      )}
    </div>
  );
}

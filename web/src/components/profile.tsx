"use client";
import { academicSession } from "@/lib/academic-session";
import { useState, useEffect } from "react";
import {
  Shield,
  Bell,
  ArrowLeft,
  LogOut,
  ChevronRight,
  UserRoundPen,
  Moon,
  Sun,
  Monitor,
  Smartphone,
  GraduationCap,
  LayoutGrid,
  Sparkles,
  Eye,
  Droplets,
  MapPin,
  Phone,
  Copy,
  Check,
  HardDrive,
  Code2,
  CheckCircle2,
  AlertCircle,
  Home,
  RefreshCw,
  ChevronDown,
} from "lucide-react";
import { rpc, type Row } from "@/lib/api";
import type { Context } from "./app";
import { Avatar, Form, Modal, ErrorBox, Switch } from "./ui";
import { enablePush, removePush, restorePush } from "@/lib/push";
import { pushEnabled as isPushEnabled, pushRegistrationState } from "@/lib/push-state";
import { InstallWebApp } from "./install-web-app";
import { AboutDeveloper } from "./about-developer";

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
          placeholder: "e.g. Tangail, Dhaka, Chattogram",
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
          required: false,
          value: profile.current_residence || "",
          placeholder: "e.g. Master Para, Hall, or residential area",
        },
      ]}
      label="Save profile"
      submit={async (d) =>
        done(
          await rpc("save_profile_details", {
            ...d,
            target_residence: d.target_residence || profile.current_residence || null,
          })
        )
      }
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
    [developerOpen, setDeveloperOpen] = useState(false),
    [receiptsEnabled, setReceiptsEnabled] = useState(true),
    [receiptsBusy, setReceiptsBusy] = useState(false),
    [pushEnabled, setPushEnabled] = useState(false),
    [pushBusy, setPushBusy] = useState(false),
    [pushStatus, setPushStatus] = useState("pending"),
    [error, setError] = useState<unknown>(null),
    [copiedId, setCopiedId] = useState(false),
    [storageInfo, setStorageInfo] = useState<string>("Calculating…"),
    [clearingStorage, setClearingStorage] = useState(false),
    [subView, setSubView] = useState<"settings" | "profile">("settings");

  useEffect(() => {
    const checkHash = () => {
      const h = location.hash.slice(1).split("/")[0];
      if (h === "profile") setSubView("profile");
      else if (h === "settings") setSubView("settings");
    };
    checkHash();
    window.addEventListener("hashchange", checkHash);
    return () => window.removeEventListener("hashchange", checkHash);
  }, []);

  useEffect(() => {
    if (!ctx.owner) return;
    rpc<{ read_receipts_enabled?: boolean }>("owner_preferences")
      .then((res) => {
        if (typeof res?.read_receipts_enabled === "boolean") {
          setReceiptsEnabled(res.read_receipts_enabled);
        }
      })
      .catch(() => {});
  }, [ctx.owner]);

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

  // Estimate storage usage
  useEffect(() => {
    const calcStorage = async () => {
      if (typeof navigator !== "undefined" && navigator.storage?.estimate) {
        try {
          const est = await navigator.storage.estimate();
          const mb = ((est.usage || 0) / (1024 * 1024)).toFixed(1);
          setStorageInfo(`${mb} MB cached`);
        } catch {
          setStorageInfo("Offline storage ready");
        }
      } else {
        setStorageInfo("Offline storage ready");
      }
    };
    calcStorage();
  }, []);

  const handleClearCache = async () => {
    setClearingStorage(true);
    try {
      if (typeof window !== "undefined" && "caches" in window) {
        const keys = await caches.keys();
        await Promise.all(keys.map((k) => caches.delete(k)));
      }
      if (typeof localStorage !== "undefined") {
        for (let i = localStorage.length - 1; i >= 0; i--) {
          const k = localStorage.key(i);
          if (
            k &&
            (k.startsWith("classmate:academic") ||
              k.startsWith("classmate:cache") ||
              k.includes("notices"))
          ) {
            localStorage.removeItem(k);
          }
        }
      }
      setStorageInfo("0.0 MB (Cleared)");
      setTimeout(async () => {
        if (navigator.storage?.estimate) {
          try {
            const est = await navigator.storage.estimate();
            setStorageInfo(`${((est.usage || 0) / (1024 * 1024)).toFixed(1)} MB cached`);
          } catch {
            setStorageInfo("Cache cleared");
          }
        }
      }, 1500);
    } catch {
      setStorageInfo("Cache cleared");
    } finally {
      setClearingStorage(false);
    }
  };

  const copyStudentId = () => {
    if (!ctx.profile.student_id) return;
    navigator.clipboard?.writeText(ctx.profile.student_id);
    setCopiedId(true);
    setTimeout(() => setCopiedId(false), 2000);
  };

  const role = ctx.owner
    ? "Owner"
    : ctx.profile.is_cr
      ? "Class representative"
      : ctx.profile.role === "teacher"
        ? "Teacher"
        : "Student";

  const isProfileComplete =
    Boolean(ctx.profile.mobile_number) &&
    Boolean(ctx.profile.home_town) &&
    Boolean(ctx.profile.blood_group && ctx.profile.blood_group !== "Unknown");

  const homeBatch = ctx.batches?.find((b) => b.id === ctx.profile.batch_id);
  const activeBatch = ctx.batches?.find((b) => b.id === ctx.batch) || ctx.batchInfo;
  const isDifferentBatch = Boolean(ctx.batch && ctx.profile.batch_id && ctx.batch !== ctx.profile.batch_id);
  const displayBatch = (ctx.profile.role === "student" && homeBatch) ? homeBatch : activeBatch;

  const deptCode = displayBatch?.departments?.code?.toUpperCase() || "MBSTU";
  const batchNum = displayBatch?.batch_number ? `Batch ${displayBatch.batch_number}` : "";
  const batchValue = batchNum ? `${deptCode} · ${batchNum}` : deptCode;
  const batchSession = displayBatch?.academic_session
    ? `Session ${academicSession(displayBatch.academic_session)}`
    : "Academic Program";
  const batchLabelTitle = isDifferentBatch ? "ACTIVE BATCH" : "DEPARTMENT";

  return (
    <div className="profile-screen">
      {subView === "profile" ? (
        <>
          <div className="page-heading">
            <button
              type="button"
              className="profile-back-btn"
              onClick={() => {
                setSubView("settings");
                history.replaceState({}, "", "/#settings");
              }}
            >
              <ArrowLeft size={16} /> Back to Settings
            </button>
            <div style={{ marginTop: "0.5rem" }}>
              <span className="eyebrow">YOUR ACCOUNT</span>
              <h1>Profile</h1>
              <p className="profile-mobile">Your academic identity and preferences</p>
            </div>
          </div>

      {/* 1. HERO IDENTITY CARD (MATCHED WITH ANDROID) */}
      <section className="card profile-hero-card-android">
        <div className="profile-hero-banner" />
        <button
          type="button"
          className="profile-hero-edit-top-btn"
          onClick={() => setEditing(true)}
          title="Edit Profile"
          aria-label="Edit Profile"
        >
          <UserRoundPen size={18} />
        </button>

        <div className="profile-hero-avatar-anchor">
          <div className="profile-hero-avatar-ring">
            <Avatar name={ctx.profile.full_name} url={ctx.profile.avatar_url} />
          </div>
        </div>

        <h2 className="profile-hero-name">{ctx.profile.full_name}</h2>

        <div className="profile-hero-badge-row">
          <span
            className={`profile-role-badge ${
              ctx.owner
                ? "owner"
                : ctx.profile.is_cr
                  ? "cr"
                  : ctx.profile.role === "teacher"
                    ? "teacher"
                    : "student"
            }`}
          >
            {role.toUpperCase()}
          </span>
        </div>

        <div
          className="profile-hero-subinfo"
          onClick={copyStudentId}
          role="button"
          tabIndex={0}
          title="Click to copy Student ID"
        >
          <span>
            {ctx.profile.student_id ? `${ctx.profile.student_id} · ` : ""}
            {ctx.profile.email}
          </span>
          {ctx.profile.student_id &&
            (copiedId ? (
              <Check size={13} className="copied" />
            ) : (
              <Copy size={13} />
            ))}
        </div>

        {/* Unified 2x2 Inset Grid inside Hero Card */}
        <div className="profile-hero-inset-grid">
          {/* Blood Group Tile */}
          <div
            className={`hero-inset-tile blood ${!ctx.profile.blood_group || ctx.profile.blood_group === "Unknown" ? "empty" : ""}`}
            onClick={() => {
              if (!ctx.profile.blood_group || ctx.profile.blood_group === "Unknown") {
                setEditing(true);
              } else {
                location.hash = "friends/blood";
              }
            }}
            role="button"
            tabIndex={0}
          >
            <div className="hero-inset-icon blood">
              <Droplets size={17} />
            </div>
            <div className="hero-inset-info">
              <span className="hero-inset-label">BLOOD GROUP</span>
              <strong className="hero-inset-value">
                {ctx.profile.blood_group && ctx.profile.blood_group !== "Unknown"
                  ? ctx.profile.blood_group
                  : "Not set"}
              </strong>
              <small className="hero-inset-sub">
                {ctx.profile.blood_group && ctx.profile.blood_group !== "Unknown"
                  ? "Tap for Blood Network"
                  : "Add to help batchmates"}
              </small>
            </div>
          </div>

          {/* Department & Batch Tile */}
          <div
            className="hero-inset-tile academic"
            onClick={() => setEditing(true)}
            role="button"
            tabIndex={0}
          >
            <div className="hero-inset-icon academic">
              <GraduationCap size={17} />
            </div>
            <div className="hero-inset-info">
              <span className="hero-inset-label">{batchLabelTitle}</span>
              <strong className="hero-inset-value">{batchValue}</strong>
              <small className="hero-inset-sub">{batchSession}</small>
            </div>
          </div>

          {/* Home Town Tile */}
          <div
            className="hero-inset-tile location"
            onClick={() => setEditing(true)}
            role="button"
            tabIndex={0}
          >
            <div className="hero-inset-icon location">
              <MapPin size={17} />
            </div>
            <div className="hero-inset-info">
              <span className="hero-inset-label">HOME TOWN</span>
              <strong className="hero-inset-value">
                {ctx.profile.home_town || "Not specified"}
              </strong>
              <small className="hero-inset-sub">
                {ctx.profile.current_residence || (ctx.profile.home_town ? "District origin" : "Tap to add hometown")}
              </small>
            </div>
          </div>

          {/* Mobile Number Tile */}
          <div
            className="hero-inset-tile contact"
            onClick={() => setEditing(true)}
            role="button"
            tabIndex={0}
          >
            <div className="hero-inset-icon contact">
              <Phone size={17} />
            </div>
            <div className="hero-inset-info">
              <span className="hero-inset-label">MOBILE NUMBER</span>
              <strong className="hero-inset-value phone-value">
                {ctx.profile.mobile_number || "Not specified"}
              </strong>
              <small className="hero-inset-sub">
                {ctx.profile.mobile_number ? "Shared with batch directory" : "Tap to add contact"}
              </small>
            </div>
          </div>
        </div>
      </section>

      {/* BLOOD EMERGENCY NETWORK ENTRY */}
      {ctx.profile.role !== "teacher" && (
        <div
          className="card profile-blood-emergency-card"
          onClick={() => {
            location.hash = "friends/blood";
          }}
          role="button"
          tabIndex={0}
        >
          <div className="emergency-icon-box">
            <Droplets size={22} />
          </div>
          <div className="emergency-info">
            <div className="emergency-title-row">
              <h4>Blood Emergency Requests</h4>
              <span className="emergency-badge">Campus Network</span>
            </div>
            <p>Find donors across batches or create an urgent blood request</p>
          </div>
          <ChevronRight size={18} className="emergency-arrow" />
        </div>
      )}

      {/* Incomplete profile callout nudge */}
      {!isProfileComplete && (
        <div className="profile-nudge-card">
          <div className="profile-nudge-content">
            <AlertCircle size={20} className="nudge-icon" />
            <div>
              <strong>Complete your profile details</strong>
              <p>Add your blood group and mobile number to connect with your batchmates.</p>
            </div>
          </div>
          <button className="primary compact" onClick={() => setEditing(true)}>
            Complete Profile
          </button>
        </div>
      )}
        </>
      ) : (
        <>
          <div className="page-heading" style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <div>
              <h1>Settings</h1>
              <p className="profile-mobile">Preferences, management and system controls</p>
            </div>
            <button
              type="button"
              className="settings-profile-launcher"
              onClick={() => {
                setSubView("profile");
                history.replaceState({}, "", "/#profile");
              }}
              title="View Profile"
            >
              <div className="settings-profile-launcher-avatar">
                <Avatar name={ctx.profile.full_name} url={ctx.profile.avatar_url} />
              </div>
              <span className="settings-profile-launcher-label">Profile</span>
            </button>
          </div>

          {/* 3. ACCOUNT ACTIONS & ACADEMIC HUB */}
          <h3 className="profile-label">ACCOUNT &amp; MANAGEMENT</h3>
      <section className="card settings-group-card">
        <button className="settings-row" onClick={() => setEditing(true)}>
          <div className="setting-icon-box blue">
            <UserRoundPen size={18} />
          </div>
          <div className="settings-row-text">
            <span>Edit Profile Information</span>
            <small>Update contact number, blood group, and hometown</small>
          </div>
          <ChevronRight size={18} className="chevron" />
        </button>

        <div className="settings-row settings-batch-row">
          <div className="settings-batch-header">
            <div className="setting-icon-box purple">
              <GraduationCap size={18} />
            </div>
            <div className="settings-row-text">
              <span>{!ctx.owner && ctx.profile.role === "student" ? "Your Academic Batch" : "Switch Active Batch"}</span>
              <small>{!ctx.owner && ctx.profile.role === "student" ? "Your enrolled academic batch" : "Active workspace and notice scope"}</small>
            </div>
          </div>
          <div className="settings-batch-select-wrap">
            <select
              aria-label="Profile batch"
              className="settings-batch-select"
              value={ctx.batch}
              disabled={!ctx.owner && ctx.profile.role === "student"}
              onChange={(e) => ctx.changeBatch?.(e.target.value)}
            >
              {(ctx.batches || [ctx.batchInfo]).map((b) => (
                <option key={b.id} value={b.id}>
                  {b.departments?.code?.toUpperCase()} {b.batch_number ? `Batch ${b.batch_number}` : ""} · Session {academicSession(b.academic_session)}
                </option>
              ))}
            </select>
            {(ctx.owner || ctx.profile.role !== "student") && <ChevronDown size={16} className="settings-batch-select-chevron" />}
          </div>
        </div>

        {(ctx.owner || ctx.profile.is_cr || ctx.profile.role === "teacher") && (
          <button className="settings-row" onClick={ctx.manage}>
            <div className="setting-icon-box emerald">
              {ctx.profile.role === "teacher" ? <Shield size={18} /> : <LayoutGrid size={18} />}
            </div>
            <div className="settings-row-text">
              <span>{ctx.profile.role === "teacher" ? "Teaching Tools" : "Batch Administration"}</span>
              <small>
                {ctx.profile.role === "teacher"
                  ? "Assigned courses and teaching resources"
                  : "Manage timetable, members and batch settings"}
              </small>
            </div>
            <ChevronRight size={18} className="chevron" />
          </button>
        )}

        {ctx.owner && ctx.ai && (
          <button className="settings-row" onClick={ctx.ai}>
            <div className="setting-icon-box amber">
              <Sparkles size={18} />
            </div>
            <div className="settings-row-text">
              <span>ClassMate AI Assistant</span>
              <small>Ask questions, compose notices and organize study days</small>
            </div>
            <ChevronRight size={18} className="chevron" />
          </button>
        )}
      </section>

      {/* 4. PREFERENCES & APPEARANCE */}
      <h3 className="profile-label">PREFERENCES &amp; APPEARANCE</h3>
      <section className="card settings-group-card">
        {/* 3-Way Appearance Picker */}
        <div className="settings-row theme-picker-row">
          <div className="setting-icon-box indigo">
            {theme === "dark" ? <Moon size={18} /> : theme === "light" ? <Sun size={18} /> : <Monitor size={18} />}
          </div>
          <div className="settings-row-text">
            <span>Theme Appearance</span>
            <small>
              {theme === "system"
                ? "Following device system settings"
                : theme === "dark"
                  ? "Comfortable dark palette"
                  : "Clean light palette"}
            </small>
          </div>
          <div className="theme-segmented-control" role="group" aria-label="Theme selection">
            <button
              type="button"
              className={`theme-segment ${theme === "light" ? "active" : ""}`}
              onClick={() => setTheme("light")}
              title="Light theme"
            >
              <Sun size={15} />
              <span>Light</span>
            </button>
            <button
              type="button"
              className={`theme-segment ${theme === "dark" ? "active" : ""}`}
              onClick={() => setTheme("dark")}
              title="Dark theme"
            >
              <Moon size={15} />
              <span>Dark</span>
            </button>
            <button
              type="button"
              className={`theme-segment ${theme === "system" ? "active" : ""}`}
              onClick={() => setTheme("system")}
              title="Follow system"
            >
              <Monitor size={15} />
              <span>System</span>
            </button>
          </div>
        </div>

        {/* Notice read receipts (Owner only) */}
        {ctx.owner && (
          <div className="settings-row">
            <div className="setting-icon-box cyan">
              <Eye size={18} />
            </div>
            <div className="settings-row-text">
              <span>Notice Read Receipts</span>
              <small>
                {receiptsEnabled
                  ? "Recording your reads on notices"
                  : "Silent mode — your reads are not published"}
              </small>
            </div>
            <Switch
              label="Notice read receipts"
              checked={receiptsEnabled}
              disabled={receiptsBusy}
              onChange={async (nextState) => {
                setReceiptsBusy(true);
                setError(null);
                try {
                  const res = await rpc<{ read_receipts_enabled?: boolean }>(
                    "save_owner_preferences",
                    { target_read_receipts: nextState }
                  );
                  setReceiptsEnabled(res?.read_receipts_enabled ?? nextState);
                } catch (e) {
                  setError(e);
                } finally {
                  setReceiptsBusy(false);
                }
              }}
            />
          </div>
        )}
      </section>

      {/* 5. NOTIFICATIONS & CONNECTIVITY */}
      <h3 className="profile-label">NOTIFICATIONS &amp; CONNECTIVITY</h3>
      <section className="card settings-group-card">
        {ctx.profile.role !== "teacher" && (
          <div className="settings-row">
            <div className="setting-icon-box amber">
              <Bell size={18} />
            </div>
            <div className="settings-row-text">
              <div className="settings-row-title-with-pill">
                <span>Push Notifications</span>
                <span
                  className={`status-pill ${
                    pushEnabled
                      ? pushStatus === "error"
                        ? "danger"
                        : "active"
                      : "muted"
                  }`}
                >
                  {pushEnabled
                    ? pushStatus === "error"
                      ? "Connection Error"
                      : "Active"
                    : "Disabled"}
                </span>
              </div>
              <small>Instant notifications for academic notices and reminders</small>
            </div>
            <Switch
              label="Push notifications"
              checked={pushEnabled}
              disabled={pushBusy}
              onChange={async (nextState) => {
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
            />
          </div>
        )}

        {ctx.profile.role !== "teacher" && pushEnabled && pushStatus === "error" && (
          <button
            className="settings-sub-action"
            disabled={pushBusy}
            onClick={async () => {
              setPushBusy(true);
              setError(null);
              try {
                await restorePush(ctx.user);
              } catch (e) {
                setError(e);
              } finally {
                setPushBusy(false);
              }
            }}
          >
            <RefreshCw size={15} />
            <span>Retry notification connection</span>
          </button>
        )}
      </section>

      {/* 6. STORAGE & DATA MANAGER */}
      <h3 className="profile-label">UTILITIES &amp; STORAGE</h3>
      <section className="card settings-group-card">
        <div className="settings-row settings-cache-row">
          <div className="settings-cache-header">
            <div className="setting-icon-box slate">
              <HardDrive size={18} />
            </div>
            <div className="settings-row-text">
              <span>Offline Cache &amp; Storage</span>
              <small>Local timetable, notices, and offline resources ({storageInfo})</small>
            </div>
          </div>
          <div className="settings-cache-action">
            <button
              className="secondary compact clear-cache-btn"
              disabled={clearingStorage}
              onClick={handleClearCache}
            >
              <span>{clearingStorage ? "Clearing…" : "Clear Cache"}</span>
            </button>
          </div>
        </div>
      </section>

      {/* 7. SYSTEM & UPDATES (MATCHED WITH ANDROID) */}
      <h3 className="profile-label">SYSTEM &amp; UPDATES</h3>
      <section className="card settings-group-card">
        <InstallWebApp inSettingsRow />

        <a
          className="settings-row external-promo"
          href="https://github.com/shuaibuddowla/ClassMate/releases/latest"
          target="_blank"
          rel="noopener noreferrer"
        >
          <div className="setting-icon-box green">
            <Smartphone size={18} />
          </div>
          <div className="settings-row-text">
            <strong>ClassMate for Android</strong>
            <small>Download latest native APK from GitHub Releases</small>
          </div>
          <ChevronRight size={18} className="chevron" />
        </a>

        <button className="settings-row" onClick={() => setDeveloperOpen(true)}>
          <div className="setting-icon-box indigo">
            <Code2 size={18} />
          </div>
          <div className="settings-row-text">
            <span>About Developer</span>
            <small>Designed &amp; built by Shuaib Uddowla · MBSTU</small>
          </div>
          <ChevronRight size={18} className="chevron" />
        </button>
      </section>

      {/* 8. SIGN OUT (MATCHED WITH ANDROID OUTLINED BUTTON) */}
      <div className="profile-signout-container">
        <button
          type="button"
          className="profile-signout-android"
          onClick={signOut}
        >
          <LogOut size={18} />
          <span>Sign Out of ClassMate</span>
        </button>
      </div>
        </>
      )}

      <ErrorBox error={error} />

      {/* Modals */}
      {editing && (
        <Modal title="Your Profile Details" close={() => setEditing(false)}>
          <ProfileForm
            profile={ctx.profile}
            done={(p) => {
              ctx.refreshProfile(p);
              setEditing(false);
            }}
          />
        </Modal>
      )}

      {developerOpen && (
        <AboutDeveloper user={ctx.user} close={() => setDeveloperOpen(false)} />
      )}
    </div>
  );
}

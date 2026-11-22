"use client";

import React, { useEffect, useState } from "react";
import {
  X,
  Bus,
  GraduationCap,
  Calendar,
  CalendarCheck,
  Sparkles,
  Users,
  Wallet,
  Droplet,
  PhoneCall,
  Sun,
  Moon,
  Monitor,
  Info,
  LogOut,
  ChevronRight,
  ShieldAlert,
} from "lucide-react";
import type { Context } from "./app";
import { Avatar, Modal } from "./ui";
import { academicSession } from "@/lib/academic-session";

interface HamburgerDrawerProps {
  ctx: Context;
  isOpen: boolean;
  onClose: () => void;
  theme: string;
  setTheme: (t: string) => void;
  signOut: () => Promise<void>;
  onNavigateTab: (tab: string) => void;
  onOpenTransit?: () => void;
  onOpenTeachers?: () => void;
  onOpenBatchFund?: () => void;
  onOpenBloodNetwork?: () => void;
  onOpenAboutDev?: () => void;
}

export function HamburgerDrawer({
  ctx,
  isOpen,
  onClose,
  theme,
  setTheme,
  signOut,
  onNavigateTab,
  onOpenTransit,
  onOpenTeachers,
  onOpenBatchFund,
  onOpenBloodNetwork,
  onOpenAboutDev,
}: HamburgerDrawerProps) {
  const [emergencyOpen, setEmergencyOpen] = useState(false);

  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  const profile = ctx.profile || {};
  const isServerOwner = ctx.owner || profile.role === "admin";
  const isTeacher = profile.role === "teacher";
  const isCr = Boolean(profile.is_cr);
  const fullName = profile.full_name?.trim() || "ClassMate User";
  const studentId = profile.student_id?.trim() || "";

  const batchInfo = ctx.batchInfo as any;
  const deptCode = batchInfo?.departments?.code?.toUpperCase() || "";
  const batchNum = batchInfo?.batch_number ? `Batch ${batchInfo.batch_number}` : "";
  const currentBatchLabel =
    [deptCode, batchNum].filter(Boolean).join(" ") ||
    (batchInfo?.academic_session ? `Session ${academicSession(batchInfo.academic_session)}` : "MBSTU");

  const hasTransit = Boolean(
    batchInfo?.institution?.has_bus_transit ??
      (batchInfo?.departments?.code !== "fisheries" && !profile?.email?.toLowerCase()?.endsWith("@ru.ac.bd"))
  );

  const roleBadge = isServerOwner
    ? { label: "Admin", color: "#fb923c", bg: "rgba(251, 146, 60, 0.16)" }
    : isTeacher
    ? { label: "Faculty", color: "#60a5fa", bg: "rgba(96, 165, 250, 0.16)" }
    : isCr
    ? { label: "CR", color: "#a78bfa", bg: "rgba(167, 139, 250, 0.16)" }
    : { label: "Student", color: "#22c55e", bg: "rgba(34, 197, 94, 0.16)" };

  const handleAction = (cb?: () => void) => {
    onClose();
    if (cb) cb();
  };

  return (
    <>
      <div className="cm-drawer-scrim" onClick={onClose} aria-hidden="true" />
      <div
        className="cm-drawer-sheet"
        role="dialog"
        aria-modal="true"
        aria-label="Navigation drawer"
      >
        <div className="cm-drawer-drag-handle" />

        {/* Header */}
        <div className="cm-drawer-header">
          <div className="cm-drawer-brand">
            <img
              src="/brand/classmate-sidebar-logo.png"
              alt="ClassMate"
              className="cm-drawer-logo"
            />
            <span className="cm-drawer-title">
              classmate<span className="cm-drawer-dot">.</span>
            </span>
          </div>
          <button
            type="button"
            className="icon cm-drawer-close-btn"
            onClick={onClose}
            aria-label="Close menu"
          >
            <X size={20} />
          </button>
        </div>

        {/* Scrollable Content */}
        <div className="cm-drawer-scroll">
          {/* Profile Card */}
          <div
            className="cm-drawer-profile-card"
            onClick={() => handleAction(() => onNavigateTab("profile"))}
            role="button"
            tabIndex={0}
          >
            <Avatar name={fullName} url={profile.avatar_url} />
            <div className="cm-drawer-profile-info">
              <strong className="cm-drawer-profile-name">{fullName}</strong>
              <span className="cm-drawer-profile-sub">
                {studentId ? `${studentId} · ${currentBatchLabel}` : currentBatchLabel}
              </span>
            </div>
            <span
              className="cm-drawer-badge"
              style={{ color: roleBadge.color, backgroundColor: roleBadge.bg }}
            >
              {roleBadge.label}
            </span>
          </div>

          {/* Section 1: Campus & Academic */}
          <div className="cm-drawer-section">
            <span className="cm-drawer-section-title">CAMPUS & ACADEMIC</span>

            {hasTransit && (
              <button
                type="button"
                className="cm-drawer-item"
                onClick={() =>
                  handleAction(() => {
                    if (onOpenTransit) onOpenTransit();
                    else onNavigateTab("transit");
                  })
                }
              >
                <div className="cm-drawer-icon-wrap transit">
                  <Bus size={20} />
                </div>
                <div className="cm-drawer-item-text">
                  <strong>Bus Transit</strong>
                  <small>Live bus radar, schedule & routes</small>
                </div>
                <ChevronRight size={16} className="cm-drawer-chevron" />
              </button>
            )}

            <button
              type="button"
              className="cm-drawer-item"
              onClick={() =>
                handleAction(() => {
                  if (onOpenTeachers) onOpenTeachers();
                  else onNavigateTab("teachers");
                })
              }
            >
              <div className="cm-drawer-icon-wrap teachers">
                <GraduationCap size={20} />
              </div>
              <div className="cm-drawer-item-text">
                <strong>Teachers Directory</strong>
                <small>Faculty contacts & assigned courses</small>
              </div>
              <ChevronRight size={16} className="cm-drawer-chevron" />
            </button>

            <button
              type="button"
              className="cm-drawer-item"
              onClick={() =>
                handleAction(() => {
                  onNavigateTab("upcoming");
                })
              }
            >
              <div className="cm-drawer-icon-wrap upcoming">
                <CalendarCheck size={20} />
              </div>
              <div className="cm-drawer-item-text">
                <strong>Upcoming Assessments</strong>
                <small>Class tests, assignments & lab deadlines</small>
              </div>
              <ChevronRight size={16} className="cm-drawer-chevron" />
            </button>

            <button
              type="button"
              className="cm-drawer-item"
              onClick={() =>
                handleAction(() => {
                  onNavigateTab("timetable");
                })
              }
            >
              <div className="cm-drawer-icon-wrap calendar">
                <Calendar size={20} />
              </div>
              <div className="cm-drawer-item-text">
                <strong>Academic Calendar</strong>
                <small>Holidays, semesters & exam dates</small>
              </div>
              <ChevronRight size={16} className="cm-drawer-chevron" />
            </button>

            {ctx.ai && (
              <button
                type="button"
                className="cm-drawer-item"
                onClick={() =>
                  handleAction(() => {
                    onNavigateTab("ai");
                  })
                }
              >
                <div className="cm-drawer-icon-wrap ai">
                  <Sparkles size={20} />
                </div>
                <div className="cm-drawer-item-text">
                  <strong>ClassMate AI</strong>
                  <small>AI academic assistant & course tutor</small>
                </div>
                <ChevronRight size={16} className="cm-drawer-chevron" />
              </button>
            )}
          </div>

          {/* Section 2: Batch Hub */}
          <div className="cm-drawer-section">
            <span className="cm-drawer-section-title">BATCH HUB</span>

            <button
              type="button"
              className="cm-drawer-item"
              onClick={() => handleAction(() => onNavigateTab("friends"))}
            >
              <div className="cm-drawer-icon-wrap batch">
                <Users size={20} />
              </div>
              <div className="cm-drawer-item-text">
                <strong>Batchmates</strong>
                <small>Student roster, rolls & contact info</small>
              </div>
              <ChevronRight size={16} className="cm-drawer-chevron" />
            </button>

            <button
              type="button"
              className="cm-drawer-item"
              onClick={() =>
                handleAction(() => {
                  if (onOpenBatchFund) onOpenBatchFund();
                  else onNavigateTab("batch-fund");
                })
              }
            >
              <div className="cm-drawer-icon-wrap fund">
                <Wallet size={20} />
              </div>
              <div className="cm-drawer-item-text">
                <strong>Batch Fund</strong>
                <small>Treasury balance, collections & expenses</small>
              </div>
              <ChevronRight size={16} className="cm-drawer-chevron" />
            </button>
          </div>

          {/* Section 3: Campus Utilities */}
          <div className="cm-drawer-section">
            <span className="cm-drawer-section-title">CAMPUS UTILITIES</span>

            <button
              type="button"
              className="cm-drawer-item"
              onClick={() =>
                handleAction(() => {
                  if (onOpenBloodNetwork) onOpenBloodNetwork();
                  else onNavigateTab("blood");
                })
              }
            >
              <div className="cm-drawer-icon-wrap blood">
                <Droplet size={20} />
              </div>
              <div className="cm-drawer-item-text">
                <strong>Blood Donor Directory</strong>
                <small>Campus emergency blood donors</small>
              </div>
              <ChevronRight size={16} className="cm-drawer-chevron" />
            </button>

            <button
              type="button"
              className="cm-drawer-item"
              onClick={() => setEmergencyOpen(true)}
            >
              <div className="cm-drawer-icon-wrap emergency">
                <PhoneCall size={20} />
              </div>
              <div className="cm-drawer-item-text">
                <strong>Emergency Hotlines</strong>
                <small>Proctor, Medical Center & Ambulance</small>
              </div>
              <ChevronRight size={16} className="cm-drawer-chevron" />
            </button>
          </div>

          {/* Section 4: Preferences & System */}
          <div className="cm-drawer-section">
            <span className="cm-drawer-section-title">PREFERENCES & ACCOUNT</span>

            <div className="cm-drawer-theme-row">
              <span className="cm-drawer-theme-label">Appearance</span>
              <div className="cm-drawer-theme-toggle">
                <button
                  type="button"
                  className={theme === "light" ? "active" : ""}
                  onClick={() => setTheme("light")}
                  aria-label="Light mode"
                >
                  <Sun size={15} />
                  <span>Light</span>
                </button>
                <button
                  type="button"
                  className={theme === "dark" ? "active" : ""}
                  onClick={() => setTheme("dark")}
                  aria-label="Dark mode"
                >
                  <Moon size={15} />
                  <span>Dark</span>
                </button>
                <button
                  type="button"
                  className={theme === "system" ? "active" : ""}
                  onClick={() => setTheme("system")}
                  aria-label="System mode"
                >
                  <Monitor size={15} />
                  <span>Auto</span>
                </button>
              </div>
            </div>

            <button
              type="button"
              className="cm-drawer-item"
              onClick={() =>
                handleAction(() => {
                  if (onOpenAboutDev) onOpenAboutDev();
                })
              }
            >
              <div className="cm-drawer-icon-wrap info">
                <Info size={20} />
              </div>
              <div className="cm-drawer-item-text">
                <strong>About Developer</strong>
                <small>ClassMate project & team</small>
              </div>
              <ChevronRight size={16} className="cm-drawer-chevron" />
            </button>

            <button
              type="button"
              className="cm-drawer-item sign-out"
              onClick={() => {
                onClose();
                signOut();
              }}
            >
              <div className="cm-drawer-icon-wrap logout">
                <LogOut size={20} />
              </div>
              <div className="cm-drawer-item-text">
                <strong>Sign out</strong>
                <small>Disconnect your university session</small>
              </div>
            </button>
          </div>

          <div className="cm-drawer-footer">
            <span>ClassMate · Student Companion</span>
          </div>
        </div>
      </div>

      {emergencyOpen && (
        <EmergencyHotlinesModal close={() => setEmergencyOpen(false)} />
      )}
    </>
  );
}

function EmergencyHotlinesModal({ close }: { close: () => void }) {
  const contacts = [
    {
      title: "MBSTU Medical Center",
      subtitle: "Campus clinic & first aid desk",
      phone: "01711-234567",
      rawDial: "01711234567",
    },
    {
      title: "MBSTU Proctor Office",
      subtitle: "Campus security & incident response",
      phone: "01712-345678",
      rawDial: "01712345678",
    },
    {
      title: "Campus Ambulance Transfer",
      subtitle: "24/7 patient transport to hospital",
      phone: "01715-998877",
      rawDial: "01715998877",
    },
    {
      title: "Tangail 250-Bed General Hospital",
      subtitle: "District emergency casualty department",
      phone: "01712-401414",
      rawDial: "01712401414",
    },
    {
      title: "Fire Service & Civil Defence",
      subtitle: "Tangail fire station rescue unit",
      phone: "01730-002288",
      rawDial: "01730002288",
    },
    {
      title: "Tangail Sadar Police Station",
      subtitle: "Local law enforcement dispatch",
      phone: "01713-373852",
      rawDial: "01713373852",
    },
    {
      title: "National Emergency Service",
      subtitle: "Police, Fire, Ambulance 24/7",
      phone: "999",
      rawDial: "999",
    },
  ];

  return (
    <Modal title="Campus Emergency Contacts" close={close}>
      <div className="emergency-modal-body">
        <p className="emergency-modal-desc">
          Tap any emergency contact below to dial directly from your device.
        </p>
        <div className="emergency-contact-list">
          {contacts.map((c) => (
            <a
              key={c.phone}
              href={`tel:${c.rawDial}`}
              className="emergency-contact-card"
            >
              <div className="emergency-contact-info">
                <strong>{c.title}</strong>
                <span>{c.subtitle}</span>
              </div>
              <span className="emergency-dial-btn">
                <PhoneCall size={16} />
                <span>{c.phone}</span>
              </span>
            </a>
          ))}
        </div>
      </div>
    </Modal>
  );
}

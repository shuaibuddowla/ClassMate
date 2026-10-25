"use client";

import { useState, useEffect } from "react";
import { Download, Check, X, Smartphone, Bell, Zap, RefreshCw, Trash2, ShieldCheck } from "lucide-react";

export const ANDROID_INSTALLED_KEY = "classmate_android_app_installed";
export const ANDROID_SNOOZE_KEY = "classmate_android_prompt_snooze";

export function isAndroidMobileDevice(): boolean {
  if (typeof window === "undefined" || typeof navigator === "undefined") return false;
  const ua = navigator.userAgent || "";
  const isAndroid = /Android/i.test(ua);
  const isMobileScreen = window.innerWidth <= 768 || "ontouchstart" in window;
  return isAndroid && isMobileScreen;
}

export function openAndroidInstallPrompt() {
  if (typeof window !== "undefined") {
    window.dispatchEvent(new CustomEvent("classmate:open-android-install"));
  }
}

export function AndroidInstallPopup() {
  const [visible, setVisible] = useState(false);
  const [step, setStep] = useState<"prompt" | "guide">("prompt");
  const [downloading, setDownloading] = useState(false);
  const [versionName, setVersionName] = useState<string | null>(null);
  const [directApkUrl, setDirectApkUrl] = useState<string | null>(null);

  // Fetch the latest release version metadata dynamically
  useEffect(() => {
    let active = true;
    fetch(`/download/android?format=json&t=${Date.now()}`)
      .then((res) => (res.ok ? res.json() : null))
      .then((data) => {
        if (!active || !data) return;
        if (data.versionName) setVersionName(data.versionName);
        if (data.apkUrl) setDirectApkUrl(data.apkUrl);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    // Listen for manual trigger from Settings / Profile
    const handleManualOpen = () => {
      setStep("prompt");
      setVisible(true);
    };
    window.addEventListener("classmate:open-android-install", handleManualOpen);

    // Automatic check on Android mobile
    if (isAndroidMobileDevice()) {
      try {
        const isInstalled = localStorage.getItem(ANDROID_INSTALLED_KEY);
        if (isInstalled === "true") {
          return () => {
            window.removeEventListener("classmate:open-android-install", handleManualOpen);
          };
        }

        const snoozeUntil = localStorage.getItem(ANDROID_SNOOZE_KEY);
        if (snoozeUntil && Number(snoozeUntil) > Date.now()) {
          return () => {
            window.removeEventListener("classmate:open-android-install", handleManualOpen);
          };
        }

        // Slight entrance delay for smooth loading
        const timer = setTimeout(() => {
          setVisible(true);
        }, 700);

        return () => {
          clearTimeout(timer);
          window.removeEventListener("classmate:open-android-install", handleManualOpen);
        };
      } catch {
        setVisible(true);
      }
    }

    return () => {
      window.removeEventListener("classmate:open-android-install", handleManualOpen);
    };
  }, []);

  const triggerDownload = () => {
    setDownloading(true);
    // Prefer verified direct APK URL from latest release, fallback to cache-busted endpoint
    const targetUrl = directApkUrl || `/download/android?t=${Date.now()}`;
    try {
      const iframe = document.createElement("iframe");
      iframe.style.display = "none";
      iframe.src = targetUrl;
      document.body.appendChild(iframe);
      setTimeout(() => iframe.remove(), 20000);
    } catch {
      window.location.href = targetUrl;
    }

    setTimeout(() => {
      setDownloading(false);
      setStep("guide");
    }, 700);
  };

  const handleSnooze = () => {
    try {
      // Snooze for 3 days (3 * 24 * 60 * 60 * 1000 ms)
      localStorage.setItem(ANDROID_SNOOZE_KEY, String(Date.now() + 3 * 24 * 60 * 60 * 1000));
    } catch {}
    setVisible(false);
  };

  const handleMarkInstalled = () => {
    try {
      localStorage.setItem(ANDROID_INSTALLED_KEY, "true");
      localStorage.removeItem(ANDROID_SNOOZE_KEY);
    } catch {}
    setVisible(false);
  };

  if (!visible) return null;

  return (
    <div
      className="android-popup-backdrop"
      role="dialog"
      aria-modal="true"
      aria-label="Download ClassMate Android App"
      onClick={(e) => {
        if (e.target === e.currentTarget) handleSnooze();
      }}
    >
      <div className="android-popup-card">
        {/* Close Button (Triggers 3-day snooze) */}
        <button
          type="button"
          className="android-popup-close"
          aria-label="Close and remind later"
          onClick={handleSnooze}
        >
          <X size={18} />
        </button>

        {step === "prompt" ? (
          /* STEP 1: PROMPT TO DOWNLOAD */
          <div className="android-popup-content">
            <div className="android-popup-badge-wrapper">
              <div className="android-popup-icon-glow">
                <img
                  src="/brand/classmate-sidebar-logo.png"
                  alt="ClassMate App Icon"
                  className="android-popup-logo-img"
                />
              </div>
              <span className="android-popup-pill">
                OFFICIAL ANDROID APP{versionName ? ` · v${versionName}` : ""}
              </span>
            </div>

            <h2 className="android-popup-title">Get ClassMate for Android</h2>
            <p className="android-popup-desc">
              Experience faster performance, reliable push notifications, and offline timetables with the latest native release.
            </p>

            <div className="android-popup-features">
              <div className="android-popup-feature-item">
                <Zap size={16} className="feature-icon icon-zap" />
                <span>Instant native speed &amp; smoother routine UI</span>
              </div>
              <div className="android-popup-feature-item">
                <Bell size={16} className="feature-icon icon-bell" />
                <span>Real-time alerts for class cancellations &amp; notices</span>
              </div>
              <div className="android-popup-feature-item">
                <ShieldCheck size={16} className="feature-icon icon-shield" />
                <span>Offline schedules &amp; automatic update checks</span>
              </div>
            </div>

            <div className="android-popup-actions">
              <button
                type="button"
                className="android-popup-btn-primary"
                onClick={triggerDownload}
                disabled={downloading}
              >
                {downloading ? (
                  <>
                    <RefreshCw size={18} className="spin" />
                    <span>Starting download…</span>
                  </>
                ) : (
                  <>
                    <Download size={18} />
                    <span>
                      Download &amp; Install Android App{versionName ? ` (v${versionName})` : ""}
                    </span>
                  </>
                )}
              </button>

              <div className="android-popup-secondary-links">
                <button
                  type="button"
                  className="android-popup-link"
                  onClick={handleMarkInstalled}
                >
                  I already have the app
                </button>
                <span className="android-popup-link-sep">•</span>
                <button
                  type="button"
                  className="android-popup-link"
                  onClick={handleSnooze}
                >
                  Maybe later
                </button>
              </div>
            </div>
          </div>
        ) : (
          /* STEP 2: INSTALL & UNINSTALL WEB INSTRUCTIONS */
          <div className="android-popup-content">
            <div className="android-popup-badge-wrapper">
              <div className="android-popup-icon-glow success">
                <Smartphone size={32} style={{ color: "var(--primary)" }} />
              </div>
              <span className="android-popup-pill success">
                {versionName ? `v${versionName} DOWNLOADED` : "DOWNLOAD INITIATED"}
              </span>
            </div>

            <h2 className="android-popup-title">
              Installing ClassMate{versionName ? ` v${versionName}` : ""}
            </h2>
            <p className="android-popup-desc">
              Follow these two simple steps to complete your setup:
            </p>

            <div className="android-popup-guide-steps">
              <div className="android-guide-step-card">
                <div className="step-num-bubble">1</div>
                <div className="step-content">
                  <strong>Install the Downloaded APK</strong>
                  <p>
                    Swipe down your notification bar or tap your browser&apos;s download notification, then press <strong>Open</strong> to install.
                  </p>
                </div>
              </div>

              <div className="android-guide-step-card highlight-uninstall">
                <div className="step-num-bubble warning">
                  <Trash2 size={14} />
                </div>
                <div className="step-content">
                  <strong>Uninstall Web Version</strong>
                  <p>
                    Once installed, please <strong>uninstall or remove the web app</strong> from your home screen so you don&apos;t get duplicate alerts.
                  </p>
                </div>
              </div>
            </div>

            <div className="android-popup-actions">
              <button
                type="button"
                className="android-popup-btn-primary success"
                onClick={handleMarkInstalled}
              >
                <Check size={18} />
                <span>Done, I&apos;ve Installed It</span>
              </button>

              <button
                type="button"
                className="android-popup-retry-btn"
                onClick={triggerDownload}
              >
                Didn&apos;t start? Tap here to retry download
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

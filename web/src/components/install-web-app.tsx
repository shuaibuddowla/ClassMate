"use client";
import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { Download, Check, ChevronRight } from "lucide-react";
import { ErrorBox, Modal } from "./ui";
type InstallPrompt = Event & {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: string }>;
};
declare global {
  interface Window {
    classmateInstallPrompt?: InstallPrompt | null;
    classmateInstalled?: boolean;
  }
}
const InstallContext = createContext<{
  prompt: InstallPrompt | null;
  installed: boolean;
  clear: () => void;
}>({ prompt: null, installed: false, clear: () => {} });
export function InstallProvider({ children }: { children: ReactNode }) {
  const [prompt, setPrompt] = useState<InstallPrompt | null>(null),
    [installed, setInstalled] = useState(false);
  useEffect(() => {
    const media = matchMedia("(display-mode: standalone)");
    const update = () =>
      setInstalled(
        window.classmateInstalled === true ||
          media.matches ||
          !!(navigator as Navigator & { standalone?: boolean }).standalone,
      );
    const ready = (event: Event) => {
      event.preventDefault();
      window.classmateInstallPrompt = event as InstallPrompt;
      setPrompt(event as InstallPrompt);
    };
    const complete = () => {
      setInstalled(true);
      window.classmateInstallPrompt = null;
      setPrompt(null);
    };
    update();
    setPrompt(window.classmateInstallPrompt || null);
    window.addEventListener("beforeinstallprompt", ready);
    window.addEventListener("appinstalled", complete);
    media.addEventListener("change", update);
    return () => {
      window.removeEventListener("beforeinstallprompt", ready);
      window.removeEventListener("appinstalled", complete);
      media.removeEventListener("change", update);
    };
  }, []);
  return (
    <InstallContext.Provider
      value={{
        prompt,
        installed,
        clear: () => {
          window.classmateInstallPrompt = null;
          setPrompt(null);
        },
      }}
    >
      {children}
    </InstallContext.Provider>
  );
}
export function InstallWebApp({ inSettingsRow = false }: { inSettingsRow?: boolean } = {}) {
  const { prompt, installed, clear } = useContext(InstallContext),
    [guide, setGuide] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null);
  const [platform, setPlatform] = useState("desktop"),
    [embedded, setEmbedded] = useState(false);
  useEffect(() => {
    const ua = navigator.userAgent;
    setPlatform(
      /Android/i.test(ua)
        ? "android"
        : /iPhone|iPad|iPod/i.test(ua) ||
            (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1)
          ? "ios"
          : "desktop",
    );
    setEmbedded(/FBAN|FBAV|Instagram|\bwv\b/i.test(ua));
  }, []);
  useEffect(() => {
    if (installed) setGuide(false);
  }, [installed]);
  async function install() {
    setError(null);
    const available = prompt || window.classmateInstallPrompt;
    if (!available) {
      setGuide(true);
      return;
    }
    setBusy(true);
    try {
      await available.prompt();
      const choice = await available.userChoice;
      clear();
      if (choice.outcome === "accepted") setGuide(false);
    } catch (e) {
      setError(e);
      setGuide(true);
      clear();
    } finally {
      setBusy(false);
    }
  }
  if (installed) {
    if (inSettingsRow) {
      return (
        <div className="settings-row">
          <div className="setting-icon-box emerald">
            <Check size={18} />
          </div>
          <div className="settings-row-text">
            <span>Web App Installed</span>
            <small>Active and running on this device</small>
          </div>
        </div>
      );
    }
    return (
      <span className="web-installed">
        <Check size={17} /> Web app installed
      </span>
    );
  }
  return (
    <>
      {inSettingsRow ? (
        <button className="settings-row" disabled={busy} onClick={install}>
          <div className="setting-icon-box blue">
            <Download size={18} />
          </div>
          <div className="settings-row-text">
            <span>Install Web App</span>
            <small>Add ClassMate to your home screen</small>
          </div>
          <ChevronRight size={18} className="chevron" />
        </button>
      ) : (
        <button className="install-web-app" disabled={busy} onClick={install}>
          <Download size={18} />
          <span>Install web app</span>
        </button>
      )}
      {guide && (
        <Modal title="Install ClassMate" close={() => setGuide(false)}>
          <div className="install-guide">
            <img src="/icon-192.png" alt="" width={64} height={64} />
            <p>Keep ClassMate on your home screen.</p>
            {prompt && (
              <button
                className="primary wide"
                disabled={busy}
                onClick={install}
              >
                <Download size={18} /> Install now
              </button>
            )}
            {platform === "android" && (
              <p>
                <strong>Android:</strong> open this site in Chrome, then use ⋮ →
                Install app or Add to Home screen.
              </p>
            )}
            {platform === "ios" && (
              <p>
                <strong>iPhone / iPad:</strong> open in Safari, then Share → Add
                to Home Screen.
              </p>
            )}
            {platform === "desktop" && (
              <p>
                <strong>Computer:</strong> use the install icon in the address
                bar, or the browser’s app menu.
              </p>
            )}
            {platform === "android" && embedded && (
              <a
                className="primary wide"
                href={`intent://${location.host}/#Intent;scheme=https;package=com.android.chrome;S.browser_fallback_url=${encodeURIComponent(location.origin + "/")};end`}
              >
                Open in Chrome
              </a>
            )}
            <small>
              If you opened ClassMate inside another app, open it in your
              regular browser first. An installed app may already be available
              on your home screen.
            </small>
            <ErrorBox error={error} />
            <button className="primary wide" onClick={() => setGuide(false)}>
              Got it
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}

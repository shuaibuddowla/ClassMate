"use client";

import { useState, useEffect, useRef } from "react";
import { supabase } from "@/lib/api";
import {
  X,
  Camera,
  CheckCircle2,
  WifiOff,
  AlertCircle,
  RefreshCw,
  ScanLine
} from "lucide-react";

interface ScannerModalProps {
  initialSessionId?: string;
  courseTitle?: string;
  onClose: () => void;
}

interface PendingScan {
  session_id: string;
  time_window: number;
  qr_token: string;
  scanned_at: number;
}

const PENDING_STORAGE_KEY = "classmate_pending_attendance_scans";

// Synthesize pleasant two-tone confirmation chime using Web Audio API
function playSuccessChime() {
  try {
    const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
    if (!AudioCtx) return;
    const ctx = new AudioCtx();
    const now = ctx.currentTime;

    // Harmonic chord: C5 (523Hz) -> E5 (659Hz) -> G5 (784Hz)
    const tones = [523.25, 659.25, 783.99];
    tones.forEach((freq, idx) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = "sine";
      osc.frequency.setValueAtTime(freq, now + idx * 0.08);

      gain.gain.setValueAtTime(0, now + idx * 0.08);
      gain.gain.linearRampToValueAtTime(0.25, now + idx * 0.08 + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.001, now + idx * 0.08 + 0.35);

      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(now + idx * 0.08);
      osc.stop(now + idx * 0.08 + 0.35);
    });

    // Mobile vibration confirmation
    if (typeof navigator !== "undefined" && navigator.vibrate) {
      navigator.vibrate([60, 40, 100]);
    }
  } catch (e) {
    console.debug("Web audio chime note:", e);
  }
}

export function AttendanceScannerModal({
  initialSessionId,
  courseTitle,
  onClose,
}: ScannerModalProps) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [hasCamera, setHasCamera] = useState<boolean>(true);
  const [scanning, setScanning] = useState<boolean>(true);
  const [scanResult, setScanResult] = useState<{
    status: "success" | "offline_saved" | "error";
    message: string;
  } | null>(null);
  const [manualCode, setManualCode] = useState<string>("");
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);

  const client = supabase();

  // Helper to submit scan to backend
  const submitScan = async (
    sessionId: string,
    timeWindow: number,
    qrToken: string
  ): Promise<{ success: boolean; offline?: boolean; error?: string }> => {
    // Check if browser is online
    if (!navigator.onLine) {
      // Save to local offline queue
      const existing: PendingScan[] = JSON.parse(
        localStorage.getItem(PENDING_STORAGE_KEY) || "[]"
      );
      // Avoid duplicate pending
      if (!existing.some((e) => e.session_id === sessionId)) {
        existing.push({
          session_id: sessionId,
          time_window: timeWindow,
          qr_token: qrToken,
          scanned_at: Date.now(),
        });
        localStorage.setItem(PENDING_STORAGE_KEY, JSON.stringify(existing));
      }
      return { success: true, offline: true };
    }

    try {
      const { data, error } = await client.schema("classmate").rpc("mark_attendance", {
        target_session: sessionId,
        qr_token: qrToken,
        time_window: timeWindow,
      });

      if (error) throw error;
      return { success: true };
    } catch (err: unknown) {
      return {
        success: false,
        error: err instanceof Error ? err.message : "Failed to record attendance",
      };
    }
  };

  // Sync offline queue when back online
  useEffect(() => {
    const syncOffline = async () => {
      const pending: PendingScan[] = JSON.parse(
        localStorage.getItem(PENDING_STORAGE_KEY) || "[]"
      );
      if (!pending.length) return;

      const remaining: PendingScan[] = [];
      for (const item of pending) {
        try {
          const { error } = await client
            .schema("classmate")
            .rpc("mark_attendance", {
              target_session: item.session_id,
              qr_token: item.qr_token,
              time_window: item.time_window,
            });
          if (error) {
            // Keep if retryable
            remaining.push(item);
          }
        } catch {
          remaining.push(item);
        }
      }
      localStorage.setItem(PENDING_STORAGE_KEY, JSON.stringify(remaining));
    };

    window.addEventListener("online", syncOffline);
    if (navigator.onLine) syncOffline();

    return () => {
      window.removeEventListener("online", syncOffline);
    };
  }, [client]);

  // Handle scanned raw text
  const handleQrDetected = async (rawText: string) => {
    if (!scanning || isSubmitting) return;

    try {
      let sessionId = "";
      let timeWindow = 0;
      let qrToken = "";

      const trimmed = rawText.trim();
      // Try parsing JSON format
      if (trimmed.startsWith("{")) {
        const parsed = JSON.parse(trimmed);
        sessionId = parsed.s;
        timeWindow = Number(parsed.w);
        qrToken = parsed.t;
      } else if (trimmed.startsWith("cm:")) {
        // Fallback compact colon format: cm:sessionId:window:token
        const parts = trimmed.split(":");
        sessionId = parts[1];
        timeWindow = Number(parts[2]);
        qrToken = parts[3];
      }

      if (!sessionId || !qrToken || isNaN(timeWindow)) {
        throw new Error("Invalid ClassMate QR format");
      }

      setScanning(false);
      setIsSubmitting(true);

      const res = await submitScan(sessionId, timeWindow, qrToken);

      if (res.offline) {
        playSuccessChime();
        setScanResult({
          status: "offline_saved",
          message: "📡 Saved — will sync automatically when back online",
        });
      } else if (res.success) {
        playSuccessChime();
        setScanResult({
          status: "success",
          message: "✓ Marked present successfully!",
        });
      } else {
        setScanResult({
          status: "error",
          message: res.error || "Attendance verification failed",
        });
      }
    } catch (e: unknown) {
      console.warn("QR Parse error:", e);
    } finally {
      setIsSubmitting(false);
    }
  };

  // Camera Stream & Barcode Detection loop
  useEffect(() => {
    let stream: MediaStream | null = null;
    let animId: number;
    let active = true;

    async function startCamera() {
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: "environment", width: { ideal: 1280 } },
        });

        if (videoRef.current && active) {
          videoRef.current.srcObject = stream;
          await videoRef.current.play();

          // Check for native BarcodeDetector API
          if ("BarcodeDetector" in window) {
            const detector = new (window as any).BarcodeDetector({
              formats: ["qr_code"],
            });

            const scanFrame = async () => {
              if (
                videoRef.current &&
                videoRef.current.readyState === videoRef.current.HAVE_ENOUGH_DATA &&
                active &&
                scanning
              ) {
                try {
                  const codes = await detector.detect(videoRef.current);
                  if (codes && codes.length > 0) {
                    const rawVal = codes[0].rawValue;
                    if (rawVal) {
                      await handleQrDetected(rawVal);
                    }
                  }
                } catch {
                  // ignore detection frame error
                }
              }
              if (active && scanning) {
                animId = requestAnimationFrame(scanFrame);
              }
            };

            animId = requestAnimationFrame(scanFrame);
          } else {
            // Browser lacks native BarcodeDetector
            if (active) setHasCamera(false);
          }
        }
      } catch (err) {
        console.warn("Camera init failed:", err);
        if (active) setHasCamera(false);
      }
    }

    startCamera();

    return () => {
      active = false;
      if (animId) cancelAnimationFrame(animId);
      if (stream) {
        stream.getTracks().forEach((track) => track.stop());
      }
    };
  }, [scanning]);

  return (
    <div className="attendance-modal-overlay">
      <div className="attendance-modal-container scanner-modal">
        {/* Header */}
        <div className="attendance-modal-header">
          <div className="attendance-header-meta">
            <span className="attendance-badge-student">
              <Camera size={13} /> STUDENT SCANNER
            </span>
            <h2>{courseTitle || "Scan Class Attendance"}</h2>
            <p>Point camera at teacher's rotating QR screen</p>
          </div>
          <button className="icon-close" onClick={onClose} aria-label="Close scanner">
            <X size={20} />
          </button>
        </div>

        {/* Viewfinder or Status Result */}
        <div className="scanner-body">
          {scanResult ? (
            <div
              className={`scan-result-card ${
                scanResult.status === "success"
                  ? "status-success"
                  : scanResult.status === "offline_saved"
                  ? "status-offline"
                  : "status-error"
              }`}
            >
              <div className="result-icon-circle">
                {scanResult.status === "success" ? (
                  <CheckCircle2 size={48} />
                ) : scanResult.status === "offline_saved" ? (
                  <WifiOff size={48} />
                ) : (
                  <AlertCircle size={48} />
                )}
              </div>
              <h3>
                {scanResult.status === "success"
                  ? "Marked Present"
                  : scanResult.status === "offline_saved"
                  ? "Saved Offline"
                  : "Scan Failed"}
              </h3>
              <p>{scanResult.message}</p>

              <div className="result-actions">
                {scanResult.status === "error" ? (
                  <button
                    className="retry-scan-btn"
                    onClick={() => {
                      setScanResult(null);
                      setScanning(true);
                    }}
                  >
                    <RefreshCw size={16} /> Scan Again
                  </button>
                ) : (
                  <button className="attendance-done-btn" onClick={onClose}>
                    Done
                  </button>
                )}
              </div>
            </div>
          ) : hasCamera ? (
            <div className="viewfinder-wrapper">
              <video
                ref={videoRef}
                playsInline
                muted
                className="viewfinder-video"
              />
              <div className="viewfinder-reticle">
                <div className="reticle-corner top-left" />
                <div className="reticle-corner top-right" />
                <div className="reticle-corner bottom-left" />
                <div className="reticle-corner bottom-right" />
                <div className="scanning-laser-beam" />
              </div>
              <div className="viewfinder-tip">
                <ScanLine size={16} />
                <span>Keep QR centered inside frame</span>
              </div>
            </div>
          ) : (
            <div className="camera-fallback-card">
              <Camera size={36} className="text-muted" />
              <h3>Camera Access Unavailable</h3>
              <p>
                Camera permissions might be disabled or not supported in this
                browser. You can enter the QR payload text below:
              </p>
              <textarea
                className="manual-input-area"
                placeholder='Paste QR code payload here (e.g. {"s":"...","w":...})'
                rows={3}
                value={manualCode}
                onChange={(e) => setManualCode(e.target.value)}
              />
              <button
                className="manual-submit-btn"
                disabled={!manualCode.trim() || isSubmitting}
                onClick={() => handleQrDetected(manualCode.trim())}
              >
                {isSubmitting ? "Verifying..." : "Submit Attendance"}
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Cloud,
  HardDrive,
  RefreshCw,
  FileText,
  Users,
  AlertTriangle,
  CheckCircle2,
  AlertCircle,
  ExternalLink,
} from "lucide-react";
import { rpc } from "@/lib/api";
import { Modal } from "./ui";

export interface R2StorageStats {
  total_bytes: number;
  total_files: number;
  quota_bytes: number; // 10 GB
  limit_bytes: number; // 9.9 GB
  warning_bytes: number; // 8.0 GB
  status: "normal" | "warning" | "exceeded";
  courses: Array<{
    course_title: string;
    course_code: string;
    file_count: number;
    total_bytes: number;
  }>;
  uploaders: Array<{
    full_name: string;
    role: string;
    file_count: number;
    total_bytes: number;
  }>;
}

export function formatBytes(bytes: number): string {
  if (bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(i >= 3 ? 2 : 1)} ${units[i]}`;
}

export function R2StorageModal({ close }: { close: () => void }) {
  const { data: stats, isLoading, isError, refetch, isFetching } = useQuery<R2StorageStats>({
    queryKey: ["r2-storage-stats"],
    queryFn: async () => {
      const res = await rpc<R2StorageStats>("get_r2_storage_stats");
      if (!res) throw new Error("Could not retrieve R2 storage stats");
      return res;
    },
    staleTime: 60_000,
  });

  const totalBytes = stats?.total_bytes ?? 0;
  const quotaBytes = stats?.quota_bytes ?? 10_737_418_240; // 10 GB
  const limitBytes = stats?.limit_bytes ?? 10_630_044_057; // 9.9 GB
  const usedGb = totalBytes / (1024 * 1024 * 1024);
  const remainingLimitBytes = Math.max(0, limitBytes - totalBytes);
  const percentOfQuota = Math.min(100, (totalBytes / quotaBytes) * 100);
  const percentOfLimit = Math.min(100, (totalBytes / limitBytes) * 100);

  const status = stats?.status ?? (totalBytes >= limitBytes ? "exceeded" : totalBytes >= 8 * 1024 * 1024 * 1024 ? "warning" : "normal");

  const statusColor =
    status === "exceeded"
      ? "#ef4444" // red
      : status === "warning"
      ? "#f59e0b" // amber
      : "#10b981"; // emerald green

  return (
    <Modal title="Cloudflare R2 Storage" close={close}>
      <div style={{ display: "flex", flexDirection: "column", gap: "1.25rem", minWidth: "min(100%, 540px)" }}>
        {/* Top Header Card */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            background: "var(--surface-muted, rgba(0,0,0,0.02))",
            padding: "0.85rem 1rem",
            borderRadius: "14px",
            border: "1px solid var(--border)",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
            <div
              style={{
                width: "36px",
                height: "36px",
                borderRadius: "10px",
                background: "rgba(14, 165, 233, 0.12)",
                color: "#0284c7",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
              }}
            >
              <Cloud size={20} />
            </div>
            <div>
              <div style={{ fontWeight: 700, fontSize: "14px", color: "var(--text)" }}>
                Cloudflare R2 Bucket
              </div>
              <div style={{ fontSize: "11.5px", color: "var(--muted)" }}>
                Free tier quota · 10.00 GB maximum
              </div>
            </div>
          </div>

          <button
            type="button"
            className="secondary compact"
            onClick={() => refetch()}
            disabled={isFetching}
            style={{ display: "inline-flex", alignItems: "center", gap: "5px", padding: "4px 10px", fontSize: "11.5px" }}
          >
            <RefreshCw size={12} className={isFetching ? "spin" : ""} />
            <span>Refresh</span>
          </button>
        </div>

        {isLoading ? (
          <div style={{ textAlign: "center", padding: "2rem", color: "var(--muted)" }}>
            <RefreshCw size={24} className="spin" style={{ margin: "0 auto 8px" }} />
            <div>Loading storage records…</div>
          </div>
        ) : isError || !stats ? (
          <div style={{ padding: "1rem", color: "#ef4444", background: "rgba(239,68,68,0.1)", borderRadius: "10px" }}>
            Unable to load storage metrics. Please check admin permissions.
          </div>
        ) : (
          <>
            {/* Visual Storage Meter Card */}
            <div
              style={{
                background: "var(--surface)",
                border: "1px solid var(--border)",
                borderRadius: "16px",
                padding: "1.1rem",
                display: "flex",
                flexDirection: "column",
                gap: "0.85rem",
              }}
            >
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
                <div>
                  <span style={{ fontSize: "22px", fontWeight: 800, color: "var(--text)" }}>
                    {usedGb.toFixed(2)} GB
                  </span>
                  <span style={{ fontSize: "13px", color: "var(--muted)", marginLeft: "6px" }}>
                    of 10.00 GB used
                  </span>
                </div>
                <span
                  style={{
                    fontSize: "11.5px",
                    fontWeight: 700,
                    padding: "3px 9px",
                    borderRadius: "999px",
                    background: status === "exceeded" ? "rgba(239,68,68,0.15)" : status === "warning" ? "rgba(245,158,11,0.15)" : "rgba(16,185,129,0.15)",
                    color: statusColor,
                    display: "inline-flex",
                    alignItems: "center",
                    gap: "4px",
                  }}
                >
                  {status === "exceeded" ? (
                    <>
                      <AlertCircle size={12} /> Limit Exceeded (9.9 GB)
                    </>
                  ) : status === "warning" ? (
                    <>
                      <AlertTriangle size={12} /> Approaching Limit
                    </>
                  ) : (
                    <>
                      <CheckCircle2 size={12} /> Storage Healthy
                    </>
                  )}
                </span>
              </div>

              {/* Progress Bar with 9.9 GB Marker */}
              <div style={{ position: "relative", width: "100%", height: "14px", background: "var(--surface-muted, #f1f5f9)", borderRadius: "999px", overflow: "hidden" }}>
                <div
                  style={{
                    height: "100%",
                    width: `${percentOfQuota}%`,
                    background: statusColor,
                    borderRadius: "999px",
                    transition: "width 0.4s ease",
                  }}
                />
              </div>

              {/* Threshold & Remaining Notes */}
              <div style={{ display: "flex", justifyContent: "space-between", fontSize: "11.5px", color: "var(--muted)" }}>
                <span>Hard upload limit: <strong>9.90 GB</strong></span>
                <span>
                  {status === "exceeded" ? (
                    <strong style={{ color: "#ef4444" }}>Uploads blocked</strong>
                  ) : (
                    <strong>{formatBytes(remainingLimitBytes)} remaining</strong>
                  )}
                </span>
              </div>
            </div>

            {/* Bento Metric Spec Row */}
            <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: "0.75rem" }}>
              <div
                style={{
                  background: "var(--surface)",
                  border: "1px solid var(--border)",
                  borderRadius: "12px",
                  padding: "0.75rem",
                  textAlign: "center",
                }}
              >
                <div style={{ fontSize: "10.5px", textTransform: "uppercase", color: "var(--muted)", fontWeight: 700, letterSpacing: "0.5px" }}>
                  Active Files
                </div>
                <div style={{ fontSize: "16px", fontWeight: 800, marginTop: "2px", color: "var(--text)" }}>
                  {stats.total_files}
                </div>
              </div>

              <div
                style={{
                  background: "var(--surface)",
                  border: "1px solid var(--border)",
                  borderRadius: "12px",
                  padding: "0.75rem",
                  textAlign: "center",
                }}
              >
                <div style={{ fontSize: "10.5px", textTransform: "uppercase", color: "var(--muted)", fontWeight: 700, letterSpacing: "0.5px" }}>
                  Total Stored
                </div>
                <div style={{ fontSize: "16px", fontWeight: 800, marginTop: "2px", color: "var(--text)" }}>
                  {formatBytes(stats.total_bytes)}
                </div>
              </div>

              <div
                style={{
                  background: "var(--surface)",
                  border: "1px solid var(--border)",
                  borderRadius: "12px",
                  padding: "0.75rem",
                  textAlign: "center",
                }}
              >
                <div style={{ fontSize: "10.5px", textTransform: "uppercase", color: "var(--muted)", fontWeight: 700, letterSpacing: "0.5px" }}>
                  Quota Used
                </div>
                <div style={{ fontSize: "16px", fontWeight: 800, marginTop: "2px", color: statusColor }}>
                  {percentOfQuota.toFixed(1)}%
                </div>
              </div>
            </div>

            {/* Course Usage Breakdown */}
            <div>
              <div style={{ display: "flex", alignItems: "center", gap: "6px", marginBottom: "0.5rem", fontWeight: 700, fontSize: "13px", color: "var(--text)" }}>
                <FileText size={15} style={{ color: "var(--primary)" }} />
                <span>Storage by Course</span>
              </div>
              <div
                style={{
                  background: "var(--surface)",
                  border: "1px solid var(--border)",
                  borderRadius: "12px",
                  overflow: "hidden",
                }}
              >
                {stats.courses.length === 0 ? (
                  <div style={{ padding: "0.85rem", fontSize: "12px", color: "var(--muted)", textAlign: "center" }}>
                    No course files uploaded yet.
                  </div>
                ) : (
                  stats.courses.map((c, i) => (
                    <div
                      key={i}
                      style={{
                        display: "flex",
                        alignItems: "center",
                        justifyContent: "space-between",
                        padding: "0.6rem 0.85rem",
                        borderBottom: i < stats.courses.length - 1 ? "1px solid var(--border)" : "none",
                        fontSize: "12px",
                      }}
                    >
                      <div style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", marginRight: "8px" }}>
                        <span style={{ fontWeight: 700, color: "var(--text)", marginRight: "6px" }}>
                          {c.course_code}
                        </span>
                        <span style={{ color: "var(--muted)" }}>{c.course_title}</span>
                      </div>
                      <div style={{ display: "flex", alignItems: "center", gap: "10px", flexShrink: 0 }}>
                        <span style={{ color: "var(--muted)", fontSize: "11px" }}>
                          {c.file_count} {c.file_count === 1 ? "file" : "files"}
                        </span>
                        <strong style={{ color: "var(--text)" }}>
                          {formatBytes(c.total_bytes)}
                        </strong>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>

            {/* Top Contributors / Uploaders Breakdown */}
            <div>
              <div style={{ display: "flex", alignItems: "center", gap: "6px", marginBottom: "0.5rem", fontWeight: 700, fontSize: "13px", color: "var(--text)" }}>
                <Users size={15} style={{ color: "var(--accent, #6366f1)" }} />
                <span>Top Contributors</span>
              </div>
              <div
                style={{
                  background: "var(--surface)",
                  border: "1px solid var(--border)",
                  borderRadius: "12px",
                  overflow: "hidden",
                }}
              >
                {stats.uploaders.length === 0 ? (
                  <div style={{ padding: "0.85rem", fontSize: "12px", color: "var(--muted)", textAlign: "center" }}>
                    No uploads recorded.
                  </div>
                ) : (
                  stats.uploaders.map((u, i) => (
                    <div
                      key={i}
                      style={{
                        display: "flex",
                        alignItems: "center",
                        justifyContent: "space-between",
                        padding: "0.6rem 0.85rem",
                        borderBottom: i < stats.uploaders.length - 1 ? "1px solid var(--border)" : "none",
                        fontSize: "12px",
                      }}
                    >
                      <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
                        <span style={{ fontWeight: 600, color: "var(--text)" }}>{u.full_name}</span>
                        <span
                          style={{
                            fontSize: "10px",
                            textTransform: "uppercase",
                            fontWeight: 700,
                            padding: "1px 5px",
                            borderRadius: "4px",
                            background: "var(--surface-muted, #f1f5f9)",
                            color: "var(--muted)",
                          }}
                        >
                          {u.role}
                        </span>
                      </div>
                      <div style={{ display: "flex", alignItems: "center", gap: "10px", flexShrink: 0 }}>
                        <span style={{ color: "var(--muted)", fontSize: "11px" }}>
                          {u.file_count} {u.file_count === 1 ? "file" : "files"}
                        </span>
                        <strong style={{ color: "var(--text)" }}>
                          {formatBytes(u.total_bytes)}
                        </strong>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>

            {/* Policy Info Footer */}
            <div
              style={{
                fontSize: "11px",
                color: "var(--muted)",
                lineHeight: "1.5",
                background: "var(--surface-muted, rgba(0,0,0,0.02))",
                padding: "0.75rem",
                borderRadius: "10px",
                border: "1px solid var(--border)",
              }}
            >
              Cloudflare R2 provides a 10 GB free tier. ClassMate automatically halts new file uploads once total storage reaches <strong>9.90 GB</strong> to guarantee zero storage billing. External Google Drive links remain unaffected.
            </div>
          </>
        )}
      </div>
    </Modal>
  );
}

from pathlib import Path

p = Path("web/src/components/library.tsx")
content = p.read_text(encoding="utf-8")

# 1. Import R2StorageStats and formatBytes if not imported
if "R2StorageStats" not in content:
    content = content.replace(
        'import { rpc, supabase, edge, openFile, uploadFile, type Row } from "@/lib/api";',
        'import { rpc, supabase, edge, openFile, uploadFile, type Row } from "@/lib/api";\nimport { type R2StorageStats, formatBytes } from "./r2-storage-modal";'
    )

# 2. Add storage query to UploadForm
target_form_start = """function UploadForm({
  ctx,
  permissions,
  defaultCourse,
  done,
}: {
  ctx: Context;
  permissions?: Row;
  defaultCourse?: string;
  done: () => Promise<void>;
}) {
  const [tab, setTab] = useState<"file" | "link">("file");
  const [file, setFile] = useState<File | null>(null);"""

replacement_form_start = """function UploadForm({
  ctx,
  permissions,
  defaultCourse,
  done,
}: {
  ctx: Context;
  permissions?: Row;
  defaultCourse?: string;
  done: () => Promise<void>;
}) {
  const [tab, setTab] = useState<"file" | "link">("file");
  const [file, setFile] = useState<File | null>(null);

  const storageQuery = useQuery({
    queryKey: ["r2-storage-stats"],
    queryFn: () => rpc<R2StorageStats>("get_r2_storage_stats"),
    staleTime: 30_000,
  });
  const isStorageExceeded = storageQuery.data?.status === "exceeded";
  const isStorageWarning = storageQuery.data?.status === "warning";"""

if target_form_start in content:
    content = content.replace(target_form_start, replacement_form_start)
elif target_form_start.replace('\n', '\r\n') in content:
    content = content.replace(target_form_start.replace('\n', '\r\n'), replacement_form_start.replace('\n', '\r\n'))

# 3. Add storage check in file tab
target_file_tab = """      {tab === "file" ? (
        <Form
          label="Upload file" """

replacement_file_tab = """      {tab === "file" && isStorageExceeded ? (
        <div style={{ padding: "1.25rem", background: "rgba(239, 68, 68, 0.1)", border: "1px solid #ef4444", borderRadius: "12px", textAlign: "center", marginBottom: "1rem" }}>
          <AlertCircle size={28} style={{ color: "#ef4444", margin: "0 auto 8px" }} />
          <div style={{ fontWeight: 700, color: "var(--text)", marginBottom: "4px" }}>
            Storage Quota Exceeded (9.9 GB Limit)
          </div>
          <div style={{ fontSize: "12.5px", color: "var(--muted)", lineHeight: 1.5 }}>
            Cloudflare R2 free tier storage is at capacity. Direct file uploads are paused.
            You can still share resources without limits using <strong>Drive Link</strong>!
          </div>
          <button
            type="button"
            className="primary"
            style={{ marginTop: "12px", width: "100%" }}
            onClick={() => setTab("link")}
          >
            Switch to Drive Link
          </button>
        </div>
      ) : tab === "file" ? (
        <>
          {isStorageWarning && storageQuery.data && (
            <div style={{ padding: "0.6rem 0.85rem", background: "rgba(245, 158, 11, 0.1)", border: "1px solid #f59e0b", borderRadius: "8px", fontSize: "11.5px", color: "var(--text)", marginBottom: "0.75rem", display: "flex", alignItems: "center", gap: "6px" }}>
              <AlertTriangle size={14} style={{ color: "#f59e0b", flexShrink: 0 }} />
              <span>Storage notice: Cloudflare R2 is at <strong>{(storageQuery.data.total_bytes / (1024**3)).toFixed(2)} GB</strong> (approaching 9.9 GB limit).</span>
            </div>
          )}
        <Form
          label="Upload file" """

if target_file_tab in content:
    content = content.replace(target_file_tab, replacement_file_tab)
elif target_file_tab.replace('\n', '\r\n') in content:
    content = content.replace(target_file_tab.replace('\n', '\r\n'), replacement_file_tab.replace('\n', '\r\n'))

# 4. Close the fragment if file tab was opened with fragment
target_tab_end = """            await done();
          }}
        />
      ) : ("""

replacement_tab_end = """            if (isStorageExceeded) {
              throw new Error("Storage quota exceeded (9.9 GB limit). Please share via Drive Link.");
            }
            if (storageQuery.data && (storageQuery.data.total_bytes + file.size > 10_630_044_057)) {
              throw new Error(`This file (${(file.size / (1024 * 1024)).toFixed(1)} MB) exceeds the remaining 9.9 GB storage quota.`);
            }
            await done();
          }}
        />
        </>
      ) : ("""

if target_tab_end in content:
    content = content.replace(target_tab_end, replacement_tab_end)
elif target_tab_end.replace('\n', '\r\n') in content:
    content = content.replace(target_tab_end.replace('\n', '\r\n'), replacement_tab_end.replace('\n', '\r\n'))

p.write_text(content, encoding="utf-8")
print("Updated library.tsx successfully")

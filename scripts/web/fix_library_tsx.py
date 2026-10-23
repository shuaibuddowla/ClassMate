from pathlib import Path

p = Path("web/src/components/library.tsx")
content = p.read_text(encoding="utf-8")

# Replace from tab === "file" to the end of form
old_block = """      {tab === "file" ? (
        <Form
          label="Upload file"
          fields={[
            {
              name: "course",
              label: "Subject",
              value: defaultCourse || undefined,
              required: !permissions?.general,
              options: ctx.courses
                .filter((c) => permissions?.courses[c.offering_id])
                .map((c) => ({ value: c.offering_id, label: c.course_title })),
            },
            { name: "title", label: "File name", required: true },
          ]}
          extra={
            <label className="upload-area">
              <Upload />
              <strong>{file?.name || "Choose a file"}</strong>
              <small>Up to 500 MB · shared with your batch</small>
              <input
                type="file"
                required
                onChange={(e) => setFile(e.target.files?.[0] || null)}
              />
            </label>
          }
          submit={async (d) => {
            if (!file) throw new Error("Choose a file.");
            if (file.size < 1 || file.size > 500_000_000)
              throw new Error("Choose a file smaller than 500 MB.");
            await uploadFile(file, {
              batch_id: ctx.batch,
              semester_course_id: d.course || null,
              title: d.title,
              category: "notes",
            });
            if (isStorageExceeded) {
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

new_block = """      {tab === "file" && isStorageExceeded ? (
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
        <div>
          {isStorageWarning && storageQuery.data && (
            <div style={{ padding: "0.6rem 0.85rem", background: "rgba(245, 158, 11, 0.1)", border: "1px solid #f59e0b", borderRadius: "8px", fontSize: "11.5px", color: "var(--text)", marginBottom: "0.75rem", display: "flex", alignItems: "center", gap: "6px" }}>
              <AlertTriangle size={14} style={{ color: "#f59e0b", flexShrink: 0 }} />
              <span>Storage notice: Cloudflare R2 is at <strong>{(storageQuery.data.total_bytes / (1024**3)).toFixed(2)} GB</strong> (approaching 9.9 GB limit).</span>
            </div>
          )}
          <Form
            label="Upload file"
            fields={[
              {
                name: "course",
                label: "Subject",
                value: defaultCourse || undefined,
                required: !permissions?.general,
                options: ctx.courses
                  .filter((c) => permissions?.courses[c.offering_id])
                  .map((c) => ({ value: c.offering_id, label: c.course_title })),
              },
              { name: "title", label: "File name", required: true },
            ]}
            extra={
              <label className="upload-area">
                <Upload />
                <strong>{file?.name || "Choose a file"}</strong>
                <small>Up to 500 MB · shared with your batch</small>
                <input
                  type="file"
                  required
                  onChange={(e) => setFile(e.target.files?.[0] || null)}
                />
              </label>
            }
            submit={async (d) => {
              if (!file) throw new Error("Choose a file.");
              if (file.size < 1 || file.size > 500_000_000)
                throw new Error("Choose a file smaller than 500 MB.");
              if (isStorageExceeded) {
                throw new Error("Storage quota exceeded (9.9 GB limit). Please share via Drive Link.");
              }
              if (storageQuery.data && (storageQuery.data.total_bytes + file.size > 10_630_044_057)) {
                throw new Error(`This file (${(file.size / (1024 * 1024)).toFixed(1)} MB) exceeds the remaining 9.9 GB storage quota.`);
              }
              await uploadFile(file, {
                batch_id: ctx.batch,
                semester_course_id: d.course || null,
                title: d.title,
                category: "notes",
              });
              await done();
            }}
          />
        </div>
      ) : ("""

if old_block in content:
    content = content.replace(old_block, new_block)
elif old_block.replace('\n', '\r\n') in content:
    content = content.replace(old_block.replace('\n', '\r\n'), new_block.replace('\n', '\r\n'))
else:
    raise RuntimeError('old_block not found in library.tsx')

p.write_text(content, encoding="utf-8")
print("Fixed library.tsx")

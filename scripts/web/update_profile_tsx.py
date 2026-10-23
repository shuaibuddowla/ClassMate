from pathlib import Path

p = Path("web/src/components/profile.tsx")
content = p.read_text(encoding="utf-8")

# 1. Add Cloud to imports and import R2StorageModal
target_import = 'import { InstallWebApp } from "./install-web-app";\nimport { AboutDeveloper } from "./about-developer";'
replacement_import = 'import { InstallWebApp } from "./install-web-app";\nimport { AboutDeveloper } from "./about-developer";\nimport { R2StorageModal } from "./r2-storage-modal";'

if 'Cloud,' not in content:
    content = content.replace('Radio,', 'Radio,\n  Cloud,')

if target_import in content:
    content = content.replace(target_import, replacement_import)
elif target_import.replace('\n', '\r\n') in content:
    content = content.replace(target_import.replace('\n', '\r\n'), replacement_import.replace('\n', '\r\n'))

# 2. Add state inside Profile component
target_state = 'const [developerOpen, setDeveloperOpen] = useState(false);'
replacement_state = 'const [developerOpen, setDeveloperOpen] = useState(false);\n  const [r2StorageOpen, setR2StorageOpen] = useState(false);'

if target_state in content:
    content = content.replace(target_state, replacement_state)
elif target_state.replace('\n', '\r\n') in content:
    content = content.replace(target_state.replace('\n', '\r\n'), replacement_state.replace('\n', '\r\n'))

# 3. Add row in UTILITIES & STORAGE section
target_section = """      {/* 6. STORAGE & DATA MANAGER */}
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
      </section>"""

replacement_section = """      {/* 6. STORAGE & DATA MANAGER */}
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

        {(ctx.owner || ctx.profile.is_cr || ctx.profile.role === "admin" || ctx.profile.role === "superadmin" || ctx.profile.role === "teacher") && (
          <button
            type="button"
            className="settings-row"
            onClick={() => setR2StorageOpen(true)}
          >
            <div className="setting-icon-box cyan">
              <Cloud size={18} />
            </div>
            <div className="settings-row-text">
              <div style={{ display: "flex", alignItems: "center", gap: "6px" }}>
                <span>Cloudflare R2 Storage</span>
                <span className="status-pill active" style={{ fontSize: "9.5px", padding: "1px 6px" }}>
                  10 GB Limit
                </span>
              </div>
              <small>Monitor active storage records, course usage, and 9.9 GB quota</small>
            </div>
            <ChevronRight size={18} className="chevron" />
          </button>
        )}
      </section>"""

if target_section in content:
    content = content.replace(target_section, replacement_section)
elif target_section.replace('\n', '\r\n') in content:
    content = content.replace(target_section.replace('\n', '\r\n'), replacement_section.replace('\n', '\r\n'))

# 4. Add modal render before closing Profile
target_modal = '{developerOpen && <AboutDeveloper close={() => setDeveloperOpen(false)} />}'
replacement_modal = '{developerOpen && <AboutDeveloper close={() => setDeveloperOpen(false)} />}\n      {r2StorageOpen && <R2StorageModal close={() => setR2StorageOpen(false)} />}'

if target_modal in content:
    content = content.replace(target_modal, replacement_modal)
elif target_modal.replace('\n', '\r\n') in content:
    content = content.replace(target_modal.replace('\n', '\r\n'), replacement_modal.replace('\n', '\r\n'))

p.write_text(content, encoding="utf-8")
print("Updated profile.tsx successfully")

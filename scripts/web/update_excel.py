import pathlib

excel_file = pathlib.Path('web/src/lib/attendance-excel.ts')
content = excel_file.read_text(encoding='utf-8')

old_part = """    // Columns for each session with day index, date, and timestamp
    sessions.forEach((sess, sIdx) => {
      let timeStr = "";
      if (sess.started_at) {
        try {
          const d = new Date(sess.started_at);
          timeStr = ` (${d.toLocaleDateString("en-GB", { day: "2-digit", month: "short" })} ${d.toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit" })})`;
        } catch {}
      }
      const colHeader = `Day ${sIdx + 1}${timeStr}`;
      const rec = s.records?.[sess.session_id];
      row[colHeader] = isRecordPresent(rec) ? "P" : "—";
    });"""

new_part = """    // Columns for each session with clean compact date header (no timestamps, no Day 1/2)
    sessions.forEach((sess) => {
      const colHeader = sessionHeaderMap.get(sess.session_id) || "Class";
      const rec = s.records?.[sess.session_id];
      row[colHeader] = isRecordPresent(rec) ? "P" : "—";
    });"""

header_builder = """  // Map session IDs to clean compact date headers (e.g. "13 Oct", "27 Oct (2)" if multiple on same date)
  const dateCounts = new Map<string, number>();
  sessions.forEach((sess) => {
    let dStr = "";
    if (sess.started_at) {
      try {
        const d = new Date(sess.started_at);
        dStr = d.toLocaleDateString("en-GB", { day: "2-digit", month: "short" });
      } catch {}
    }
    if (!dStr && sess.date) {
      try {
        const d = new Date(sess.date);
        dStr = d.toLocaleDateString("en-GB", { day: "2-digit", month: "short" });
      } catch {
        dStr = String(sess.date);
      }
    }
    if (!dStr) dStr = "Class";
    dateCounts.set(dStr, (dateCounts.get(dStr) || 0) + 1);
  });

  const occurrenceMap = new Map<string, number>();
  const sessionHeaderMap = new Map<string, string>();
  sessions.forEach((sess, sIdx) => {
    let dStr = "";
    if (sess.started_at) {
      try {
        const d = new Date(sess.started_at);
        dStr = d.toLocaleDateString("en-GB", { day: "2-digit", month: "short" });
      } catch {}
    }
    if (!dStr && sess.date) {
      try {
        const d = new Date(sess.date);
        dStr = d.toLocaleDateString("en-GB", { day: "2-digit", month: "short" });
      } catch {
        dStr = String(sess.date);
      }
    }
    if (!dStr) dStr = `Class ${sIdx + 1}`;

    const total = dateCounts.get(dStr) || 1;
    const cur = (occurrenceMap.get(dStr) || 0) + 1;
    occurrenceMap.set(dStr, cur);

    if (total > 1) {
      sessionHeaderMap.set(sess.session_id, `${dStr} (${cur})`);
    } else {
      sessionHeaderMap.set(sess.session_id, dStr);
    }
  });

  // Build matrix rows sorted cleanly by roll"""

old_cols = """  // Column width calculations for clean auto-fit
  const colKeys = rows.length > 0 ? Object.keys(rows[0]) : [];
  worksheet["!cols"] = colKeys.map((k) => ({
    wch: Math.max(k.length + 3, 12),
  }));"""

new_cols = """  // Compact column width calculations: fits 30+ classes in one page!
  const colKeys = rows.length > 0 ? Object.keys(rows[0]) : [];
  worksheet["!cols"] = colKeys.map((k) => {
    if (k === "SL") return { wch: 4 };
    if (k.toLowerCase().includes("roll") || k.toLowerCase().includes("student id")) return { wch: 12 };
    if (k.toLowerCase().includes("name")) return { wch: 18 };
    if (k.toLowerCase().includes("present") || k.toLowerCase().includes("classes") || k.includes("%")) return { wch: 8 };
    // Date session columns: ultra-compact 6.5 characters!
    return { wch: 6.5 };
  });

  // Configure print/page setup to fit 30+ classes across 1 landscape page
  worksheet["!pageSetup"] = {
    orientation: "landscape",
    paperSize: 9, // A4
    fitToPage: true,
    fitToWidth: 1,
    fitToHeight: 0,
  };"""

assert '  // Build matrix rows sorted cleanly by roll' in content
content = content.replace('  // Build matrix rows sorted cleanly by roll', header_builder)
assert old_part in content
content = content.replace(old_part, new_part)
assert old_cols in content
content = content.replace(old_cols, new_cols)

excel_file.write_text(content, encoding='utf-8')
print("Successfully updated web/src/lib/attendance-excel.ts")

# Now update web/src/app/api/export-attendance/route.ts
route_file = pathlib.Path('web/src/app/api/export-attendance/route.ts')
route_content = route_file.read_text(encoding='utf-8')

route_old_cols = """      const worksheet = XLSX.utils.json_to_sheet(rows);
      const colKeys = rows.length > 0 ? Object.keys(rows[0]) : [];
      worksheet["!cols"] = colKeys.map((k) => ({
        wch: Math.max(k.length + 3, 12),
      }));"""

route_new_cols = """      const worksheet = XLSX.utils.json_to_sheet(rows);
      const colKeys = rows.length > 0 ? Object.keys(rows[0]) : [];
      worksheet["!cols"] = colKeys.map((k) => {
        if (k === "SL") return { wch: 4 };
        if (k.toLowerCase().includes("roll") || k.toLowerCase().includes("student id")) return { wch: 12 };
        if (k.toLowerCase().includes("name")) return { wch: 18 };
        if (k.toLowerCase().includes("present") || k.toLowerCase().includes("classes") || k.includes("%")) return { wch: 8 };
        return { wch: 6.5 };
      });

      worksheet["!pageSetup"] = {
        orientation: "landscape",
        paperSize: 9,
        fitToPage: true,
        fitToWidth: 1,
        fitToHeight: 0,
      };"""

route_old_part = """        // Columns for each session with day index, date, and timestamp
        sessions.forEach((sess: any, sIdx: number) => {
          let timeStr = "";
          if (sess.started_at) {
            try {
              const d = new Date(sess.started_at);
              timeStr = ` (${d.toLocaleDateString("en-GB", {
                day: "2-digit",
                month: "short",
              })} ${d.toLocaleTimeString("en-US", {
                hour: "2-digit",
                minute: "2-digit",
              })})`;
            } catch {}
          }
          const colHeader = `Day ${sIdx + 1}${timeStr}`;
          const rec = s.records?.[sess.session_id];
          row[colHeader] = isRecordPresent(rec) ? "P" : "—";
        });"""

route_new_part = """        // Columns for each session with clean compact date header (no timestamps, no Day 1/2)
        sessions.forEach((sess: any) => {
          const colHeader = sessionHeaderMap.get(sess.session_id) || "Class";
          const rec = s.records?.[sess.session_id];
          row[colHeader] = isRecordPresent(rec) ? "P" : "—";
        });"""

route_header_builder = """      // Map session IDs to clean compact date headers (e.g. "13 Oct", "27 Oct (2)" if multiple on same date)
      const dateCounts = new Map<string, number>();
      sessions.forEach((sess: any) => {
        let dStr = "";
        if (sess.started_at) {
          try {
            const d = new Date(sess.started_at);
            dStr = d.toLocaleDateString("en-GB", { day: "2-digit", month: "short" });
          } catch {}
        }
        if (!dStr && sess.date) {
          try {
            const d = new Date(sess.date);
            dStr = d.toLocaleDateString("en-GB", { day: "2-digit", month: "short" });
          } catch {
            dStr = String(sess.date);
          }
        }
        if (!dStr) dStr = "Class";
        dateCounts.set(dStr, (dateCounts.get(dStr) || 0) + 1);
      });

      const occurrenceMap = new Map<string, number>();
      const sessionHeaderMap = new Map<string, string>();
      sessions.forEach((sess: any, sIdx: number) => {
        let dStr = "";
        if (sess.started_at) {
          try {
            const d = new Date(sess.started_at);
            dStr = d.toLocaleDateString("en-GB", { day: "2-digit", month: "short" });
          } catch {}
        }
        if (!dStr && sess.date) {
          try {
            const d = new Date(sess.date);
            dStr = d.toLocaleDateString("en-GB", { day: "2-digit", month: "short" });
          } catch {
            dStr = String(sess.date);
          }
        }
        if (!dStr) dStr = `Class ${sIdx + 1}`;

        const total = dateCounts.get(dStr) || 1;
        const cur = (occurrenceMap.get(dStr) || 0) + 1;
        occurrenceMap.set(dStr, cur);

        if (total > 1) {
          sessionHeaderMap.set(sess.session_id, `${dStr} (${cur})`);
        } else {
          sessionHeaderMap.set(sess.session_id, dStr);
        }
      });

      // Build matrix rows sorted cleanly by roll"""

if '      // Build matrix rows sorted cleanly by roll' in route_content:
    route_content = route_content.replace('      // Build matrix rows sorted cleanly by roll', route_header_builder)
    assert route_old_part in route_content, "route_old_part not found"
    route_content = route_content.replace(route_old_part, route_new_part)
    assert route_old_cols in route_content, "route_old_cols not found"
    route_content = route_content.replace(route_old_cols, route_new_cols)
    route_file.write_text(route_content, encoding='utf-8')
    print("Successfully updated web/src/app/api/export-attendance/route.ts")

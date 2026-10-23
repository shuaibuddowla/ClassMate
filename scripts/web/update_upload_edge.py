from pathlib import Path

path = Path('supabase/functions/upload-classmate-resource/index.ts')
content = path.read_text(encoding='utf-8')

target = """  if (body.action === "start") {
    const { batch_id, semester_course_id, title, file_type, mime_type, size_bytes, category } = body;
    if (typeof batch_id !== "string" || typeof title !== "string" || !title.trim()
      || typeof file_type !== "string" || typeof mime_type !== "string"
      || !Number.isSafeInteger(size_bytes) || size_bytes < 1 || size_bytes > 500_000_000
      || !["notes", "slides", "questions", "syllabus", "other"].includes(category)) {
      return new Response("Invalid upload metadata", { status: 400 });
    }"""

replacement = """  if (body.action === "start") {
    const { batch_id, semester_course_id, title, file_type, mime_type, size_bytes } = body;
    const category = body.category || "other";
    if (typeof batch_id !== "string" || typeof title !== "string" || !title.trim()
      || typeof file_type !== "string" || typeof mime_type !== "string"
      || !Number.isSafeInteger(size_bytes) || size_bytes < 1 || size_bytes > 500_000_000) {
      return new Response("Invalid upload metadata", { status: 400 });
    }

    // Enforce 9.9 GB storage limit (10,630,044,057 bytes)
    const LIMIT_BYTES = 10_630_044_057;
    const { data: statsData, error: statsError } = await admin.rpc("get_r2_storage_stats");
    if (!statsError && statsData && typeof statsData.total_bytes === "number") {
      if (statsData.total_bytes + size_bytes > LIMIT_BYTES) {
        return new Response(JSON.stringify({
          error: "Cloudflare R2 storage quota reached (9.9 GB limit). File uploads are paused.",
          total_bytes: statsData.total_bytes,
          limit_bytes: LIMIT_BYTES,
        }), { status: 400, headers: { "Content-Type": "application/json" } });
      }
    }"""

if target in content:
    content = content.replace(target, replacement)
elif target.replace('\n', '\r\n') in content:
    content = content.replace(target.replace('\n', '\r\n'), replacement.replace('\n', '\r\n'))
else:
    raise RuntimeError('Target string not found in index.ts')

path.write_text(content, encoding='utf-8')
print('Successfully updated upload-classmate-resource/index.ts')

import { openResource } from "./file-navigation";
import { createClient, type SupabaseClient } from "@supabase/supabase-js";
// Rows are API-owned JSON. Form contracts below send only explicit RPC arguments.
export type Row = Record<string, any>;
let client: SupabaseClient<any, "classmate">;
export const configured = () =>
  Boolean(
    process.env.NEXT_PUBLIC_SUPABASE_URL &&
      process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY,
  );
export function supabase() {
  if (!configured())
    throw new Error("The web server needs Supabase configuration.");
  return (client ??= createClient(
    process.env.NEXT_PUBLIC_SUPABASE_URL!,
    process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY!,
    {
      db: { schema: "classmate" },
      auth: {
        flowType: "pkce",
        detectSessionInUrl: false,
        persistSession: true,
        autoRefreshToken: true,
      },
    },
  ));
}
export async function rpc<T = Row>(name: string, args: Row = {}): Promise<T> {
  if (typeof navigator !== "undefined" && !navigator.onLine)
    throw new Error("Connect to the internet to complete this action.");
  const { data, error } = await supabase().schema("classmate").rpc(name, args);
  if (error) throw new Error(error.message);
  return data as T;
}
export async function rows(
  table: string,
  filter: Row = {},
  select = "*",
  order = "id",
  limit = 1000,
): Promise<Row[]> {
  let query = supabase()
    .schema("classmate")
    .from(table)
    .select(select)
    .order(order)
    .limit(limit);
  for (const [key, value] of Object.entries(filter))
    query = Array.isArray(value) ? query.in(key, value) : query.eq(key, value);
  const { data, error } = await query;
  if (error) throw new Error(error.message);
  return data || [];
}
export async function edge(name: string, body: Row) {
  if (name === "classmate-ai" && !navigator.onLine)
    throw new Error("Connect to the internet to use ClassMate AI.");
  const { data, error } = await supabase().functions.invoke(name, { body });
  if (error) {
    let message =
      name === "classmate-ai"
        ? "Could not reach ClassMate AI. Check your connection and try again."
        : "Could not complete the file request. Check your access and try again.";
    if (error.context instanceof Response) {
      const text = await error.context.text();
      if (text && text.length < 400) {
        try {
          message =
            JSON.parse(text).message || JSON.parse(text).error || message;
        } catch {
          message = text;
        }
      }
    }
    throw new Error(message);
  }
  return data as Row;
}
export { openResource } from "./file-navigation";
export async function openFile(resource: string | Row) {
  if (typeof resource !== "string" && resource?.link_url) {
    return openResource(() => Promise.resolve(String(resource.link_url)));
  }
  const id = typeof resource === "string" ? resource : String(resource?.id);
  return openResource(async () => {
    // 1. Check if resource is a link or has public metadata
    try {
      const { data } = await supabase()
        .schema("classmate")
        .rpc("get_public_resource", { target_identifier: id });
      if (data?.link_url) {
        return String(data.link_url);
      }
    } catch {
      // Continue to signed resource / direct download
    }

    // 2. Try the edge signing function
    try {
      const result = await edge("signed-classmate-resource", { resource_id: id });
      if (result?.url) return String(result.url);
    } catch {
      // Fallback
    }

    // 3. Fallback to server-side public download route
    return `/api/public-download/${id}?mode=inline`;
  });
}
export async function uploadFile(file: File, metadata: Row) {
  const start = await edge("upload-classmate-resource", {
    action: "start",
    ...metadata,
    file_type: file.name.split(".").pop()?.toLowerCase() || "file",
    mime_type: file.type || "application/octet-stream",
    size_bytes: file.size,
  });
  const result = await fetch(start.upload_url, {
    method: "PUT",
    headers: { "Content-Type": file.type || "application/octet-stream" },
    body: file,
  });
  if (!result.ok) throw new Error("File upload interrupted. Please try again.");
  return edge("upload-classmate-resource", {
    action: "finish",
    resource_id: start.resource_id,
  });
}
export async function mutation(
  table: string,
  action: "upsert" | "insert" | "delete",
  data: Row,
) {
  if (!navigator.onLine) throw new Error("Connect before making changes.");
  const tableQuery = supabase().schema("classmate").from(table);
  let query;
  if (action === "delete") {
    query = tableQuery.delete();
    for (const [key, value] of Object.entries(data))
      query = query.eq(key, value);
  } else
    query =
      action === "insert" ? tableQuery.insert(data) : tableQuery.upsert(data);
  const { error } = await query;
  if (error) throw new Error(error.message);
}

import { NextRequest, NextResponse } from "next/server";
import { createClient } from "@supabase/supabase-js";

export const runtime = "nodejs";

function getAdminClient() {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || "https://cbskpifgbdbgdmnnxfrh.supabase.co";
  const rawServiceKey = process.env.SUPABASE_SERVICE_ROLE_KEY || "";
  const anonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || "";
  // Prefer modern publishable or secret key over disabled legacy JWT service keys
  const serviceKey = rawServiceKey.startsWith("sb_") ? rawServiceKey : anonKey;
  return {
    client: createClient(url, serviceKey, { auth: { persistSession: false } }),
    url,
    serviceKey,
  };
}

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  const { id } = await params;
  const mode = request.nextUrl.searchParams.get("mode") || "open"; // "open", "inline", "download"
  const wantsJson = request.nextUrl.searchParams.get("json") === "true";

  if (!id || !/^[a-z0-9_-]{1,128}$/i.test(id)) {
    if (wantsJson) return NextResponse.json({ error: "Invalid resource identifier" }, { status: 400 });
    return NextResponse.redirect(new URL("/?error=invalid_resource", request.url));
  }

  const { client, url, serviceKey } = getAdminClient();

  let rawMeta: any = null;
  const { data: dbData, error: dbError } = await client.schema("classmate").rpc("get_public_resource", {
    target_identifier: id,
  });

  if (!dbError && dbData) {
    rawMeta = dbData;
  } else {
    const { data: fallbackData } = await client.rpc("get_public_resource", {
      target_identifier: id,
    });
    if (fallbackData) rawMeta = fallbackData;
  }

  if (!rawMeta) {
    if (wantsJson) return NextResponse.json({ error: "Resource not found or inactive" }, { status: 404 });
    return NextResponse.redirect(new URL(`/r/${id}?error=not_found`, request.url));
  }

  const meta = rawMeta as {
    id: string;
    slug?: string;
    title: string;
    is_link: boolean;
    link_url?: string;
    file_type?: string;
    mime_type?: string;
    size_bytes?: number;
  };

  // If it's an external link or Google Drive link
  if (meta.is_link && meta.link_url) {
    let targetLink = meta.link_url.trim();

    if (!/^https?:\/\//i.test(targetLink)) {
      if (wantsJson) return NextResponse.json({ error: "Invalid link protocol" }, { status: 400 });
      return NextResponse.redirect(new URL(`/r/${meta.slug || meta.id}?error=invalid_protocol`, request.url));
    }

    const isGoogleDrive = /drive\.google\.com|docs\.google\.com/i.test(targetLink);
    const isDriveFolder = isGoogleDrive && (
      /\/folders\/[a-zA-Z0-9_-]+/i.test(targetLink) ||
      /\/folderview/i.test(targetLink) ||
      targetLink.toLowerCase().includes("folder")
    );

    if (mode === "download" && !isDriveFolder) {
      let fileId: string | null = null;
      const matchFile = targetLink.match(/\/file\/d\/([a-zA-Z0-9_-]+)/);
      if (matchFile) fileId = matchFile[1];
      if (!fileId) {
        const matchId = targetLink.match(/[?&]id=([a-zA-Z0-9_-]+)/);
        if (matchId) fileId = matchId[1];
      }
      if (!fileId) {
        const matchDoc = targetLink.match(/\/(?:document|spreadsheets|presentation)\/d\/([a-zA-Z0-9_-]+)/);
        if (matchDoc) fileId = matchDoc[1];
      }

      if (fileId) {
        if (targetLink.includes("/document/d/")) {
          targetLink = `https://docs.google.com/document/d/${fileId}/export?format=pdf`;
        } else if (targetLink.includes("/spreadsheets/d/")) {
          targetLink = `https://docs.google.com/spreadsheets/d/${fileId}/export?format=xlsx`;
        } else if (targetLink.includes("/presentation/d/")) {
          targetLink = `https://docs.google.com/presentation/d/${fileId}/export/pdf`;
        } else {
          targetLink = `https://drive.google.com/uc?export=download&id=${fileId}&confirm=t`;
        }
      }
    }

    if (wantsJson) {
      return NextResponse.json({ url: targetLink, is_link: true, is_folder: isDriveFolder });
    }
    return NextResponse.redirect(targetLink, 302);
  }

  // If it's an uploaded file in Cloudflare R2, request signed URL with ResponseContentDisposition
  const ext = (meta.file_type || "pdf").toLowerCase().replace(/^\./, "");
  const cleanTitle = (meta.title || "resource").replace(/["\r\n\/\\]/g, " ").trim();
  const finalFilename = cleanTitle.toLowerCase().endsWith(`.${ext}`) ? cleanTitle : `${cleanTitle}.${ext}`;
  const disposition = mode === "download" ? "attachment" : "inline";

  try {
    const edgeRes = await fetch(`${url}/functions/v1/signed-classmate-resource`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${serviceKey}`,
      },
      body: JSON.stringify({
        resource_id: meta.id,
        disposition,
        filename: finalFilename,
      }),
    });

    if (!edgeRes.ok) {
      const errText = await edgeRes.text().catch(() => "");
      if (wantsJson) {
        return NextResponse.json(
          { error: `Failed to sign file URL: ${errText || edgeRes.statusText}` },
          { status: 502 }
        );
      }
      return NextResponse.redirect(new URL(`/r/${meta.slug || meta.id}?error=sign_failed`, request.url));
    }

    const { url: signedUrl } = await edgeRes.json();
    if (!signedUrl) {
      if (wantsJson) {
        return NextResponse.json({ error: "Missing download URL" }, { status: 502 });
      }
      return NextResponse.redirect(new URL(`/r/${meta.slug || meta.id}?error=missing_url`, request.url));
    }

    if (wantsJson) {
      return NextResponse.json({ url: signedUrl, is_link: false });
    }

    // Direct 302 redirect to Cloudflare R2 signed URL with ResponseContentDisposition
    return NextResponse.redirect(signedUrl, 302);
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : "Internal error";
    if (wantsJson) {
      return NextResponse.json({ error: message }, { status: 500 });
    }
    return NextResponse.redirect(new URL(`/r/${meta.slug || meta.id}?error=server_error`, request.url));
  }
}

import { NextRequest, NextResponse } from "next/server";
import { createClient } from "@supabase/supabase-js";

export const runtime = "nodejs";

function getAdminClient() {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || "https://cbskpifgbdbgdmnnxfrh.supabase.co";
  const serviceKey = process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || "";
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
  if (!id || !/^[a-z0-9_-]{1,128}$/i.test(id)) {
    return NextResponse.json({ error: "Invalid resource identifier" }, { status: 400 });
  }

  const { client, url, serviceKey } = getAdminClient();

  const { data: rawMeta, error } = await client.schema("classmate").rpc("get_public_resource", {
    target_identifier: id,
  });

  if (error || !rawMeta) {
    return NextResponse.json({ error: "Resource not found or inactive" }, { status: 404 });
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

  const mode = request.nextUrl.searchParams.get("mode") || "open"; // "open", "inline", "download"
  const wantsJson = request.nextUrl.searchParams.get("json") === "true";

  // If it's an external link or Google Drive link
  if (meta.is_link && meta.link_url) {
    let targetLink = meta.link_url.trim();

    if (mode === "download") {
      // Check for Google Drive file link and convert to direct download if applicable
      const gdriveFileMatch = meta.link_url.match(/\/file\/d\/([a-zA-Z0-9_-]+)/);
      if (gdriveFileMatch && gdriveFileMatch[1]) {
        targetLink = `https://drive.google.com/uc?export=download&id=${gdriveFileMatch[1]}`;
      }
    }

    if (!/^https?:\/\//i.test(targetLink)) {
      return NextResponse.json({ error: "Invalid link protocol" }, { status: 400 });
    }

    if (wantsJson) {
      return NextResponse.json({ url: targetLink, is_link: true });
    }
    return NextResponse.redirect(targetLink, 302);
  }

  // If it's an uploaded file in Cloudflare R2, request signed URL from edge function
  try {
    const edgeRes = await fetch(`${url}/functions/v1/signed-classmate-resource`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${serviceKey}`,
      },
      body: JSON.stringify({ resource_id: meta.id }),
    });

    if (!edgeRes.ok) {
      const errText = await edgeRes.text().catch(() => "");
      return NextResponse.json(
        { error: `Failed to sign file URL: ${errText || edgeRes.statusText}` },
        { status: 502 }
      );
    }

    const { url: signedUrl } = await edgeRes.json();
    if (!signedUrl) {
      return NextResponse.json({ error: "Missing download URL" }, { status: 502 });
    }

    if (wantsJson) {
      return NextResponse.json({ url: signedUrl, is_link: false });
    }

    const ext = (meta.file_type || "pdf").toLowerCase().replace(/^\./, "");
    const cleanTitle = (meta.title || "resource").replace(/["\r\n\/\\]/g, " ").trim();
    const finalFilename = cleanTitle.toLowerCase().endsWith(`.${ext}`) ? cleanTitle : `${cleanTitle}.${ext}`;

    const fileStream = await fetch(signedUrl);
    if (!fileStream.ok) {
      return NextResponse.redirect(signedUrl, 302);
    }

    const contentType = fileStream.headers.get("content-type") || meta.mime_type || "application/pdf";
    const dispositionType = mode === "download" ? "attachment" : "inline";

    return new Response(fileStream.body, {
      status: 200,
      headers: {
        "Content-Type": contentType,
        "Content-Disposition": `${dispositionType}; filename="${encodeURIComponent(finalFilename)}"`,
        "Cache-Control": "public, max-age=3600, stale-while-revalidate=86400",
        ...(fileStream.headers.get("content-length")
          ? { "Content-Length": fileStream.headers.get("content-length")! }
          : {}),
      },
    });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : "Internal error";
    return NextResponse.json({ error: message }, { status: 500 });
  }
}

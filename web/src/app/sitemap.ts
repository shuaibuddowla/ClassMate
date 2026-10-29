import type { MetadataRoute } from "next";
import { createClient } from "@supabase/supabase-js";

export const revalidate = 3600; // Revalidate sitemap every hour

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const baseUrl = "https://classmatebd.vercel.app";

  const staticEntries: MetadataRoute.Sitemap = [
    {
      url: baseUrl,
      lastModified: new Date(),
      changeFrequency: "daily",
      priority: 1.0,
    },
    {
      url: `${baseUrl}/download`,
      lastModified: new Date(),
      changeFrequency: "weekly",
      priority: 0.9,
    },
    {
      url: `${baseUrl}/about`,
      lastModified: new Date(),
      changeFrequency: "monthly",
      priority: 0.8,
    },
    {
      url: `${baseUrl}/privacy`,
      lastModified: new Date(),
      changeFrequency: "monthly",
      priority: 0.4,
    },
    {
      url: `${baseUrl}/terms`,
      lastModified: new Date(),
      changeFrequency: "monthly",
      priority: 0.4,
    },
  ];

  try {
    const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL || "https://cbskpifgbdbgdmnnxfrh.supabase.co";
    const supabaseKey = process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || "";
    
    if (supabaseKey) {
      const db = createClient(supabaseUrl, supabaseKey, { auth: { persistSession: false } });
      const { data: notices } = await db
        .schema("classmate")
        .from("notices")
        .select("id, published_at")
        .order("published_at", { ascending: false })
        .limit(60);

      if (notices && Array.isArray(notices)) {
        const noticeEntries: MetadataRoute.Sitemap = notices.map((n) => ({
          url: `${baseUrl}/n/${n.id}`,
          lastModified: n.published_at ? new Date(n.published_at) : new Date(),
          changeFrequency: "weekly" as const,
          priority: 0.7,
        }));
        return [...staticEntries, ...noticeEntries];
      }
    }
  } catch {
    // If database lookup fails during build, safely return static entries
  }

  return staticEntries;
}

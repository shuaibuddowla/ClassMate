import type { MetadataRoute } from "next";

export default function robots(): MetadataRoute.Robots {
  const baseUrl = "https://classmatebd.vercel.app";

  return {
    rules: [
      {
        userAgent: "*",
        allow: [
          "/",
          "/about",
          "/download",
          "/download/android",
          "/privacy",
          "/terms",
          "/n/",
          "/r/",
        ],
        disallow: [
          "/auth/",
          "/api/",
          "/_next/",
        ],
      },
      {
        userAgent: "Googlebot",
        allow: [
          "/",
          "/about",
          "/download",
          "/download/android",
          "/privacy",
          "/terms",
          "/n/",
          "/r/",
        ],
        disallow: [
          "/auth/",
          "/api/",
          "/_next/",
        ],
      },
    ],
    sitemap: `${baseUrl}/sitemap.xml`,
  };
}

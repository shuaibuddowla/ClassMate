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
          "/privacy",
          "/terms",
          "/transit-preview",
          "/n/",
          "/r/",
          "/c/",
        ],
        disallow: [
          "/auth/",
          "/api/",
          "/card/",
          "/download/android",
          "/_next/",
        ],
      },
      {
        userAgent: "Googlebot",
        allow: [
          "/",
          "/about",
          "/download",
          "/privacy",
          "/terms",
          "/transit-preview",
          "/n/",
          "/r/",
          "/c/",
        ],
        disallow: [
          "/auth/",
          "/api/",
          "/card/",
          "/download/android",
          "/_next/",
        ],
      },
    ],
    sitemap: `${baseUrl}/sitemap.xml`,
  };
}

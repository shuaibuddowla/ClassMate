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
        ],
        disallow: [
          "/c/",
          "/card/",
          "/auth/",
          "/api/",
          "/download/android",
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
        ],
        disallow: [
          "/c/",
          "/card/",
          "/auth/",
          "/api/",
          "/download/android",
        ],
      },
    ],
    sitemap: `${baseUrl}/sitemap.xml`,
  };
}

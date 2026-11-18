import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "MBSTU Live Bus Tracker & Schedules · ClassMate Transit",
  description:
    "Live GPS tracking, route stoppages, corridor timings, and real-time schedules for Mawlana Bhashani Science and Technology University campus buses.",
  keywords: [
    "MBSTU bus tracker",
    "MBSTU bus routine",
    "MBSTU transit",
    "ClassMate transit",
    "Mawlana Bhashani Science and Technology University bus schedule",
    "MBSTU bus routes",
  ],
  alternates: {
    canonical: "https://classmatebd.vercel.app/transit-preview",
  },
  openGraph: {
    title: "MBSTU Live Bus Tracker & Schedules · ClassMate Transit",
    description:
      "Live GPS tracking, 80 route points, 28 bus stops, and schedules for MBSTU university buses.",
    url: "https://classmatebd.vercel.app/transit-preview",
    siteName: "ClassMate",
    images: [{ url: "/logo.png", width: 512, height: 512, alt: "ClassMate Transit" }],
  },
  twitter: {
    card: "summary",
    title: "MBSTU Live Bus Tracker & Schedules · ClassMate Transit",
    description:
      "Live GPS tracking, route stoppages, and schedules for MBSTU university buses.",
    images: ["/logo.png"],
  },
};

const transitJsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "WebApplication",
      "@id": "https://classmatebd.vercel.app/transit-preview#transit",
      "name": "ClassMate MBSTU Live Bus Tracker",
      "url": "https://classmatebd.vercel.app/transit-preview",
      "applicationCategory": "TravelApplication",
      "operatingSystem": "All",
      "description":
        "Live GPS tracking, route stoppages, and timing schedules for Mawlana Bhashani Science and Technology University campus buses.",
      "publisher": {
        "@type": "Organization",
        "name": "ClassMate",
        "url": "https://classmatebd.vercel.app",
        "logo": "https://classmatebd.vercel.app/logo.png",
      },
    },
    {
      "@type": "BreadcrumbList",
      "itemListElement": [
        {
          "@type": "ListItem",
          "position": 1,
          "name": "Home",
          "item": "https://classmatebd.vercel.app",
        },
        {
          "@type": "ListItem",
          "position": 2,
          "name": "Campus Transit",
          "item": "https://classmatebd.vercel.app/transit-preview",
        },
      ],
    },
  ],
};

export default function TransitPreviewLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(transitJsonLd) }}
      />
      {children}
    </>
  );
}

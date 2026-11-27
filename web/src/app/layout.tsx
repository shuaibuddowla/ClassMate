import type { Metadata, Viewport } from "next";
import Script from "next/script";
import { Suspense } from "react";
import { Analytics } from "@vercel/analytics/next";
import { SpeedInsights } from "@vercel/speed-insights/next";
import { AnalyticsTracker } from "@/components/analytics-tracker";
import "./globals.css";

const siteUrl = "https://classmatebd.vercel.app";

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: {
    default: "ClassMate — MBSTU Smart Campus, Routine & Attendance App",
    template: "%s · ClassMate",
  },
  description:
    "ClassMate is the smart campus companion for MBSTU students and faculty. Check live class routines, RFID attendance, verified notices, bus schedules, and study materials.",
  applicationName: "ClassMate",
  category: "education",
  keywords: [
    "ClassMate",
    "ClassMate MBSTU",
    "MBSTU ClassMate",
    "MBSTU",
    "MBSTU CSE",
    "Mawlana Bhashani Science and Technology University",
    "MBSTU Class Routine",
    "MBSTU Bus Schedule",
    "RFID Attendance Management",
    "MBSTU Academic Calendar",
    "MBSTU Question Bank",
    "ClassMate Android APK",
    "ClassMate App",
  ],
  authors: [{ name: "Mohammad Shuaib-Ud-Dowla", url: "https://shuaibuddowla.github.io" }],
  creator: "Mohammad Shuaib-Ud-Dowla",
  publisher: "ClassMate",
  verification: {
    google: "N1jaz91POPN5mOXCtWpSiuQUC1Z5dIon3079aDpR1SE",
  },
  openGraph: {
    type: "website",
    locale: "en_US",
    url: siteUrl,
    siteName: "ClassMate",
    title: "ClassMate — MBSTU Smart Campus, Routine & Attendance App",
    description:
      "Live class routines, RFID attendance tracking, academic notices, bus schedules, and course libraries for MBSTU students and faculty.",
    images: [
      {
        url: "/opengraph-image",
        width: 1200,
        height: 630,
        alt: "ClassMate — Smart University & Attendance Platform",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "ClassMate — MBSTU Smart Campus, Routine & Attendance App",
    description:
      "Live class routines, RFID attendance tracking, academic notices, and MBSTU bus schedules.",
    images: ["/opengraph-image"],
  },
  robots: {
    index: true,
    follow: true,
    googleBot: {
      index: true,
      follow: true,
      "max-video-preview": -1,
      "max-image-preview": "large",
      "max-snippet": -1,
    },
  },
  manifest: "/manifest.webmanifest",
  icons: { icon: "/logo.png", apple: "/logo.png" },
  appleWebApp: { capable: true, statusBarStyle: "default", title: "ClassMate" },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#ffffff" },
    { media: "(prefers-color-scheme: dark)", color: "#0a0a0c" },
  ],
};

const jsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "WebSite",
      "@id": "https://classmatebd.vercel.app/#website",
      "name": "ClassMate",
      "alternateName": ["ClassMate MBSTU", "MBSTU ClassMate", "ClassMate Smart Campus"],
      "url": "https://classmatebd.vercel.app",
      "description":
        "Smart campus companion for MBSTU students and faculty: class routines, RFID attendance, academic notices, bus schedules, and study materials.",
      "inLanguage": "en-US",
      "publisher": {
        "@id": "https://classmatebd.vercel.app/#organization",
      },
    },
    {
      "@type": "SoftwareApplication",
      "@id": "https://classmatebd.vercel.app/#webapp",
      "name": "ClassMate",
      "alternateName": "ClassMate MBSTU",
      "url": "https://classmatebd.vercel.app",
      "downloadUrl": "https://classmatebd.vercel.app/download",
      "softwareVersion": "1.1.50",
      "applicationCategory": "EducationalApplication",
      "operatingSystem": "Android 8.0+, Web, iOS",
      "description":
        "Smart campus academic platform featuring RFID attendance tracking, live class routines, bus schedules, course libraries, and notices for MBSTU.",
      "featureList": [
        "Live day-by-day class routine & timetable",
        "Automated RFID attendance tracking",
        "Real-time batch notices & interactive polls",
        "University bus schedules & transit preview",
        "Semester study materials & question archive",
        "Emergency campus blood donor network",
      ],
      "offers": {
        "@type": "Offer",
        "price": "0",
        "priceCurrency": "BDT",
      },
      "screenshot": "https://classmatebd.vercel.app/opengraph-image",
      "author": {
        "@type": "Person",
        "name": "Mohammad Shuaib-Ud-Dowla",
        "url": "https://shuaibuddowla.github.io",
      },
    },
    {
      "@type": "EducationalOrganization",
      "@id": "https://classmatebd.vercel.app/#organization",
      "name": "ClassMate",
      "alternateName": ["ClassMate (MBSTU)", "ClassMate MBSTU"],
      "url": "https://classmatebd.vercel.app",
      "logo": "https://classmatebd.vercel.app/logo.png",
      "areaServed": "Bangladesh",
      "knowsAbout": [
        "MBSTU Class Routine",
        "RFID Attendance",
        "University Bus Schedule",
        "Academic Notices",
        "Course Library",
      ],
      "founder": {
        "@type": "Person",
        "name": "Mohammad Shuaib-Ud-Dowla",
        "url": "https://shuaibuddowla.github.io",
      },
      "sameAs": [
        "https://github.com/shuaibuddowla/ClassMate",
        "https://github.com/shuaibuddowla",
        "https://shuaibuddowla.github.io",
        "https://facebook.com/shuaibuddowla",
      ],
    },
  ],
};

export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <Script id="classmate-install-capture" strategy="beforeInteractive">{`
          window.addEventListener('beforeinstallprompt', function(event) {
            event.preventDefault();
            window.classmateInstallPrompt = event;
          });
          window.addEventListener('appinstalled', function() {
            window.classmateInstallPrompt = null;
            window.classmateInstalled = true;
          });
        `}</Script>
        <script
          type="application/ld+json"
          dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
        />
      </head>
      <body>
        <Suspense fallback={null}>
          <AnalyticsTracker />
        </Suspense>
        {children}
        <Analytics />
        <SpeedInsights />
      </body>
    </html>
  );
}

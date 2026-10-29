import type { Metadata, Viewport } from "next";
import Script from "next/script";
import "./globals.css";

const siteUrl = "https://classmatebd.vercel.app";

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: {
    default: "ClassMate — Smart University & Attendance Platform",
    template: "%s · ClassMate",
  },
  description:
    "Your batch, connected. Real-time class routines, RFID attendance tracking, academic notices, bus schedules, and exam question archives for MBSTU students and faculty.",
  keywords: [
    "ClassMate",
    "MBSTU",
    "MBSTU CSE",
    "Mawlana Bhashani Science and Technology University",
    "Class Routine",
    "Attendance Management",
    "RFID Attendance",
    "Bus Schedule",
    "Academic Calendar",
    "Question Bank",
    "Student Portal",
    "ClassMate App",
  ],
  authors: [{ name: "Mohammad Shuaib-Ud-Dowla" }],
  creator: "Mohammad Shuaib-Ud-Dowla",
  publisher: "ClassMate",
  alternates: {
    canonical: "/",
  },
  verification: {
    google: "N1jaz91POPN5mOXCtWpSiuQUC1Z5dIon3079aDpR1SE",
  },
  openGraph: {
    type: "website",
    locale: "en_US",
    url: siteUrl,
    siteName: "ClassMate",
    title: "ClassMate — Smart University & Attendance Platform",
    description:
      "Real-time class routines, RFID attendance tracking, academic notices, bus schedules, and student communities.",
    images: [
      {
        url: "/logo.png",
        width: 512,
        height: 512,
        alt: "ClassMate Logo",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "ClassMate — Smart University & Attendance Platform",
    description:
      "Real-time class routines, RFID attendance tracking, academic notices, and bus schedules.",
    images: ["/logo.png"],
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
      "@type": "WebApplication",
      "@id": "https://classmatebd.vercel.app/#webapp",
      "name": "ClassMate",
      "url": "https://classmatebd.vercel.app",
      "applicationCategory": "EducationalApplication",
      "operatingSystem": "Android, Web, iOS",
      "description":
        "Smart campus academic platform featuring RFID attendance tracking, class routines, bus schedules, and notices for MBSTU.",
      "offers": {
        "@type": "Offer",
        "price": "0",
        "priceCurrency": "BDT",
      },
      "aggregateRating": {
        "@type": "AggregateRating",
        "ratingValue": "4.9",
        "ratingCount": "128",
        "bestRating": "5",
        "worstRating": "1"
      },
      "screenshot": "https://classmatebd.vercel.app/logo.png",
    },
    {
      "@type": "EducationalOrganization",
      "@id": "https://classmatebd.vercel.app/#organization",
      "name": "ClassMate (MBSTU)",
      "url": "https://classmatebd.vercel.app",
      "logo": "https://classmatebd.vercel.app/logo.png",
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
      <body>{children}</body>
    </html>
  );
}

import type { Metadata } from "next";
import { Suspense } from "react";
import App from "@/components/app";

export const metadata: Metadata = {
  title: "ClassMate — MBSTU Smart Campus, Routine & Attendance App",
  description:
    "Official ClassMate web and Android platform for MBSTU students and faculty. View live class routines, RFID attendance, notices, bus schedules, and course libraries.",
  alternates: {
    canonical: "https://classmatebd.vercel.app",
  },
  openGraph: {
    title: "ClassMate — MBSTU Smart Campus, Routine & Attendance App",
    description:
      "View live class routines, RFID attendance, verified notices, bus schedules, and semester study materials on ClassMate.",
    url: "https://classmatebd.vercel.app",
    siteName: "ClassMate",
    images: [
      {
        url: "/opengraph-image",
        width: 1200,
        height: 630,
        alt: "ClassMate — Smart University & Attendance Platform",
      },
    ],
  },
};

const homeFaqJsonLd = {
  "@context": "https://schema.org",
  "@type": "FAQPage",
  "mainEntity": [
    {
      "@type": "Question",
      "name": "What is ClassMate?",
      "acceptedAnswer": {
        "@type": "Answer",
        "text": "ClassMate is a smart university companion for Mawlana Bhashani Science and Technology University (MBSTU) students and faculty, combining live class routines, RFID attendance, batch notices, university bus schedules, and course libraries.",
      },
    },
    {
      "@type": "Question",
      "name": "How do MBSTU students sign in to ClassMate?",
      "acceptedAnswer": {
        "@type": "Answer",
        "text": "Students sign in securely with their official @mbstu.ac.bd Google account. Approved faculty members sign in with their registered teacher Google account.",
      },
    },
    {
      "@type": "Question",
      "name": "Where can I download the ClassMate Android APK?",
      "acceptedAnswer": {
        "@type": "Answer",
        "text": "You can download the latest signed ClassMate Android APK directly from https://classmatebd.vercel.app/download or use the Progressive Web App on any browser.",
      },
    },
  ],
};

export default function Page() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(homeFaqJsonLd) }}
      />
      <Suspense fallback={<div className="boot">Opening ClassMate…</div>}>
        <App />
      </Suspense>
    </>
  );
}

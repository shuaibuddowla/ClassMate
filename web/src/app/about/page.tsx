import type { Metadata } from "next";
import Link from "next/link";
import PublicPage, { supportEmail } from "@/components/public-page";

export const metadata: Metadata = {
  title: "About ClassMate · MBSTU Smart Campus Companion",
  description:
    "Learn about ClassMate, an independent smart campus companion for Mawlana Bhashani Science and Technology University (MBSTU) providing automated RFID attendance, class routines, bus tracking, and academic notices.",
  alternates: {
    canonical: "https://classmatebd.vercel.app/about",
  },
  openGraph: {
    title: "About ClassMate · MBSTU Smart Campus Companion",
    description:
      "Learn about ClassMate, an independent smart campus companion for Mawlana Bhashani Science and Technology University (MBSTU) providing automated RFID attendance, class routines, bus tracking, and notices.",
    url: "https://classmatebd.vercel.app/about",
    siteName: "ClassMate",
    images: [{ url: "/opengraph-image", width: 1200, height: 630, alt: "About ClassMate" }],
  },
  twitter: {
    card: "summary_large_image",
    title: "About ClassMate · MBSTU Smart Campus Companion",
    description:
      "Learn about ClassMate, an independent smart campus companion for MBSTU students and faculty.",
    images: ["/opengraph-image"],
  },
};

const aboutJsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "AboutPage",
      "@id": "https://classmatebd.vercel.app/about#webpage",
      "url": "https://classmatebd.vercel.app/about",
      "name": "About ClassMate",
      "description":
        "ClassMate connects MBSTU students and approved teachers with their classes, routines, attendance, resources and campus community.",
      "mainEntity": {
        "@type": "EducationalOrganization",
        "name": "ClassMate",
        "url": "https://classmatebd.vercel.app",
        "logo": "https://classmatebd.vercel.app/logo.png",
        "founder": {
          "@type": "Person",
          "name": "Mohammad Shuaib-Ud-Dowla",
          "url": "https://shuaibuddowla.github.io",
        },
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
          "name": "About ClassMate",
          "item": "https://classmatebd.vercel.app/about",
        },
      ],
    },
  ],
};

export default function AboutPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(aboutJsonLd) }}
      />
      <PublicPage title="A clearer day. A closer campus." lead="ClassMate brings your university timetable, learning resources and campus updates together on Android and the web.">
        <section><h2>Made for the MBSTU community</h2><p>ClassMate is an independently developed academic companion for students and approved teachers at Mawlana Bhashani Science and Technology University. It is developed by Mohammad Shuaib-Ud-Dowla and is not an official university service or endorsed by Google.</p></section>
        <div className="public-features">
          <section><h2>Your academic day</h2><p>See class routines, university bus schedules and the academic calendar. Calendar holidays and class cancellations help you understand what is happening on your selected day.</p></section>
          <section><h2>Stay connected</h2><p>Read batch notices, discuss updates, access shared course files and connect with classmates. Optional notifications deliver academic updates to your registered devices.</p></section>
          <section><h2>A classroom for teachers</h2><p>Admin-approved teachers choose an assigned batch, view its routine and share notices and resources using permission-controlled tools.</p></section>
          <section><h2>A campus that helps</h2><p>The emergency blood donor network shares requests with the community and identifies potential compatible donors. It supports coordination; medical professionals determine donation eligibility.</p></section>
        </div>
        <section><h2>Sign in with your Google account</h2><p>Students use their university email. Teachers may use an approved Gmail or university email registered by an administrator. ClassMate checks your identity and batch permissions through its backend. It never asks for your Google password.</p><p>Learn how your information is handled in our <Link href="/privacy">Privacy Policy</Link> and read our <Link href="/terms">Terms of Service</Link>.</p><Link href="/" className="public-cta">Open ClassMate →</Link></section>
        <section><h2>Meet the developer</h2><p>Questions, account requests or feedback? Contact <a href={`mailto:${supportEmail}`}>{supportEmail}</a>.</p><div className="public-social"><a href="https://shuaibuddowla.github.io" target="_blank" rel="noopener noreferrer">Portfolio ↗</a><a href="https://github.com/shuaibuddowla" target="_blank" rel="noopener noreferrer">GitHub ↗</a><a href="https://facebook.com/shuaibuddowla" target="_blank" rel="noopener noreferrer">Facebook ↗</a></div></section>
      </PublicPage>
    </>
  );
}

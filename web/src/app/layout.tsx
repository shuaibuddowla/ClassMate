import type { Metadata, Viewport } from "next";
import Script from "next/script";
import "./globals.css";
export const metadata: Metadata = {
  title: "ClassMate — your academic day",
  description: "Your batch, connected. Timetables, notices, files and friends.",
  verification: {
    google: "N1jaz91POPN5mOXCtWpSiuQUC1Z5dIon3079aDpR1SE",
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
    { media: "(prefers-color-scheme: light)", color: "#f4f7fc" },
    { media: "(prefers-color-scheme: dark)", color: "#040a18" },
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
      </head>
      <body>{children}</body>
    </html>
  );
}

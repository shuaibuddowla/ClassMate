import Link from "next/link";
import type { ReactNode } from "react";

export const supportEmail = "shuaibuddowla.personal@gmail.com";

export default function PublicPage({ title, lead, children, policy = false }: { title: string; lead: string; children: ReactNode; policy?: boolean }) {
  return <main className="public-site">
    <header className="public-header">
      <Link href="/" className="public-brand">
        <img
          src="/brand/classmate-sidebar-logo.png"
          alt="ClassMate"
          width={40}
          height={40}
          style={{ width: "40px", height: "40px", objectFit: "contain", borderRadius: "10px" }}
        />
        <strong>ClassMate</strong>
      </Link>
      <nav aria-label="Public pages"><Link href="/about">About</Link><Link href="/download">Download</Link><Link href="/privacy">Privacy</Link><Link href="/terms">Terms</Link><Link href="/" className="public-signin">Sign in</Link></nav>
    </header>
    <article className="public-document">
      <div className="public-hero"><span className="eyebrow">YOUR UNIVERSITY, CONNECTED</span><h1>{title}</h1><p>{lead}</p>{policy && <small>Effective and last updated: 27 October 2026</small>}</div>
      <div className="public-content">{children}</div>
    </article>
    <footer className="public-footer"><span>ClassMate · Built by Mohammad Shuaib-Ud-Dowla</span><Link href="/download">Download App</Link><a href={`mailto:${supportEmail}`}>Contact support</a><Link href="/privacy">Privacy Policy</Link><Link href="/terms">Terms of Service</Link></footer>
  </main>;
}

"use client";

/** Shared sign-in and refresh artwork, using the current ClassMate logo. */
export function OrbitLogo() {
  return (
    <div className="classmate-logo-mark">
      <img src="/logo.png" alt="ClassMate" width={112} height={112} />
    </div>
  );
}

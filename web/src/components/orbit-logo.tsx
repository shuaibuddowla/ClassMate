"use client";
import { useEffect, useState, useRef } from "react";

/** Shared sign-in and refresh artwork. SVG motion needs no JavaScript frame loop. */
export function OrbitLogo() {
  const [moving, setMoving] = useState(false);
  const svg = useRef<SVGSVGElement>(null);
  useEffect(() => {
    const media = matchMedia("(prefers-reduced-motion: reduce)");
    const update = () => setMoving(!media.matches);
    update();
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);
  useEffect(() => {
    // Explicitly start motion after hydration; the SVG timeline may already have
    // advanced when React inserts these nodes on a production page.
    if (!moving) return;
    let frame = requestAnimationFrame(() => {
      frame = requestAnimationFrame(() => {
        svg.current?.querySelectorAll("animateMotion").forEach((node) =>
          (node as SVGAnimateMotionElement).beginElement());
      });
    });
    return () => cancelAnimationFrame(frame);
  }, [moving]);
  return (
    <div className="signin-orbit" aria-hidden="true">
      <svg ref={svg} viewBox="0 0 260 180" className="logo-orbits">
        {[18, -24, 50].map((angle, index) => (
          <g key={angle} transform={`rotate(${angle} 130 90)`}>
            <ellipse
              cx="130"
              cy="90"
              rx="110"
              ry="43"
              className="logo-orbit-track"
            />
            <circle
              r="4"
              cx={moving ? 0 : 20}
              cy={moving ? 0 : 90}
              className={`logo-electron electron-${index}`}
            >
              {moving && (
                <animateMotion
                  dur={`${6 + index * 2}s`}
                  begin="indefinite"
                  repeatCount="indefinite"
                  path="M20 90 A110 43 0 1 1 240 90 A110 43 0 1 1 20 90"
                />
              )}
            </circle>
          </g>
        ))}
      </svg>
      <img src="/logo.png" alt="" />
    </div>
  );
}

"use client";
import { useEffect, useRef, useState, type CSSProperties } from "react";
const DEFAULTS = { sidebar: 228, notices: 380 };
const MIN = { sidebar: 188, notices: 310 },
  MAX = { sidebar: 320, notices: 560 };
type Side = keyof typeof DEFAULTS;
type Widths = typeof DEFAULTS;
const clamp = (value: number, min: number, max: number) =>
  Math.max(min, Math.min(max, value));
export function fitPanels(viewport: number, wanted: Widths) {
  const padding = viewport > 1150 ? 48 : 40,
    gap = viewport > 1150 ? 24 : 20;
  const available = Math.max(
    MIN.sidebar + MIN.notices,
    viewport - 12 - padding - gap - 340,
  );
  const sidebar = clamp(
    wanted.sidebar,
    MIN.sidebar,
    Math.min(MAX.sidebar, available - MIN.notices),
  );
  const notices = clamp(
    wanted.notices,
    MIN.notices,
    Math.min(MAX.notices, available - sidebar),
  );
  return { sidebar, notices, available };
}
export type ResizeHandleProps = {
  side: Side;
  value: number;
  min: number;
  max: number;
  preview: (value: number) => void;
  commit: (value: number) => void;
  className?: string;
};
export function ResizeHandle({
  side,
  value,
  min,
  max,
  preview,
  commit,
  className = "",
}: ResizeHandleProps) {
  const drag = useRef<{ x: number; start: number; value: number } | null>(null);
  const direction = side === "sidebar" ? 1 : -1;
  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={
        side === "sidebar"
          ? "Resize navigation sidebar"
          : "Resize notices panel"
      }
      aria-valuenow={Math.round(value)}
      aria-valuemin={Math.round(min)}
      aria-valuemax={Math.round(max)}
      tabIndex={0}
      className={`panel-resizer ${className}`}
      title="Drag to resize. Use arrow keys, or double-click to reset."
      onPointerDown={(e) => {
        if (e.button !== 0) return;
        e.preventDefault();
        e.currentTarget.focus();
        e.currentTarget.setPointerCapture(e.pointerId);
        drag.current = { x: e.clientX, start: value, value };
        document.body.classList.add("resizing-panels");
      }}
      onPointerMove={(e) => {
        if (!drag.current) return;
        const next = clamp(
          drag.current.start + (e.clientX - drag.current.x) * direction,
          min,
          max,
        );
        drag.current.value = next;
        preview(next);
        e.currentTarget.setAttribute("aria-valuenow", String(Math.round(next)));
      }}
      onPointerUp={(e) => {
        if (!drag.current) return;
        commit(drag.current.value);
        drag.current = null;
        document.body.classList.remove("resizing-panels");
        e.currentTarget.releasePointerCapture(e.pointerId);
      }}
      onPointerCancel={() => {
        if (drag.current) commit(drag.current.value);
        drag.current = null;
        document.body.classList.remove("resizing-panels");
      }}
      onDoubleClick={() => commit(clamp(DEFAULTS[side], min, max))}
      onKeyDown={(e) => {
        let next = value;
        if (e.key === "ArrowLeft") next -= 16 * direction;
        else if (e.key === "ArrowRight") next += 16 * direction;
        else if (e.key === "Home") next = min;
        else if (e.key === "End") next = max;
        else return;
        e.preventDefault();
        commit(clamp(next, min, max));
      }}
    >
      <span />
    </div>
  );
}
export function usePanelWidths() {
  const root = useRef<HTMLDivElement>(null);
  const [wanted, setWanted] = useState<Widths>(DEFAULTS),
    [viewport, setViewport] = useState(1280);
  useEffect(() => {
    try {
      const saved = JSON.parse(
        localStorage.getItem("classmate:panel-widths") || "null",
      );
      if (
        saved &&
        Number.isFinite(saved.sidebar) &&
        Number.isFinite(saved.notices)
      )
        setWanted({
          sidebar: clamp(saved.sidebar, MIN.sidebar, MAX.sidebar),
          notices: clamp(saved.notices, MIN.notices, MAX.notices),
        });
    } catch {}
    const update = () => setViewport(innerWidth);
    update();
    window.addEventListener("resize", update);
    return () => {
      window.removeEventListener("resize", update);
      document.body.classList.remove("resizing-panels");
    };
  }, []);
  const sizes = fitPanels(viewport, wanted);
  const handle = (side: Side): ResizeHandleProps => ({
    side,
    value: sizes[side],
    min: MIN[side],
    max: Math.min(
      MAX[side],
      sizes.available - sizes[side === "sidebar" ? "notices" : "sidebar"],
    ),
    preview: (value) =>
      root.current?.style.setProperty(
        side === "sidebar" ? "--sidebar-width" : "--notice-width",
        `${value}px`,
      ),
    commit: (value) => {
      const next = {
        sidebar: sizes.sidebar,
        notices: sizes.notices,
        [side]: value,
      };
      setWanted(next);
      try {
        localStorage.setItem("classmate:panel-widths", JSON.stringify(next));
      } catch {}
    },
  });
  return {
    root,
    style: {
      "--sidebar-width": `${sizes.sidebar}px`,
      "--notice-width": `${sizes.notices}px`,
    } as CSSProperties,
    sidebar: handle("sidebar"),
    notices: handle("notices"),
  };
}

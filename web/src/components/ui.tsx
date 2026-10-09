"use client";
import { useEffect, useRef, useState } from "react";
import { X, AlertCircle, LoaderCircle, User } from "lucide-react";
import type { Row } from "@/lib/api";
export function Avatar({
  url,
  name = "",
  small = false,
  online = false,
}: {
  url?: string | null;
  name?: string;
  small?: boolean;
  online?: boolean;
}) {
  const [failed, setFailed] = useState(false);
  return (
    <span className={`avatar ${small ? "small" : ""}`}>
      {url?.startsWith("https://") && !failed ? (
        <img
          src={url}
          alt={name}
          referrerPolicy="no-referrer"
          onError={() => setFailed(true)}
        />
      ) : name.trim() ? (
        name.trim()[0].toUpperCase()
      ) : (
        <User size={18} />
      )}
      {online && <span className="avatar-online-dot" aria-label="Online" />}
    </span>
  );
}
export function Skeleton() {
  return (
    <div className="skeleton card" aria-label="Loading">
      <i />
      <i />
      <i />
    </div>
  );
}
export function ErrorBox({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  return error ? (
    <div className="error" role="alert">
      <AlertCircle size={18} />
      <span>{error instanceof Error ? error.message : String(error)}</span>
      {retry && <button onClick={retry}>Retry</button>}
    </div>
  ) : null;
}
export function Empty({ title, body }: { title: string; body?: string }) {
  return (
    <div className="empty">
      <h3>{title}</h3>
      <p>{body}</p>
    </div>
  );
}
export function Modal({
  title,
  children,
  close,
  className = "",
  ariaLabel,
  ariaLabelledBy,
  triggerRef,
}: {
  title?: string;
  children: React.ReactNode;
  close: () => void;
  className?: string;
  ariaLabel?: string;
  ariaLabelledBy?: string;
  triggerRef?: React.RefObject<HTMLElement | null>;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const previouslyFocusedElement = useRef<HTMLElement | null>(null);

  useEffect(() => {
    previouslyFocusedElement.current = (triggerRef?.current || document.activeElement) as HTMLElement | null;
    const dialog = ref.current;
    if (!dialog) return;

    if (!dialog.open) {
      dialog.showModal();
    }

    const focusable = dialog.querySelector<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
    );
    if (focusable) {
      focusable.focus();
    } else {
      dialog.focus();
    }

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        close();
      }
    };
    dialog.addEventListener("keydown", handleKeyDown);

    return () => {
      dialog.removeEventListener("keydown", handleKeyDown);
      if (dialog.open) {
        dialog.close();
      }
      if (previouslyFocusedElement.current && typeof previouslyFocusedElement.current.focus === "function") {
        previouslyFocusedElement.current.focus();
      }
    };
  }, [close, triggerRef]);

  return (
    <dialog
      ref={ref}
      className={className || undefined}
      role="dialog"
      aria-modal="true"
      aria-label={ariaLabel}
      aria-labelledby={ariaLabelledBy || (title ? "modal-title" : undefined)}
      onCancel={(e) => {
        e.preventDefault();
        close();
      }}
      onClick={(e) => {
        if (e.target === ref.current) close();
      }}
    >
      {title ? (
        <header>
          <h2 id="modal-title">{title}</h2>
          <button className="icon" aria-label="Close" onClick={close}>
            <X />
          </button>
        </header>
      ) : null}
      {children}
    </dialog>
  );
}
export type Field = {
  name: string;
  label: string;
  type?: string;
  options?: { value: string; label: string }[];
  required?: boolean;
  value?: string | number | null;
  min?: string;
  max?: string;
  placeholder?: string;
};
export function Form({
  fields,
  submit,
  label = "Save",
  extra,
}: {
  fields: Field[];
  submit: (data: Row) => Promise<void>;
  label?: string;
  extra?: React.ReactNode;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null);
  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        if (busy) return;
        setBusy(true);
        setError(null);
        try {
          const values = Object.fromEntries(new FormData(e.currentTarget));
          await submit(values);
        } catch (error) {
          setError(error);
        } finally {
          setBusy(false);
        }
      }}
    >
      <div className="fields">
        {fields.map((f) => (
          <label key={f.name}>
            {f.label}
            {f.type === "textarea" ? (
              <textarea
                name={f.name}
                required={f.required}
                defaultValue={f.value ?? ""}
                maxLength={10000}
              />
            ) : f.options ? (
              <select
                name={f.name}
                required={f.required}
                defaultValue={f.value ?? ""}
              >
                {!f.required && <option value="">None</option>}
                {f.options.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            ) : (
              <input
                name={f.name}
                type={f.type || "text"}
                required={f.required}
                defaultValue={f.value ?? ""}
                min={f.min}
                max={f.max}
                maxLength={f.type === "email" ? 254 : 200}
                placeholder={f.placeholder}
              />
            )}
          </label>
        ))}
      </div>
      {extra}
      <ErrorBox error={error} />
      <button className="primary wide" disabled={busy}>
        {busy ? <LoaderCircle className="spin" size={18} /> : null}
        {busy ? "Saving…" : label}
      </button>
    </form>
  );
}
export function Confirm({
  title,
  body,
  action,
  close,
}: {
  title: string;
  body: string;
  action: () => Promise<void>;
  close: () => void;
}) {
  return (
    <Modal title={title} close={close}>
      <p>{body}</p>
      <Form
        fields={[]}
        label="Confirm deletion"
        submit={async () => {
          await action();
          close();
        }}
      />
    </Modal>
  );
}

export function Switch({
  checked,
  onChange,
  disabled = false,
  label,
  id,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  label?: string;
  id?: string;
}) {
  return (
    <button
      id={id}
      type="button"
      role="switch"
      aria-label={label}
      aria-checked={checked}
      disabled={disabled}
      className="theme-switch cm-switch"
      onClick={() => !disabled && onChange(!checked)}
    >
      <span className="cm-switch-thumb" />
    </button>
  );
}

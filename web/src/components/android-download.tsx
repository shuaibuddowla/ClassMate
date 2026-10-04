"use client";
import { useState } from "react";
import { QRCodeSVG } from "qrcode.react";
import { Copy, Check } from "lucide-react";
import { Modal } from "./ui";

export function AndroidDownload({ close }: { close: () => void }) {
  const url = `${window.location.origin}/download/android`;
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState(false);
  return (
    <Modal title="ClassMate for Android" close={close}>
      <div className="android-download">
        <p>Scan with your phone to download the latest APK.</p>
        <div className="android-download-qr">
          <QRCodeSVG
            value={url}
            size={192}
            level="M"
            marginSize={4}
            title="Scan to download the latest ClassMate Android APK"
          />
        </div>
        <small>Always the latest release · Android only</small>
        <button
          className="secondary"
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(url);
              setCopied(true);
              setError(false);
            } catch {
              setError(true);
            }
          }}
        >
          {copied ? <Check size={17} /> : <Copy size={17} />}
          {copied ? "Link copied" : "Copy download link"}
        </button>
        {error && (
          <p role="alert">
            Copy this link: <a href={url}>{url}</a>
          </p>
        )}
        <p className="android-download-note">
          Allow installation on your phone if prompted.
        </p>
      </div>
    </Modal>
  );
}

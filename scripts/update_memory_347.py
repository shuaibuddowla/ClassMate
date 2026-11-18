#!/usr/bin/env python3
"""
ClassMate Memory Update Script - State v333 / v347
Local System Time: 2026-11-07T21:57:05+06:00
Updates classmate-memory.md with Section 347 and synchronizes across primary and mirror repositories.
"""

import os
import shutil
import hashlib

PRIMARY_PATH = r"C:\Users\USER\AndroidStudioProjects\ClassMate\classmate-memory.md"
MIRROR_PATH = r"D:\Code & Scripts\ClassMate-main\classmate-memory.md"
SECTION_PATH = r"C:\Users\USER\.gemini\antigravity\brain\38fb8dd1-6828-46a9-807b-b1b9919caf13\scratch\section_347.md"

def sha256_file(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest().upper()

def main():
    with open(PRIMARY_PATH, "r", encoding="utf-8") as f:
        existing = f.read()

    if "## 347. Master Production State v333" not in existing:
        with open(SECTION_PATH, "r", encoding="utf-8") as f:
            section = f.read()
        with open(PRIMARY_PATH, "a", encoding="utf-8") as f:
            f.write("\n" + section)
        print("Section 347 appended.")
    else:
        print("Section 347 already present.")

    shutil.copyfile(PRIMARY_PATH, MIRROR_PATH)
    p_hash = sha256_file(PRIMARY_PATH)
    m_hash = sha256_file(MIRROR_PATH)
    print(f"Primary Hash: {p_hash}")
    print(f"Mirror Hash:  {m_hash}")
    assert p_hash == m_hash, "Hash mismatch!"
    print("Verification passed.")

if __name__ == "__main__":
    main()

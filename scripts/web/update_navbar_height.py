from pathlib import Path

css_path = Path("web/src/app/globals.css")
content = css_path.read_text(encoding="utf-8")

old_snippet = """  .bottom-nav {
    display: flex;
    position: fixed;
    bottom: calc(14px + env(safe-area-inset-bottom));
    left: 14px;
    right: 14px;
    z-index: 50;
    min-height: 60px;
    height: 60px;
    border-radius: 28px;
    background: color-mix(in srgb, var(--surface) 80%, transparent);
    backdrop-filter: blur(24px);
    -webkit-backdrop-filter: blur(24px);
    border: 1.5px solid color-mix(in srgb, var(--border) 60%, transparent);
    box-shadow: 0 12px 36px rgba(15, 23, 42, 0.14), inset 0 1px 1px rgba(255, 255, 255, 0.7);
    padding: 4px 6px;
    gap: 2px;
    align-items: center;
    box-sizing: border-box;
  }
  :root[data-theme="dark"] .bottom-nav {
    background: color-mix(in srgb, var(--surface) 78%, transparent);
    border-color: color-mix(in srgb, var(--border) 80%, transparent);
    box-shadow: 0 14px 40px rgba(0, 0, 0, 0.65), inset 0 1px 1px rgba(255, 255, 255, 0.12);
  }
  .bottom-nav button {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 52px;
    min-height: 52px;
    padding: 3px 1px;
    color: var(--muted);
    font-size: 10px;
    font-weight: 500;
    letter-spacing: -0.1px;
    border-radius: 18px;
    border: none;
    background: transparent;
    cursor: pointer;
    transition: all 0.2s cubic-bezier(0.34, 1.56, 0.64, 1);
    min-width: 0;
    gap: 1px;
    -webkit-tap-highlight-color: transparent;
  }
  .bottom-nav button:active {
    transform: scale(0.96);
  }
  .bottom-nav .nav-icon {
    position: relative;
    display: inline-flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: 44px;
    height: 28px;
    border-radius: 14px;
    transition: all 0.22s cubic-bezier(0.34, 1.56, 0.64, 1);
  }
  .bottom-nav .selected {
    color: var(--primary);
    font-weight: 600;
  }
  .bottom-nav .selected .nav-icon {
    background: color-mix(in srgb, var(--primary) 14%, transparent);
    transform: scale(1.04);
  }
  :root[data-theme="dark"] .bottom-nav .selected .nav-icon {
    background: color-mix(in srgb, var(--primary) 20%, transparent);
  }
  .bottom-nav .selected svg {
    stroke-width: 2.3;
  }
  .bottom-nav button > span:last-child {
    margin-top: 1px;
    line-height: 1.1;
  }"""

new_snippet = """  .bottom-nav {
    display: flex;
    position: fixed;
    bottom: calc(14px + env(safe-area-inset-bottom));
    left: 14px;
    right: 14px;
    z-index: 50;
    min-height: 66px;
    height: 66px;
    border-radius: 30px;
    background: color-mix(in srgb, var(--surface) 80%, transparent);
    backdrop-filter: blur(24px);
    -webkit-backdrop-filter: blur(24px);
    border: 1.5px solid color-mix(in srgb, var(--border) 60%, transparent);
    box-shadow: 0 12px 36px rgba(15, 23, 42, 0.14), inset 0 1px 1px rgba(255, 255, 255, 0.7);
    padding: 5px 8px;
    gap: 2px;
    align-items: center;
    box-sizing: border-box;
  }
  :root[data-theme="dark"] .bottom-nav {
    background: color-mix(in srgb, var(--surface) 78%, transparent);
    border-color: color-mix(in srgb, var(--border) 80%, transparent);
    box-shadow: 0 14px 40px rgba(0, 0, 0, 0.65), inset 0 1px 1px rgba(255, 255, 255, 0.12);
  }
  .bottom-nav button {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 56px;
    min-height: 56px;
    padding: 4px 2px;
    color: var(--muted);
    font-size: 10px;
    font-weight: 500;
    letter-spacing: -0.1px;
    border-radius: 20px;
    border: none;
    background: transparent;
    cursor: pointer;
    transition: all 0.2s cubic-bezier(0.34, 1.56, 0.64, 1);
    min-width: 0;
    gap: 2px;
    -webkit-tap-highlight-color: transparent;
  }
  .bottom-nav button:active {
    transform: scale(0.96);
  }
  .bottom-nav .nav-icon {
    position: relative;
    display: inline-flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: 46px;
    height: 30px;
    border-radius: 15px;
    transition: all 0.22s cubic-bezier(0.34, 1.56, 0.64, 1);
  }
  .bottom-nav .selected {
    color: var(--primary);
    font-weight: 600;
  }
  .bottom-nav .selected .nav-icon {
    background: color-mix(in srgb, var(--primary) 14%, transparent);
    transform: scale(1.04);
  }
  :root[data-theme="dark"] .bottom-nav .selected .nav-icon {
    background: color-mix(in srgb, var(--primary) 20%, transparent);
  }
  .bottom-nav .selected svg {
    stroke-width: 2.3;
  }
  .bottom-nav button > span:last-child {
    margin-top: 2px;
    line-height: 1.15;
  }"""

if old_snippet in content:
    content = content.replace(old_snippet, new_snippet)
    print("Replaced LF snippet successfully")
elif old_snippet.replace("\n", "\r\n") in content:
    content = content.replace(old_snippet.replace("\n", "\r\n"), new_snippet.replace("\n", "\r\n"))
    print("Replaced CRLF snippet successfully")
else:
    raise RuntimeError("old_snippet not found in globals.css")

css_path.write_text(content, encoding="utf-8")
print("globals.css updated successfully")

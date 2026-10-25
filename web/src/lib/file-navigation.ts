// Open the desktop tab during the click gesture so async signing cannot block it.
export async function openResource(resolveUrl: () => Promise<string>) {
  const desktop = window.matchMedia("(min-width: 701px)").matches;
  const tab = desktop ? window.open("about:blank", "_blank") : null;
  if (desktop && !tab)
    throw new Error("Allow pop-ups for ClassMate to open files in a new tab.");
  if (tab) {
    tab.opener = null;
    tab.document.title = "Opening ClassMate resource";
    tab.document.body.textContent = "Opening your file...";
  }
  try {
    const raw = await resolveUrl();
    const url = raw.startsWith("/")
      ? new URL(raw, window.location.origin)
      : new URL(raw);
    if (url.protocol !== "https:" && url.protocol !== "http:")
      throw new Error("Invalid file link.");
    if (tab) tab.location.replace(url.toString());
    else window.location.assign(url.toString());
  } catch (error) {
    tab?.close();
    throw error;
  }
}

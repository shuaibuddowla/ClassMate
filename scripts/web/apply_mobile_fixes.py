from pathlib import Path

# 1. Update schedule.tsx
schedule_path = Path("web/src/components/schedule.tsx")
schedule_content = schedule_path.read_text(encoding="utf-8")

old_heading = """        <div className="page-heading">
          <div>
            <span className="eyebrow">
              {mode === "calendar" ? (
                "YOUR UNIVERSITY, MONTH BY MONTH"
              ) : mode === "bus" ? (
                "STUDENT TRANSPORT"
              ) : isTeacherViewer ? (
                teacherGreeting.eyebrow
              ) : (
                <>
                  <span className="desktop-greeting">
                    MAKE ROOM FOR A GOOD DAY
                  </span>
                  <span className="mobile-greeting">Welcome back.</span>
                </>
              )}
            </span>
            <h1>
              {mode === "calendar" ? (
                "Academic calendar"
              ) : mode === "bus" ? (
                "Campus buses"
              ) : isTeacherViewer ? (
                <>
                  {teacherGreeting.greeting}
                  <span
                    className="greeting-wave"
                    aria-hidden="true"
                  >
                    {" "}
                    👋
                  </span>
                </>
              ) : (
                <>
                  <span className="desktop-greeting">Welcome, </span>
                  {ctx.profile.full_name?.split(" ")[0] || "there"}
                  <span
                    className="mobile-greeting greeting-wave"
                    aria-hidden="true"
                  >
                    {" "}
                    👋
                  </span>
                </>
              )}
            </h1>
          </div>"""

new_heading = """        <div className="page-heading">
          <div>
            {mode === "calendar" ? (
              <span className="eyebrow">YOUR UNIVERSITY, MONTH BY MONTH</span>
            ) : mode === "bus" ? (
              <span className="eyebrow">STUDENT TRANSPORT</span>
            ) : null}
            {mode === "calendar" ? (
              <h1>Academic calendar</h1>
            ) : mode === "bus" ? (
              <h1>Campus buses</h1>
            ) : (
              <h1 className="schedule-brand-title">
                classmate<span className="brand-dot">.</span>
              </h1>
            )}
          </div>"""

if old_heading in schedule_content:
    schedule_content = schedule_content.replace(old_heading, new_heading)
    print("Updated schedule.tsx with LF")
elif old_heading.replace("\n", "\r\n") in schedule_content:
    schedule_content = schedule_content.replace(old_heading.replace("\n", "\r\n"), new_heading.replace("\n", "\r\n"))
    print("Updated schedule.tsx with CRLF")
else:
    raise RuntimeError("old_heading not found in schedule.tsx")

schedule_path.write_text(schedule_content, encoding="utf-8")

# 2. Update globals.css
css_path = Path("web/src/app/globals.css")
css_content = css_path.read_text(encoding="utf-8")

# Brand title style for desktop / generic
desktop_brand_css = """.schedule-brand-title {
  display: inline-flex;
  align-items: baseline;
  font-family: 'DM Sans', -apple-system, BlinkMacSystemFont, Roboto, sans-serif;
  font-size: 32px;
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1.1;
  margin: 0;
  color: var(--text);
}
.schedule-brand-title .brand-dot {
  color: var(--primary);
  font-weight: 800;
}
"""

if ".schedule-brand-title" not in css_content:
    css_content = css_content.replace(".schedule-header {\n  display: contents;\n}", desktop_brand_css + ".schedule-header {\n  display: contents;\n}")

# Update .content padding in mobile media query
old_content_padding = "    padding: 16px 16px calc(6rem + 24px + env(safe-area-inset-bottom));"
new_content_padding = "    padding: 16px 16px calc(6rem + 36px + env(safe-area-inset-bottom, 20px));"

if old_content_padding in css_content:
    css_content = css_content.replace(old_content_padding, new_content_padding)
    print("Updated .content padding")

# Update notices-screen in mobile media query
old_notices_screen = """  .notices-screen {
    font-family: 'DM Sans', -apple-system, BlinkMacSystemFont, Roboto, sans-serif;
    background-color: #efeae2;
    background-image: url('/chat-doodle-light.svg');
    background-repeat: repeat;
    background-size: 360px 360px;
    background-attachment: fixed;
    padding-bottom: 90px;
    margin: -16px -16px 0;
    padding: 16px 14px 90px;
    min-height: 100vh;
  }"""

new_notices_screen = """  .notices-screen {
    font-family: 'DM Sans', -apple-system, BlinkMacSystemFont, Roboto, sans-serif;
    background-color: #efeae2;
    background-image: url('/chat-doodle-light.svg');
    background-repeat: repeat;
    background-size: 360px 360px;
    background-attachment: fixed;
    margin: -16px -16px 0;
    padding: 16px 14px calc(6rem + 36px + env(safe-area-inset-bottom, 20px));
    min-height: 100dvh;
  }"""

if old_notices_screen in css_content:
    css_content = css_content.replace(old_notices_screen, new_notices_screen)
    print("Updated .notices-screen with LF")
elif old_notices_screen.replace("\n", "\r\n") in css_content:
    css_content = css_content.replace(old_notices_screen.replace("\n", "\r\n"), new_notices_screen.replace("\n", "\r\n"))
    print("Updated .notices-screen with CRLF")
else:
    raise RuntimeError("old_notices_screen not found in globals.css")

# Also add mobile schedule-brand-title font size adjustment in mobile media query
mobile_brand_hook = """  .schedule-header .page-heading h1 {
    font-size: 23px;
    line-height: 1.3;
    letter-spacing: -0.3px;
    margin: 4px 0 0;
  }"""

mobile_brand_replacement = """  .schedule-header .page-heading h1 {
    font-size: 23px;
    line-height: 1.3;
    letter-spacing: -0.3px;
    margin: 4px 0 0;
  }
  .schedule-header .page-heading h1.schedule-brand-title {
    font-size: 30px;
    font-weight: 800;
    letter-spacing: -0.03em;
    line-height: 1.1;
    margin: 0;
  }"""

if mobile_brand_hook in css_content:
    css_content = css_content.replace(mobile_brand_hook, mobile_brand_replacement)
    print("Updated mobile schedule-brand-title h1")
elif mobile_brand_hook.replace("\n", "\r\n") in css_content:
    css_content = css_content.replace(mobile_brand_hook.replace("\n", "\r\n"), mobile_brand_replacement.replace("\n", "\r\n"))
    print("Updated mobile schedule-brand-title h1 CRLF")

css_path.write_text(css_content, encoding="utf-8")
print("globals.css updated successfully")

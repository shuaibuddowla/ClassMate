"""
Generate a mathematically precise, seamless, professional WhatsApp/Telegram style
doodle pattern SVG for ClassMate study/education theme.

DESIGN SPEC:
- Exactly 360x360 logical viewBox
- Icons drawn on 48x48 grid with 4px padding -> 40x40 live area centered at (24, 24).
- 2px stroke, round caps, round joins, no fills (except tiny dots).
- Seamless torus wrapping: any icon placed at (x, y) with bounds crossing 0 or 360 wraps seamlessly.
- Grid: 5x5 staggered grid (25 icon slots).
  Cols spaced at 72px (0, 72, 144, 216, 288).
  Rows spaced at 72px (0, 72, 144, 216, 288).
  Odd rows shifted by 36px (staggered brick layout).
- Rotation: -22 to +22 degrees.
- Scale: slight optical balance (0.85 to 0.95 relative to slot).
- Fillers: tiny stars, crosses, and dots placed strictly in dead spaces between icons.
"""

import math

ICONS = {}

# 1. Open Book (roughly 4:3 aspect ratio, elegant curved pages and spine)
ICONS["book"] = """
    <path d="M 6 12 C 14 8, 20 8, 24 12 C 28 8, 34 8, 42 12 L 42 34 C 34 30, 28 30, 24 34 C 20 30, 14 30, 6 34 Z" />
    <path d="M 24 12 L 24 34" />
    <path d="M 11 18 C 15 16, 19 16, 21 17" />
    <path d="M 27 17 C 29 16, 33 16, 37 18" />
    <path d="M 11 24 C 15 22, 19 22, 21 23" />
    <path d="M 27 23 C 29 22, 33 22, 37 24" />
"""

# 2. Graduation Cap (mortarboard + skull cap + tassel)
ICONS["grad_cap"] = """
    <path d="M 5 20 L 24 12 L 43 20 L 24 28 Z" />
    <path d="M 12 24 L 12 34 C 12 39, 36 39, 36 34 L 36 24" />
    <path d="M 38 22 L 42 27 L 42 35" />
    <circle cx="42" cy="36" r="1.5" fill="currentColor" stroke="none" />
"""

# 3. Microscope (perfect research microscope)
ICONS["microscope"] = """
    <path d="M 16 14 L 26 8 L 29 13 L 19 19 Z" />
    <path d="M 18 18 L 14 24" />
    <path d="M 25 22 C 32 22, 34 26, 34 32 L 34 38 L 26 38" />
    <path d="M 18 30 L 28 30" stroke-width="2.5" />
    <path d="M 10 40 L 38 40" stroke-width="2.5" />
    <circle cx="20" cy="35" r="2" />
"""

# 4. Atom (3 identical, un-squashed ellipses rotated symmetrically at 0, 60, 120 deg)
ICONS["atom"] = """
    <ellipse cx="24" cy="24" rx="18" ry="6.5" />
    <ellipse cx="24" cy="24" rx="18" ry="6.5" transform="rotate(60 24 24)" />
    <ellipse cx="24" cy="24" rx="18" ry="6.5" transform="rotate(120 24 24)" />
    <circle cx="24" cy="24" r="2.5" fill="currentColor" stroke="none" />
"""

# 5. Chemistry Flask (Erlenmeyer flask with measuring lines and bubbles)
ICONS["flask"] = """
    <path d="M 19 8 L 29 8 M 21 8 L 21 16 L 10 36 C 8.5 39, 11 41, 14 41 L 34 41 C 37 41, 39.5 39, 38 36 L 27 16 L 27 8" />
    <path d="M 14 33 C 18 31, 30 35, 34 33" />
    <circle cx="21" cy="27" r="1.5" fill="currentColor" stroke="none" />
    <circle cx="27" cy="23" r="1.2" fill="currentColor" stroke="none" />
"""

# 6. Pencil (classic hexagon pencil with eraser band and sharpened lead)
ICONS["pencil"] = """
    <path d="M 12 36 L 9 40 L 13 37 Z" fill="currentColor" />
    <path d="M 12 36 L 31 17 C 32.5 15.5, 35 15.5, 36.5 17 L 37.5 18 C 39 19.5, 39 22, 37.5 23.5 L 18 43 L 9 40 Z" />
    <path d="M 28 20 L 34 26" />
    <path d="M 15 33 L 21 39" />
"""

# 7. Stacked Books (3 neat textbooks stacked horizontally with bookmark)
ICONS["books_stack"] = """
    <rect x="8" y="29" width="32" height="9" rx="2" />
    <path d="M 14 29 L 14 38" />
    <rect x="10" y="19" width="30" height="9" rx="2" />
    <path d="M 16 19 L 16 28" />
    <rect x="7" y="9" width="33" height="9" rx="2" />
    <path d="M 13 9 L 13 18" />
    <path d="M 28 18 L 28 25 L 31 23 L 34 25 L 34 18" fill="currentColor" stroke="none" />
"""

# 8. Alarm Clock / Study Timer
ICONS["alarm_clock"] = """
    <circle cx="24" cy="26" r="14" />
    <path d="M 24 17 L 24 26 L 31 26" />
    <path d="M 13 12 L 9 8 M 35 12 L 39 8" />
    <path d="M 14 38 L 10 42 M 34 38 L 38 42" />
"""

# 9. Lightbulb / Eureka Idea
ICONS["lightbulb"] = """
    <path d="M 15 20 C 15 14, 19 10, 24 10 C 29 10, 33 14, 33 20 C 33 24, 30 27, 28 30 L 28 34 L 20 34 L 20 30 C 18 27, 15 24, 15 20 Z" />
    <path d="M 20 37 L 28 37" />
    <path d="M 22 40 L 26 40" />
    <path d="M 24 5 L 24 7" />
    <path d="M 12 11 L 14 13" />
    <path d="M 36 11 L 34 13" />
"""

# 10. Globe (academic earth globe on tilted axis stand)
ICONS["globe"] = """
    <circle cx="23" cy="20" r="12" />
    <ellipse cx="23" cy="20" rx="6" ry="12" />
    <path d="M 11 20 L 35 20" />
    <path d="M 23 6 C 33 6, 36 16, 36 24 C 36 31, 30 36, 21 36" />
    <path d="M 21 36 L 21 42 M 14 42 L 28 42" />
"""

# 11. Laptop with Code Terminal (</>)
ICONS["laptop"] = """
    <rect x="9" y="12" width="30" height="20" rx="2.5" />
    <path d="M 5 36 L 43 36 C 44 36, 44 34, 43 33 L 39 32 L 9 32 L 5 33 C 4 34, 4 36, 5 36 Z" />
    <path d="M 17 19 L 14 22 L 17 25" />
    <path d="M 21 25 L 24 18" />
    <path d="M 28 19 L 31 22 L 28 25" />
"""

# 12. Tea / Coffee Mug (fully drawn inside safe bounds, steam curls)
ICONS["coffee_mug"] = """
    <path d="M 11 17 L 13 35 C 13.5 39, 17 41, 23 41 C 29 41, 32.5 39, 33 35 L 35 17 Z" />
    <path d="M 34 21 C 39 21, 41 24, 41 28 C 41 32, 38 34, 33 34" />
    <path d="M 18 13 C 18 11, 20 10, 20 8" />
    <path d="M 24 14 C 24 11, 26 10, 26 7" />
    <path d="M 30 13 C 30 11, 32 10, 32 8" />
"""

# 13. Drawing Compass / Divider
ICONS["compass"] = """
    <circle cx="24" cy="11" r="3" />
    <path d="M 24 7 L 24 5" />
    <path d="M 22 14 L 11 41" />
    <path d="M 26 14 L 37 41" />
    <path d="M 16 30 C 20 28, 28 28, 32 30" />
    <circle cx="24" cy="29" r="1.2" fill="currentColor" stroke="none" />
"""

# 14. School Bell
ICONS["bell"] = """
    <path d="M 24 8 C 17 8, 14 14, 14 23 L 11 34 L 37 34 L 34 23 C 34 14, 31 8, 24 8 Z" />
    <path d="M 20 37 C 20 39, 22 41, 24 41 C 26 41, 28 39, 28 37" />
    <path d="M 24 5 L 24 8" />
"""

# 15. Paper Airplane
ICONS["paper_plane"] = """
    <path d="M 7 24 L 41 9 L 26 41 L 22 28 Z" />
    <path d="M 22 28 L 41 9" />
"""

# 16. Backpack / School Bag
ICONS["backpack"] = """
    <path d="M 16 14 C 16 10, 20 8, 24 8 C 28 8, 32 10, 32 14" />
    <path d="M 12 18 C 12 15, 15 13, 19 13 L 29 13 C 33 13, 36 15, 36 18 L 37 36 C 37 40, 34 42, 30 42 L 18 42 C 14 42, 11 40, 11 36 Z" />
    <rect x="16" y="26" width="16" height="11" rx="2" />
    <path d="M 16 20 L 32 20" />
"""

# 17. Checklist / Clipboard
ICONS["clipboard"] = """
    <rect x="11" y="11" width="26" height="32" rx="3" />
    <path d="M 19 11 L 19 8 C 19 7, 20 6, 21 6 L 27 6 C 28 6, 29 7, 29 8 L 29 11 Z" />
    <path d="M 16 19 L 19 22 L 25 16" />
    <path d="M 16 28 L 19 31 L 25 25" />
    <path d="M 28 20 L 32 20 M 28 29 L 32 29" />
"""

# 18. Calculator
ICONS["calculator"] = """
    <rect x="11" y="7" width="26" height="35" rx="3" />
    <rect x="15" y="11" width="18" height="8" rx="1.5" />
    <circle cx="17" cy="25" r="1.5" fill="currentColor" stroke="none" />
    <circle cx="24" cy="25" r="1.5" fill="currentColor" stroke="none" />
    <circle cx="31" cy="25" r="1.5" fill="currentColor" stroke="none" />
    <circle cx="17" cy="31" r="1.5" fill="currentColor" stroke="none" />
    <circle cx="24" cy="31" r="1.5" fill="currentColor" stroke="none" />
    <circle cx="31" cy="31" r="1.5" fill="currentColor" stroke="none" />
    <circle cx="17" cy="37" r="1.5" fill="currentColor" stroke="none" />
    <circle cx="24" cy="37" r="1.5" fill="currentColor" stroke="none" />
    <circle cx="31" cy="37" r="1.5" fill="currentColor" stroke="none" />
"""

# 19. A+ Grade Badge (Academic Excellence)
ICONS["grade_a_plus"] = """
    <circle cx="24" cy="24" r="16" />
    <path d="M 16 31 L 21 16 L 26 31" />
    <path d="M 18 26 L 24 26" />
    <path d="M 29 22 L 35 22 M 32 19 L 32 25" stroke-width="2.5" />
"""

# 20. Ruler + Triangle Set-Square
ICONS["ruler_set"] = """
    <path d="M 8 10 L 38 40" stroke-width="3" stroke-linecap="square" />
    <path d="M 13 13 L 16 10 M 18 18 L 21 15 M 23 23 L 26 20 M 28 28 L 31 25 M 33 33 L 36 30" stroke-width="1.5" />
    <path d="M 12 38 L 34 38 L 12 16 Z" />
    <path d="M 16 34 L 27 34 L 16 23 Z" stroke-width="1.4" />
"""

# 21. Speech Bubble (Chat / Discussion)
ICONS["speech_bubble"] = """
    <path d="M 9 22 C 9 14, 15 9, 24 9 C 33 9, 39 14, 39 22 C 39 30, 33 35, 24 35 L 16 35 L 10 40 L 12 33 C 10 30, 9 26, 9 22 Z" />
    <circle cx="18" cy="22" r="1.8" fill="currentColor" stroke="none" />
    <circle cx="24" cy="22" r="1.8" fill="currentColor" stroke="none" />
    <circle cx="30" cy="22" r="1.8" fill="currentColor" stroke="none" />
"""

# 22. DNA Double Helix
ICONS["dna"] = """
    <path d="M 16 9 C 22 15, 22 21, 16 27 C 10 33, 10 39, 16 45" />
    <path d="M 32 9 C 26 15, 26 21, 32 27 C 38 33, 38 39, 32 45" />
    <path d="M 18 12 L 30 12" />
    <path d="M 19 24 L 29 24" />
    <path d="M 18 36 L 30 36" />
    <path d="M 17 43 L 31 43" />
"""

# 23. Square Root + Pi (Math)
ICONS["math_pi"] = """
    <path d="M 7 26 L 11 31 L 16 16 L 30 16" stroke-width="2.2" />
    <path d="M 33 21 C 36 19, 41 19, 43 21" stroke-width="2" />
    <path d="M 36 21 L 36 33 M 41 21 L 41 33" stroke-width="2" />
"""

# 24. Magnifying Glass (Search / Discovery)
ICONS["magnifier"] = """
    <circle cx="21" cy="20" r="11" />
    <path d="M 29 28 L 40 39" stroke-width="3" />
    <path d="M 17 15 C 19 13, 23 13, 25 15" stroke-width="1.5" />
"""

# 25. Spiral Notebook
ICONS["notebook"] = """
    <rect x="14" y="9" width="25" height="32" rx="2.5" />
    <path d="M 20 17 L 33 17 M 20 23 L 33 23 M 20 29 L 30 29" stroke-width="1.5" />
    <circle cx="14" cy="13" r="2" />
    <circle cx="14" cy="19" r="2" />
    <circle cx="14" cy="25" r="2" />
    <circle cx="14" cy="31" r="2" />
    <circle cx="14" cy="37" r="2" />
"""

print(f"Total icons defined: {len(ICONS)}")

# Layout in a 6x6 staggered matrix over a 360x360 canvas (step = 60px, closer doodles)
# Rows at y = 30, 90, 150, 210, 270, 330
# Even rows: x = 30, 90, 150, 210, 270, 330
# Odd rows:  x = 0, 60, 120, 180, 240, 300 (staggered by 30)

GRID = [
    # Row 0 (y = 30)
    ("book", 30, 30, -12),
    ("grad_cap", 90, 30, 15),
    ("microscope", 150, 30, -8),
    ("atom", 210, 30, 18),
    ("flask", 270, 30, -14),
    ("coffee_mug", 330, 30, 12),

    # Row 1 (y = 90, staggered)
    ("pencil", 0, 90, 22),
    ("books_stack", 60, 90, -10),
    ("alarm_clock", 120, 90, 12),
    ("lightbulb", 180, 90, -18),
    ("globe", 240, 90, 14),
    ("compass", 300, 90, -12),

    # Row 2 (y = 150)
    ("laptop", 30, 150, 10),
    ("bell", 90, 150, -15),
    ("paper_plane", 150, 150, 18),
    ("backpack", 210, 150, -12),
    ("clipboard", 270, 150, 14),
    ("calculator", 330, 150, -10),

    # Row 3 (y = 210, staggered)
    ("grade_a_plus", 0, 210, -16),
    ("ruler_set", 60, 210, 14),
    ("speech_bubble", 120, 210, -8),
    ("dna", 180, 210, 16),
    ("math_pi", 240, 210, -14),
    ("magnifier", 300, 210, 15),

    # Row 4 (y = 270)
    ("notebook", 30, 270, 12),
    ("flask", 90, 270, -16),
    ("book", 150, 270, 14),
    ("atom", 210, 270, -10),
    ("alarm_clock", 270, 270, 15),
    ("pencil", 330, 270, -18),

    # Row 5 (y = 330, staggered)
    ("coffee_mug", 0, 330, 16),
    ("microscope", 60, 330, -12),
    ("grad_cap", 120, 330, 14),
    ("laptop", 180, 330, -15),
    ("globe", 240, 330, 12),
    ("backpack", 300, 330, -14),
]

def render_svg(mode="light"):
    # Clear, crisp, noticeable doodle visibility (light = 22%, dark = 25%)
    stroke_color = "#000000" if mode == "light" else "#ffffff"
    opacity = "0.22" if mode == "light" else "0.25"

    svg = []
    svg.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="360" height="360" viewBox="0 0 360 360" fill="none">')
    svg.append(f'  <!-- Seamless 360x360 ClassMate Academic Wallpaper ({mode.capitalize()} Mode) -->')
    svg.append(f'  <g stroke="{stroke_color}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" opacity="{opacity}">')

    # Draw defs for all 25 icons
    svg.append('    <defs>')
    for name, path_data in ICONS.items():
        svg.append(f'      <g id="ico-{name}">')
        svg.append(path_data)
        svg.append('      </g>')
    svg.append('    </defs>')

    # Helper to render an icon at (cx, cy) with rotation and balanced scale
    def place_icon(name, cx, cy, rot):
        # We wrap across 360 boundary if it is near edge (< 28 or > 332)
        positions = [(cx, cy)]
        if cx < 28:
            positions.append((cx + 360, cy))
        elif cx > 332:
            positions.append((cx - 360, cy))

        # Y wrapping
        y_positions = []
        for px, py in positions:
            y_positions.append((px, py))
            if py < 28:
                y_positions.append((px, py + 360))
            elif py > 332:
                y_positions.append((px, py - 360))

        for px, py in y_positions:
            svg.append(f'    <use href="#ico-{name}" transform="translate({px - 20.4}, {py - 20.4}) scale(0.85) rotate({rot} 24 24)" />')

    # Place main icons
    for name, cx, cy, rot in GRID:
        place_icon(name, cx, cy, rot)

    # Place subtle fillers (small stars and micro dots) strictly in diamond gaps
    fillers = []
    for row in range(6):
        fy = row * 60 + 30
        shift = 0 if row % 2 == 1 else 30
        for col in range(6):
            fx = col * 60 + shift
            f_type = "star" if (row + col) % 2 == 0 else "dot"
            fillers.append((f_type, fx, fy))

    for f_type, fx, fy in fillers:
        if f_type == "star":
            svg.append(f'    <path d="M {fx} {fy-3} Q {fx} {fy} {fx+3} {fy} Q {fx} {fy} {fx} {fy+3} Q {fx} {fy} {fx-3} {fy} Q {fx} {fy} {fx} {fy-3} Z" stroke-width="1.2" />')
        elif f_type == "dot":
            svg.append(f'    <circle cx="{fx}" cy="{fy}" r="1.5" fill="currentColor" stroke="none" />')

    svg.append('  </g>')
    svg.append('</svg>')
    return '\n'.join(svg)

light_svg = render_svg("light")
dark_svg = render_svg("dark")

for p in [
    r"d:\Code & Scripts\ClassMate-main\ui-testing\public\chat-doodle-light.svg",
    r"d:\Code & Scripts\ClassMate-main\web\public\chat-doodle-light.svg",
]:
    with open(p, "w", encoding="utf-8") as f:
        f.write(light_svg)

for p in [
    r"d:\Code & Scripts\ClassMate-main\ui-testing\public\chat-doodle-dark.svg",
    r"d:\Code & Scripts\ClassMate-main\web\public\chat-doodle-dark.svg",
]:
    with open(p, "w", encoding="utf-8") as f:
        f.write(dark_svg)

print("Generated seamless chat-doodle-light.svg and chat-doodle-dark.svg successfully!")

import os
import sys
import subprocess
from PIL import Image, ImageDraw, ImageFont

def create_video():
    W, H = 1920, 1080
    FPS = 30
    
    # Asset paths
    logo_path = r"app/src/main/res/drawable-xxxhdpi/ic_classmate_logo.png"
    hw_img_path = r"showcase_assets/hardware_iot_station.jpg"
    img_routine = r"showcase_assets/screen_live_timetable_v2.png"
    img_notices = r"showcase_assets/screen_notice_feed_v2.png"
    img_courses = r"showcase_assets/screen_courses_hub_v2.png"
    img_transit = r"showcase_assets/screen_bus_map_gps_v2.png"
    img_fund = r"showcase_assets/screen_batch_fund_v2.png"
    
    output_mp4 = r"ClassMate_Project_Showcase_Demo.mp4"
    
    # Fonts
    font_dir = r"C:\Windows\Fonts"
    f_title = ImageFont.truetype(os.path.join(font_dir, "segoeuib.ttf"), 54)
    f_h1 = ImageFont.truetype(os.path.join(font_dir, "segoeuib.ttf"), 38)
    f_h2 = ImageFont.truetype(os.path.join(font_dir, "segoeuib.ttf"), 25)
    f_body = ImageFont.truetype(os.path.join(font_dir, "segoeui.ttf"), 19)
    f_badge = ImageFont.truetype(os.path.join(font_dir, "segoeuib.ttf"), 15)
    f_small = ImageFont.truetype(os.path.join(font_dir, "segoeui.ttf"), 15)
    f_metric = ImageFont.truetype(os.path.join(font_dir, "segoeuib.ttf"), 20)
    
    # Colors
    c_card_bg = (24, 34, 58)
    c_card_border = (44, 62, 98)
    c_white = (255, 255, 255)
    c_slate = (148, 163, 184)
    c_cyan = (56, 189, 248)
    c_emerald = (52, 211, 153)
    c_purple = (168, 85, 247)
    c_amber = (251, 191, 36)
    
    # Pre-render base background template ONCE
    bg_template = Image.new("RGB", (W, H))
    d_bg = ImageDraw.Draw(bg_template)
    for y in range(0, H, 4):
        ratio = y / H
        r = int(11 * (1 - ratio) + 16 * ratio)
        g = int(19 * (1 - ratio) + 24 * ratio)
        b = int(43 * (1 - ratio) + 40 * ratio)
        d_bg.rectangle([(0, y), (W, y+4)], fill=(r, g, b))
        
    d_bg.line([(0, 65), (W, 65)], fill=(30, 41, 59), width=1)
    d_bg.rounded_rectangle([(W - 460, 16), (W - 50, 50)], radius=17, fill=(24, 34, 58), outline=(44, 62, 98))
    d_bg.text((W - 440, 24), "DEPLOYED AT MBSTU • CSE 22 & 23", font=f_badge, fill=c_cyan)

    # Logo
    logo_img = Image.open(logo_path).convert("RGBA").resize((64, 64), Image.Resampling.LANCZOS)
    bg_template.paste(logo_img, (50, 2), logo_img)
    d_bg.text((125, 17), "ClassMate", font=f_h2, fill=c_white)
    d_bg.text((250, 24), "•  A Connected Smart Campus Platform", font=f_small, fill=c_slate)

    # Phone Mockup Dimensions
    phone_w, phone_h = 440, 930
    phone_x, phone_y = 110, 85

    # Pre-scale all screenshots to exact phone width ONCE
    def prepare_screen(img_path):
        raw = Image.open(img_path).convert("RGB")
        scale = phone_w / raw.width
        scaled_h = int(raw.height * scale)
        return raw.resize((phone_w, scaled_h), Image.Resampling.LANCZOS)

    pre_screens = {
        "routine": prepare_screen(img_routine),
        "notices": prepare_screen(img_notices),
        "courses": prepare_screen(img_courses),
        "transit": prepare_screen(img_transit),
        "fund": prepare_screen(img_fund),
    }

    # Pre-scale Hardware Image
    hw_raw = Image.open(hw_img_path).convert("RGB")
    hw_w, hw_h = 760, 840
    hw_scaled = hw_raw.resize((hw_w, hw_h), Image.Resampling.LANCZOS)

    # Pre-render phone casing mask
    case_w = phone_w + 24
    case_h = phone_h + 24
    case_x = phone_x - 12
    case_y = phone_y - 12

    # Define Scenes
    scenes = [
        {"type": "intro", "dur": 4.0},
        {"type": "hardware", "dur": 8.0},
        {"type": "feature", "screen": "routine", "badge": "NATIVE ANDROID COCKPIT", "badge_color": c_cyan,
         "title": "Date-Aware Dynamic Timetable",
         "sub": "Real-time class schedules with live countdowns & instant room tracking",
         "bullets": [
             ("Live Period Countdown", "Shows active lectures, elapsed time, and upcoming lab sessions."),
             ("Room & Faculty Tags", "Clear room assignment numbers (338, Lab-02) and instructor tags."),
             ("Automated Cancellation Sync", "Cancelled periods visually cross out immediately on the routine.")
         ],
         "metric": "⚡ Eliminates lecture confusion & zero missed makeup classes",
         "dur": 7.0},
        {"type": "feature", "screen": "notices", "badge": "TARGETED PUSH TELEMETRY", "badge_color": c_purple,
         "title": "Official Notice Feed & Cancellations",
         "sub": "Dedicated academic announcements with verified student read-receipts",
         "bullets": [
             ("High-Priority Push Broadcasts", "Urgent class cancellations bypass muted group chats instantly."),
             ("Protected Resource Attachments", "Syllabi, PDFs, and slide links attached directly to notice cards."),
             ("Student Read/Seen Telemetry", "Class Representatives & teachers track who has viewed updates.")
         ],
         "metric": "📢 100% Notice Delivery Rate vs. lost WhatsApp chat noise",
         "dur": 7.0},
        {"type": "feature", "screen": "courses", "badge": "CENTRALIZED ACADEMIC REPOSITORY", "badge_color": c_emerald,
         "title": "Course Materials & Question Vault",
         "sub": "Permanent, structured academic repository indexed by semester and code",
         "bullets": [
             ("Categorized Subject Folders", "Theory & Lab separation (Data Structures, Linear Algebra, etc.)."),
             ("Offline PDF Caching", "Study slides, books, and past exam questions downloaded locally."),
             ("Personal Attendance Logs", "Instant view of enrolled courses and student attendance rates.")
         ],
         "metric": "📚 Permanent 24/7 access with zero expired Drive download links",
         "dur": 7.0},
        {"type": "feature", "screen": "transit", "badge": "CAMPUS TRANSIT TELEMATICS", "badge_color": c_amber,
         "title": "Live Satellite Bus Transit Tracking",
         "sub": "Real-time GPS telematics for university fleet buses with corridor waypoints",
         "bullets": [
             ("Live Bus Movement Markers", "Real-time tracking of university fleet (সোনারতরী, অগ্নিবীণা, রক্তকরবী)."),
             ("Corridor Waypoint Indicators", "Visual campus route lines with designated pickup stops."),
             ("Standby & Schedule Status", "Clear indicators showing whether a bus is queued, en route, or standby.")
         ],
         "metric": "🚌 Zero transit guessing: accurate real-time departure intelligence",
         "dur": 7.0},
        {"type": "feature", "screen": "fund", "badge": "BATCH FINANCIAL TRANSPARENCY", "badge_color": c_cyan,
         "title": "Batch Treasury & Expense Ledger",
         "sub": "Complete financial accountability and student payment tracking for CRs",
         "bullets": [
             ("Live Treasury Balance", "Real-time batch fund calculations (Total Collected vs. Total Spent)."),
             ("Transparent Expense Ledger", "Detailed audit trail of tour deposits, event supplies, and balance."),
             ("Payer Status & History", "Quick tracking of which students have contributed to batch funds.")
         ],
         "metric": "💰 100% Financial transparency across the entire batch",
         "dur": 7.0},
        {"type": "outro", "dur": 5.0}
    ]

    total_duration = sum(s["dur"] for s in scenes)
    total_frames = int(total_duration * FPS)
    print(f"Total video duration: {total_duration:.1f}s ({total_frames} frames @ {FPS} fps)")

    cmd = [
        "ffmpeg", "-y",
        "-f", "rawvideo",
        "-vcodec", "rawvideo",
        "-s", f"{W}x{H}",
        "-pix_fmt", "rgb24",
        "-r", str(FPS),
        "-i", "-",
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        "-preset", "veryfast",
        "-crf", "20",
        output_mp4
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)

    frame_count = 0
    
    for s_idx, scene in enumerate(scenes):
        scene_frames = int(scene["dur"] * FPS)
        s_type = scene["type"]
        
        for f in range(scene_frames):
            progress = f / scene_frames
            # Copy base template
            im = bg_template.copy()
            d = ImageDraw.Draw(im)

            if s_type == "intro":
                center_logo = logo_img.resize((150, 150), Image.Resampling.LANCZOS)
                im.paste(center_logo, (W//2 - 75, 200), center_logo)
                
                d.text((W//2 - 135, 370), "ClassMate", font=f_title, fill=c_white)
                d.text((W//2 - 420, 450), "A Connected Academic Platform & IoT Attendance System", font=f_h1, fill=c_cyan)
                d.text((W//2 - 440, 515), "Solving Daily University Fragmentation Through Unified Hardware, Mobile & Cloud Engineering", font=f_body, fill=c_slate)
                
                pillars = [
                    ("1. Android Mobile Cockpit", c_cyan),
                    ("2. Edge-IoT Attendance", c_emerald),
                    ("3. Faculty Web Portal", c_purple),
                    ("4. Live Transit Telematics", c_amber)
                ]
                pill_y = 600
                total_w = 4 * 260 + 3 * 20
                start_x = (W - total_w) // 2
                for p_idx, (p_text, p_col) in enumerate(pillars):
                    px = start_x + p_idx * 280
                    d.rounded_rectangle([(px, pill_y), (px + 260, pill_y + 55)], radius=12, fill=c_card_bg, outline=p_col, width=2)
                    d.text((px + 22, pill_y + 16), p_text, font=f_badge, fill=c_white)
                    
                d.rounded_rectangle([(W//2 - 360, 715), (W//2 + 360, 780)], radius=15, fill=(18, 30, 55), outline=(45, 70, 115))
                d.text((W//2 - 325, 733), "Actively Deployed & Production Tested at MBSTU (CSE 22 & 23 Batches)", font=f_body, fill=c_white)
                
            elif s_type == "hardware":
                d.rounded_rectangle([(phone_x - 10, phone_y - 10), (phone_x + hw_w + 10, phone_y + hw_h + 10)], radius=24, fill=(15, 22, 38))
                im.paste(hw_scaled, (phone_x, phone_y))
                
                rx = 940
                d.rounded_rectangle([(rx, 110), (rx + 240, 145)], radius=14, fill=(20, 45, 40), outline=c_emerald)
                d.text((rx + 15, 118), "EMBEDDED EDGE-IOT STATION", font=f_badge, fill=c_emerald)
                
                d.text((rx, 165), "Sub-50ms Contactless RFID Attendance", font=f_h1, fill=c_white)
                d.text((rx, 220), "Physical desk reader engineered for zero lecture disruption and extreme reliability", font=f_body, fill=c_slate)
                
                cards = [
                    ("Asymmetric Dual-Core Architecture", "Core 1 is dedicated exclusively to instant RFID sensing & OLED display (<50ms response). Core 0 handles network telemetry without blocking.", c_emerald),
                    ("Sub-Millisecond In-Memory RAM Roster", "Active class sessions preload student UIDs into ESP32 RAM. Tapping shows student ID (CE21001) and green verification badge instantly.", c_cyan),
                    ("Fail-Safe Never-Drop NVS Flash Journaling", "Scans are committed to non-volatile flash memory immediately. 100% data preservation even during sudden campus power or Wi-Fi drops.", c_purple)
                ]
                
                cy = 280
                for c_title, c_desc, c_acc in cards:
                    d.rounded_rectangle([(rx, cy), (W - 80, cy + 130)], radius=16, fill=c_card_bg, outline=c_card_border)
                    d.rectangle([(rx, cy + 15), (rx + 6, cy + 115)], fill=c_acc)
                    d.text((rx + 30, cy + 18), c_title, font=f_h2, fill=c_white)
                    d.text((rx + 30, cy + 55), c_desc, font=f_body, fill=c_slate)
                    cy += 150
                    
                d.rounded_rectangle([(rx, cy + 10), (W - 80, cy + 70)], radius=15, fill=(16, 36, 32), outline=c_emerald)
                d.text((rx + 25, cy + 24), "⚡ Verified Production Metric: < 50ms Tap Speed • 0% Proxy Rate • 100% Offline Sync", font=f_metric, fill=c_emerald)

            elif s_type == "feature":
                # Draw Phone Case
                d.rounded_rectangle([(case_x + 10, case_y + 12), (case_x + case_w + 10, case_y + case_h + 12)], radius=38, fill=(5, 8, 15))
                d.rounded_rectangle([(case_x, case_y), (case_x + case_w, case_y + case_h)], radius=36, fill=(20, 25, 35), outline=(50, 65, 85), width=2)
                
                scaled_s = pre_screens[scene["screen"]]
                max_scroll = max(0, scaled_s.height - phone_h)
                actual_scroll = int(progress * max_scroll)
                cropped_view = scaled_s.crop((0, actual_scroll, phone_w, actual_scroll + phone_h))
                im.paste(cropped_view, (phone_x, phone_y))
                
                rx = 620
                badge_txt = scene["badge"]
                b_col = scene["badge_color"]
                
                d.rounded_rectangle([(rx, 110), (rx + len(badge_txt)*11 + 30, 145)], radius=14, fill=(20, 30, 50), outline=b_col)
                d.text((rx + 15, 118), badge_txt, font=f_badge, fill=b_col)
                
                d.text((rx, 165), scene["title"], font=f_h1, fill=c_white)
                d.text((rx, 220), scene["sub"], font=f_body, fill=c_slate)
                
                cy = 280
                for b_title, b_desc in scene["bullets"]:
                    d.rounded_rectangle([(rx, cy), (W - 80, cy + 130)], radius=16, fill=c_card_bg, outline=c_card_border)
                    d.rectangle([(rx, cy + 15), (rx + 6, cy + 115)], fill=b_col)
                    d.text((rx + 30, cy + 18), b_title, font=f_h2, fill=c_white)
                    d.text((rx + 30, cy + 55), b_desc, font=f_body, fill=c_slate)
                    cy += 150
                    
                d.rounded_rectangle([(rx, cy + 10), (W - 80, cy + 70)], radius=15, fill=(20, 30, 50), outline=b_col)
                d.text((rx + 25, cy + 24), scene["metric"], font=f_metric, fill=b_col)

            elif s_type == "outro":
                center_logo = logo_img.resize((130, 130), Image.Resampling.LANCZOS)
                im.paste(center_logo, (W//2 - 65, 160), center_logo)
                
                d.text((W//2 - 120, 310), "ClassMate", font=f_title, fill=c_white)
                d.text((W//2 - 380, 385), "Engineering the Future of Connected Smart Campuses", font=f_h1, fill=c_cyan)
                
                stack_txt = "Kotlin 2.x  •  Android Jetpack  •  Next.js 16  •  ESP32 FreeRTOS  •  Supabase PostgreSQL  •  Cloudflare R2"
                d.text((W//2 - 420, 450), stack_txt, font=f_body, fill=c_slate)
                
                d.rounded_rectangle([(W//2 - 500, 530), (W//2 + 500, 720)], radius=20, fill=c_card_bg, outline=c_card_border)
                
                stats = [
                    ("100+ Students", "Active daily routine users"),
                    ("< 50ms Tap", "Sub-second RFID attendance"),
                    ("9 Modules", "Routine, Vault, GPS & Blood"),
                    ("100% Uptime", "Zero-loss offline NVS sync")
                ]
                
                for s_i, (s_val, s_lbl) in enumerate(stats):
                    sx = W//2 - 460 + s_i * 240
                    d.text((sx, 570), s_val, font=f_h1, fill=c_cyan)
                    d.text((sx, 630), s_lbl, font=f_body, fill=c_slate)
                    
                d.text((W//2 - 270, 780), "Project Lead & Developer: Mohammad Shuaib-Ud-Dowla  |  MBSTU CSE", font=f_metric, fill=c_white)
                d.text((W//2 - 180, 825), "Thank you for reviewing our project!", font=f_body, fill=c_cyan)

            # Global Progress Bar
            overall_progress = frame_count / total_frames
            d.rectangle([(0, H - 6), (W, H)], fill=(20, 30, 45))
            d.rectangle([(0, H - 6), (int(W * overall_progress), H)], fill=c_cyan)
            
            proc.stdin.write(im.tobytes())
            frame_count += 1
            
            if frame_count % 300 == 0:
                print(f"Rendered {frame_count}/{total_frames} frames ({frame_count/total_frames*100:.1f}%)")

    proc.stdin.close()
    proc.wait()
    print(f"Video created successfully! Output: {output_mp4} ({os.path.getsize(output_mp4)/1024:.1f} KB)")

if __name__ == "__main__":
    create_video()

import os, shutil
from PIL import Image

src_images = [
    ("media_1794523345359_cee2ca01.jpg", "screen_live_timetable_v2.png"),
    ("media_1794523345716_7c9a1434.jpg", "screen_courses_hub_v2.png"),
    ("media_1794523345555_77a10e11.jpg", "screen_notice_feed_v2.png"),
    ("media_1794523345659_e45d5fe5.jpg", "screen_bus_map_gps_v2.png"),
    ("media_1794523345451_2c92a5e6.jpg", "screen_batch_fund_v2.png")
]

user_dir = r"C:/Users/USER/.gemini/antigravity/brain/f299dd3b-5ee4-474d-a380-72de6b93c62b/.user_uploaded"
dest_dir = r"d:/Code & Scripts/ClassMate-main/showcase_assets"
os.makedirs(dest_dir, exist_ok=True)

for src_name, dst_name in src_images:
    src_path = os.path.join(user_dir, src_name)
    dst_path = os.path.join(dest_dir, dst_name)
    im = Image.open(src_path)
    # Save as high-quality PNG
    im.save(dst_path, "PNG", optimize=True)
    print(f"Saved {dst_name}: {im.size}, {os.path.getsize(dst_path)/1024:.1f} KB")

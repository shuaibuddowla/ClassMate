import os
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"

SOURCE_PATH = Path(r"D:\Video\ChatGPT Image Jun 6, 2026, 01_12_10 PM.png")

def main():
    print(f"Loading source image from {SOURCE_PATH}...")
    if not SOURCE_PATH.exists():
        raise FileNotFoundError(f"Source image not found at {SOURCE_PATH}")

    # Load original image and convert to RGBA
    img = Image.open(SOURCE_PATH).convert("RGBA")
    w, h = img.size
    print(f"Original image size: {w}x{h}")

    # Step 1: Create transparency mask using fast flood fill
    print("Generating transparency mask...")
    gray = img.convert("L")
    binary = gray.point(lambda p: 255 if p > 240 else 0)
    
    # Flood fill from the four corners to identify background
    ImageDraw.floodfill(binary, (0, 0), 128)
    ImageDraw.floodfill(binary, (w - 1, 0), 128)
    ImageDraw.floodfill(binary, (0, h - 1), 128)
    ImageDraw.floodfill(binary, (w - 1, h - 1), 128)

    # Any pixel with value 128 is background, set alpha to 0
    alpha = binary.point(lambda p: 0 if p == 128 else 255)
    img.putalpha(alpha)

    # Step 2: Crop to a perfectly centered square covering the squircle + shadow
    # Bounding box is around (204, 174, 1051, 1057)
    # Center: (627, 615). Size: 884x884.
    crop_size = 884
    cx, cy = 627, 615
    left = cx - crop_size // 2
    top = cy - crop_size // 2
    right = left + crop_size
    bottom = top + crop_size
    
    print(f"Cropping base logo to square: ({left}, {top}, {right}, {bottom})...")
    base_logo = img.crop((left, top, right, bottom))
    print(f"Base logo size: {base_logo.size}")

    # Create directories if they do not exist
    os.makedirs(ROOT / "brand_exports", exist_ok=True)

    densities = {
        "mdpi": 1.0,
        "hdpi": 1.5,
        "xhdpi": 2.0,
        "xxhdpi": 3.0,
        "xxxhdpi": 4.0,
    }

    # Step 3: Generate legacy launcher icons (48dp size)
    launcher_dp = 48
    print("Generating legacy launcher icons (ic_launcher.webp and ic_launcher_round.webp)...")
    for density, factor in densities.items():
        size = int(launcher_dp * factor)
        resized = base_logo.resize((size, size), Image.Resampling.LANCZOS)
        
        # Save as webp
        webp_path = RES / f"mipmap-{density}" / "ic_launcher.webp"
        webp_round_path = RES / f"mipmap-{density}" / "ic_launcher_round.webp"
        os.makedirs(webp_path.parent, exist_ok=True)
        
        resized.save(webp_path, "WEBP", quality=92, method=6)
        resized.save(webp_round_path, "WEBP", quality=92, method=6)
        print(f"  - {density}: {size}x{size} -> {webp_path.name}")

    # Step 4: Generate adaptive launcher foregrounds (108dp size, logo scaled to 74% / 80dp)
    adaptive_dp = 108
    logo_scale = 0.74 # 80dp / 108dp
    print("Generating adaptive launcher foregrounds (ic_launcher_foreground.png)...")
    for density, factor in densities.items():
        canvas_size = int(adaptive_dp * factor)
        logo_size = int(canvas_size * logo_scale)
        
        # Create transparent canvas
        canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
        resized_logo = base_logo.resize((logo_size, logo_size), Image.Resampling.LANCZOS)
        
        # Paste logo centered
        offset = (canvas_size - logo_size) // 2
        canvas.paste(resized_logo, (offset, offset), resized_logo)
        
        png_path = RES / f"mipmap-{density}" / "ic_launcher_foreground.png"
        canvas.save(png_path, "PNG", optimize=True)
        print(f"  - {density}: {canvas_size}x{canvas_size} (logo {logo_size}x{logo_size}) -> {png_path.name}")

    # Step 5: Generate app logos and brand assets
    # ic_classmate_logo.png is used for windowSplashScreenAnimatedIcon.
    # To prevent system splash screen from clipping the logo to a circle,
    # we center the logo inside a 240dp transparent canvas scaled to 60% (144dp).
    splash_canvas_dp = 240
    splash_logo_dp = 144
    print("Generating app logo and additional brand drawables...")
    for density, factor in densities.items():
        # Main splash logo (240dp canvas with 144dp logo)
        canvas_size = int(splash_canvas_dp * factor)
        logo_size = int(splash_logo_dp * factor)
        
        canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
        logo_resized = base_logo.resize((logo_size, logo_size), Image.Resampling.LANCZOS)
        offset = (canvas_size - logo_size) // 2
        canvas.paste(logo_resized, (offset, offset), logo_resized)
        
        logo_path = RES / f"drawable-{density}" / "ic_classmate_logo.png"
        os.makedirs(logo_path.parent, exist_ok=True)
        canvas.save(logo_path, "PNG", optimize=True)
        
        # Additional drawables to keep consistent
        # App icon (96dp)
        app_icon_size = int(96 * factor)
        app_icon_resized = base_logo.resize((app_icon_size, app_icon_size), Image.Resampling.LANCZOS)
        app_icon_resized.save(RES / f"drawable-{density}" / "ic_classmate_app_icon.png", "PNG", optimize=True)
        
        # Splash logo (180dp)
        splash_size = int(180 * factor)
        splash_resized = base_logo.resize((splash_size, splash_size), Image.Resampling.LANCZOS)
        splash_resized.save(RES / f"drawable-{density}" / "ic_classmate_splash_logo.png", "PNG", optimize=True)
        
        # Transparent (96dp)
        app_icon_resized.save(RES / f"drawable-{density}" / "ic_classmate_transparent.png", "PNG", optimize=True)
        
        # Toolbar (36dp)
        toolbar_size = int(36 * factor)
        toolbar_resized = base_logo.resize((toolbar_size, toolbar_size), Image.Resampling.LANCZOS)
        toolbar_resized.save(RES / f"drawable-{density}" / "ic_classmate_toolbar_logo.png", "PNG", optimize=True)
        
        # Monochrome (96dp) and Notification (24dp)
        # Create a white mask from base logo alpha channel
        mono_size = int(96 * factor)
        mono_resized = base_logo.resize((mono_size, mono_size), Image.Resampling.LANCZOS)
        mono_mask = mono_resized.split()[3]
        mono_canvas = Image.new("RGBA", (mono_size, mono_size), (255, 255, 255, 0))
        white_solid = Image.new("RGBA", (mono_size, mono_size), (255, 255, 255, 255))
        mono_canvas.paste(white_solid, (0, 0), mono_mask)
        mono_canvas.save(RES / f"drawable-{density}" / "ic_classmate_monochrome.png", "PNG", optimize=True)
        
        notif_size = int(24 * factor)
        notif_resized = base_logo.resize((notif_size, notif_size), Image.Resampling.LANCZOS)
        notif_mask = notif_resized.split()[3]
        notif_canvas = Image.new("RGBA", (notif_size, notif_size), (255, 255, 255, 0))
        notif_white = Image.new("RGBA", (notif_size, notif_size), (255, 255, 255, 255))
        notif_canvas.paste(notif_white, (0, 0), notif_mask)
        notif_canvas.save(RES / f"drawable-{density}" / "ic_classmate_notification.png", "PNG", optimize=True)

    # Save export high-res assets (1024x1024)
    print("Generating high-res brand export assets...")
    export_dir = ROOT / "brand_exports"
    logo_1024 = base_logo.resize((1024, 1024), Image.Resampling.LANCZOS)
    logo_1024.save(export_dir / "classmate_app_icon_1024.png", "PNG", optimize=True)
    logo_1024.save(export_dir / "classmate_splash_logo_1024.png", "PNG", optimize=True)
    logo_1024.save(export_dir / "classmate_transparent_mark_1024.png", "PNG", optimize=True)
    
    # Monochrome high-res
    mono_mask_1024 = logo_1024.split()[3]
    mono_1024 = Image.new("RGBA", (1024, 1024), (255, 255, 255, 0))
    white_1024 = Image.new("RGBA", (1024, 1024), (255, 255, 255, 255))
    mono_1024.paste(white_1024, (0, 0), mono_mask_1024)
    mono_1024.save(export_dir / "classmate_monochrome_mark_1024.png", "PNG", optimize=True)

    print("Brand assets generation completed successfully!")

if __name__ == "__main__":
    main()

from __future__ import annotations

import math
from pathlib import Path
from typing import Iterable, Sequence, Tuple

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"

BG_TOP = (23, 34, 65, 255)
BG_MID = (7, 17, 31, 255)
BG_BOTTOM = (2, 8, 23, 255)
BLUE = (59, 130, 246, 255)
SOFT_BLUE = (96, 165, 250, 255)
BLUE_DARK = (29, 78, 216, 255)
GOLD = (251, 191, 36, 255)
WARM_GOLD = (245, 158, 11, 255)
WHITE = (248, 250, 252, 255)
PAGE = (222, 229, 240, 255)
INK = (7, 17, 31, 255)


def lerp(a: int, b: int, t: float) -> int:
    return int(round(a + (b - a) * t))


def mix(c1: Sequence[int], c2: Sequence[int], t: float) -> Tuple[int, int, int, int]:
    return tuple(lerp(c1[i], c2[i], t) for i in range(4))  # type: ignore[return-value]


def xy(size: int, x: float, y: float) -> Tuple[int, int]:
    return int(round(x / 256 * size)), int(round(y / 256 * size))


def pxy(size: int, points: Iterable[Tuple[float, float]]) -> list[Tuple[int, int]]:
    return [xy(size, x, y) for x, y in points]


def gradient(size: int, top: Sequence[int], mid: Sequence[int], bottom: Sequence[int]) -> Image.Image:
    img = Image.new("RGBA", (size, size))
    for y in range(size):
        ty = y / max(size - 1, 1)
        if ty < 0.48:
            c = mix(top, mid, ty / 0.48)
        else:
            c = mix(mid, bottom, (ty - 0.48) / 0.52)
        ImageDraw.Draw(img).line([(0, y), (size, y)], fill=c)
    return img


def add_shadow(base: Image.Image, mask: Image.Image, offset: Tuple[int, int], blur: int, alpha: int) -> None:
    shadow = Image.new("RGBA", base.size, (0, 0, 0, 0))
    colored = Image.new("RGBA", base.size, (0, 0, 0, alpha))
    shadow_mask = mask.filter(ImageFilter.GaussianBlur(blur))
    shadow.alpha_composite(colored)
    shadow.putalpha(shadow_mask)
    shifted = Image.new("RGBA", base.size, (0, 0, 0, 0))
    shifted.alpha_composite(shadow, offset)
    base.alpha_composite(shifted)


def draw_poly_layer(base: Image.Image, size: int, points: Sequence[Tuple[float, float]], fill: Sequence[int], blur_shadow: bool = True) -> None:
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    mask = Image.new("L", base.size, 0)
    ImageDraw.Draw(mask).polygon(pxy(size, points), fill=255)
    if blur_shadow:
        add_shadow(base, mask, (0, max(1, size // 170)), max(1, size // 95), 70)
    ImageDraw.Draw(layer).polygon(pxy(size, points), fill=tuple(fill))
    base.alpha_composite(layer)


def quad_points(p0, p1, p2, samples=44):
    for i in range(samples + 1):
        t = i / samples
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t**2 * p2[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t**2 * p2[1]
        yield x, y


def draw_arc(draw: ImageDraw.ImageDraw, size: int, points: list[Tuple[float, float]], start: Sequence[int], end: Sequence[int], width: float) -> None:
    scaled = pxy(size, points)
    color = mix(start, end, 0.32)
    draw.line(scaled, fill=color, width=max(1, int(width / 256 * size)), joint="curve")
    radius = max(1, int(width / 512 * size))
    for point, color in ((scaled[0], start), (scaled[-1], end)):
        draw.ellipse((point[0] - radius, point[1] - radius, point[0] + radius, point[1] + radius), fill=tuple(color))


def draw_symbol(base: Image.Image, monochrome: bool = False) -> None:
    size = base.size[0]
    draw = ImageDraw.Draw(base)

    arc_left = list(quad_points((128, 64), (75, 64), (75, 123)))
    arc_right = list(quad_points((128, 64), (181, 64), (181, 123)))
    draw_arc(draw, size, arc_left, WHITE if monochrome else SOFT_BLUE, WHITE if monochrome else BLUE_DARK, 10)
    draw_arc(draw, size, arc_right, WHITE if monochrome else GOLD, WHITE if monochrome else WARM_GOLD, 10)

    head_r = 14 / 256 * size
    for cx, color in ((84, WHITE if monochrome else BLUE), (172, WHITE if monochrome else GOLD)):
        x, y = xy(size, cx, 117)
        draw.ellipse((x - head_r, y - head_r, x + head_r, y + head_r), fill=color)

    draw_poly_layer(base, size, [(64, 134), (83, 134), (109, 146), (121, 177), (121, 189), (98, 170), (61, 164), (59, 149)], WHITE if monochrome else BLUE)
    draw_poly_layer(base, size, [(192, 134), (173, 134), (147, 146), (135, 177), (135, 189), (158, 170), (195, 164), (197, 149)], WHITE if monochrome else WARM_GOLD)

    nib = [(128, 74), (151, 126), (135, 187), (128, 202), (121, 187), (105, 126)]
    draw_poly_layer(base, size, nib, WHITE)
    draw_poly_layer(base, size, [(121, 187), (113, 126), (128, 202)], (210, 218, 234, 150), blur_shadow=False)
    draw.line([xy(size, 128, 76), xy(size, 128, 116)], fill=INK, width=max(1, int(size * 4 / 256)))
    hx, hy = xy(size, 128, 122)
    hr = 9 / 256 * size
    draw.ellipse((hx - hr, hy - hr, hx + hr, hy + hr), fill=INK)

    page_fill = WHITE if monochrome else WHITE
    draw_poly_layer(base, size, [(61, 154), (86, 155), (108, 170), (128, 202), (102, 182), (80, 176), (55, 177), (57, 164)], page_fill)
    draw_poly_layer(base, size, [(195, 154), (170, 155), (148, 170), (128, 202), (154, 182), (176, 176), (201, 177), (199, 164)], page_fill)
    draw_poly_layer(base, size, [(49, 187), (79, 184), (105, 190), (128, 204), (97, 198), (72, 197), (45, 199)], WHITE if monochrome else BLUE_DARK)
    draw_poly_layer(base, size, [(207, 187), (177, 184), (151, 190), (128, 204), (159, 198), (184, 197), (211, 199)], WHITE if monochrome else BLUE)


def draw_icon(size: int, transparent: bool = False, monochrome: bool = False, foreground: bool = False) -> Image.Image:
    scale = 4 if size <= 256 else 2
    large = size * scale
    canvas = Image.new("RGBA", (large, large), (0, 0, 0, 0))

    if not transparent:
        radius = int(44 / 256 * large)
        rect = [int(20 / 256 * large), int(20 / 256 * large), int(236 / 256 * large), int(236 / 256 * large)]
        mask = Image.new("L", (large, large), 0)
        ImageDraw.Draw(mask).rounded_rectangle(rect, radius=radius, fill=255)
        add_shadow(canvas, mask, (0, int(14 / 256 * large)), int(18 / 256 * large), 110)
        bg = gradient(large, BG_TOP, BG_MID, BG_BOTTOM)
        clipped = Image.new("RGBA", (large, large), (0, 0, 0, 0))
        clipped.alpha_composite(bg)
        clipped.putalpha(mask)
        canvas.alpha_composite(clipped)
        edge = ImageDraw.Draw(canvas)
        edge.rounded_rectangle(rect, radius=radius, outline=(77, 95, 146, 75), width=max(1, int(1.5 / 256 * large)))

    if foreground:
        icon_layer = Image.new("RGBA", (large, large), (0, 0, 0, 0))
        draw_symbol(icon_layer, monochrome=monochrome)
        icon_layer = icon_layer.resize((int(large * 0.78), int(large * 0.78)), Image.Resampling.LANCZOS)
        ox = (large - icon_layer.size[0]) // 2
        oy = (large - icon_layer.size[1]) // 2
        canvas.alpha_composite(icon_layer, (ox, oy))
    else:
        draw_symbol(canvas, monochrome=monochrome)

    return canvas.resize((size, size), Image.Resampling.LANCZOS)


def save_png(path: Path, image: Image.Image) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)


def save_webp(path: Path, image: Image.Image) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "WEBP", quality=92, method=6)


def main() -> None:
    densities = {
        "mdpi": 1.0,
        "hdpi": 1.5,
        "xhdpi": 2.0,
        "xxhdpi": 3.0,
        "xxxhdpi": 4.0,
    }

    launcher_sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

    for density, size in launcher_sizes.items():
        icon = draw_icon(size)
        save_webp(RES / f"mipmap-{density}" / "ic_launcher.webp", icon)
        save_webp(RES / f"mipmap-{density}" / "ic_launcher_round.webp", icon)
        save_png(RES / f"mipmap-{density}" / "ic_launcher_foreground.png", draw_icon(size, transparent=True, foreground=True))

    for density, factor in densities.items():
        save_png(RES / f"drawable-{density}" / "ic_classmate_app_icon.png", draw_icon(int(96 * factor)))
        save_png(RES / f"drawable-{density}" / "ic_classmate_splash_logo.png", draw_icon(int(180 * factor)))
        save_png(RES / f"drawable-{density}" / "ic_classmate_transparent.png", draw_icon(int(96 * factor), transparent=True))
        save_png(RES / f"drawable-{density}" / "ic_classmate_toolbar_logo.png", draw_icon(int(36 * factor), transparent=True))
        save_png(RES / f"drawable-{density}" / "ic_classmate_monochrome.png", draw_icon(int(96 * factor), transparent=True, monochrome=True))
        save_png(RES / f"drawable-{density}" / "ic_classmate_notification.png", draw_icon(int(24 * factor), transparent=True, monochrome=True))

    export_dir = ROOT / "brand_exports"
    save_png(export_dir / "classmate_app_icon_1024.png", draw_icon(1024))
    save_png(export_dir / "classmate_splash_logo_1024.png", draw_icon(1024))
    save_png(export_dir / "classmate_transparent_mark_1024.png", draw_icon(1024, transparent=True))
    save_png(export_dir / "classmate_monochrome_mark_1024.png", draw_icon(1024, transparent=True, monochrome=True))


if __name__ == "__main__":
    main()

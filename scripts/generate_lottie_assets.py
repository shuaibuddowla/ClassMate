import json, math, os

out_dir = "d:/Code & Scripts/ClassMate-main/web/public/animations"
os.makedirs(out_dir, exist_ok=True)

def make_lottie_base(name, w=300, h=300, fr=30, total_frames=60):
    return {
        "v": "5.7.4",
        "fr": fr,
        "ip": 0,
        "op": total_frames,
        "w": w,
        "h": h,
        "nm": name,
        "ddd": 0,
        "assets": [],
        "layers": []
    }

def make_transform(p=[0, 0], s=[100, 100], r=0, o=100, a=[0, 0]):
    def val(v):
        if isinstance(v, list) and len(v) > 0 and isinstance(v[0], dict):
            return {"a": 1, "k": v}
        return {"a": 0, "k": v}
    return {
        "p": val(p),
        "s": val(s),
        "r": val(r),
        "o": val(o),
        "a": val(a)
    }

def make_color_fill(rgb, opacity=100):
    return {
        "ty": "fl",
        "nm": "Fill",
        "c": {"a": 0, "k": rgb},
        "o": {"a": 0, "k": opacity},
        "r": 1,
        "bm": 0
    }

def make_stroke(rgb, width=3, opacity=100):
    return {
        "ty": "st",
        "nm": "Stroke",
        "c": {"a": 0, "k": rgb},
        "o": {"a": 0, "k": opacity},
        "w": {"a": 0, "k": width},
        "lc": 2,
        "lj": 2
    }

def make_ellipse_shape(size=[50, 50], pos=[0, 0]):
    return {
        "ty": "el",
        "nm": "Ellipse",
        "p": {"a": 0, "k": pos},
        "s": {"a": 0, "k": size}
    }

def make_rect_shape(size=[50, 50], pos=[0, 0], r=8):
    return {
        "ty": "rc",
        "nm": "Rect",
        "p": {"a": 0, "k": pos},
        "s": {"a": 0, "k": size},
        "r": {"a": 0, "k": r}
    }

def make_path_shape(v, i=None, o=None, closed=True):
    if i is None: i = [[0, 0]] * len(v)
    if o is None: o = [[0, 0]] * len(v)
    return {
        "ty": "sh",
        "nm": "Path",
        "ks": {
            "a": 0,
            "k": {
                "c": closed,
                "v": v,
                "i": i,
                "o": o
            }
        }
    }

def make_shape_layer(name, ind, shapes, ks=None):
    if ks is None:
        ks = make_transform()
    return {
        "ddd": 0,
        "ind": ind,
        "ty": 4,
        "nm": name,
        "sr": 1,
        "ks": ks,
        "ao": 0,
        "shapes": shapes,
        "ip": 0,
        "op": 60,
        "st": 0,
        "bm": 0
    }

# 1. EID ANIMATION (Moon, Lantern swaying, twinkling stars)
def build_eid():
    doc = make_lottie_base("Eid Mubarak", 300, 300, 30, 60)
    
    # Twinkling Stars
    star_positions = [(60, 60), (240, 70), (80, 220), (230, 210), (150, 45)]
    for idx, (sx, sy) in enumerate(star_positions):
        st = idx * 6
        kf = [
            {"t": 0, "s": [50, 50, 100], "h": 0},
            {"t": (st + 15) % 60, "s": [120, 120, 100], "h": 0},
            {"t": (st + 30) % 60, "s": [40, 40, 100], "h": 0},
            {"t": 60, "s": [50, 50, 100], "h": 0}
        ]
        kf.sort(key=lambda x: x["t"])
        star_shape = {
            "ty": "gr",
            "it": [
                make_path_shape(
                    [[0, -12], [3, -3], [12, 0], [3, 3], [0, 12], [-3, 3], [-12, 0], [-3, -3]],
                    closed=True
                ),
                make_color_fill([1.0, 0.88, 0.35], 90),
                make_transform()
            ]
        }
        doc["layers"].append(make_shape_layer(f"Star_{idx}", idx + 1, [star_shape], make_transform(p=[sx, sy], s=kf)))

    # Golden Crescent Moon
    moon_pts = [
        [-10, -75], [45, -30], [50, 35], [-5, 75],
        [22, 45], [20, -35]
    ]
    moon_i = [[0, 0], [-35, -20], [-25, 25], [0, 0], [15, 25], [15, -25]]
    moon_o = [[35, 10], [25, 30], [0, 0], [-25, 20], [-15, -25], [0, 0]]
    moon_group = {
        "ty": "gr",
        "it": [
            make_path_shape(moon_pts, moon_i, moon_o, closed=True),
            make_color_fill([1.0, 0.82, 0.2], 100),
            make_transform()
        ]
    }
    moon_kf_y = [
        {"t": 0, "s": [115, 130, 0]},
        {"t": 30, "s": [115, 122, 0]},
        {"t": 60, "s": [115, 130, 0]}
    ]
    doc["layers"].append(make_shape_layer("Moon", 10, [moon_group], make_transform(p=moon_kf_y, s=[110, 110])))

    # Hanging Lantern (Fanous) that sways
    lantern_cord = {
        "ty": "gr",
        "it": [
            make_path_shape([[0, 0], [0, 50]], closed=False),
            make_stroke([1.0, 0.82, 0.2], width=2),
            make_transform()
        ]
    }
    lantern_body = {
        "ty": "gr",
        "it": [
            make_path_shape([[0, 50], [18, 65], [12, 105], [0, 118], [-12, 105], [-18, 65]], closed=True),
            make_color_fill([0.96, 0.65, 0.12], 95),
            make_stroke([1.0, 0.85, 0.3], width=2),
            make_transform()
        ]
    }
    lantern_flame = {
        "ty": "gr",
        "it": [
            make_ellipse_shape(size=[12, 22], pos=[0, 85]),
            make_color_fill([1.0, 0.95, 0.6], 90),
            make_transform()
        ]
    }
    rot_kf = [
        {"t": 0, "s": [-7]},
        {"t": 30, "s": [7]},
        {"t": 60, "s": [-7]}
    ]
    lantern_ks = make_transform(p=[195, 30], a=[0, 0], r=rot_kf)
    doc["layers"].append(make_shape_layer("Lantern", 20, [lantern_cord, lantern_body, lantern_flame], lantern_ks))

    return doc

# 2. PUJA ANIMATION (Diya lamp with flickering flame and radiant mandala)
def build_puja():
    doc = make_lottie_base("Puja Festive", 300, 300, 30, 60)

    # Radiant Mandala / Aura (Rotating & Pulsing)
    mandala_rot = [{"t": 0, "s": [0]}, {"t": 60, "s": [180]}]
    mandala_scale = [{"t": 0, "s": [85, 85, 100]}, {"t": 30, "s": [105, 105, 100]}, {"t": 60, "s": [85, 85, 100]}]
    mandala_petals = []
    for deg in range(0, 360, 30):
        rad = math.radians(deg)
        x = math.cos(rad) * 60
        y = math.sin(rad) * 60
        mandala_petals.append(make_ellipse_shape(size=[14, 14], pos=[x, y]))
    mandala_group = {
        "ty": "gr",
        "it": mandala_petals + [
            make_color_fill([1.0, 0.8, 0.2], 25),
            make_transform()
        ]
    }
    doc["layers"].append(make_shape_layer("MandalaAura", 1, [mandala_group], make_transform(p=[150, 135], s=mandala_scale, r=mandala_rot)))

    # Diya Base (Golden terracotta earthen lamp)
    diya_base = {
        "ty": "gr",
        "it": [
            make_path_shape([[-65, 0], [65, 0], [45, 40], [-45, 40]], closed=True),
            make_color_fill([0.88, 0.38, 0.15], 100),
            make_stroke([1.0, 0.78, 0.28], width=3),
            make_transform()
        ]
    }
    diya_rim = {
        "ty": "gr",
        "it": [
            make_ellipse_shape(size=[135, 20], pos=[0, 0]),
            make_color_fill([0.98, 0.65, 0.18], 100),
            make_stroke([1.0, 0.85, 0.35], width=2),
            make_transform()
        ]
    }
    doc["layers"].append(make_shape_layer("DiyaBase", 10, [diya_base, diya_rim], make_transform(p=[150, 190])))

    # Dancing Flame with keyframe scale & position flicker
    flame_scale = [
        {"t": 0, "s": [100, 100, 100]},
        {"t": 15, "s": [90, 115, 100]},
        {"t": 30, "s": [108, 92, 100]},
        {"t": 45, "s": [95, 110, 100]},
        {"t": 60, "s": [100, 100, 100]}
    ]
    flame_outer = {
        "ty": "gr",
        "it": [
            make_path_shape([[0, -45], [22, -10], [0, 15], [-22, -10]], closed=True),
            make_color_fill([1.0, 0.55, 0.05], 90),
            make_transform()
        ]
    }
    flame_inner = {
        "ty": "gr",
        "it": [
            make_path_shape([[0, -32], [14, -8], [0, 10], [-14, -8]], closed=True),
            make_color_fill([1.0, 0.92, 0.3], 95),
            make_transform()
        ]
    }
    doc["layers"].append(make_shape_layer("Flame", 20, [flame_outer, flame_inner], make_transform(p=[150, 172], a=[0, 15], s=flame_scale)))

    return doc

# 3. VACATION ANIMATION (Sun, Tropical Palm, Ocean waves, beach ball)
def build_vacation():
    doc = make_lottie_base("Vacation Holiday", 300, 300, 30, 60)

    # Pulsing Sun in the sky
    sun_scale = [{"t": 0, "s": [90, 90, 100]}, {"t": 30, "s": [105, 105, 100]}, {"t": 60, "s": [90, 90, 100]}]
    sun_body = {
        "ty": "gr",
        "it": [
            make_ellipse_shape(size=[65, 65]),
            make_color_fill([1.0, 0.76, 0.15], 100),
            make_transform()
        ]
    }
    sun_glow = {
        "ty": "gr",
        "it": [
            make_ellipse_shape(size=[90, 90]),
            make_color_fill([1.0, 0.85, 0.3], 35),
            make_transform()
        ]
    }
    doc["layers"].append(make_shape_layer("Sun", 1, [sun_glow, sun_body], make_transform(p=[210, 85], s=sun_scale)))

    # Swaying Palm Tree
    palm_trunk = {
        "ty": "gr",
        "it": [
            make_path_shape([[0, 0], [10, -50], [-5, -110]], closed=False),
            make_stroke([0.55, 0.35, 0.18], width=10),
            make_transform()
        ]
    }
    palm_leaf1 = {
        "ty": "gr",
        "it": [
            make_path_shape([[-5, -110], [-45, -135], [-75, -115]], closed=False),
            make_stroke([0.18, 0.72, 0.38], width=7),
            make_transform()
        ]
    }
    palm_leaf2 = {
        "ty": "gr",
        "it": [
            make_path_shape([[-5, -110], [35, -145], [65, -130]], closed=False),
            make_stroke([0.22, 0.78, 0.42], width=7),
            make_transform()
        ]
    }
    palm_leaf3 = {
        "ty": "gr",
        "it": [
            make_path_shape([[-5, -110], [-25, -165], [0, -180]], closed=False),
            make_stroke([0.16, 0.65, 0.32], width=7),
            make_transform()
        ]
    }
    palm_rot = [{"t": 0, "s": [-2]}, {"t": 30, "s": [3]}, {"t": 60, "s": [-2]}]
    doc["layers"].append(make_shape_layer("PalmTree", 10, [palm_trunk, palm_leaf1, palm_leaf2, palm_leaf3], make_transform(p=[85, 230], a=[0, 0], r=palm_rot)))

    # Gentle Ocean Waves
    wave_pos = [
        {"t": 0, "s": [150, 235, 0]},
        {"t": 30, "s": [150, 238, 0]},
        {"t": 60, "s": [150, 235, 0]}
    ]
    wave1 = {
        "ty": "gr",
        "it": [
            make_path_shape([[-150, 0], [-75, -8], [0, 0], [75, -8], [150, 0], [150, 50], [-150, 50]], closed=True),
            make_color_fill([0.15, 0.65, 0.92], 80),
            make_transform()
        ]
    }
    doc["layers"].append(make_shape_layer("Wave", 15, [wave1], make_transform(p=wave_pos)))

    # Bouncing Beach Ball
    ball_kf_y = [
        {"t": 0, "s": [185, 205, 0]},
        {"t": 30, "s": [185, 185, 0]},
        {"t": 60, "s": [185, 205, 0]}
    ]
    ball_rot = [{"t": 0, "s": [0]}, {"t": 60, "s": [360]}]
    ball_group = {
        "ty": "gr",
        "it": [
            make_ellipse_shape(size=[36, 36]),
            make_color_fill([0.95, 0.3, 0.3], 100),
            make_stroke([1.0, 1.0, 1.0], width=3),
            make_transform()
        ]
    }
    ball_stripe = {
        "ty": "gr",
        "it": [
            make_ellipse_shape(size=[18, 36]),
            make_color_fill([0.2, 0.55, 0.95], 90),
            make_transform()
        ]
    }
    doc["layers"].append(make_shape_layer("BeachBall", 20, [ball_group, ball_stripe], make_transform(p=ball_kf_y, r=ball_rot)))

    return doc

# 4. NATIONAL HOLIDAY (Memorial & Bangladesh Flag Harmony)
def build_national():
    doc = make_lottie_base("National Holiday", 300, 300, 30, 60)

    # Radiant Rising Red Sun (National emblem)
    sun_scale = [{"t": 0, "s": [95, 95, 100]}, {"t": 30, "s": [108, 108, 100]}, {"t": 60, "s": [95, 95, 100]}]
    red_sun = {
        "ty": "gr",
        "it": [
            make_ellipse_shape(size=[90, 90]),
            make_color_fill([0.94, 0.22, 0.25], 100),
            make_transform()
        ]
    }
    sun_aura = {
        "ty": "gr",
        "it": [
            make_ellipse_shape(size=[120, 120]),
            make_color_fill([0.94, 0.22, 0.25], 25),
            make_transform()
        ]
    }
    doc["layers"].append(make_shape_layer("RedSun", 1, [sun_aura, red_sun], make_transform(p=[150, 130], s=sun_scale)))

    # Shaheed Minar Architectural Silhouette (Clean Modern Monument)
    center_col = {
        "ty": "gr",
        "it": [
            make_rect_shape(size=[24, 110], pos=[0, 0], r=3),
            make_color_fill([0.96, 0.96, 0.96], 100),
            make_stroke([0.1, 0.45, 0.25], width=3),
            make_transform()
        ]
    }
    left_col = {
        "ty": "gr",
        "it": [
            make_rect_shape(size=[18, 85], pos=[-34, 12], r=3),
            make_color_fill([0.92, 0.92, 0.92], 100),
            make_stroke([0.1, 0.45, 0.25], width=2),
            make_transform()
        ]
    }
    right_col = {
        "ty": "gr",
        "it": [
            make_rect_shape(size=[18, 85], pos=[34, 12], r=3),
            make_color_fill([0.92, 0.92, 0.92], 100),
            make_stroke([0.1, 0.45, 0.25], width=2),
            make_transform()
        ]
    }
    base_plinth = {
        "ty": "gr",
        "it": [
            make_rect_shape(size=[150, 14], pos=[0, 60], r=2),
            make_color_fill([0.1, 0.45, 0.25], 100),
            make_transform()
        ]
    }
    doc["layers"].append(make_shape_layer("Monument", 10, [center_col, left_col, right_col, base_plinth], make_transform(p=[150, 160])))

    # Floating floral petals (Respectful red and green celebration)
    for idx, (px, py) in enumerate([(80, 210), (120, 225), (180, 225), (220, 210)]):
        petal_y = [
            {"t": 0, "s": [px, py, 0]},
            {"t": 30, "s": [px, py - 8, 0]},
            {"t": 60, "s": [px, py, 0]}
        ]
        petal = {
            "ty": "gr",
            "it": [
                make_ellipse_shape(size=[12, 8]),
                make_color_fill([0.94, 0.25, 0.3], 90),
                make_transform()
            ]
        }
        doc["layers"].append(make_shape_layer(f"Petal_{idx}", 20 + idx, [petal], make_transform(p=petal_y)))

    return doc

# 5. WEEKEND / FREE DAY (Steaming coffee cup & relaxing vibes)
def build_weekend():
    doc = make_lottie_base("Weekend Off", 300, 300, 30, 60)

    # Coffee Cup Body
    cup_body = {
        "ty": "gr",
        "it": [
            make_path_shape([[-40, -35], [40, -35], [30, 30], [-30, 30]], closed=True),
            make_color_fill([0.22, 0.58, 0.98], 100),
            make_stroke([0.15, 0.42, 0.85], width=3),
            make_transform()
        ]
    }
    cup_handle = {
        "ty": "gr",
        "it": [
            make_path_shape([[40, -15], [62, -15], [62, 15], [35, 15]], closed=False),
            make_stroke([0.22, 0.58, 0.98], width=6),
            make_transform()
        ]
    }
    cup_saucer = {
        "ty": "gr",
        "it": [
            make_ellipse_shape(size=[110, 16], pos=[0, 35]),
            make_color_fill([0.88, 0.92, 0.98], 100),
            make_stroke([0.22, 0.58, 0.98], width=2),
            make_transform()
        ]
    }
    cup_y = [
        {"t": 0, "s": [140, 165, 0]},
        {"t": 30, "s": [140, 160, 0]},
        {"t": 60, "s": [140, 165, 0]}
    ]
    doc["layers"].append(make_shape_layer("CoffeeCup", 10, [cup_saucer, cup_body, cup_handle], make_transform(p=cup_y)))

    # Rising Steam Wisps (Sinusoidal vertical drift)
    for i, sx in enumerate([-15, 0, 15]):
        st_y = [
            {"t": 0, "s": [140 + sx, 120 - (i * 6), 0]},
            {"t": 30, "s": [140 + sx + (4 if i % 2 == 0 else -4), 105 - (i * 6), 0]},
            {"t": 60, "s": [140 + sx, 120 - (i * 6), 0]}
        ]
        steam_op = [
            {"t": 0, "s": [70]},
            {"t": 30, "s": [25]},
            {"t": 60, "s": [70]}
        ]
        steam = {
            "ty": "gr",
            "it": [
                make_path_shape([[0, 15], [4, 0], [-4, -15]], closed=False),
                make_stroke([0.7, 0.75, 0.85], width=3),
                make_transform()
            ]
        }
        doc["layers"].append(make_shape_layer(f"Steam_{i}", 20 + i, [steam], make_transform(p=st_y, o=steam_op)))

    # Resting Book under cup
    book_cover = {
        "ty": "gr",
        "it": [
            make_rect_shape(size=[170, 22], pos=[0, 198], r=4),
            make_color_fill([0.98, 0.65, 0.2], 100),
            make_stroke([0.88, 0.52, 0.12], width=2),
            make_transform()
        ]
    }
    book_pages = {
        "ty": "gr",
        "it": [
            make_rect_shape(size=[160, 14], pos=[0, 198], r=2),
            make_color_fill([1.0, 1.0, 1.0], 100),
            make_transform()
        ]
    }
    doc["layers"].append(make_shape_layer("Book", 5, [book_cover, book_pages], make_transform(p=[140, 0])))

    return doc

# 6. CELEBRATION / PARTY (Confetti burst & party popper)
def build_celebration():
    doc = make_lottie_base("Celebration Party", 300, 300, 30, 60)

    # Party Popper Cone
    cone = {
        "ty": "gr",
        "it": [
            make_path_shape([[0, 0], [45, -45], [60, -25]], closed=True),
            make_color_fill([0.95, 0.25, 0.45], 100),
            make_stroke([1.0, 0.8, 0.2], width=3),
            make_transform()
        ]
    }
    cone_bounce = [
        {"t": 0, "s": [90, 215, 0]},
        {"t": 15, "s": [85, 220, 0]},
        {"t": 30, "s": [92, 212, 0]},
        {"t": 60, "s": [90, 215, 0]}
    ]
    doc["layers"].append(make_shape_layer("Popper", 10, [cone], make_transform(p=cone_bounce)))

    # Exploding Confetti Particles
    confetti_colors = [
        [0.98, 0.75, 0.18], # Gold
        [0.22, 0.65, 0.98], # Blue
        [0.18, 0.82, 0.45], # Emerald
        [0.92, 0.28, 0.55], # Pink
        [0.62, 0.35, 0.92]  # Purple
    ]
    
    positions = [
        (130, 150), (150, 120), (180, 100), (210, 80),
        (160, 170), (190, 140), (220, 120), (240, 110),
        (120, 110), (170, 75), (200, 60), (240, 65)
    ]

    for idx, (target_x, target_y) in enumerate(positions):
        color = confetti_colors[idx % len(confetti_colors)]
        t_start = (idx % 3) * 4
        kf_pos = [
            {"t": 0, "s": [120, 185, 0]},
            {"t": t_start, "s": [120, 185, 0]},
            {"t": t_start + 22, "s": [target_x, target_y, 0]},
            {"t": 60, "s": [target_x + (5 if idx % 2 == 0 else -5), target_y + 25, 0]}
        ]
        kf_rot = [{"t": 0, "s": [0]}, {"t": 60, "s": [360 * (1 if idx % 2 == 0 else -1)]}]
        
        shape = make_rect_shape(size=[10, 6], r=2) if idx % 2 == 0 else make_ellipse_shape(size=[8, 8])
        confetti_group = {
            "ty": "gr",
            "it": [
                shape,
                make_color_fill(color, 95),
                make_transform()
            ]
        }
        doc["layers"].append(make_shape_layer(f"Confetti_{idx}", 20 + idx, [confetti_group], make_transform(p=kf_pos, r=kf_rot)))

    return doc

# Build all 6 files
builds = {
    "eid.json": build_eid(),
    "puja.json": build_puja(),
    "vacation.json": build_vacation(),
    "national.json": build_national(),
    "weekend.json": build_weekend(),
    "celebration.json": build_celebration()
}

for filename, content in builds.items():
    path = os.path.join(out_dir, filename)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(content, f, separators=(',', ':'))
    print(f"Generated {filename}: {os.path.getsize(path)} bytes")

print("All 6 Lottie animations generated successfully in", out_dir)

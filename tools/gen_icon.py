#!/usr/bin/env python3
"""小飞象图标生成器。

几何定义是唯一来源，同时产出：
  - Android 矢量 XML（自适应图标 foreground / background）
  - 各级密度的 PNG（给 API 21-25 的老设备用，它们不支持自适应图标）

设计：暖橙色渐变底 + 蓝灰色卡通小象，大耳朵张开像在飞，象鼻卷起，脸颊带腮红。
所有内容控制在自适应图标 66dp 安全区内（半径 33，圆心 54,54）。
"""
import os
from PIL import Image, ImageDraw

# 从脚本自身的位置推导，而不是写死绝对路径 —— 写死的话换台机器/换个目录就跑不了
RES = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res"
)

# ---------- 调色 ----------
BG_TOP = (255, 150, 64)      # 渐变上
BG_BOTTOM = (243, 92, 0)     # 渐变下
EAR_OUT = (155, 184, 217)
EAR_IN = (247, 195, 208)
BODY = (180, 204, 230)
DARK = (46, 59, 78)
WHITE = (255, 255, 255)
BLUSH = (255, 175, 192)
SPARK = (255, 217, 138)

K = 0.5522847498  # 圆的三次贝塞尔逼近常数

# ---------- 几何 ----------

# 象鼻：三次贝塞尔 + 描边。画在头之前，所以根部被头挡住。
TRUNK = {
    "p0": (53.0, 62.0),
    "c1": (51.0, 74.0),
    "c2": (55.0, 82.0),
    "p1": (64.0, 80.5),
    "c3": (69.0, 79.5),
    "c4": (70.5, 76.0),
    "p2": (68.5, 73.5),
    "width": 8.5,
}

EARS_OUT = [  # 外耳
    {"c": (33.0, 52.0), "rx": 10.5, "ry": 12.5},
    {"c": (75.0, 52.0), "rx": 10.5, "ry": 12.5},
]
EARS_IN = [  # 内耳（粉色）
    {"c": (33.0, 52.5), "rx": 5.6, "ry": 7.0},
    {"c": (75.0, 52.5), "rx": 5.6, "ry": 7.0},
]
HEAD = {"c": (54.0, 56.0), "r": 15.0}
# 头顶一撮呆毛，稍微歪一点才像头发而不是提手
TUFT = {"p0": (51.5, 42.0), "c1": (52.0, 36.8), "c2": (55.5, 36.2), "p1": (57.6, 39.2),
        "width": 2.2}
EYES = [(48.0, 53.0, 2.6), (60.0, 53.0, 2.6)]
GLINTS = [(47.1, 52.1, 0.95), (59.1, 52.1, 0.95)]
BLUSHES = [
    {"c": (42.0, 61.0), "rx": 3.6, "ry": 2.3},
    {"c": (66.0, 61.0), "rx": 3.6, "ry": 2.3},
]
SPARKS = [(29.0, 33.0, 2.4), (79.0, 35.0, 1.8), (76.0, 78.0, 2.1)]


def ellipse_path(cx, cy, rx, ry):
    return (
        "M%.2f,%.2f "
        "C%.2f,%.2f %.2f,%.2f %.2f,%.2f "
        "C%.2f,%.2f %.2f,%.2f %.2f,%.2f "
        "C%.2f,%.2f %.2f,%.2f %.2f,%.2f "
        "C%.2f,%.2f %.2f,%.2f %.2f,%.2f Z"
    ) % (
        cx - rx, cy,
        cx - rx, cy - K * ry, cx - K * rx, cy - ry, cx, cy - ry,
        cx + K * rx, cy - ry, cx + rx, cy - K * ry, cx + rx, cy,
        cx + rx, cy + K * ry, cx + K * rx, cy + ry, cx, cy + ry,
        cx - K * rx, cy + ry, cx - rx, cy + K * ry, cx - rx, cy,
    )


def bezier_path(*pts):
    """首点是 M，之后每 3 个点构成一段 C（可传多段）"""
    s = "M%.2f,%.2f" % pts[0]
    rest = pts[1:]
    for i in range(0, len(rest), 3):
        s += " C%.2f,%.2f %.2f,%.2f %.2f,%.2f" % (
            rest[i] + rest[i + 1] + rest[i + 2]
        )
    return s


def hexc(rgb):
    return "#%02X%02X%02X" % rgb


# ---------- 矢量 XML ----------

def elephant_vector_paths():
    """返回 (pathData, fillColor, strokeColor, strokeWidth) 列表，按绘制顺序"""
    out = []
    # 外耳
    for e in EARS_OUT:
        out.append((ellipse_path(*e["c"], e["rx"], e["ry"]), hexc(EAR_OUT), None, 0))
    # 内耳
    for e in EARS_IN:
        out.append((ellipse_path(*e["c"], e["rx"], e["ry"]), hexc(EAR_IN), None, 0))
    # 象鼻（描边，无填充）
    t = TRUNK
    out.append((
        bezier_path(t["p0"], t["c1"], t["c2"], t["p1"], t["c3"], t["c4"], t["p2"]),
        None, hexc(BODY), t["width"],
    ))
    # 头
    out.append((ellipse_path(*HEAD["c"], HEAD["r"], HEAD["r"]), hexc(BODY), None, 0))
    # 呆毛
    f = TUFT
    out.append((bezier_path(f["p0"], f["c1"], f["c2"], f["p1"]), None, hexc(EAR_OUT), f["width"]))
    # 腮红
    for b in BLUSHES:
        out.append((ellipse_path(*b["c"], b["rx"], b["ry"]), hexc(BLUSH), None, 0))
    # 眼睛 + 高光
    for (x, y, r) in EYES:
        out.append((ellipse_path(x, y, r, r), hexc(DARK), None, 0))
    for (x, y, r) in GLINTS:
        out.append((ellipse_path(x, y, r, r), hexc(WHITE), None, 0))
    # 星星
    for (x, y, r) in SPARKS:
        out.append((ellipse_path(x, y, r, r), hexc(SPARK), None, 0))
    return out


FOREGROUND_HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<!-- 小飞象 —— 自适应图标前景层。内容在 66dp 安全圆内。由 /tmp/gen_icon.py 生成 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
'''

BACKGROUND_XML = '''<?xml version="1.0" encoding="utf-8"?>
<!-- 小飞象图标背景：暖橙渐变 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr xmlns:aapt="http://schemas.android.com/aapt" name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0" android:startY="0"
                android:endX="108" android:endY="108"
                android:startColor="%s"
                android:endColor="%s" />
        </aapt:attr>
    </path>
</vector>
''' % (hexc(BG_TOP), hexc(BG_BOTTOM))

ADAPTIVE_XML = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''


def write_vectors():
    parts = [FOREGROUND_HEAD]
    for (d, fill, stroke, sw) in elephant_vector_paths():
        if fill:
            parts.append('    <path\n        android:fillColor="%s"\n        android:pathData="%s" />\n'
                         % (fill, d))
        else:
            parts.append(
                '    <path\n        android:strokeColor="%s"\n        android:strokeWidth="%.2f"\n'
                '        android:strokeLineCap="round"\n        android:strokeLineJoin="round"\n'
                '        android:pathData="%s" />\n' % (stroke, sw, d))
    parts.append("</vector>\n")

    d = os.path.join(RES, "drawable")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "ic_launcher_foreground.xml"), "w") as f:
        f.write("".join(parts))
    with open(os.path.join(d, "ic_launcher_background.xml"), "w") as f:
        f.write(BACKGROUND_XML)

    # 单色层：给 Android 13+ 主题化图标用，只保留小象剪影
    mono = [FOREGROUND_HEAD]
    for (p, fill, stroke, sw) in elephant_vector_paths():
        if fill:
            mono.append('    <path\n        android:fillColor="#FFFFFF"\n        android:pathData="%s" />\n' % p)
        else:
            mono.append('    <path\n        android:strokeColor="#FFFFFF"\n        android:strokeWidth="%.2f"\n'
                        '        android:strokeLineCap="round"\n        android:strokeLineJoin="round"\n'
                        '        android:pathData="%s" />\n' % (sw, p))
    mono.append("</vector>\n")
    with open(os.path.join(d, "ic_launcher_monochrome.xml"), "w") as f:
        f.write("".join(mono))

    m = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(m, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(m, name), "w") as f:
            f.write(ADAPTIVE_XML)


# ---------- PNG 渲染 ----------

SS = 8  # 超采样倍数，最后缩小得到抗锯齿


def bezier_points(p0, c1, c2, p1, n=160):
    pts = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        x = u*u*u*p0[0] + 3*u*u*t*c1[0] + 3*u*t*t*c2[0] + t*t*t*p1[0]
        y = u*u*u*p0[1] + 3*u*u*t*c1[1] + 3*u*t*t*c2[1] + t*t*t*p1[1]
        pts.append((x, y))
    return pts


def trunk_points():
    t = TRUNK
    seg1 = bezier_points(t["p0"], t["c1"], t["c2"], t["p1"], 120)
    seg2 = bezier_points(t["p1"], t["c3"], t["c4"], t["p2"], 80)
    return seg1 + seg2[1:]


def render(size, round_mask, zoom=1.0):
    """
    zoom: 小象相对其 108 画布的缩放。
      自适应图标走矢量 XML（本函数不参与），必须留在 66dp 安全圆内 → 1.0
      传统方形图标没有蒙版，可以放大填满画面 → ~1.28
      传统圆形图标受内切圆限制 → ~1.35
    """
    S = size * SS
    k = S / 108.0
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 背景渐变
    bg = Image.new("RGB", (S, S))
    bp = bg.load()
    for y in range(S):
        for x in range(S):
            t = (x + y) / (2.0 * (S - 1))
            bp[x, y] = tuple(
                int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3)
            )
    bg = bg.convert("RGBA")

    if round_mask:
        mask = Image.new("L", (S, S), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, S - 1, S - 1], fill=255)
        img.paste(bg, (0, 0), mask)
    else:
        img.paste(bg, (0, 0))

    def sc(v):
        # 绕画布中心 (54,54) 缩放
        return (54.0 + (v - 54.0) * zoom) * k

    def sr(v):
        return v * zoom * k

    def ell(cx, cy, rx, ry, color):
        d.ellipse([sc(cx - rx), sc(cy - ry), sc(cx + rx), sc(cy + ry)], fill=color)

    def resample(pts, spacing):
        """沿折线按固定间距重采样"""
        out = [pts[0]]
        for i in range(1, len(pts)):
            x0, y0 = pts[i - 1]
            x1, y1 = pts[i]
            seg = ((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5
            n = max(1, int(seg / spacing))
            for j in range(1, n + 1):
                t = j / n
                out.append((x0 + (x1 - x0) * t, y0 + (y1 - y0) * t))
        return out

    def stroke(pts, color, width):
        # 沿路径密集打圆点：PIL 的宽线绘制在拐弯处会有接缝，打点法能得到
        # 实心、无接缝、天然圆头的描边
        r = max(1.0, sr(width) / 2.0)
        for (x, y) in resample(pts, max(0.35, r / 3.0)):
            cx, cy = sc(x), sc(y)
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)

    # 绘制顺序与外耳 -> 内耳 -> 鼻 -> 头 -> 呆毛 -> 腮红 -> 眼 -> 高光 -> 星星
    for e in EARS_OUT:
        ell(e["c"][0], e["c"][1], e["rx"], e["ry"], EAR_OUT)
    for e in EARS_IN:
        ell(e["c"][0], e["c"][1], e["rx"], e["ry"], EAR_IN)
    stroke(trunk_points(), BODY, TRUNK["width"])
    ell(HEAD["c"][0], HEAD["c"][1], HEAD["r"], HEAD["r"], BODY)
    stroke(bezier_points(TUFT["p0"], TUFT["c1"], TUFT["c2"], TUFT["p1"], 60),
           EAR_OUT, TUFT["width"])
    for b in BLUSHES:
        ell(b["c"][0], b["c"][1], b["rx"], b["ry"], BLUSH + (200,))
    for (x, y, r) in EYES:
        ell(x, y, r, r, DARK)
    for (x, y, r) in GLINTS:
        ell(x, y, r, r, WHITE)
    for (x, y, r) in SPARKS:
        ell(x, y, r, r, SPARK)

    return img.resize((size, size), Image.LANCZOS)


def write_pngs():
    # 传统图标：48dp 基准
    for folder, px in (("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                       ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)):
        out = os.path.join(RES, folder)
        os.makedirs(out, exist_ok=True)
        render(px, False, zoom=1.28).save(os.path.join(out, "ic_launcher.png"))
        render(px, True, zoom=1.32).save(os.path.join(out, "ic_launcher_round.png"))
        print("  %s -> %dpx" % (folder, px))


if __name__ == "__main__":
    write_vectors()
    print("矢量 XML 已生成")
    write_pngs()
    # 额外渲染一张大图供人工检查
    render(512, False).save("/tmp/icon_preview.png")
    render(512, True).save("/tmp/icon_preview_round.png")
    print("预览图: /tmp/icon_preview.png")

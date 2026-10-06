# -*- coding: utf-8 -*-
"""生成 tabBar 图标：81x81 PNG，4x 超采样抗锯齿，仅用标准库"""
import struct, zlib, math, os

OUT = r"D:\project\github\senior-anti-fraud-guard\wechat-miniprogram\icons"
N = 81
S = 4  # 超采样倍数
NORMAL = (148, 163, 184)   # #94A3B8
ACTIVE = (59, 130, 246)    # #3B82F6

def dist_seg(px, py, x1, y1, x2, y2):
    dx, dy = x2 - x1, y2 - y1
    L2 = dx * dx + dy * dy
    if L2 == 0:
        return math.hypot(px - x1, py - y1)
    t = max(0, min(1, ((px - x1) * dx + (py - y1) * dy) / L2))
    return math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))

def in_circle(px, py, cx, cy, r):
    return (px - cx) ** 2 + (py - cy) ** 2 <= r * r

def in_rrect(px, py, x1, y1, x2, y2, rad=3):
    if not (x1 <= px <= x2 and y1 <= py <= y2):
        return False
    for cx, cy in ((x1 + rad, y1 + rad), (x2 - rad, y1 + rad), (x1 + rad, y2 - rad), (x2 - rad, y2 - rad)):
        corner_x = x1 if cx == x1 + rad else x2
        corner_y = y1 if cy == y1 + rad else y2
        if ((px < x1 + rad and corner_x == x1) or (px > x2 - rad and corner_x == x2)) and \
           ((py < y1 + rad and corner_y == y1) or (py > y2 - rad and corner_y == y2)):
            if (px - cx) ** 2 + (py - cy) ** 2 > rad * rad:
                return False
    return True

def in_poly(px, py, pts):
    inside = False
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        if (y1 > py) != (y2 > py):
            xin = x1 + (py - y1) * (x2 - x1) / (y2 - y1)
            if px < xin:
                inside = not inside
    return inside

def seg_thick(px, py, x1, y1, x2, y2, w):
    return dist_seg(px, py, x1, y1, x2, y2) <= w / 2

# ---- 各图标形状定义（返回像素是否着色）----
def icon_dashboard(x, y):
    return in_rrect(x, y, 14, 14, 38, 38) or in_rrect(x, y, 43, 14, 67, 38) or \
           in_rrect(x, y, 14, 43, 38, 67) or in_rrect(x, y, 43, 43, 67, 67)

def icon_alert(x, y):
    tri = in_poly(x, y, [(40.5, 12), (69, 65), (12, 65)])
    bar = seg_thick(x, y, 40.5, 30, 40.5, 46, 6)
    dot = in_circle(x, y, 40.5, 55, 3.5)
    return tri and not (bar or dot)

def icon_map(x, y):
    head = in_circle(x, y, 40.5, 30, 19)
    tail = in_poly(x, y, [(25.5, 38), (55.5, 38), (40.5, 70)])
    hole = in_circle(x, y, 40.5, 30, 8)
    return (head or tail) and not hole

def icon_evidence(x, y):
    doc = in_rrect(x, y, 19, 11, 62, 70, rad=4)
    lines = in_rrect(x, y, 27, 26, 54, 31) or in_rrect(x, y, 27, 38, 54, 43) or in_rrect(x, y, 27, 50, 47, 55)
    return doc and not lines

def icon_guide(x, y):
    shield = in_poly(x, y, [(40.5, 10), (66, 20), (66, 42), (63, 52), (40.5, 71), (18, 52), (15, 42), (15, 20)])
    check = seg_thick(x, y, 30, 40, 38, 49, 6) or seg_thick(x, y, 38, 49, 53, 32, 6)
    return shield and not check

ICONS = {
    "dashboard": icon_dashboard,
    "alert": icon_alert,
    "map": icon_map,
    "evidence": icon_evidence,
    "guide": icon_guide,
}

def write_png(path, rgb, inside_fn):
    rows = []
    for j in range(N):
        row = []
        for i in range(N):
            cnt = 0
            for a in range(S):
                for b in range(S):
                    x = (i + (a + 0.5) / S)
                    y = (j + (b + 0.5) / S)
                    if inside_fn(x, y):
                        cnt += 1
            alpha = round(255 * cnt / (S * S))
            row.append((rgb[0], rgb[1], rgb[2], alpha))
        rows.append(row)
    raw = b""
    for row in rows:
        raw += b"\x00" + bytes(v for px in row for v in px)
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", N, N, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)

os.makedirs(OUT, exist_ok=True)
for name, fn in ICONS.items():
    write_png(os.path.join(OUT, f"{name}.png"), NORMAL, fn)
    write_png(os.path.join(OUT, f"{name}-active.png"), ACTIVE, fn)
    print(f"OK {name}.png / {name}-active.png")

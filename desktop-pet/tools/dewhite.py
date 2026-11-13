#!/usr/bin/env python3
"""
去掉立绘的白底抠图残留（外圈白色描边）。

背景：这些立绘由白底图抠出，边缘留下一圈「实心白 + 数层柔化白」的描边，
放在桌面壁纸上就是一圈发亮的白边。它不是半透明雾，所以「反混合」无效；
直接腐蚀又会把角色削瘦、吃掉发丝。

做法：从外向内逐层剥离。
    只要一个偏白像素能从透明区摸到（8 邻域），就把它变成全透明；
    它的内侧邻居随之暴露，继续剥离，直到碰到角色的深色描边为止。
    角色本身有描边包住，所以剥不到主体内部；白 T 恤、眼白这些被描边围住的白色也不会受影响。

为什么不「找白边再染色」：那需要判断白边是否与外部连通，阈值稍差就整圈漏网；
而且取错源色会留下灰边（这版之前试过，边缘会变成脏灰 + 毛刺）。

用法：
    python tools/dewhite.py                 # 处理 stepboy 皮肤全部 webp + png
    python tools/dewhite.py --dry-run       # 只报告会剥掉多少，不改文件
    python tools/dewhite.py --file a.webp   # 处理单个文件
"""

import argparse
import glob
import os
from collections import deque

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PARTS = os.path.join(ROOT, "web", "assets", "skins", "stepboy", "parts")

# 偏白阈值。放得比较低：抠图残留经 WebP 压缩后，边缘会从 255 掉到 180 左右的中灰，
# 阈值定高了这些「灰白边」会整片漏网，在深色背景上照样是一圈亮边。
SOFT_WHITE = 150
# 最多剥多少层，防止某处出现例外（例如没有描边的浅色区域）时一路剥穿主体
MAX_LAYERS = 18

NEIGHBORS = ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1))


def strip_white_border(im, max_layers=MAX_LAYERS):
    """逐层剥离与透明区连通的偏白像素，返回移除的像素数。"""
    w, h = im.size
    px = im.load()

    def bright(p):
        r, g, b, a = p
        return a > 0 and r >= SOFT_WHITE and g >= SOFT_WHITE and b >= SOFT_WHITE

    marked = bytearray(w * h)
    layer = deque()

    # 种子：本身偏白、且 8 邻域里已经有透明像素
    for y in range(h):
        for x in range(w):
            if px[x, y][3] == 0 or not bright(px[x, y]):
                continue
            for dx, dy in NEIGHBORS:
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and px[nx, ny][3] == 0:
                    marked[y * w + x] = 1
                    layer.append((x, y))
                    break

    removed = 0
    for _ in range(max_layers):
        if not layer:
            break
        nxt = deque()
        while layer:
            x, y = layer.popleft()
            r, g, b, _a = px[x, y]
            px[x, y] = (r, g, b, 0)
            removed += 1
            for dx, dy in NEIGHBORS:
                nx, ny = x + dx, y + dy
                if not (0 <= nx < w and 0 <= ny < h):
                    continue
                i = ny * w + nx
                if marked[i]:
                    continue
                if bright(px[nx, ny]):
                    marked[i] = 1
                    nxt.append((nx, ny))
        layer = nxt
    return removed


def process(path, dry_run=False, max_layers=MAX_LAYERS):
    im = Image.open(path).convert("RGBA")
    n = strip_white_border(im if not dry_run else im.copy(), max_layers)
    if n == 0 or dry_run:
        return (os.path.basename(path), n, 0)
    if path.lower().endswith(".webp"):
        # alpha_quality=100：alpha 无损。有损压缩会在「透明/不透明」交界重新生成
        # 一圈半透明噪点，刚剥干净的白边又被压回来。
        im.save(path, "WEBP", quality=95, alpha_quality=100, method=6)
    else:
        im.save(path, "PNG", optimize=True)
    return (os.path.basename(path), n, os.path.getsize(path))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--file")
    # 画面里有「白色道具」（咖啡杯、笔记本屏幕）时调小，避免把道具一起剥掉
    ap.add_argument("--layers", type=int, default=MAX_LAYERS)
    args = ap.parse_args()

    if args.file:
        files = [args.file]
    else:
        files = sorted(glob.glob(os.path.join(PARTS, "*.webp")) + glob.glob(os.path.join(PARTS, "*.png")))

    total = 0
    for f in files:
        name, n, size = process(f, args.dry_run, args.layers)
        total += n
        if n:
            tail = "(dry-run)" if args.dry_run else f"→ {size / 1024:.0f} KB"
            print(f"  {name:<22} 剥离 {n:>6} px  {tail}")
    print(f"\n共 {len(files)} 个文件，剥离像素合计 {total}")


if __name__ == "__main__":
    main()

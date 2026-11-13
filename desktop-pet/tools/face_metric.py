"""尺度基准：用「头发深色连通块」测量头部大小。

桌宠多姿态之间最容易被看出的不一致是头部大小变化 —— 按整体身高归一化会把坐姿/趴姿放大，
按画布构图换算又会被模型自由放大缩小带偏。头发是一整块深色区域、在所有姿态里都独立且完整，
用它当尺子比肤色稳（抬手时手臂的肤色块会盖过脸）。
"""

from collections import deque

from PIL import Image

LUMA = 92


def _dark_mask(img: Image.Image, w: int):
    px = img.load()
    h = img.height
    mask = bytearray(w * h)
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y][:3]
            if (r * 299 + g * 587 + b * 114) // 1000 < LUMA:
                mask[y * w + x] = 1
    return mask


def hair_bbox(img: Image.Image, band=0.65):
    """返回头发包围盒（原图坐标系）。缩到 256 宽做连通域，再映射回原尺寸。"""
    rgb = img.convert("RGB")
    sw = 256
    sh = max(1, round(rgb.height * sw / rgb.width))
    small = rgb.resize((sw, sh), Image.BILINEAR)
    mask = _dark_mask(small, sw)

    limit = int(sh * band)
    best = None
    seen = bytearray(sw * sh)
    for start in range(sw * sh):
        if not mask[start] or seen[start]:
            continue
        sy = start // sw
        if sy > limit:
            continue
        q = deque([start])
        seen[start] = 1
        minx = maxx = start % sw
        miny = maxy = sy
        n = 0
        while q:
            i = q.popleft()
            y, x = divmod(i, sw)
            n += 1
            if x < minx:
                minx = x
            if x > maxx:
                maxx = x
            if y < miny:
                miny = y
            if y > maxy:
                maxy = y
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < sw and 0 <= ny < sh:
                    j = ny * sw + nx
                    if mask[j] and not seen[j]:
                        seen[j] = 1
                        q.append(j)
        bw = maxx - minx + 1
        bh = maxy - miny + 1
        if n < 60 or bw < 12 or bh < 8:
            continue
        # 头发偏圆；长宽比过大的多半是衣服描边或腿
        if not (0.7 < bw / bh < 2.6):
            continue
        if best is None or n > best[0]:
            best = (n, minx, miny, maxx + 1, maxy + 1, bw, bh)

    if best is None:
        return None
    f = rgb.width / sw
    _, x0, y0, x1, y1, bw, bh = best
    return (round(x0 * f), round(y0 * f), round(x1 * f), round(y1 * f), bw * f, bh * f)


def head_size(img: Image.Image):
    """头部的等效直径：头发块长宽的几何平均。"""
    b = hair_bbox(img)
    if b is None:
        return None
    return (b[4] * b[5]) ** 0.5

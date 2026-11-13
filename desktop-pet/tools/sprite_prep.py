"""把 AI 生成的白底全身立绘处理成桌宠部件：抠透明 + 对齐到 body 坐标系。

用法：
    python tools/sprite_prep.py <输入图> <输出png>

对齐策略
--------
* 尺度：以「参考图头部大小 / 本图头部大小」做等比校正，让六个姿态的头围一致（差异通常 <4%）。
  不用整体身高归一化 —— 坐姿、趴姿会被放大。
* 水平：头发块中心对齐画布中轴，抬手、张臂都不会把人物推歪。
* 垂直：内容底边贴地面线（body.png 的鞋底所在行 y=517）。
* 画布：高度固定 520，宽度按角色需要向两侧对称扩展（皮肤画布 200，故上限 400px）。
  skin.json 里声明 `w = 画布宽 / 2`、`h = 260`、`pivot = [0.5, 1.0]`，角色大小不变。

参考图基准：chibi-matted-raw.png 1024x1536，内容 bbox (207,142,849,1435)
body.png  ：263x520，内容 bbox (3,3,260,517)
"""

import sys
from collections import deque

from PIL import Image, ImageFilter

from face_metric import head_size, hair_bbox

CANVAS_H = 520
MAX_W = 400
MIN_W = 263
PAD = 3

REF_BOX = (207, 142, 849, 1435)
BODY_BOX = (3, 3, 260, 517)

SCALE = (BODY_BOX[3] - BODY_BOX[1]) / (REF_BOX[3] - REF_BOX[1])
GROUND_Y = BODY_BOX[3]
AXIS_X = (BODY_BOX[0] + BODY_BOX[2]) / 2

BG_THRESHOLD = 246
REF_IMG = r"C:\Users\86173\.workbuddy\clipboard-images\clipboard-2026-11-12T03-36-39-486Z-dc4bb8a6.jpg"


def remove_white_bg(img: Image.Image) -> Image.Image:
    """抠白底：从画面四边泛洪，把「浅色且近中性」的像素全部变透明。

    为什么不是简单的「白色阈值泛洪」：edit 类生成常在角色两侧留下被擦除手臂的浅色残影
    （约 #FBFAF7，中性偏白），它们被角色自己的白色外发光描边圈住、与画面边缘不连通，
    简单泛洪会漏掉，抠完就变成两块白斑。这里改用「浅 + 中性」判定：
      白 T 恤   (248,243,223) → R-B≈25，明显偏暖，不算背景，保留
      白球鞋    (255,255,255) → 中性，但被深色描边包住，泛洪到不了，保留
      残影 / 外发光描边 → 中性浅色且与边缘连通，一起清掉
    """
    rgb = img.convert("RGB")
    w, h = rgb.size
    px = rgb.load()

    def is_bg(c):
        r, g, b = c
        return min(r, g, b) >= 230 and max(r, g, b) - min(r, g, b) <= 14

    bg = bytearray(w * h)
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if is_bg(px[x, y]):
                i = y * w + x
                if not bg[i]:
                    bg[i] = 1
                    q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if is_bg(px[x, y]):
                i = y * w + x
                if not bg[i]:
                    bg[i] = 1
                    q.append((x, y))

    while q:
        x, y = q.popleft()
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if 0 <= nx < w and 0 <= ny < h:
                i = ny * w + nx
                if not bg[i] and is_bg(px[nx, ny]):
                    bg[i] = 1
                    q.append((nx, ny))

    alpha = Image.new("L", (w, h), 255)
    ap = alpha.load()
    for i, v in enumerate(bg):
        if v:
            ap[i % w, i // w] = 0

    alpha = alpha.filter(ImageFilter.GaussianBlur(0.7))
    alpha = alpha.filter(ImageFilter.MinFilter(3))
    alpha = alpha.filter(ImageFilter.GaussianBlur(0.6))

    out = rgb.convert("RGBA")
    out.putalpha(alpha)
    return out


def flat(img: Image.Image) -> Image.Image:
    bg = Image.new("RGBA", img.size, (255, 255, 255, 255))
    bg.alpha_composite(img.convert("RGBA"))
    return bg.convert("RGB")


_REF_HEAD = None


def ref_head():
    global _REF_HEAD
    if _REF_HEAD is None:
        _REF_HEAD = head_size(Image.open(REF_IMG).convert("RGB"))
    return _REF_HEAD


def prep(src: str, dst: str):
    cut = remove_white_bg(Image.open(src))
    cb = cut.getchannel("A").getbbox()
    if cb is None:
        raise SystemExit("抠图后没有内容，检查输入图是否白底")

    hs = head_size(flat(cut))
    if not hs:
        raise SystemExit("测不到头部，检查输入图")
    factor = SCALE * (ref_head() / hs)

    small = cut.resize((max(1, round(cut.width * factor)), max(1, round(cut.height * factor))), Image.LANCZOS)
    sb = small.getchannel("A").getbbox()
    small = small.crop(sb)

    hb = hair_bbox(flat(small))
    axis_px = (hb[0] + hb[2]) / 2  # 头发块中心在裁剪图中的 x
    half = max(axis_px, small.width - axis_px) + PAD
    half = max(half, MIN_W / 2)
    width = int(min(MAX_W, round(half * 2)))
    height = max(CANVAS_H, small.height + PAD * 2)

    # 水平：优先让头发中心落在中轴；横向姿态放不下时改成整体居中
    x = round(width / 2 - axis_px)
    if x < 0 or x > width - small.width:
        x = round((width - small.width) / 2)
    y = height - PAD - small.height

    canvas = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    canvas.alpha_composite(small, (x, y))
    canvas.save(dst)

    ob = canvas.getchannel("A").getbbox()
    warn = "  ⚠ 触边" if (ob[0] <= 0 or ob[2] >= width or ob[1] <= 0 or ob[3] >= height) else ""
    print(
        f"{dst}  画布 {width}x{height}  内容 {ob}  头校正 {ref_head()/hs:.3f}"
        f"  -> skin.json: w={width/2:g}, h={height/2:g}{warn}"
    )


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    prep(sys.argv[1], sys.argv[2])

#!/usr/bin/env python3
"""
从 SVG 源生成多尺寸 icon.ico。

用法（项目根目录）：
    python tools/make-icon.py
    python tools/make-icon.py --preview     # 额外输出各尺寸对照图

为什么不用现成的 ico 工具：
    完整版和小尺寸版是两张不同的 SVG。Pillow 的 ico 保存只能对一张图统一缩放，
    没法让 16/24 帧用另一套图案。所以这里先分别渲染两张 256 母版，
    再手工组装 ICO 目录（每帧存 PNG 数据，Vista 起支持）。

为什么用 Edge 而不是 cairosvg：
    本机没有 cairosvg/svglib，但 Windows 自带 Edge，headless 截图精度足够，
    且能正确处理渐变、透明度、圆角。

依赖：Python + Pillow，系统装有 Edge 或 Chrome。
"""

import io
import os
import shutil
import struct
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(ROOT, "src-tauri", "icons", "source")
OUT_ICO = os.path.join(ROOT, "src-tauri", "icons", "icon.ico")
PREVIEW = os.path.join(SRC_DIR, "icon-preview.png")

MASTER = 256

# 尺寸 -> 用哪张母版。"full" 给大尺寸，"small" 给 16/24。
PLAN = [
    (256, "full"),
    (128, "full"),
    (64, "full"),
    (48, "full"),
    (32, "full"),
    (24, "small"),
    (16, "small"),
]

EDGE_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
]


def find_browser():
    for p in EDGE_CANDIDATES:
        if os.path.exists(p):
            return p
    found = shutil.which("msedge") or shutil.which("chrome")
    if found:
        return found
    sys.exit("找不到 Edge / Chrome，无法渲染 SVG。请安装 Edge 或改用其他方式生成 ico。")


def render_master(browser, svg_path, out_png):
    """把 SVG 渲染成 MASTER×MASTER 的透明 PNG。"""
    with open(svg_path, encoding="utf-8") as fh:
        svg = fh.read()

    # 注入固定宽高，否则 SVG 作为独立文档时尺寸推断不可靠（会被裁切或留白）
    svg = svg.replace("<svg ", f'<svg width="{MASTER}" height="{MASTER}" ', 1)
    html = (
        "<!DOCTYPE html><html><head><meta charset='utf-8'><style>"
        "html,body{margin:0;padding:0;background:transparent;overflow:hidden}"
        "svg{display:block}</style></head><body>" + svg + "</body></html>"
    )

    work = tempfile.mkdtemp(prefix="peticon_")
    try:
        page = os.path.join(work, "page.html")
        with open(page, "w", encoding="utf-8") as fh:
            fh.write(html)

        cmd = [
            browser,
            "--headless=new",
            "--disable-gpu",
            "--hide-scrollbars",
            "--no-sandbox",
            "--no-first-run",
            "--no-default-browser-check",
            "--default-background-color=00000000",  # 关键：截图背景透明
            "--user-data-dir=" + os.path.join(work, "profile"),
            "--virtual-time-budget=2500",
            "--screenshot=" + out_png,
            f"--window-size={MASTER},{MASTER}",
            "file:///" + page.replace("\\", "/"),
        ]
        subprocess.run(cmd, capture_output=True, timeout=90)
        if not os.path.exists(out_png):
            sys.exit(f"渲染失败，没有产出 {out_png}")

        im = Image.open(out_png).convert("RGBA")
        # 校验：中心必须不透明（说明图形画上了），四角必须透明（说明背景是透明的）
        center_alpha = im.getpixel((MASTER // 2, MASTER // 2))[3]
        corner_alpha = im.getpixel((1, 1))[3]
        if center_alpha < 10:
            sys.exit("渲染结果中心是透明的，说明页面没画出来（检查 SVG 或浏览器参数）。")
        if corner_alpha > 10:
            sys.exit("渲染结果四角不透明，说明背景没透明（检查 --default-background-color）。")
        return im
    finally:
        shutil.rmtree(work, ignore_errors=True)


def build_ico(frames, out_path):
    """手工组装 ICO：每帧直接存 PNG 数据。"""
    header = struct.pack("<HHH", 0, 1, len(frames))
    offset = 6 + 16 * len(frames)
    entries = b""
    blobs = b""
    for size, data in frames:
        # 256 在目录里用 0 表示
        dim = 0 if size >= 256 else size
        entries += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
        blobs += data
    with open(out_path, "wb") as fh:
        fh.write(header + entries + blobs)


def write_preview(frame_imgs, out_path):
    """各尺寸对照图：小尺寸按整数倍放大，方便肉眼检查像素。"""
    zoom = {256: 1, 128: 1, 64: 2, 48: 2, 32: 4, 24: 5, 16: 8}
    pad = 12
    tiles = [(s, im, zoom.get(s, 1)) for s, im in frame_imgs]
    width = sum(max(im.width * z, 40) + pad for _, im, z in tiles) + pad
    height = max(im.height * z for _, im, z in tiles) + 50

    canvas = Image.new("RGBA", (width, height), (32, 32, 32, 255))
    draw = ImageDraw.Draw(canvas)
    x = pad
    for s, im, z in tiles:
        big = im.resize((im.width * z, im.height * z), Image.NEAREST)
        canvas.alpha_composite(big, (x, 20))
        draw.text((x, height - 22), f"{s}px x{z}", fill=(200, 200, 200, 255))
        x += big.width + pad
    canvas.save(out_path)


def main():
    browser = find_browser()
    print(f"[icon] 浏览器：{browser}")

    masters = {}
    for key, fname in (("full", "logo-full.svg"), ("small", "logo-small.svg")):
        path = os.path.join(SRC_DIR, fname)
        if not os.path.exists(path):
            sys.exit(f"缺少源文件：{path}")
        print(f"[icon] 渲染 {fname} …")
        tmp_png = os.path.join(tempfile.gettempdir(), f"peticon_{key}.png")
        masters[key] = render_master(browser, path, tmp_png)

    frames, frame_imgs = [], []
    for size, key in PLAN:
        im = masters[key].resize((size, size), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, format="PNG", optimize=True)
        frames.append((size, buf.getvalue()))
        frame_imgs.append((size, im))

    build_ico(frames, OUT_ICO)
    print(f"[icon] 已输出 {OUT_ICO}（{os.path.getsize(OUT_ICO) / 1024:.1f} KB，{len(frames)} 个尺寸）")

    if "--preview" in sys.argv:
        write_preview(frame_imgs, PREVIEW)
        print(f"[icon] 预览图 {PREVIEW}")


if __name__ == "__main__":
    main()

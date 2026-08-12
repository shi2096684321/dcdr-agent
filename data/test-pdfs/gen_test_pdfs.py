# -*- coding: utf-8 -*-
"""
生成两份 RAG OCR 测试用 PDF:
  1. 文字版 PDF (有文字层, PDFBox 能直接抽到) — 验证不误触发 OCR
  2. 扫描版 PDF (纯图片, 无文字层) — 验证 qwen3.5-ocr 降级

运行: python gen_test_pdfs.py
输出: ./text-version.pdf  ./scanned-version.pdf
"""
import os
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from PIL import Image, ImageDraw, ImageFont

OUT_DIR = os.path.dirname(os.path.abspath(__file__))
FONT_CN = "C:/Windows/Fonts/msyh.ttc"  # 微软雅黑, 支持中文

# ===== 测试文本 (模拟一份"作者简历"知识库文档) =====
TEXT = """\
作者简历

姓名: 石准
职业: 全栈工程师
擅长: Java / Spring Boot / WebFlux / RAG 知识库

项目经历:
2024 年主导开发 dcdr-agent 多模态智能对话助手,
集成文本对话、文生图、文生视频、语音合成、RAG 知识库检索等能力。
采用 Spring AI 框架, 支持 OpenAI / 通义千问 / 智谱 / DeepSeek 多模型热切换。

2025 年新增 V2.1 RAG 知识库模块, 支持 PDF / Word / TXT / HTML / Markdown,
使用 pgvector 向量检索, Top-K=5, min-score=0.3,
embedding 采用 DashScope text-embedding-v4, 1536 维向量。

2026 年接入 qwen3.5-ocr, 支持扫描版 PDF 文本提取,
PDFBox 文字版抽取失败时自动降级调多模态 OCR, 实现知识库对扫描件的支持。

联系方式: example@dcdr.com
"""


def gen_text_pdf(path):
    """文字版 PDF: 用 reportlab canvas 直接写文字, 有真实文字层"""
    pdfmetrics.registerFont(TTFont("MSYH", FONT_CN))
    c = canvas.Canvas(path, pagesize=A4)
    c.setFont("MSYH", 11)
    y = A4[1] - 60
    for line in TEXT.splitlines():
        c.drawString(60, y, line)
        y -= 18
        if y < 60:
            c.showPage()
            c.setFont("MSYH", 11)
            y = A4[1] - 60
    c.save()
    print(f"[OK] 文字版 PDF: {path}")


def gen_scanned_pdf(path):
    """
    扫描版 PDF: 把内容渲染成图片, 再把图片塞进 PDF, 没有文字层。
    PDFBox 的 PDFTextStripper 抽出来是空, 会触发 qwen3.5-ocr。
    模拟扫描件效果: 略微调灰背景 + 字体带轻微锯齿。
    """
    # A4 @ 150dpi ≈ 1240 x 1754
    W, H = 1240, 1754
    img = Image.new("RGB", (W, H), color=(250, 250, 248))  # 微泛黄背景模拟纸张
    draw = ImageDraw.Draw(img)
    font_title = ImageFont.truetype(FONT_CN, 48)
    font_body = ImageFont.truetype(FONT_CN, 24)

    lines = TEXT.splitlines()
    y = 80
    for i, line in enumerate(lines):
        font = font_title if i == 0 else font_body
        # 模拟扫描噪点: 给文字加轻微的灰色阴影
        draw.text((82, y + 2), line, fill=(180, 180, 180), font=font)  # 阴影
        draw.text((80, y), line, fill=(30, 30, 30), font=font)
        y += 56 if i == 0 else 38

    # 直接保存为 PDF (Pillow 的多页/单页 PDF, 图片型, 无文字层)
    img.save(path, "PDF", resolution=150.0)
    print(f"[OK] 扫描版 PDF: {path}")


if __name__ == "__main__":
    text_path = os.path.join(OUT_DIR, "text-version.pdf")
    scan_path = os.path.join(OUT_DIR, "scanned-version.pdf")
    gen_text_pdf(text_path)
    gen_scanned_pdf(scan_path)

    # 打印文件信息便于核对
    for p in (text_path, scan_path):
        size = os.path.getsize(p)
        print(f"  {os.path.basename(p)}: {size:,} 字节")

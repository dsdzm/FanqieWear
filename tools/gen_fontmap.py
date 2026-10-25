#!/usr/bin/env python3
"""
番茄小说网页字体映射生成工具。

背景：番茄把高频汉字替换成私有区码点（U+E000-U+F8FF），再用自定义 woff2 渲染。
本脚本把字体里的每个私有区字形渲染成位图，与 Noto Sans CJK 的常用字逐字比对，
再用匈牙利算法做全局最优一一分配，输出 { "0xe3e8": "只", ... } 供 App 离线解码。

用法：
    python3 tools/gen_fontmap.py                       # 自动从榜单页提取字体并生成
    python3 tools/gen_fontmap.py --url <woff2 地址>     # 指定字体文件
    python3 tools/gen_fontmap.py --out app/src/main/assets/fontmap.json

依赖：pip install fonttools brotli pillow numpy scipy
参考字体：/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc（Debian: fonts-noto-cjk）
"""
import argparse
import json
import re
import sys
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0 Safari/537.36")
REF_FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
SIZE = 64


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": "https://fanqienovel.com/"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def find_font_url() -> str:
    """从排行榜页面里抓 @font-face 的 woff2 地址。"""
    html = fetch("https://fanqienovel.com/rank").decode("utf-8", "ignore")
    m = re.search(r"awesome-font/c/([0-9a-f]+)\.woff2", html)
    if not m:
        sys.exit("未在页面中找到字体文件，请用 --url 手动指定")
    return f"https://lf6-awef.bytetos.com/obj/awesome-font/c/{m.group(1)}.woff2"


def build_candidates():
    """GB2312 汉字 + ASCII + 常用中文标点。"""
    chars = []
    for b1 in range(0xB0, 0xF8):
        for b2 in range(0xA1, 0xFF):
            try:
                chars.append(bytes([b1, b2]).decode("gb2312"))
            except UnicodeDecodeError:
                pass
    chars += [chr(c) for c in range(0x20, 0x7F)]
    chars += list("，。、；：？！“”‘’（）《》—…·【】")
    return list(dict.fromkeys(chars))


def render(font_path: str, chars, label=""):
    import numpy as np
    from PIL import Image, ImageDraw, ImageFont, ImageFilter

    font = ImageFont.truetype(font_path, 96)
    out = np.zeros((len(chars), SIZE, SIZE), dtype=np.float32)
    for i, ch in enumerate(chars):
        canvas = Image.new("L", (300, 300), 0)
        ImageDraw.Draw(canvas).text((96, 96), ch, font=font, fill=255)
        box = canvas.getbbox()
        if not box:
            continue
        glyph = canvas.crop(box)
        scale = (SIZE - 6) / max(glyph.size)
        glyph = glyph.resize((max(1, int(glyph.width * scale)), max(1, int(glyph.height * scale))),
                             Image.LANCZOS)
        tile = Image.new("L", (SIZE, SIZE), 0)
        tile.paste(glyph, ((SIZE - glyph.width) // 2, (SIZE - glyph.height) // 2))
        out[i] = np.asarray(tile.filter(ImageFilter.GaussianBlur(1.2)), dtype=np.float32) / 255.0
    print(f"  渲染 {len(chars)} 个字形（{label}）")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", help="woff2 字体地址，缺省自动从榜单页提取")
    ap.add_argument("--out", default="app/src/main/assets/fontmap.json")
    ap.add_argument("--ref", default=REF_FONT, help="参考字体路径")
    args = ap.parse_args()

    import numpy as np
    from fontTools.ttLib import TTFont
    from scipy.optimize import linear_sum_assignment

    url = args.url or find_font_url()
    print(f"字体：{url}")
    raw = fetch(url)
    open("/tmp/_fq.woff2", "wb").write(raw)
    font = TTFont("/tmp/_fq.woff2")
    font.flavor = None
    font.save("/tmp/_fq.otf")

    cmap = font.getBestCmap()
    pua = sorted(c for c in cmap if 0xE000 <= c <= 0xF8FF)
    print(f"私有区码点：{len(pua)}")
    if not pua:
        sys.exit("字体里没有私有区字符，可能番茄已换方案")

    cands = build_candidates()
    cand_vec = render(args.ref, cands, "参考字体")
    pua_vec = render("/tmp/_fq.otf", [chr(c) for c in pua], "番茄字体")

    def unit(v):
        v = v.reshape(len(v), -1)
        return v / (np.linalg.norm(v, axis=1, keepdims=True) + 1e-6)

    sim = unit(pua_vec) @ unit(cand_vec).T
    rows, cols = linear_sum_assignment(-sim)
    mapping = {hex(pua[i]): cands[j] for i, j in zip(rows, cols)}
    score = {hex(pua[i]): float(sim[i, j]) for i, j in zip(rows, cols)}

    low = sorted(((k, v, round(score[k], 3)) for k, v in mapping.items() if score[k] < 0.9),
                 key=lambda x: x[2])
    print(f"低置信条目 {len(low)} 个（建议人工核对）：")
    for k, v, s in low:
        print(f"   {k} -> {v}  ({s})")

    json.dump(mapping, open(args.out, "w"), ensure_ascii=False)
    print(f"已写入 {args.out}（{len(mapping)} 条，唯一字 {len(set(mapping.values()))} 个）")


if __name__ == "__main__":
    main()

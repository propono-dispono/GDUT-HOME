# -*- coding: utf-8 -*-
"""生成「学士帽」默认头像 PNG（6 种低饱和底色，按用户 ID 分派）。

设计说明：
    底圆 r=100，帽板为菱形（中心 100,84，半宽 50，半高 25），帽身是圆角矩形。
    颜色统一用驼色 #8A7355，底色 6 组轮换 —— 目的是让没有头像的用户不至于全都长一样。

产物：xiaojii-mp/assets/images/avatars/hat-1.png ~ hat-6.png（200×200）
"""

import os

from PIL import Image, ImageDraw

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "xiaojii-mp", "assets", "images", "avatars")
SIZE = 200
SS = 4  # 超采样倍数，保证边缘平滑
HAT = (138, 115, 85)  # #8A7355

BGS = [
    (240, 234, 225),  # 米白
    (230, 235, 229),  # 灰绿
    (231, 235, 239),  # 灰蓝
    (241, 234, 234),  # 藕粉
    (237, 235, 227),  # 沙色
    (233, 237, 236),  # 青灰
]


def draw_one(bg):
    w = SIZE * SS
    img = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    def s(v):
        return int(round(v * SS))

    d.ellipse([0, 0, w - 1, w - 1], fill=bg + (255,))

    # 帽身：下缘带圆角，压在菱形底边之下，形成「托着帽板」的观感
    d.rounded_rectangle([s(76), s(106), s(124), s(136)], radius=s(13), fill=HAT + (255,))

    # 帽板：菱形
    cx, cy, hw, hh = 100, 84, 50, 25
    d.polygon([(s(cx), s(cy - hh)), (s(cx + hw), s(cy)),
               (s(cx), s(cy + hh)), (s(cx - hw), s(cy))], fill=HAT + (255,))

    # 帽顶小扣：菱形正中一个浅色小圆，增加层次
    d.ellipse([s(cx - 6), s(cy - 6), s(cx + 6), s(cy + 6)], fill=bg + (255,))

    return img.resize((SIZE, SIZE), Image.LANCZOS)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for i, bg in enumerate(BGS, start=1):
        img = draw_one(bg)
        path = os.path.join(OUT_DIR, "hat-%d.png" % i)
        img.save(path, "PNG", optimize=True)
        print("saved", path, os.path.getsize(path), "B")
    print("done", len(BGS), "files ->", OUT_DIR)


if __name__ == "__main__":
    main()

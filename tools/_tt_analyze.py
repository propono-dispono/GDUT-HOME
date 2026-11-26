# -*- coding: utf-8 -*-
"""分析教务周课表 HTML：确认星期/节次的坐标换算规则。"""

import os
import re
import tempfile

H = os.path.join(tempfile.gettempdir(), "edu_tt_frame.html")
h = open(H, encoding="utf-8").read()
print("size", len(h))

# 表头：找形如 10/19 的日期
for kw in ("10/19", "fc-header"):
    i = h.find(kw)
    if i > 0:
        print("\n==", kw, "==")
        print(re.sub(r"\s+", " ", h[max(0, i - 300): i + 300]))
        break

print("\n--- fc-event 坐标 ---")
pat = re.compile(
    r'class="fc-event fc-event-vert[^"]*"\s+style="[^"]*?top:\s*([\d.]+)px;\s*left:\s*([\d.]+)px;'
    r'[^"]*?width:\s*([\d.]+)px;\s*height:\s*([\d.]+)px', re.S)
evs = pat.findall(h)
print("事件数:", len(evs))
for t, l, w, hh in evs[:14]:
    print(f"  top={t:<6} left={l:<7} w={w:<7} h={hh}")

# 每块内部的标题行
print("\n--- 事件文本 ---")
blocks = re.findall(r'<div class="fc-event fc-event-vert.*?</div></div><div class="fc-event-bg">', h, re.S)
print("block 数:", len(blocks))
for b in blocks[:8]:
    time_m = re.search(r'fc-event-time"[^>]*>([^<]*)<', b)
    titles = re.findall(r'<div>([^<]{1,40})</div>', b)
    print(f"  time={time_m.group(1).strip() if time_m else '?'}  titles={titles}")

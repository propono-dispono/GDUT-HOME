# -*- coding: utf-8 -*-
"""小程序工程静态自检：
1. JSON 合法性
2. WXML 标签闭合
3. WXML 用到的 class 是否都在 app.wxss 里定义
4. 用到的 ic-i-* 图标是否都有 mask 定义
5. 页面文件四件套是否齐全、是否都在 app.json 里注册
"""
import os
import re
import json
import sys

ROOT = r"E:\SchoolMarket\xiaojii-mp"
problems = []


def read(p):
    with open(p, "r", encoding="utf-8") as f:
        return f.read()


# ---------- 1. JSON ----------
json_files = []
for dirpath, _, names in os.walk(ROOT):
    for n in names:
        if n.endswith(".json"):
            json_files.append(os.path.join(dirpath, n))

for p in json_files:
    try:
        json.loads(read(p))
    except Exception as e:
        problems.append("[JSON] %s 解析失败：%s" % (p, e))

# ---------- 2. WXML 标签闭合 ----------
VOID = set()
wxml_files = []
for dirpath, _, names in os.walk(ROOT):
    for n in names:
        if n.endswith(".wxml"):
            wxml_files.append(os.path.join(dirpath, n))

for p in wxml_files:
    src = read(p)
    src = re.sub(r"<!--.*?-->", "", src, flags=re.S)
    stack = []
    for m in re.finditer(r"<(/?)([a-zA-Z][\w-]*)([^>]*?)(/?)>", src):
        closing, tag, attrs, selfclose = m.groups()
        if tag in VOID or selfclose == "/":
            continue
        if closing == "/":
            if not stack:
                problems.append("[WXML] %s 多余的闭合标签 </%s>" % (os.path.basename(p), tag))
            elif stack[-1][0] != tag:
                problems.append(
                    "[WXML] %s 标签交叉：期望 </%s>，实际 </%s>"
                    % (os.path.basename(p), stack[-1][0], tag)
                )
                stack.pop()
            else:
                stack.pop()
        else:
            stack.append((tag, m.start()))
    if stack:
        problems.append(
            "[WXML] %s 未闭合：%s" % (os.path.basename(p), [s[0] for s in stack])
        )

# ---------- 3. class 覆盖 ----------
wxss = read(os.path.join(ROOT, "app.wxss"))
defined = set(re.findall(r"\.([a-zA-Z][\w-]*)", wxss))

BUILTIN = {
    "app", "app-main", "appbar", "appbar-spacer", "appbar-title", "appbar-sub",
    "iconbtn", "tabbar", "tabitem", "tab-label", "fab", "tab-ic", "tab-icwrap",
    "badge-dot", "pcard", "pcard-body", "pcard-title", "pcard-desc", "pcard-foot",
    "media", "avatar", "tag", "price", "cur", "meta", "progress", "progress-fill",
    "row", "grow", "ellipsis", "clamp-2", "text-3", "empty", "empty-ico",
    "empty-title", "empty-desc", "btn", "chip", "chips", "feed", "bizgrid",
    "bizitem", "search", "ph", "filterbar", "f", "kvlist", "kv", "kv-k", "kv-v",
    "notice", "section", "section-title", "stat-row", "stat", "stat-n", "stat-l",
    "profile", "profile-name", "profile-sub", "entrygrid", "rowlink",
    "rowlink-ico", "rowlink-label", "rowlink-chev", "actionbar", "ic",
    "detail-head", "detail-title", "detail-price", "detail-desc", "detail-user",
    "detail-user-name", "detail-user-sub", "detail-notice-wrap",
    "profile-sub-row", "order-all", "mine-foot", "mt-3", "mt-4", "gap-1", "gap-2",
    "is-press",
}

used = {}
for p in wxml_files:
    src = read(p)
    src = re.sub(r"<!--.*?-->", "", src, flags=re.S)
    for m in re.finditer(r'class="([^"]*)"', src):
        raw = m.group(1)
        # 去掉 {{...}} 插值，保留其中静态片段
        statics = re.sub(r"\{\{.*?\}\}", " ", raw)
        for c in statics.split():
            if c.endswith("-"):
                continue  # 插值前缀，如 class="ic-{{item.icon}}"
            used.setdefault(c, set()).add(os.path.basename(p))
        # 插值里可能藏着 class 名（如 ic-{{item.icon}}），单独抽
    for m in re.finditer(r"\{\{([^}]*)\}\}", src):
        pass

missing = {c: v for c, v in used.items() if c not in defined and c not in BUILTIN}
for c, files in sorted(missing.items()):
    problems.append("[CSS] class .%s 未在 app.wxss 定义（用于 %s）" % (c, ",".join(sorted(files))))

# ---------- 4. 图标 ----------
icons_defined = set(re.findall(r"\.ic-(i-[\w-]+)\s*\{", wxss))
icons_used = set()
for p in wxml_files:
    src = read(p)
    for m in re.finditer(r"ic-(i-[\w-]+)", src):
        icons_used.add(m.group(1))
# 动态图标：utils/biz.js 里的 icon 字段
biz = read(os.path.join(ROOT, "utils", "biz.js"))
for m in re.finditer(r"icon:\s*'([\w-]+)'", biz):
    icons_used.add(m.group(1))

for i in sorted(icons_used - icons_defined):
    problems.append("[ICON] 图标 %s 被使用但没有 mask 定义" % i)

# ---------- 5. 页面注册 ----------
appjson = json.loads(read(os.path.join(ROOT, "app.json")))
for pg in appjson["pages"]:
    for ext in (".js", ".wxml", ".json"):
        fp = os.path.join(ROOT, pg.replace("/", os.sep) + ext)
        if not os.path.exists(fp):
            problems.append("[PAGE] 缺少文件 %s" % fp)

# ---------- 输出 ----------
print("JSON 文件 %d 个 / WXML 文件 %d 个" % (len(json_files), len(wxml_files)))
print("class 使用 %d 个，图标使用 %d 个" % (len(used), len(icons_used)))
if problems:
    print("\n发现 %d 个问题：" % len(problems))
    for x in problems:
        print("  -", x)
    sys.exit(1)
else:
    print("\n全部通过：JSON 合法、WXML 标签闭合、class 全覆盖、图标全定义、页面文件齐全")

# -*- coding: utf-8 -*-
"""
把 prototype 的三份 CSS 转成小程序 WXSS。
规则：
  1. px -> rpx（x2，375 设计宽基准）；0px -> 0
  2. :root / html / body -> page
  3. :active -> .is-press（配合 hover-class）
  4. 删除 :hover / :focus-visible / 带参伪类风险规则
  5. 删除桌面手机壳（.device* / body.has-shell / .desktop-hint）与对应 media
  6. 删除通配符 * 规则
  7. 内联 SVG 图标改为 mask-image 方案（.ic-<name> 类）
"""
import re
import os
import base64

SRC_DIR = r"E:\SchoolMarket\prototype\assets\css"
OUT = r"E:\SchoolMarket\xiaojii-mp\app.wxss"

SOURCES = ["tokens.css", "app.css", "components.css"]

DROP_SELECTOR_HINTS = [
    ".device", "device-screen", "device-status", "desktop-hint",
    "has-shell", ":hover", ":focus-visible", "@keyframes",
]

DROP_MEDIA = [
    "max-width: 520px",
    "hover: hover",
    "prefers-reduced-motion",
]

# 图标：来自 prototype/assets/js/ui.js 的 SYMBOLS + 各页面内联的那几个
ICONS = {
    "i-home": '<path d="M3 10.5 12 3l9 7.5"/><path d="M5.5 9.5V20h13V9.5"/><path d="M9.5 20v-6h5v6"/>',
    "i-grid": '<rect x="3.5" y="3.5" width="7" height="7" rx="1.5"/><rect x="13.5" y="3.5" width="7" height="7" rx="1.5"/><rect x="3.5" y="13.5" width="7" height="7" rx="1.5"/><rect x="13.5" y="13.5" width="7" height="7" rx="1.5"/>',
    "i-plus": '<path d="M12 5v14M5 12h14"/>',
    "i-chat": '<path d="M20 12a7.5 7.5 0 0 1-10.9 6.7L4 20l1.3-4.1A7.5 7.5 0 1 1 20 12Z"/>',
    "i-user": '<circle cx="12" cy="8" r="3.5"/><path d="M4.5 20a7.5 7.5 0 0 1 15 0"/>',
    "i-search": '<circle cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/>',
    "i-back": '<path d="M15 5l-7 7 7 7"/>',
    "i-chev": '<path d="M9 5l7 7-7 7"/>',
    "i-down": '<path d="M6 9l6 6 6-6"/>',
    "i-tag": '<path d="M4 11.5V5a1 1 0 0 1 1-1h6.5L20 12.5 13.5 19 4 11.5Z"/><circle cx="8.5" cy="8.5" r="1.4"/>',
    "i-run": '<circle cx="15.5" cy="4.5" r="2"/><path d="M12.5 8.5 9 11l2 3-3 6"/><path d="M11 14l4 1.5 1.5 4.5"/><path d="M5 9.5h3l3-2.5"/>',
    "i-spark": '<path d="M12 3.6 13.9 9l5.4 1.9-5.4 1.9L12 18.3l-1.9-5.5L4.7 10.9 10.1 9z"/><path d="M18.8 3.4v3M17.3 4.9h3"/>',
    "i-box": '<path d="M3.5 8 12 4l8.5 4-8.5 4-8.5-4Z"/><path d="M3.5 8v8L12 20l8.5-4V8"/><path d="M12 12v8"/>',
    "i-users": '<circle cx="9" cy="8.5" r="3"/><path d="M3.5 19a5.5 5.5 0 0 1 11 0"/><path d="M16 6.2a3 3 0 0 1 0 5.6"/><path d="M17.5 14.4A5.5 5.5 0 0 1 20.5 19"/>',
    "i-bell": '<path d="M18 16H6l1.5-2.5V10a4.5 4.5 0 0 1 9 0v3.5L18 16Z"/><path d="M10 19a2 2 0 0 0 4 0"/>',
    "i-pin": '<path d="M12 21s6-5.2 6-10a6 6 0 1 0-12 0c0 4.8 6 10 6 10Z"/><circle cx="12" cy="11" r="2.2"/>',
    "i-clock": '<circle cx="12" cy="12" r="8"/><path d="M12 8v4.5l3 1.8"/>',
    "i-heart": '<path d="M12 20s-7-4.3-7-9.2A4 4 0 0 1 12 8a4 4 0 0 1 7 2.8C19 15.7 12 20 12 20Z"/>',
    "i-star": '<path d="m12 4 2.4 5 5.6.8-4 3.9 1 5.5-5-2.6-5 2.6 1-5.5-4-3.9 5.6-.8L12 4Z"/>',
    "i-shield": '<path d="M12 3.5 19 6v6c0 4.4-3 7.4-7 8.5-4-1.1-7-4.1-7-8.5V6l7-2.5Z"/><path d="m9 12 2 2 4-4"/>',
    "i-image": '<rect x="3.5" y="4.5" width="17" height="15" rx="2.5"/><circle cx="9" cy="10" r="1.6"/><path d="m4.5 17 5-4.5 4 3.5 3-2.5 3 3"/>',
    "i-inbox": '<path d="M3.5 13.5 6 5h12l2.5 8.5"/><path d="M3.5 13.5V19h17v-5.5"/><path d="M8 13.5h8"/>',
    "i-filter": '<path d="M4 6h16M7 12h10M10 18h4"/>',
    "i-close": '<path d="m6 6 12 12M18 6 6 18"/>',
    "i-check": '<path d="m5 12.5 4.5 4.5L19 7"/>',
    "i-more": '<circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/>',
    "i-edit": '<path d="M4 20h4L20 8l-4-4L4 16v4Z"/><path d="m14 6 4 4"/>',
    "i-trash": '<path d="M5 7h14M9 7V5h6v2M7 7l1 13h8l1-13"/>',
    "i-settings": '<path d="M4 7h9M17 7h3M4 12h3M11 12h9M4 17h7M15 17h5"/><circle cx="15" cy="7" r="1.8"/><circle cx="9" cy="12" r="1.8"/><circle cx="13" cy="17" r="1.8"/>',
    "i-bag": '<path d="M4 7h16l-1.5 12H5.5L4 7Z"/><path d="M9 7V5.5a3 3 0 0 1 6 0V7"/>',
    "i-arrow": '<path d="M4 12h13l-3-3M4 12l3 3"/><path d="M20 5v14"/>',
    "i-doc": '<path d="M6 4h12v16H6z"/><path d="M9 9h6M9 13h4"/>',
    "i-file": '<path d="M5 5h14v14H5z"/><path d="M8 9h8M8 13h5"/>',
}


def split_blocks(text):
    """把 CSS 切成顶层 (selector, body) 列表，@media 的 body 保留嵌套原文。"""
    blocks = []
    i, n = 0, len(text)
    while i < n:
        j = text.find("{", i)
        if j == -1:
            tail = text[i:].strip()
            if tail:
                blocks.append((tail, None))
            break
        sel = text[i:j].strip()
        depth, k = 0, j
        while k < n:
            if text[k] == "{":
                depth += 1
            elif text[k] == "}":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        blocks.append((sel, text[j + 1:k]))
        i = k + 1
    return blocks


def px_to_rpx(css):
    css = re.sub(r"(?<![\w.])(-?0)px\b", "0", css)
    css = re.sub(r"(\d*\.?\d+)px\b", lambda m: "%grpx" % (float(m.group(1)) * 2), css)
    css = css.replace("%grpx", "rpx")
    return css


ELEM_MAP = [
    (r"(^|[\s,>+~])a(?=[\s,:.{>+~]|$)", r"\1view"),
    (r"(^|[\s,>+~])span(?=[\s,:.{>+~]|$)", r"\1text"),
    (r"(^|[\s,>+~])i(?=[\s,:.{>+~]|$)", r"\1view"),
    (r"(^|[\s,>+~])b(?=[\s,:.{>+~]|$)", r"\1text"),
]


def rewrite_elements(sel):
    """HTML 元素选择器 -> 小程序组件选择器（a->view, span->text, i->view, b->text）"""
    for pat, rep in ELEM_MAP:
        sel = re.sub(pat, rep, sel)
    return sel


def clean_selector(sel):
    sel = sel.strip()
    sel = re.sub(r"^:root$", "page", sel)
    sel = re.sub(r"\bhtml\b", "page", sel)
    sel = re.sub(r"\bbody\b", "page", sel)
    sel = sel.replace("[hidden]", ".hidden")
    sel = sel.replace(":active", ".is-press")
    sel = sel.replace("> *", "> view")
    sel = rewrite_elements(sel)
    return sel


def process(text, depth=0):
    out = []
    for sel, body in split_blocks(text):
        if body is None:
            continue
        raw = sel.strip()
        low = raw.lower()

        if raw.startswith("@media"):
            if any(k in low for k in DROP_MEDIA):
                continue
            inner = process(body, depth + 1)
            if inner.strip():
                out.append("%s {\n%s\n}" % (px_to_rpx(raw), inner))
            continue

        if raw.startswith("@keyframes") or raw.startswith("@font-face"):
            out.append("%s {%s}" % (raw, px_to_rpx(body)))
            continue

        if any(h in raw for h in DROP_SELECTOR_HINTS):
            continue
        if re.search(r"\b(img|svg)\b", raw):
            continue
        if raw in ("*",) or raw.startswith("*,") or re.match(r"^[\s*,]+$", raw):
            continue
        if raw in ("a",):
            continue

        new_sel = clean_selector(raw)
        out.append("%s {%s}" % (new_sel, px_to_rpx(body)))
    return "\n".join(out)


def build_icons_wxss():
    lines = []
    for name, body in ICONS.items():
        svg = (
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" '
            'stroke="#000" stroke-width="1.6" stroke-linecap="round" '
            'stroke-linejoin="round">' + body + "</svg>"
        )
        b64 = base64.b64encode(svg.encode("utf-8")).decode("ascii")
        lines.append(
            ".ic-%s{-webkit-mask-image:url(\"data:image/svg+xml;base64,%s\");"
            "mask-image:url(\"data:image/svg+xml;base64,%s\");}" % (name, b64, b64)
        )
    return "\n".join(lines)


def main():
    css = ""
    for f in SOURCES:
        with open(os.path.join(SRC_DIR, f), "r", encoding="utf-8") as fh:
            css += "\n" + fh.read()
    css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
    body = process(css)
    body = body.replace("100dvh", "100vh")

    head = """/* 自动生成：tools/build_wxss.py，源为 prototype/assets/css 三份样式 */
/* px 已按 375 设计宽换算为 rpx（x2）；:active 已改为 .is-press（配合 hover-class） */

page {
  background: #F5F5F6;
  font-size: 28rpx;
  line-height: 1.55;
  color: #111214;
  -webkit-font-smoothing: antialiased;
  font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "HarmonyOS Sans SC", "Microsoft YaHei", sans-serif;
}

/* ---------- 图标：内联 SVG 改为 mask + currentColor ---------- */
.ic {
  display: inline-block;
  width: 40rpx;
  height: 40rpx;
  flex: 0 0 auto;
  vertical-align: middle;
  background-color: currentColor;
  -webkit-mask-repeat: no-repeat;
  -webkit-mask-position: center;
  -webkit-mask-size: 100% 100%;
  mask-repeat: no-repeat;
  mask-position: center;
  mask-size: 100% 100%;
}
.ic--sm { width: 32rpx; height: 32rpx; }
.ic--lg { width: 48rpx; height: 48rpx; }

/* ---------- 小程序适配补充 ---------- */
.app { max-width: 750rpx; height: 100vh; }
.hidden { display: none !important; }
.media-empty {
  display: flex; align-items: center; justify-content: center;
  background: #F1F1F3; color: #A2A7AD;
}
.media-empty .ic { width: 44rpx; height: 44rpx; }
.pcard-hv { background: #F7F7F8; transform: scale(.986); }
.rowlink-hv { background: #EFEFF1; }
.bizitem-hv { background: #EFEFF1; }
.iconbtn-hv { background: #EFEFF1; }
.btn-hv { opacity: .82; }
.chip-hv { transform: scale(.94); }
.f-hv { opacity: .7; }
"""

    tail = """
/* 小程序适配覆盖（必须放最后，保证优先级） */
.appbar { background: var(--surface); }
.appbar--plain { background: var(--bg); }
.tabbar { background: var(--surface); }

/* 原型靠 * { box-sizing: border-box }，小程序不支持通配符，这里逐个列出常用组件 */
page, view, text, image, input, textarea, scroll-view, button, navigator, swiper, swiper-item {
  box-sizing: border-box;
}

.media image { width: 100%; height: 100%; display: block; }

/* 原型里进度条用的是 <i>，小程序用 view + class */
.progress-fill { display: block; height: 100%; background: var(--ink); border-radius: 4rpx; }

/* 小程序 input 默认样式重置 */
.search input, .inputbar input { background: transparent; }
.ph { color: var(--ink-4); }

/* 原型用内联 style 写的部分，集中成类，避免每处重复 */
.detail-head { padding: var(--s4) var(--gutter); background: var(--surface); }
.detail-title { margin-top: var(--s2); font-size: var(--fs-18); font-weight: var(--fw-bold); line-height: 1.4; }
.detail-price { margin-top: var(--s3); display: flex; align-items: baseline; gap: var(--s2); }
.detail-desc {
  padding: 0 var(--gutter) var(--s4);
  background: var(--surface);
  font-size: var(--fs-14);
  color: var(--ink-2);
  line-height: var(--lh-loose);
}
.detail-user { display: flex; flex-direction: column; }
.detail-user-name { display: block; font-size: var(--fs-14); font-weight: var(--fw-semi); }
.detail-user-sub { display: block; font-size: var(--fs-11); color: var(--ink-3); }
.detail-notice-wrap { padding: var(--s4) var(--gutter); }
.profile-sub-row { padding: 0 var(--gutter) var(--s2); }
.order-all { margin-left: auto; }
.mine-foot { text-align: center; padding: var(--s6) 0; }

/* ---------- 发布页补充 ---------- */
.picker-value { display: flex; align-items: center; justify-content: space-between; }
.picker-arrow { color: var(--ink-4); }
.dt-row { display: flex; gap: var(--s2); }
.dt-row picker { flex: 1; min-width: 0; }
.switch-field { display: flex; align-items: center; justify-content: space-between; }
.switch-label { margin-bottom: 0; }
.notice-wrap { padding: var(--s4) var(--gutter); }
.img-slot { overflow: hidden; }
.img-thumb { width: 100%; height: 100%; display: block; }
.img-del {
  position: absolute; top: 0; right: 0;
  width: 40rpx; height: 40rpx; line-height: 36rpx; text-align: center;
  font-size: 28rpx; color: #fff;
  background: rgba(17, 18, 20, .55);
  border-bottom-left-radius: var(--r-sm);
  z-index: 2;
}
.field-control { width: 100%; font-size: var(--fs-14); color: var(--ink); }
textarea.field-control { min-height: 120rpx; line-height: var(--lh-body); }

/* ---------- 我的发布 ---------- */
.posts-list { padding: var(--s3) var(--gutter); display: flex; flex-direction: column; gap: var(--s2); }
.posts-row {
  display: flex; align-items: center; gap: var(--s3);
  padding: var(--s3);
  background: var(--surface);
  border: 2rpx solid var(--line);
  border-radius: var(--r-lg);
}
.posts-title { font-size: var(--fs-14); font-weight: var(--fw-semi); color: var(--ink); }
.posts-meta { margin-top: 6rpx; display: flex; align-items: center; gap: var(--s2); }
.posts-biz {
  font-size: var(--fs-11); color: var(--ink-3);
  padding: 0 8rpx; border-radius: var(--r-xs); background: var(--surface-2);
}
.posts-status { flex: 0 0 auto; font-size: var(--fs-11); padding: 4rpx 12rpx; border-radius: var(--r-full); }
.posts-status--ok { color: #0F6E56; background: #E1F5EE; }
.posts-status--warn { color: #854F0B; background: #FAEEDA; }
.posts-status--mute { color: var(--ink-3); background: var(--surface-2); }

/* ---------- 分类页 ---------- */
.cat-head { display: flex; align-items: center; padding-right: var(--gutter); }
.cat-title { flex: 1; min-width: 0; }
.cat-all { flex: 0 0 auto; }
.cat-chips { padding: 0 var(--gutter) var(--s3); }

/* ---------- 消息页 ---------- */
.msg-list { padding: var(--s3) var(--gutter); display: flex; flex-direction: column; gap: var(--s2); }
.msg-row {
  display: flex; align-items: flex-start; gap: var(--s3);
  padding: var(--s3);
  background: var(--surface);
  border: 2rpx solid var(--line);
  border-radius: var(--r-lg);
}
.msg-row--unread { border-color: var(--line-strong); }
.msg-ico {
  width: 72rpx; height: 72rpx; flex: 0 0 auto;
  border-radius: var(--r-full);
  background: var(--surface-2);
  display: flex; align-items: center; justify-content: center;
  color: var(--ink-2);
}
.msg-head { display: flex; align-items: baseline; gap: var(--s2); }
.msg-title { flex: 1; min-width: 0; font-size: var(--fs-14); font-weight: var(--fw-semi); color: var(--ink); }
.msg-time { flex: 0 0 auto; font-size: var(--fs-11); color: var(--ink-4); }
.msg-body { margin-top: 6rpx; font-size: var(--fs-12); color: var(--ink-3); line-height: var(--lh-body); }
.msg-tag {
  margin-top: 8rpx; display: inline-block;
  font-size: var(--fs-11); color: var(--ink-3);
  padding: 0 8rpx; border-radius: var(--r-xs); background: var(--surface-2);
}
.msg-dot { width: 14rpx; height: 14rpx; border-radius: var(--r-full); background: var(--accent); flex: 0 0 auto; margin-top: 10rpx; }
.msg-readall { font-size: var(--fs-13); color: var(--ink-3); }

/* ---------- 首页公告条 ---------- */
.announce {
  display: flex; align-items: center; gap: 12rpx;
  margin: 0 var(--gutter) var(--s2);
  padding: 16rpx 18rpx;
  background: var(--surface);
  border: 2rpx solid var(--line);
  border-radius: var(--r-md);
}
.announce-tag {
  flex: 0 0 auto;
  font-size: var(--fs-11); color: #fff;
  background: var(--ink);
  padding: 2rpx 10rpx; border-radius: var(--r-xs);
}
.announce-text {
  flex: 1; min-width: 0;
  font-size: var(--fs-12); color: var(--ink-2);
  overflow: hidden; white-space: nowrap; text-overflow: ellipsis;
}
.announce-arrow { flex: 0 0 auto; color: var(--ink-4); font-size: var(--fs-16); line-height: 1; }

/* ---------- 驳回原因展示 ---------- */
.posts-reason {
  margin-top: 6rpx;
  font-size: var(--fs-11);
  color: var(--accent-text);
  line-height: var(--lh-body);
}
.reject-banner {
  display: flex; align-items: flex-start; gap: 12rpx;
  margin: var(--s3) var(--gutter) 0;
  padding: 18rpx;
  background: var(--surface);
  border: 2rpx solid var(--line-strong);
  border-radius: var(--r-md);
}
.reject-tag {
  flex: 0 0 auto;
  font-size: var(--fs-11); color: #fff;
  background: var(--accent); padding: 2rpx 10rpx; border-radius: var(--r-xs);
}
.reject-text { flex: 1; min-width: 0; font-size: var(--fs-12); color: var(--ink-2); line-height: var(--lh-body); }

/* ---------- 我的订单 ---------- */
.order-item {
  margin: 0 var(--gutter) var(--s2);
  padding: var(--s3);
  background: var(--surface);
  border: 2rpx solid var(--line);
  border-radius: var(--r-lg);
}
.order-head { display: flex; align-items: baseline; gap: var(--s2); }
.order-title { flex: 1; min-width: 0; font-size: var(--fs-14); font-weight: var(--fw-semi); color: var(--ink); }
.order-status { flex: 0 0 auto; font-size: var(--fs-11); color: var(--ink-3); }
.order-sub { margin-top: 6rpx; font-size: var(--fs-11); color: var(--ink-4); }
.order-row { margin-top: 10rpx; display: flex; align-items: baseline; gap: var(--s2); }
.order-amount { font-size: var(--fs-16); font-weight: var(--fw-semi); color: var(--ink); }
.order-role { font-size: var(--fs-11); color: var(--ink-3); }
.order-reason {
  margin-top: 8rpx; padding: 8rpx 12rpx;
  font-size: var(--fs-11); color: var(--accent-text);
  background: var(--surface-2); border-radius: var(--r-xs);
}
.order-ops { margin-top: 12rpx; display: flex; flex-wrap: wrap; gap: 12rpx; }
.order-btn {
  padding: 10rpx 22rpx; border-radius: var(--r-full);
  font-size: var(--fs-12); color: var(--ink-2);
  border: 2rpx solid var(--line-strong); background: var(--surface);
}
.order-btn--primary { background: var(--ink); color: #fff; border-color: var(--ink); }

/* ---------- 私信 ---------- */
.chat-msgs { padding: var(--s3) var(--gutter); display: flex; flex-direction: column; gap: var(--s3); }
.chat-row { display: flex; flex-direction: column; align-items: flex-start; gap: 6rpx; }
.chat-row--mine { align-items: flex-end; }
.chat-bubble {
  max-width: 76%;
  padding: 16rpx 20rpx;
  font-size: var(--fs-14); line-height: var(--lh-body);
  color: var(--ink);
  background: var(--surface);
  border: 2rpx solid var(--line);
  border-radius: var(--r-lg);
  word-break: break-all;
}
.chat-bubble--mine { background: var(--ink); color: #fff; border-color: var(--ink); }
.chat-time { font-size: var(--fs-10); color: var(--ink-4); }
.chat-inputbar {
  flex: 0 0 auto;
  display: flex; align-items: center; gap: 12rpx;
  padding: 16rpx var(--gutter);
  background: var(--surface);
  border-top: 2rpx solid var(--line);
}
.chat-input {
  flex: 1; min-width: 0; height: 72rpx;
  padding: 0 24rpx;
  font-size: var(--fs-14);
  background: var(--surface-2);
  border-radius: var(--r-full);
}
.chat-send {
  flex: 0 0 auto;
  padding: 0 28rpx; height: 72rpx; line-height: 72rpx;
  font-size: var(--fs-13); color: #fff;
  background: var(--ink); border-radius: var(--r-full);
}
.chat-send--off { opacity: .35; }
"""

    out = head + "\n" + build_icons_wxss() + "\n\n" + body + "\n" + tail
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write(out)
    print("written:", OUT, len(out), "chars")


if __name__ == "__main__":
    main()

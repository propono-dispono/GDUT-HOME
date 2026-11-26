/* ============================================================
   校集 · UI 层  ui.js
   依赖：tokens.css / app.css / components.css
   提供：图标 sprite、手机壳、5 Tab、Toast、卡片渲染、信息流渲染
   ⚠️ 本文件不写任何颜色值。
   ============================================================ */
window.UI = (function () {
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* ---------------- 图标 sprite ---------------- */
  var SYMBOLS = {
    'i-home':  '<path d="M3 10.5 12 3l9 7.5"/><path d="M5.5 9.5V20h13V9.5"/><path d="M9.5 20v-6h5v6"/>',
    'i-grid':  '<rect x="3.5" y="3.5" width="7" height="7" rx="1.5"/><rect x="13.5" y="3.5" width="7" height="7" rx="1.5"/><rect x="3.5" y="13.5" width="7" height="7" rx="1.5"/><rect x="13.5" y="13.5" width="7" height="7" rx="1.5"/>',
    'i-plus':  '<path d="M12 5v14M5 12h14"/>',
    'i-chat':  '<path d="M20 12a7.5 7.5 0 0 1-10.9 6.7L4 20l1.3-4.1A7.5 7.5 0 1 1 20 12Z"/>',
    'i-user':  '<circle cx="12" cy="8" r="3.5"/><path d="M4.5 20a7.5 7.5 0 0 1 15 0"/>',
    'i-search':'<circle cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/>',
    'i-back':  '<path d="M15 5l-7 7 7 7"/>',
    'i-chev':  '<path d="M9 5l7 7-7 7"/>',
    'i-down':  '<path d="M6 9l6 6 6-6"/>',
    'i-tag':   '<path d="M4 11.5V5a1 1 0 0 1 1-1h6.5L20 12.5 13.5 19 4 11.5Z"/><circle cx="8.5" cy="8.5" r="1.4"/>',
    'i-run':   '<circle cx="15.5" cy="4.5" r="2"/><path d="M12.5 8.5 9 11l2 3-3 6"/><path d="M11 14l4 1.5 1.5 4.5"/><path d="M5 9.5h3l3-2.5"/>',
    'i-spark': '<path d="M12 3.6 13.9 9l5.4 1.9-5.4 1.9L12 18.3l-1.9-5.5L4.7 10.9 10.1 9z"/><path d="M18.8 3.4v3M17.3 4.9h3"/>',
    'i-box':   '<path d="M3.5 8 12 4l8.5 4-8.5 4-8.5-4Z"/><path d="M3.5 8v8L12 20l8.5-4V8"/><path d="M12 12v8"/>',
    'i-users': '<circle cx="9" cy="8.5" r="3"/><path d="M3.5 19a5.5 5.5 0 0 1 11 0"/><path d="M16 6.2a3 3 0 0 1 0 5.6"/><path d="M17.5 14.4A5.5 5.5 0 0 1 20.5 19"/>',
    'i-bell':  '<path d="M18 16H6l1.5-2.5V10a4.5 4.5 0 0 1 9 0v3.5L18 16Z"/><path d="M10 19a2 2 0 0 0 4 0"/>',
    'i-pin':   '<path d="M12 21s6-5.2 6-10a6 6 0 1 0-12 0c0 4.8 6 10 6 10Z"/><circle cx="12" cy="11" r="2.2"/>',
    'i-clock': '<circle cx="12" cy="12" r="8"/><path d="M12 8v4.5l3 1.8"/>',
    'i-heart': '<path d="M12 20s-7-4.3-7-9.2A4 4 0 0 1 12 8a4 4 0 0 1 7 2.8C19 15.7 12 20 12 20Z"/>',
    'i-star':  '<path d="m12 4 2.4 5 5.6.8-4 3.9 1 5.5-5-2.6-5 2.6 1-5.5-4-3.9 5.6-.8L12 4Z"/>',
    'i-shield':'<path d="M12 3.5 19 6v6c0 4.4-3 7.4-7 8.5-4-1.1-7-4.1-7-8.5V6l7-2.5Z"/><path d="m9 12 2 2 4-4"/>',
    'i-image': '<rect x="3.5" y="4.5" width="17" height="15" rx="2.5"/><circle cx="9" cy="10" r="1.6"/><path d="m4.5 17 5-4.5 4 3.5 3-2.5 3 3"/>',
    'i-inbox': '<path d="M3.5 13.5 6 5h12l2.5 8.5"/><path d="M3.5 13.5V19h17v-5.5"/><path d="M8 13.5h8"/>',
    'i-filter':'<path d="M4 6h16M7 12h10M10 18h4"/>',
    'i-close': '<path d="m6 6 12 12M18 6 6 18"/>',
    'i-check': '<path d="m5 12.5 4.5 4.5L19 7"/>',
    'i-more':  '<circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/>',
    'i-edit':  '<path d="M4 20h4L20 8l-4-4L4 16v4Z"/><path d="m14 6 4 4"/>',
    'i-trash': '<path d="M5 7h14M9 7V5h6v2M7 7l1 13h8l1-13"/>'
  };

  function icon(name, cls) {
    var body = SYMBOLS[name] || SYMBOLS['i-box'];
    return '<svg class="ic ' + (cls || '') + '" aria-hidden="true" viewBox="0 0 24 24">' + body + '</svg>';
  }

  function sprite() {
    if (document.getElementById('ui-sprite')) return;
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.id = 'ui-sprite';
    svg.setAttribute('width', '0');
    svg.setAttribute('height', '0');
    svg.setAttribute('aria-hidden', 'true');
    svg.style.position = 'absolute';
    var html = '';
    Object.keys(SYMBOLS).forEach(function (k) {
      html += '<symbol id="' + k + '" viewBox="0 0 24 24">' + SYMBOLS[k] + '</symbol>';
    });
    svg.innerHTML = html;
    document.body.appendChild(svg);
  }

  /* ---------------- 手机壳 ---------------- */
  function shell() {
    sprite();
    if (document.querySelector('.device')) return;
    var app = document.querySelector('.app');
    if (!app) return;

    var device = document.createElement('div');
    device.className = 'device';
    var screen = document.createElement('div');
    screen.className = 'device-screen';

    var d = new Date();
    var hh = String(d.getHours()).padStart(2, '0');
    var mm = String(d.getMinutes()).padStart(2, '0');
    var status = document.createElement('div');
    status.className = 'device-status';
    status.innerHTML = '<span>' + hh + ':' + mm + '</span><span class="sig">●●● ▮</span>';

    screen.appendChild(status);
    screen.appendChild(app);
    device.appendChild(screen);
    document.body.appendChild(device);
    document.body.classList.add('has-shell');
  }

  /* ---------------- 5 Tab ---------------- */
  var TABS = [
    { k: 'home',     t: '首页', i: 'i-home', href: 'home.html' },
    { k: 'category', t: '分类', i: 'i-grid', href: 'category.html' },
    { k: 'publish',  t: '发布', i: 'i-plus', href: 'publish.html', fab: true },
    { k: 'messages', t: '消息', i: 'i-chat', href: 'messages.html' },
    { k: 'mine',     t: '我的', i: 'i-user', href: 'mine.html' }
  ];

  function tabbar(active) {
    var el = document.getElementById('tabbar');
    if (!el) return;
    el.classList.add('tabbar');
    el.setAttribute('aria-label', '主导航');
    el.innerHTML = TABS.map(function (t) {
      var on = t.k === active;
      if (t.fab) {
        return '<a class="tabitem tabitem--fab' + (on ? ' tabitem--on' : '') + '" href="' + t.href + '" aria-label="发布">'
          + '<span class="fab">' + icon(t.i) + '</span>'
          + '<span class="tab-label">' + t.t + '</span></a>';
      }
      return '<a class="tabitem' + (on ? ' tabitem--on' : '') + '" href="' + t.href + '"'
        + (on ? ' aria-current="page"' : '') + '>'
        + '<span class="tab-icwrap">' + icon(t.i, 'tab-ic')
        + (t.k === 'messages' ? '<i class="badge-dot"></i>' : '')
        + '</span><span class="tab-label">' + t.t + '</span></a>';
    }).join('');
  }

  /* ---------------- Toast ---------------- */
  var toastEl, toastTimer;
  function toast(msg) {
    var host = document.querySelector('.app') || document.body;
    if (!toastEl) {
      toastEl = document.createElement('div');
      toastEl.className = 'toast';
      host.appendChild(toastEl);
    }
    toastEl.textContent = msg;
    toastEl.classList.add('toast--show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.classList.remove('toast--show'); }, 1800);
  }

  /* ---------------- 媒体占位 ---------------- */
  function media(name, cls) {
    var inner = '';
    if (name) {
      inner = '<img src="../assets/img/items/' + name + '.jpg" alt="" loading="lazy"'
        + ' onload="this.classList.add(\'is-loaded\')" onerror="this.remove()">';
    }
    return '<div class="media ' + (cls || '') + '">' + inner + '</div>';
  }

  function priceTag(p) {
    if (p.price == null) return '<span class="price--none">线下沟通</span>';
    var unit = p.priceUnit ? ' <span class="meta" style="margin:0">' + esc(p.priceUnit) + '</span>' : '';
    return '<span class="price"><span class="cur">¥</span>' + p.price + unit + '</span>';
  }

  /* ---------------- 卡片：4 种形态 ---------------- */
  function card(p) {
    var biz = window.BIZ[p.biz] || {};
    var form = biz.form || 'item';
    var href = 'detail.html?type=' + p.biz + '&id=' + p.id;

    if (form === 'person') {
      return '<a class="pcard" href="' + href + '">'
        + '<span class="avatar avatar--lg">' + esc(p.user) + '</span>'
        + '<div class="pcard-body">'
        + '<div class="pcard-title ellipsis">' + esc(p.title) + '</div>'
        + '<div class="pcard-desc ellipsis">' + esc(p.desc) + '</div>'
        + '<div class="pcard-foot">' + priceTag(p) + '<span class="meta">' + esc(p.meta) + '</span></div>'
        + '</div></a>';
    }

    if (form === 'notice') {
      return '<a class="pcard" href="' + href + '">'
        + media(p.cover, 'media--sm')
        + '<div class="pcard-body">'
        + '<div class="row gap-1">'
        + '<span class="tag tag--outline">' + esc(p.tag || '失物') + '</span>'
        + '<span class="text-3">' + esc(p.meta) + '</span></div>'
        + '<div class="pcard-title ellipsis">' + esc(p.title) + '</div>'
        + '<div class="pcard-desc ellipsis">' + esc(p.desc) + '</div>'
        + '</div></a>';
    }

    if (form === 'task') {
      var pg = p.progress || [1, 1];
      var pct = Math.max(4, Math.round(pg[0] / pg[1] * 100));
      return '<a class="pcard" href="' + href + '">'
        + media(p.cover)
        + '<div class="pcard-body">'
        + '<div class="pcard-title clamp-2">' + esc(p.title) + '</div>'
        + '<div class="pcard-desc ellipsis">' + esc(p.desc) + '</div>'
        + '<div class="progress"><i style="width:' + pct + '%"></i></div>'
        + '<div class="pcard-foot">' + priceTag(p) + '<span class="meta">' + esc(p.meta) + '</span></div>'
        + '</div></a>';
    }

    /* item */
    return '<a class="pcard" href="' + href + '">'
      + media(p.cover)
      + '<div class="pcard-body">'
      + '<div class="pcard-title clamp-2">' + esc(p.title) + '</div>'
      + '<div class="pcard-desc ellipsis">' + esc(p.desc) + '</div>'
      + '<div class="pcard-foot">' + priceTag(p) + '<span class="meta">' + esc(p.meta) + '</span></div>'
      + '</div></a>';
  }

  /* ---------------- 信息流 ---------------- */
  function renderFeed(mount, opt) {
    opt = opt || {};
    var biz = opt.biz || 'ALL';
    var kw = (opt.kw || '').trim();
    var list = window.MOCK.posts.filter(function (p) {
      if (biz !== 'ALL' && p.biz !== biz) return false;
      if (kw && p.title.indexOf(kw) < 0 && p.desc.indexOf(kw) < 0) return false;
      return true;
    });

    if (!list.length) {
      mount.innerHTML = '<div class="empty">'
        + '<div class="empty-ico">' + icon('i-inbox', 'ic--lg') + '</div>'
        + '<div class="empty-title">没有找到相关内容</div>'
        + '<div class="empty-desc">换个关键词，或者去发布一条</div>'
        + '<button class="btn btn--primary btn--sm" onclick="location.href=\'publish.html\'">去发布</button>'
        + '</div>';
      return 0;
    }
    mount.innerHTML = list.map(card).join('');
    return list.length;
  }

  /* ---------------- 会话 ---------------- */
  function renderSessions(mount) {
    mount.innerHTML = window.MOCK.sessions.map(function (s) {
      return '<a class="rowlink" href="chat.html?id=' + s.id + '">'
        + '<span class="avatar">' + esc(s.name.charAt(0)) + '</span>'
        + '<span class="grow">'
        + '<span class="row" style="gap:8px">'
        + '<b style="font-size:var(--fs-14);font-weight:600">' + esc(s.name) + '</b>'
        + '<span class="meta" style="margin-left:auto">' + esc(s.time) + '</span></span>'
        + '<span class="text-3 ellipsis" style="display:block">' + esc(s.last) + '</span>'
        + '</span>'
        + (s.unread ? '<span class="badge-count">' + s.unread + '</span>' : '')
        + '</a>';
    }).join('');
  }

  /* ---------------- 订单 ---------------- */
  function renderOrders(mount, opt) {
    opt = opt || {};
    var role = opt.role || 'BUYER';
    var status = opt.status || 'ALL';
    var list = window.MOCK.orders.filter(function (o) {
      return o.role === role && (status === 'ALL' || o.status === status);
    });
    if (!list.length) {
      mount.innerHTML = '<div class="empty">'
        + '<div class="empty-ico">' + icon('i-inbox', 'ic--lg') + '</div>'
        + '<div class="empty-title">暂无订单</div>'
        + '<div class="empty-desc">这里会显示你的买卖记录</div></div>';
      return;
    }
    mount.innerHTML = list.map(function (o) {
      var st = window.STATUS[o.status] || { text: o.status, cls: '' };
      return '<a class="pcard" href="order-detail.html?id=' + o.id + '">'
        + media(null, 'media--sm')
        + '<div class="pcard-body">'
        + '<div class="pcard-title ellipsis">' + esc(o.title) + '</div>'
        + '<div class="pcard-desc ellipsis">' + esc(o.no) + ' · ' + esc(o.other) + '</div>'
        + '<div class="pcard-foot"><span class="price price--sm"><span class="cur">¥</span>' + o.amount + '</span>'
        + '<span class="meta"><span class="status ' + st.cls + '">' + esc(st.text) + '</span></span></div>'
        + '</div></a>';
    }).join('');
  }

  /* ---------------- 交互增强：涟漪 / 凸起按钮特效 ---------------- */
  var RIPPLE_SEL = '.btn, .chip, .segmented button';

  document.addEventListener('pointerdown', function (e) {
    /* 涟漪 */
    var t = e.target.closest(RIPPLE_SEL);
    if (t && !t.disabled) {
      t.classList.add('ripple-host');
      var r = t.getBoundingClientRect();
      var d = Math.max(r.width, r.height);
      var ink = document.createElement('span');
      ink.className = 'ripple-ink';
      ink.style.width = ink.style.height = d + 'px';
      ink.style.left = (e.clientX - r.left - d / 2) + 'px';
      ink.style.top = (e.clientY - r.top - d / 2) + 'px';
      t.appendChild(ink);
      setTimeout(function () { if (ink.parentNode) ink.remove(); }, 620);
    }

    /* 中间凸起按钮：环形扩散 + 图标旋转 */
    var fab = e.target.closest('.tabitem--fab');
    if (fab) {
      fab.classList.remove('is-press');
      void fab.offsetWidth;          /* 强制重排，保证连点也能重播动画 */
      fab.classList.add('is-press');
      setTimeout(function () { fab.classList.remove('is-press'); }, 640);
    }
  });

  /* 全局 data-toast */
  document.addEventListener('click', function (e) {
    var t = e.target.closest('[data-toast]');
    if (!t) return;
    e.preventDefault();
    toast(t.dataset.toast);
  });

  return {
    $: $, $$: $$, esc: esc,
    icon: icon, sprite: sprite, shell: shell, tabbar: tabbar, toast: toast,
    media: media, card: card, renderFeed: renderFeed,
    renderSessions: renderSessions, renderOrders: renderOrders
  };
})();

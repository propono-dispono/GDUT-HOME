/* 校集管理后台 · 纯静态版（零构建）
   API 指向本地后端；token 存 localStorage。
   注意：本文件只依赖原生 fetch，不引入任何框架。 */

const API = 'http://127.0.0.1:8080/api';
let TOKEN = localStorage.getItem('xj_admin_token') || '';
let CURRENT = 'dashboard';

/* ---------------- 基础工具 ---------------- */
function $(s) { return document.querySelector(s); }
function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}
let toastTimer;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('on');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('on'), 2000);
}

/* ---------------- 请求层 ---------------- */
async function api(path, opt) {
  opt = opt || {};
  const headers = { 'Content-Type': 'application/json' };
  if (TOKEN) headers.Authorization = 'Bearer ' + TOKEN;
  let res;
  try {
    res = await fetch(API + path, {
      method: opt.method || 'GET',
      headers,
      body: opt.body ? JSON.stringify(opt.body) : undefined
    });
  } catch (e) {
    throw new Error('连不上后端，请确认 8080 已启动');
  }
  let j = null;
  try { j = await res.json(); } catch (e) { throw new Error('响应解析失败'); }
  if (j && j.code === 0) return j.data;
  if (j && (j.code === 401 || j.code === 1003)) {
    TOKEN = '';
    localStorage.removeItem('xj_admin_token');
    showLogin();
    throw new Error('登录已失效，请重新登录');
  }
  throw new Error((j && j.msg) || ('请求失败 ' + (j && j.code)));
}

/* ---------------- 弹窗 ---------------- */
let modalResolve = null;
function askReason(opts) {
  opts = opts || {};
  $('#modalTitle').textContent = opts.title || '请填写原因';
  $('#modalDesc').textContent = opts.desc || '';
  const inp = $('#modalInput');
  inp.value = '';
  inp.placeholder = opts.placeholder || '请填写原因（会展示给用户）';
  $('#modal').hidden = false;
  inp.focus();
  return new Promise(resolve => { modalResolve = resolve; });
}
function closeModal(v) {
  $('#modal').hidden = true;
  if (modalResolve) { modalResolve(v); modalResolve = null; }
}

/* ---------------- 登录 ---------------- */
function showLogin() {
  $('#app').hidden = true;
  $('#login').hidden = false;
}
function showApp() {
  $('#login').hidden = true;
  $('#app').hidden = false;
}
async function doLogin() {
  const username = $('#username').value.trim();
  const password = $('#password').value;
  $('#loginErr').textContent = '';
  if (!username || !password) { $('#loginErr').textContent = '请输入账号和密码'; return; }
  $('#loginBtn').disabled = true;
  try {
    const d = await api('/admin/auth/login', { method: 'POST', body: { username, password } });
    TOKEN = d.token;
    localStorage.setItem('xj_admin_token', TOKEN);
    showApp();
    go('dashboard');
    toast('登录成功');
  } catch (e) {
    $('#loginErr').textContent = e.message;
  } finally {
    $('#loginBtn').disabled = false;
  }
}

/* ---------------- 导航 ---------------- */
const TITLES = { dashboard: '数据概览', verify: '认证审核', post: '内容审核', user: '用户管理' };
function go(page) {
  CURRENT = page;
  document.querySelectorAll('.nav-item[data-page]').forEach(a => {
    a.classList.toggle('nav-on', a.dataset.page === page);
  });
  $('#pageTitle').textContent = TITLES[page] || page;
  render();
}
async function refreshBadges(o) {
  try {
    const d = o || await api('/admin/dashboard/overview');
    setBadge('#badgeVerify', d.pendingVerify);
    setBadge('#badgePost', d.pendingPost);
    return d;
  } catch (e) { return null; }
}
function setBadge(sel, n) {
  const e = $(sel);
  if (!e) return;
  if (n > 0) { e.textContent = n; e.classList.add('on'); }
  else { e.textContent = ''; e.classList.remove('on'); }
}

/* ---------------- 渲染入口 ---------------- */
async function render() {
  const v = $('#view');
  v.innerHTML = '<div class="loading">加载中…</div>';
  try {
    if (CURRENT === 'dashboard') await renderDashboard(v);
    else if (CURRENT === 'verify') await renderVerify(v);
    else if (CURRENT === 'post') await renderPost(v);
    else if (CURRENT === 'user') await renderUser(v);
  } catch (e) {
    v.innerHTML = '';
    const box = el('div', 'empty');
    box.appendChild(el('p', null, e.message));
    v.appendChild(box);
  }
}

/* ---------------- 概览 ---------------- */
async function renderDashboard(v) {
  const d = await api('/admin/dashboard/overview');
  refreshBadges(d);
  const items = [
    ['待审核认证', d.pendingVerify, 'warn'],
    ['待审核内容', d.pendingPost, 'warn'],
    ['展示中内容', d.onlinePost, 'ok'],
    ['今日新增内容', d.todayPost, ''],
    ['内容总数', d.totalPost, ''],
    ['用户总数', d.totalUser, ''],
    ['已认证用户', d.verifiedUser, 'ok'],
    ['受限账号', d.bannedUser, '']
  ];
  v.innerHTML = '';
  const grid = el('div', 'stats');
  items.forEach(([label, num, cls]) => {
    const s = el('div', 'stat' + (cls ? ' stat-' + cls : ''));
    s.appendChild(el('div', 'stat-l', label));
    s.appendChild(el('div', 'stat-n', num));
    grid.appendChild(s);
  });
  v.appendChild(grid);

  if (d.pendingVerify || d.pendingPost) {
    const tip = el('div', 'empty');
    tip.style.padding = '24px 0 0';
    tip.style.textAlign = 'left';
    tip.innerHTML = '⚠️ 有 <b>' + (d.pendingVerify + d.pendingPost) + '</b> 条内容在等你审核，' +
      '点左侧「认证审核」「内容审核」处理。';
    v.appendChild(tip);
  }
}

/* ---------------- 认证审核 ---------------- */
async function renderVerify(v) {
  const p = await api('/admin/verify/list?status=0&page=1&size=12');
  refreshBadges();
  v.innerHTML = '';
  if (!p.list.length) {
    v.appendChild(el('div', 'empty', '没有待审核的认证申请 🎉'));
    return;
  }
  const cards = el('div', 'cards');
  p.list.forEach(it => {
    const card = el('div', 'audit-card');
    if (it.cardImage) {
      const img = el('img');
      img.src = it.cardImage;
      img.alt = '校园卡照片';
      img.onerror = function () { this.style.display = 'none'; };
      card.appendChild(img);
    } else {
      const ph = el('div', 'empty', '未上传校园卡照片');
      ph.style.height = '180px';
      ph.style.display = 'grid';
      ph.style.placeItems = 'center';
      card.appendChild(ph);
    }
    const body = el('div', 'audit-body');
    body.appendChild(el('div', 'audit-title', it.nickname || '未知用户'));
    body.appendChild(row('学号', it.studentNo));
    body.appendChild(row('姓名', it.realName));
    body.appendChild(row('提交', it.submittedAt || '-'));
    card.appendChild(body);

    const ops = el('div', 'audit-ops');
    const ok = el('button', 'btn btn-ok', '通过');
    const no = el('button', 'btn btn-danger', '驳回');
    ok.onclick = async () => {
      if (!confirm('确认通过该用户的校园认证？')) return;
      try {
        await api('/admin/verify/' + it.id + '/approve', { method: 'POST', body: {} });
        toast('已通过');
        render();
      } catch (e) { toast(e.message); }
    };
    no.onclick = async () => {
      const reason = await askReason({ title: '驳回认证', desc: '原因会通过站内信发给用户', placeholder: '例如：校园卡照片模糊，请重新拍摄' });
      if (!reason) return;
      try {
        await api('/admin/verify/' + it.id + '/reject', { method: 'POST', body: { reason } });
        toast('已驳回');
        render();
      } catch (e) { toast(e.message); }
    };
    ops.appendChild(ok); ops.appendChild(no);
    card.appendChild(ops);
    cards.appendChild(card);
  });
  v.appendChild(cards);
}

function row(k, val) {
  const r = el('div', 'audit-row');
  r.appendChild(el('span', 'audit-k', k));
  r.appendChild(el('span', 'audit-v', val == null || val === '' ? '-' : val));
  return r;
}

/* ---------------- 内容审核 ---------------- */
async function renderPost(v) {
  const p = await api('/admin/post/list?auditStatus=0&page=1&size=20');
  refreshBadges();
  v.innerHTML = '';
  if (!p.list.length) {
    v.appendChild(el('div', 'empty', '没有待审核的内容 🎉'));
    return;
  }
  const panel = el('div', 'panel');
  const tbl = el('table', 'tbl');
  tbl.innerHTML =
    '<thead><tr><th>ID</th><th>业务线</th><th>标题</th><th>金额</th><th>作者</th>' +
    '<th>状态</th><th>提交时间</th><th style="width:220px">操作</th></tr></thead>';
  const tb = el('tbody');
  p.list.forEach(it => {
    const tr = el('tr');
    tr.innerHTML =
      '<td class="muted">#' + it.id + '</td>' +
      '<td><span class="chip">' + esc(bizName(it.biz)) + '</span></td>' +
      '<td class="strong">' + esc(it.title) + '</td>' +
      '<td>' + (it.price ? '¥' + esc(it.price) : '<span class="muted">未填</span>') + '</td>' +
      '<td>' + esc(it.user || '-') + '</td>' +
      '<td class="muted">' + esc(it.auditStatusText || '') + '</td>' +
      '<td class="muted">' + esc(it.createdAt || '') + '</td>';
    const td = el('td');
    const ops = el('div', 'ops');

    const ok = el('button', 'btn btn-mini btn-ok', '通过');
    ok.onclick = async () => {
      try {
        await api('/admin/post/' + it.id + '/approve', { method: 'POST', body: {} });
        toast('已上架');
        render();
      } catch (e) { toast(e.message); }
    };
    const no = el('button', 'btn btn-mini btn-danger', '驳回');
    no.onclick = async () => {
      const reason = await askReason({ title: '驳回内容', desc: '《' + it.title + '》' });
      if (!reason) return;
      try {
        await api('/admin/post/' + it.id + '/reject', { method: 'POST', body: { reason } });
        toast('已驳回');
        render();
      } catch (e) { toast(e.message); }
    };
    ops.appendChild(ok); ops.appendChild(no);
    td.appendChild(ops);
    tr.appendChild(td);
    tb.appendChild(tr);
  });
  tbl.appendChild(tb);
  panel.appendChild(tbl);
  v.appendChild(panel);
}

function bizName(b) {
  return ({ SECONDHAND: '二手闲置', ERRAND: '跑腿代办', SKILL: '技能互助',
    RENTAL: '物品租赁', LOST: '失物招领', GROUPBUY: '校园拼单' })[b] || b;
}

/* ---------------- 用户管理 ---------------- */
let userPage = 1;
let userKw = '';   // 搜索词必须存在模块级：render() 会先清空 #view，读 DOM 拿不到
async function renderUser(v) {
  const kw = userKw;
  const p = await api('/admin/user/list?page=' + userPage + '&size=10' + (kw ? '&keyword=' + encodeURIComponent(kw) : ''));
  v.innerHTML = '';

  const bar = el('div', 'panel');
  bar.style.marginBottom = '12px';
  bar.style.padding = '12px 14px';
  bar.style.display = 'flex';
  bar.style.gap = '8px';
  bar.innerHTML = '<input id="userKw" placeholder="搜索昵称" value="' + esc(kw) +
    '" style="height:34px;padding:0 12px;border:1px solid var(--line2);border-radius:8px;font:inherit;font-size:13px;outline:none">';
  const search = el('button', 'btn', '搜索');
  search.onclick = () => {
    userKw = $('#userKw').value.trim();
    userPage = 1;
    render();
  };
  const clear = el('button', 'btn', '清空');
  clear.onclick = () => { userKw = ''; userPage = 1; render(); };
  bar.appendChild(search);
  bar.appendChild(clear);
  v.appendChild(bar);

  const panel = el('div', 'panel');
  const tbl = el('table', 'tbl');
  tbl.innerHTML =
    '<thead><tr><th>ID</th><th>昵称</th><th>openid</th><th>认证</th><th>信用分</th>' +
    '<th>账号状态</th><th>发布</th><th>注册时间</th><th style="width:250px">操作</th></tr></thead>';
  const tb = el('tbody');
  p.list.forEach(u => {
    const tr = el('tr');
    tr.innerHTML =
      '<td class="muted">#' + u.id + '</td>' +
      '<td class="strong">' + esc(u.nickname) + '</td>' +
      '<td class="muted">' + esc(u.openidMasked || '') + '</td>' +
      '<td>' + esc(u.verifyStatusText) + '</td>' +
      '<td>' + esc(u.creditScore) + '</td>' +
      '<td>' + esc(u.statusText) + '</td>' +
      '<td>' + esc(u.postCount) + '</td>' +
      '<td class="muted">' + esc(u.createdAt || '') + '</td>';
    const td = el('td');
    const ops = el('div', 'ops');

    const credit = el('button', 'btn btn-mini', '信用分');
    credit.onclick = async () => {
      const raw = await askReason({
        title: '调整 ' + u.nickname + ' 的信用分',
        desc: '当前 ' + u.creditScore + ' 分。正数加分、负数扣分，服务端会限制在 0-100。',
        placeholder: '请输入分值，例如 10 或 -5（这是分值，不是原因）'
      });
      if (!raw) return;
      const delta = parseInt(raw, 10);
      if (!delta) { toast('请输入有效数字'); return; }
      try {
        await api('/admin/user/' + u.id + '/credit', { method: 'POST', body: { delta, reason: '管理员手动调整' } });
        toast('信用分已更新');
        render();
      } catch (e) { toast(e.message); }
    };
    ops.appendChild(credit);

    if (u.status === 1) {
      const ban = el('button', 'btn btn-mini btn-danger', '封禁');
      ban.onclick = async () => {
        const reason = await askReason({ title: '封禁 ' + u.nickname, placeholder: '封禁原因' });
        if (!reason) return;
        try {
          await api('/admin/user/' + u.id + '/status', { method: 'POST', body: { status: 3, reason } });
          toast('已封禁');
          render();
        } catch (e) { toast(e.message); }
      };
      ops.appendChild(ban);
    } else {
      const unban = el('button', 'btn btn-mini btn-ok', '解封');
      unban.onclick = async () => {
        try {
          await api('/admin/user/' + u.id + '/status', { method: 'POST', body: { status: 1, reason: '管理员解封' } });
          toast('已解封');
          render();
        } catch (e) { toast(e.message); }
      };
      ops.appendChild(unban);
    }
    td.appendChild(ops);
    tr.appendChild(td);
    tb.appendChild(tr);
  });
  tbl.appendChild(tb);
  panel.appendChild(tbl);

  const pager = el('div', 'pager');
  pager.innerHTML = '共 ' + p.total + ' 条　第 ' + p.page + ' 页';
  const prev = el('button', 'btn btn-mini', '上一页');
  const next = el('button', 'btn btn-mini', '下一页');
  prev.disabled = p.page <= 1;
  next.disabled = !p.hasMore;
  prev.onclick = () => { userPage = Math.max(1, userPage - 1); render(); };
  next.onclick = () => { userPage = userPage + 1; render(); };
  pager.appendChild(prev); pager.appendChild(next);
  panel.appendChild(pager);
  v.appendChild(panel);
}

/* ---------------- 事件绑定 ---------------- */
$('#loginBtn').onclick = doLogin;
$('#password').addEventListener('keydown', e => { if (e.key === 'Enter') doLogin(); });
$('#logoutBtn').onclick = () => {
  TOKEN = '';
  localStorage.removeItem('xj_admin_token');
  showLogin();
};
$('#refreshBtn').onclick = () => render();
$('#modalCancel').onclick = () => closeModal(null);
$('#modalOk').onclick = () => closeModal($('#modalInput').value.trim());
$('#modal').addEventListener('click', e => { if (e.target.id === 'modal') closeModal(null); });
document.querySelectorAll('.nav-item[data-page]').forEach(a => {
  a.onclick = () => go(a.dataset.page);
});

/* ---------------- 启动 ---------------- */
(async function boot() {
  if (!TOKEN) { showLogin(); return; }
  try {
    await api('/admin/dashboard/overview');
    showApp();
    go('dashboard');
  } catch (e) {
    showLogin();
  }
})();

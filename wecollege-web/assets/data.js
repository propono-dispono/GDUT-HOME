/* ============================================================
   WeCollege 原版 Web 复刻 · 数据层
   ⚠️ 数据来源说明（重要）：
      原版数据存放在「微信云开发数据库」里，仓库源码中没有任何种子数据。
      以下内容是从仓库自带 demo/ 真机截图中逐条还原的真实文案与数字。
   ============================================================ */

const IMG = '../WeCollege/miniprogram/projects/campus/images/';

const DATA = {
  /* 表白墙 —— 来自 demo/2表白墙.png */
  board: [
    {
      id: 1, to: 'my gf', hasPic: true,
      desc: '想找一个简简单单的女孩，她没有灯红酒绿、浓妆艳抹、她很可爱、也有点傻傻的、喜欢粘着我。我会抱着她在她耳边对她说：“遇到你是我上辈子来的福份。”然后为了她使劲奋斗，这就是幸福。',
      from: '', time: '2024-03-25 11:28', likes: 962, comments: 845, favs: 438
    },
    {
      id: 2, to: '', hasPic: false,
      desc: '让那晚风占了便宜',
      from: '', time: '2024-03-25 11:28', likes: 565, comments: 161, favs: 24
    },
    {
      id: 3, to: '', hasPic: false,
      desc: '有没有认识的同学帮我捞一捞',
      from: '', time: '2024-03-25 11:28', likes: 0, comments: 0, favs: 0
    }
  ],

  /* 失物招领 —— 来自 demo/4失物招领.png */
  lost: [
    { id: 1, cate: 2, title: '黑色拉杆箱（内有行李）', pics: 3, time: '2024-03-25 11:42', views: 412, likes: 100, favs: 578, status: 1 },
    { id: 2, cate: 1, title: '小米手机充电器', pics: 3, time: '2024-03-25 11:40', views: 268, likes: 76, favs: 340, status: 1 },
    { id: 3, cate: 1, title: '（原文案被截断）', pics: 1, time: '2024-03-25 11:44', views: 307, likes: 519, favs: 352, status: 1 }
  ],

  /* 兼职招聘 —— 来自 demo/6兼职.png */
  job: [
    { id: 1, title: '行政文员', address: '行政楼', money: '80每天', time: '2024-03-25 11:52', views: 913, likes: 19, favs: 883, status: 1 },
    { id: 2, title: '大型写字楼或商场安保', address: '根据需求而定，可就近安排', money: '120元/天，提供午餐和晚餐', time: '2024-03-25 11:50', views: 146, likes: 950, favs: 35, status: 1 },
    { id: 3, title: '活动展会礼仪', address: '根据活动地点而定', money: '130元/半日；260元/日', time: '2024-03-25 11:48', views: 205, likes: 312, favs: 98, status: 1 },
    { id: 4, title: '演唱会外场促销', address: '体育中心外场', money: '面议', time: '2024-03-25 11:46', views: 88, likes: 45, favs: 12, status: 1 }
  ],

  /* 闲置买卖 —— 来自 demo/8闲置.png */
  leave: [
    { id: 1, title: '9成新网球拍', cate: '运动器材', time: '2024-03-25 12:17', views: 544, likes: 230, favs: 812, status: 1 },
    { id: 2, title: 'Yeezy350新黑', cate: '数码电子', time: '2024-03-25 12:16', views: 313, likes: 927, favs: 121, status: 1 },
    { id: 3, title: '九阳榨汁机', cate: '数码电子', time: '2024-03-25 12:14', views: 946, likes: 593, favs: 753, status: 1 },
    { id: 4, title: '#雅思书籍#剑桥雅思真题4-11精讲4-11共14本', cate: '书籍资料', time: '2024-03-25 12:14', views: 502, likes: 461, favs: 233, status: 1 },
    { id: 5, title: '一套新手皮革工具', cate: '生活用品', time: '2024-03-25 12:12', views: 176, likes: 88, favs: 41, status: 1 }
  ],

  /* 公告 —— 来自 demo/10公告.png */
  news: [
    { id: 1, title: '校园失物招领 Ta在这里...', excerpt: '校运会间，人潮汹涌，犹如热情的音符，奏响校园的旋律。在这期...', time: '2024-03-25', views: 420 },
    { id: 2, title: '关于设立校园失物招领...', excerpt: '在校园安全工作调研过程中发现，我们的师生捡到遗失物品后有的...', time: '2024-03-25', views: 318 },
    { id: 3, title: '【校园资讯】让闲置物...', excerpt: '本着服务同学，发展校园文化，构建和谐校园的原则，让即将毕业...', time: '2024-03-25', views: 205 },
    { id: 4, title: '广科人都会进的校园内...', excerpt: '记得有一个很火的理论 定期扔东西，可提升生活质量~小编觉得...', time: '2024-03-25', views: 166 }
  ]
};

/* ---------------- 工具 ---------------- */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
function qs(n) { return new URLSearchParams(location.search).get(n); }

/* 头像：原版是用户上传照片（云存储），仓库无素材 → 统一浅色头像块 */
function avatar() { return `<div class="left avatar-ph"></div>`; }

/* 图片占位：原版图片在云存储，仓库无素材 → 统一浅色块（尺寸/圆角与原版一致） */
function ph(i, cls) { return `<div class="ph-img ph-${(i % 4) + 1} ${cls || ''}"></div>`; }

/* ---------------- 渲染 ---------------- */
function renderBoard(box) {
  box.innerHTML = DATA.board.map(p => `
    <a class="item shadow-project" href="detail.html?type=board&id=${p.id}">
      <div class="top">
        ${avatar()}
        <div class="right">
          <div class="title">
            <div class="name">${p.to ? 'TO: ' + p.to : ''}</div>
          </div>
          <div class="desc"><div>${p.desc}</div></div>
          ${p.hasPic ? `<div class="pic"><div class="ph-img ph-2 board-pic"></div></div>` : ''}
          ${p.from ? `<div class="line1" style="opacity:.9">From: ${p.from}</div>` : ''}
          <div class="line2">${p.time}</div>
        </div>
      </div>
      <div class="bottom">
        <div class="info">
          <div><span class="icon-appreciate"></span>${p.likes}</div>
          <div><span class="icon-comment"></span>${p.comments}</div>
          <div><span class="icon-favor"></span>${p.favs}</div>
        </div>
      </div>
    </a>`).join('');
}

function renderLost(box) {
  box.innerHTML = DATA.lost.map(p => `
    <a class="item shadow-project" href="detail.html?type=lost&id=${p.id}">
      <div class="arrow icon-right"></div>
      <div class="mark-status ${p.status === 1 ? 'bg-green' : 'bg-grey'} light">${p.status === 1 ? '进行中' : '已结束'}</div>
      <div class="top">
        <div class="right">
          <div class="title">
            <div class="name">
              <span class="type ${p.cate === 1 ? 'bg-pink' : 'bg-cyan'} light">${p.cate === 1 ? "失" : "拾"}</span>${p.title}
            </div>
          </div>
          <div class="pic">
            ${Array.from({ length: p.pics }).map((_, i) => ph(i, 'grid-img')).join('')}
          </div>
        </div>
      </div>
      <div class="bottom">
        <div class="info">
          <div>${p.time}</div>
          <div><span class="icon-attention"></span>${p.views}</div>
          <div><span class="icon-appreciate"></span>${p.likes}</div>
          <div><span class="icon-favor"></span>${p.favs}</div>
        </div>
      </div>
    </a>`).join('');
}

function renderJob(box) {
  box.innerHTML = DATA.job.map(p => `
    <a class="item shadow-project" href="detail.html?type=job&id=${p.id}">
      <div class="arrow icon-right"></div>
      <div class="mark-status ${p.status === 1 ? 'bg-green' : 'bg-grey'} light">${p.status === 1 ? '招聘中' : '已结束'}</div>
      <div class="top">
        <div class="right">
          <div class="title"><div class="name">${p.title}</div></div>
          <div class="desc"><div class="t1">工作地点：</div><div class="t2">${p.address}</div></div>
          <div class="desc"><div class="t1">工资待遇：</div><div class="t2">${p.money}</div></div>
        </div>
      </div>
      <div class="bottom">
        <div class="info">
          <div>${p.time}</div>
          <div><span class="icon-attention"></span>${p.views}</div>
          <div><span class="icon-appreciate"></span>${p.likes}</div>
          <div><span class="icon-favor"></span>${p.favs}</div>
        </div>
      </div>
    </a>`).join('');
}

function renderLeave(box) {
  box.innerHTML = DATA.leave.map((p, idx) => `
    <a class="item shadow-project" href="detail.html?type=leave&id=${p.id}">
      <div class="left">
        <div class="line1"><div class="title content-cut-two"><span>${p.title}</span></div></div>
        <div class="line-bottom">
          <div class="line2 margin-top-xxs">
            <span><span class="icon-attention margin-right-xxxs"></span>${p.views}</span>
            <span><span class="icon-appreciate margin-right-xxxs"></span>${p.likes}</span>
            <span><span class="icon-favor margin-right-xxxs"></span>${p.favs}</span>
          </div>
          <div class="line2">
            <span>${p.time}</span>
            <span>#${p.cate}</span>
          </div>
        </div>
      </div>
      <div class="right">
        <div class="status ${p.status === 1 ? 'bg-green' : 'bg-grey'} light">${p.status === 1 ? '出售中' : '已售出'}</div>
        ${ph(idx, 'leave-thumb')}
      </div>
    </a>`).join('');
}

function renderNews(box) {
  box.innerHTML = `
    <div class="list">
      ${DATA.news.map((p, i) => `
        <a class="item shadow-project news-item" href="detail.html?type=news&id=${p.id}">
          ${ph(i, 'news-thumb')}
          <div class="news-body">
            <div class="news-title">${p.title}</div>
            <div class="news-excerpt">${p.excerpt}</div>
            <div class="news-date">${p.time}</div>
          </div>
        </a>`).join('')}
    </div>`;
}

/* ---------------- 底部 Tab ---------------- */
function mountTab(active) {
  const el = document.getElementById('tabbar');
  if (!el) return;
  const items = [
    { k: 'home', t: '首页', i: 'home', h: 'index.html' },
    { k: 'news', t: '公告', i: 'news', h: 'news.html' },
    { k: 'my',   t: '我的', i: 'my',   h: 'my.html' }
  ];
  el.className = 'tabbar';
  el.innerHTML = items.map(it => `
    <a class="${it.k === active ? 'on' : ''}" href="${it.h}">
      <img src="${IMG}tabbar/${it.i}${it.k === active ? '_cur' : ''}.png" alt="">
      <span>${it.t}</span>
    </a>`).join('');
}

/* ---------------- 筛选标签 ---------------- */
function bindTabs() {
  $$('.ft-item').forEach(el => {
    el.addEventListener('click', () => {
      $$('.ft-item').forEach(x => x.classList.remove('on'));
      el.classList.add('on');
    });
  });
}

/* 回到顶部 */
function mountBackTop() {
  const b = document.createElement('div');
  b.className = 'back-top';
  b.innerHTML = '↑';
  b.addEventListener('click', () => window.scrollTo({ top: 0, behavior: 'smooth' }));
  document.querySelector('.app').appendChild(b);
}

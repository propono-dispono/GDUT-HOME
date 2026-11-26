/* ============================================================
   校集 · 数据层  data.js
   数据均为演示数据；接 Spring Boot 后替换本文件即可。
   红线：不出现任何支付/托管/代收/分佣的正向表述。
   ============================================================ */

/* ---------- 六条业务线注册表 ---------- */
window.BIZ = {
  SECONDHAND: {
    label: '二手闲置', short: '二手', form: 'item', icon: 'i-tag',
    desc: '教材、数码、生活用品',
    priceLabel: '售价', cta: '我想要',
    notice: '请当面验货、当面结算，平台不参与资金流转。',
    fields: [
      { k: '成色', v: '9成新' },
      { k: '可否单卖', v: '可单卖' },
      { k: '面交地点', v: '东区宿舍 楼下' },
      { k: '购入时间', v: '2024 年 9 月' }
    ]
  },
  ERRAND: {
    label: '跑腿代办', short: '跑腿', form: 'task', icon: 'i-run',
    desc: '代取快递、代买、代送',
    priceLabel: '酬金', cta: '我来接单',
    notice: '酬金由双方线下当面结算，平台不代收。',
    fields: [
      { k: '取件地', v: '菜鸟驿站（东门）' },
      { k: '送达地', v: '教学1号楼 3 层' },
      { k: '截止时间', v: '今天 18:00' },
      { k: '是否垫付', v: '不垫付' }
    ]
  },
  SKILL: {
    label: '技能互助', short: '技能', form: 'person', icon: 'i-spark',
    desc: 'PPT、修图、剪辑、答疑',
    priceLabel: '单次', cta: '预约 TA',
    notice: '严禁代写、代做、代考；一经发现封号处理。',
    fields: [
      { k: '技能类目', v: 'PPT 美化 / 排版' },
      { k: '服务方式', v: '线上 · 腾讯会议' },
      { k: '单次时长', v: '1 小时' },
      { k: '可服务时段', v: '工作日 19:00 后' }
    ]
  },
  RENTAL: {
    label: '物品租赁', short: '租赁', form: 'item', icon: 'i-box',
    desc: '相机、正装、帐篷、充电宝',
    priceLabel: '日租金', cta: '申请租用',
    notice: '押金由双方线下自行协商，平台不代收、不代管。',
    fields: [
      { k: '租期', v: '3 天起租' },
      { k: '押金', v: '线下自行协商' },
      { k: '归还地点', v: '西区宿舍 宿管处' },
      { k: '物品成色', v: '95 新' }
    ]
  },
  LOST: {
    label: '失物招领', short: '失物', form: 'notice', icon: 'i-search',
    desc: '寻物启事、拾物招领',
    priceLabel: '无', cta: '提供线索',
    notice: '请勿在公开信息中暴露证件号等敏感信息。',
    fields: [
      { k: '类型', v: '拾物招领' },
      { k: '物品名称', v: '黑色长柄雨伞' },
      { k: '地点', v: '图书馆 3 楼' },
      { k: '时间', v: '今天 11:20' }
    ]
  },
  GROUPBUY: {
    label: '校园拼单', short: '拼单', form: 'task', icon: 'i-users',
    desc: '水果、文具、日用品',
    priceLabel: '人均', cta: '参与拼单',
    notice: '仅限日用品 / 文具 / 水果等自提拼单，不含外卖熟食；货款线下自行结算。',
    fields: [
      { k: '目标人数', v: '5 人' },
      { k: '已拼人数', v: '3 人' },
      { k: '截止时间', v: '今晚 22:00' },
      { k: '自提点', v: '教学1号楼 门厅' }
    ]
  }
};

/* ---------- 信息流内容 ---------- */
window.MOCK = {
  posts: [
    {
      id: 1, biz: 'SECONDHAND', title: '高等数学 同济第七版 上下册',
      desc: '9成新，无笔记，可单卖', price: 25, meta: '东区宿舍 · 10分钟前',
      user: '林', cover: 'sh-01'
    },
    {
      id: 2, biz: 'SECONDHAND', title: '罗技 MX Master 3S 无线鼠标',
      desc: '自用半年，功能完好，配件齐', price: 320, meta: '西区宿舍 · 32分钟前',
      user: '周', cover: 'sh-02'
    },
    {
      id: 3, biz: 'ERRAND', title: '代取快递：菜鸟驿站 → 教学1号楼',
      desc: '两个小件，今天 18:00 前送到', price: 5, meta: '18分钟前',
      user: '王', cover: 'er-01', progress: [2, 3]
    },
    {
      id: 4, biz: 'LOST', title: '黑色长柄雨伞', desc: '图书馆 3 楼自习区拾到',
      meta: '2小时前', user: '孙', cover: 'ls-01', tag: '拾物'
    },
    {
      id: 5, biz: 'SKILL', title: 'PPT 美化 / 排版', desc: '可线上，单次 1 小时',
      price: 40, priceUnit: '时薪', meta: '已服务 12 次',
      user: '陈', cover: 'sk-01'
    },
    {
      id: 6, biz: 'RENTAL', title: '索尼 A7M3 相机（含 24-70）',
      desc: '95 新，3 天起租', price: 45, priceUnit: '日租金', meta: '西区宿舍 · 1小时前',
      user: '赵', cover: 'rt-01'
    },
    {
      id: 7, biz: 'GROUPBUY', title: '赣南脐橙 整箱拼单',
      desc: '10 斤装，今晚截止', price: 18, meta: '已拼 3/5',
      user: '李', cover: 'gb-01', progress: [3, 5]
    },
    {
      id: 8, biz: 'SECONDHAND', title: '考研英语真题（近十年）',
      desc: '有少量铅笔标记，可擦', price: 15, meta: '图书馆 · 3小时前',
      user: '吴', cover: 'sh-03'
    },
    {
      id: 9, biz: 'ERRAND', title: '代打印并装订 3 份材料',
      desc: 'A4 双面，送到 实验楼', price: 6, meta: '45分钟前',
      user: '郑', cover: 'er-02', progress: [1, 1]
    },
    {
      id: 10, biz: 'LOST', title: '校园卡（姓名已遮挡）', desc: '第一饭堂附近丢失，急用',
      meta: '3小时前', user: '黄', cover: 'ls-02', tag: '寻物'
    },
    {
      id: 11, biz: 'SKILL', title: '吉他入门陪练', desc: '线下，宿舍区可约',
      price: 35, priceUnit: '时薪', meta: '已服务 8 次',
      user: '徐', cover: 'sk-02'
    },
    {
      id: 12, biz: 'RENTAL', title: '正装一套（M 码）',
      desc: '含衬衫领带，面试季必备', price: 20, priceUnit: '日租金', meta: '实验楼 · 5小时前',
      user: '马', cover: 'rt-02'
    }
  ],

  sessions: [
    { id: 101, name: '林同学', last: '书还在吗？可以今晚 3 号楼下面交吗', time: '14:22', unread: 2 },
    { id: 102, name: '王同学', last: '好的，我 18:00 前送到 7 号楼', time: '13:05', unread: 0 },
    { id: 103, name: '孙同学', last: '伞我放在宿管阿姨那里了', time: '昨天', unread: 1 }
  ],

  orders: [
    { id: 9001, no: '20261017001', biz: 'SECONDHAND', title: '高等数学 同济第七版 上下册', amount: 25, role: 'BUYER', status: 'CREATED', other: '林同学', time: '10-17 14:20' },
    { id: 9002, no: '20261017002', biz: 'ERRAND', title: '代取快递：菜鸟驿站 → 教学1号楼', amount: 5, role: 'BUYER', status: 'CONFIRMED', other: '王同学', time: '10-17 13:02' },
    { id: 9003, no: '20261016008', biz: 'SECONDHAND', title: '罗技 MX Master 3S 无线鼠标', amount: 320, role: 'SELLER', status: 'DELIVERED', other: '周同学', time: '10-16 19:40' },
    { id: 9004, no: '20261015003', biz: 'SECONDHAND', title: '考研英语真题（近十年）', amount: 15, role: 'SELLER', status: 'COMPLETED', other: '陈同学', time: '10-15 10:11' }
  ],

  messages: [
    { me: false, text: '你好，请问高等数学还在吗？', time: '14:18' },
    { me: true,  text: '在的，上下册一起 25，可以单卖', time: '14:19' },
    { me: false, text: '我要上册，15 可以吗', time: '14:20' },
    { me: true,  text: '可以，今晚 3 号楼下面交？', time: '14:21' },
    { me: false, text: '书还在吗？可以今晚 3 号楼下面交吗', time: '14:22' }
  ],

  me: { name: '林同学', initial: '林', verified: true, credit: 82, joined: 12, posts: 6, deals: 4 }
};

window.STATUS = {
  CREATED:   { text: '待确认', cls: '' },
  CONFIRMED: { text: '待交付', cls: '' },
  IN_PROGRESS:{ text: '进行中', cls: '' },
  DELIVERED: { text: '待收货', cls: '' },
  COMPLETED: { text: '已完成', cls: 'status--done' },
  REVIEWED:  { text: '已评价', cls: 'status--done' },
  CANCELLED: { text: '已取消', cls: '' },
  DISPUTE:   { text: '仲裁中', cls: 'status--danger' }
};

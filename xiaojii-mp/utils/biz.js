/**
 * 六条业务线注册表
 * 与后端 CategoryEnum / bizType 枚举一一对应（后端用枚举不建表，前端同样不查库）
 * form 决定信息流卡片的形态：
 *   item   图文卡（二手 / 租赁）
 *   task   带进度条的卡（跑腿 / 拼单）
 *   person 头像卡（技能互助）
 *   notice 小图 + 标签卡（失物招领）
 *
 * ⚠️ notice 是合规提示文案。它属于「以后可能要改」的内容，理想做法是从后端下发
 *    （详情接口已有 notice 字段），但发布页在提交前就要展示，目前没有配置接口，
 *    暂存在前端。等后端补了配置接口，这里应改为从云端读，避免改文案就要重新审核小程序。
 */
const BIZ = {
  SECONDHAND: {
    label: '二手闲置', short: '二手', form: 'item', icon: 'i-tag',
    notice: '请当面验货、当面结算，平台不参与资金流转。'
  },
  ERRAND: {
    label: '跑腿代办', short: '跑腿', form: 'task', icon: 'i-run',
    notice: '酬金由双方线下当面结算，平台不代收。'
  },
  SKILL: {
    label: '技能互助', short: '技能', form: 'person', icon: 'i-spark',
    notice: '严禁代写、代做、代考；一经发现封号处理。'
  },
  RENTAL: {
    label: '物品租赁', short: '租赁', form: 'item', icon: 'i-box',
    notice: '押金由双方线下自行协商，平台不代收、不代管。'
  },
  LOST: {
    label: '失物招领', short: '失物', form: 'notice', icon: 'i-search',
    notice: '请勿在公开信息中暴露证件号等敏感信息。'
  },
  GROUPBUY: {
    label: '校园拼单', short: '拼单', form: 'task', icon: 'i-users',
    notice: '仅限日用品 / 文具 / 水果等自提拼单，不含外卖熟食；货款线下自行结算。'
  }
}

const ORDER = ['SECONDHAND', 'ERRAND', 'SKILL', 'RENTAL', 'LOST', 'GROUPBUY']

/** 给信息流条目补上前端渲染要用的派生字段 */
function decorate(item) {
  const biz = BIZ[item.biz] || BIZ.SECONDHAND
  const pg = item.progress || [0, 0]
  const pct = pg[1] > 0 ? Math.max(4, Math.round((pg[0] / pg[1]) * 100)) : 0
  return Object.assign({}, item, {
    _form: biz.form,
    _icon: biz.icon,
    _pct: pct
  })
}

module.exports = { BIZ, ORDER, decorate }

/**
 * 首页截图（验证「今日课程」卡片布局）。
 * 用法同 shot_edu2.js。
 */
const automator = require('miniprogram-automator')

const OUT = 'E:/SchoolMarket/tools/'
const wait = (ms) => new Promise((r) => setTimeout(r, ms))

async function main() {
  const mp = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:9420' })
  mp.on('exception', (e) => console.log('[exc]', (e && e.message) || e))

  await mp.evaluate(() => wx.reLaunch({ url: '/pages/home/home' }))
  await wait(3500)

  const page = await mp.currentPage()
  console.log('page =', page.path)
  const d = await page.data()
  console.log('todayText =', d.todayText, ' courses =', (d.todayCourses || []).length,
    ' hint =', d.todayHint || '(无)')
  ;(d.todayCourses || []).forEach((c) => console.log('   ', c.periodText, '|', c.name, '|', c.metaText))

  await mp.screenshot({ path: OUT + '_v_home.png' })
  console.log('shot ->', OUT + '_v_home.png')
  process.exit(0)
}

main().catch((e) => {
  console.error('FAILED', (e && (e.message || e)) || e)
  process.exit(1)
})

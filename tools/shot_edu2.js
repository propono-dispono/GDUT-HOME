/**
 * 教务页截图（可用版）：先 reLaunch 到教务页，再伪造登录态、加载数据、截图。
 *
 * 用法：
 *   cd /c/Users/damn/.workbuddy/binaries/node/workspace && npm i miniprogram-automator  # 只需一次
 *   cd "/e/微信web开发者工具" && ./cli.bat auto --project "E:/SchoolMarket/xiaojii-mp" --auto-port 9420
 *   MSYS_NO_PATHCONV=1 NODE_PATH='C:/Users/damn/.workbuddy/binaries/node/workspace/node_modules' \
 *     node tools/shot_edu2.js
 */
const automator = require('miniprogram-automator')

const OUT = 'E:/SchoolMarket/tools/'
const wait = (ms) => new Promise((r) => setTimeout(r, ms))

async function main() {
  const mp = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:9420' })
  mp.on('exception', (e) => console.log('[exc]', (e && e.message) || e))

  await mp.evaluate(() => wx.reLaunch({ url: '/pages/edu/edu' }))
  await wait(2500)

  const page = await mp.currentPage()
  console.log('page =', page.path)
  if (page.path.indexOf('edu/edu') < 0) throw new Error('没跳到教务页')

  // 伪造登录态（后端 session 才是真的，这里只是让页面渲染已登录分支）
  await page.setData({
    loading: false,
    status: { loggedIn: true, saved: true, accountMasked: '3124****95', expiresAt: null }
  })
  await wait(300)

  console.log('--- 课表 ---')
  await mp.evaluate(() => {
    const p = getCurrentPages().slice(-1)[0]
    p.loadTimetable(0, true)
  })
  await wait(4000)
  let d = await page.data()
  const panel = (d.weekPanels || [])[d.week - 1] || { blocks: [] }
  console.log('week =', d.week, ' panels =', (d.weekPanels || []).length,
    ' 本周块 =', panel.blocks.length, ' ttErr =', d.ttErr || '(无)')
  await mp.screenshot({ path: OUT + '_v_tt.png' })
  console.log('shot ->', OUT + '_v_tt.png')

  console.log('--- 考试 ---')
  await page.setData({ tab: 'exam' })
  await mp.evaluate(() => {
    const p = getCurrentPages().slice(-1)[0]
    p.loadExams(true)
  })
  await wait(3000)
  d = await page.data()
  console.log('examsShown =', (d.examsShown || []).length, ' total =', d.exTotal,
    ' todo =', d.exTodo, ' done =', d.exDone, ' err =', d.exErr || '(无)')
  await mp.screenshot({ path: OUT + '_v_exam.png' })
  console.log('shot ->', OUT + '_v_exam.png')

  process.exit(0)
}

main().catch((e) => {
  console.error('FAILED', (e && (e.message || e)) || e)
  process.exit(1)
})

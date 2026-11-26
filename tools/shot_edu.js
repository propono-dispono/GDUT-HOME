/**
 * 教务中心三 Tab 截图（本地验收）
 * MSYS_NO_PATHCONV=1 NODE_PATH='C:/Users/damn/.workbuddy/binaries/node/workspace/node_modules' node tools/shot_edu.js
 *
 * 本地没有真实教务会话，这里把登录态置为已登录再直接调页面方法，
 * 用来验证课表网格 / 成绩 GPA / 考试列表的渲染。
 */
const automator = require('miniprogram-automator')
const OUT = 'E:/SchoolMarket/tools/'
const wait = (ms) => new Promise((r) => setTimeout(r, ms))

async function call(mp, method, arg) {
  return mp.evaluate(
    (m, a) => {
      const pages = getCurrentPages()
      const p = pages[pages.length - 1]
      if (typeof p[m] !== 'function') return 'no-method'
      p[m](a)
      return 'ok'
    },
    method,
    arg
  )
}

async function main() {
  const mp = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:9420' })
  mp.on('exception', (e) => console.log('[exc]', (e && e.message) || e))
  mp.on('console', (m) => {
    if (m.type === 'error') console.log('[err]', (m.args || []).join(' '))
  })

  const page = await mp.currentPage()
  console.log('page =', page.path)

  await page.setData({
    loading: false,
    status: { loggedIn: true, saved: true, accountMasked: '3124****95', expiresAt: '2026-11-18 19:00' }
  })
  await wait(300)

  console.log('timetable:', await call(mp, 'loadTimetable', 8))
  await wait(1800)
  const d1 = await page.data()
  console.log('  week=', d1.week, 'blocks=', (d1.blocks || []).length, 'fixture=', d1.fixture)
  await mp.screenshot({ path: OUT + '_edu_timetable.png' })

  await page.setData({ tab: 'score' })
  console.log('scores:', await call(mp, 'loadScores', true))
  await wait(1800)
  const d2 = await page.data()
  console.log('  gpa=', d2.stats && d2.stats.gpaText, 'terms=', (d2.terms || []).length)
  await mp.screenshot({ path: OUT + '_edu_score.png' })

  await mp.evaluate(() => {
    const p = getCurrentPages().slice(-1)[0]
    p.onScoreMode({ currentTarget: { dataset: { mode: 'grade' } } })
  })
  await wait(500)
  await mp.screenshot({ path: OUT + '_edu_score_grade.png' })

  await page.setData({ tab: 'exam' })
  console.log('exams:', await call(mp, 'loadExams', true))
  await wait(1200)
  const d3 = await page.data()
  console.log('  exams=', (d3.exams || []).length)
  await mp.screenshot({ path: OUT + '_edu_exam.png' })

  await mp.disconnect()
  console.log('done')
}

main().catch((e) => console.error('FAILED', (e && (e.message || e)) || e))

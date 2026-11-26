/**
 * 小程序页面自动化自检（调试用）
 *
 * 用法：
 *   1) 先起自动化端口：cli.bat auto --project "E:\SchoolMarket\xiaojii-mp" --auto-port 9420
 *   2) NODE_PATH=<workspace>/node_modules node tools/auto_check.js /pages/edu/edu
 *
 * 输出：页面 data、关键节点是否存在、控制台报错、异常、整页截图。
 */
const automator = require('miniprogram-automator')

const TARGET = process.argv[2] || '/pages/edu/edu'
const SHOT = process.argv[3] || 'E:/SchoolMarket/tools/_shot.png'

async function main() {
  const mp = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:9420' })

  mp.on('console', (msg) => {
    const args = msg.args ? msg.args.join(' ') : ''
    console.log('[console]', msg.type, args)
  })
  mp.on('exception', (err) => {
    console.log('[exception]', (err && (err.message || err.stack)) || JSON.stringify(err))
  })

  const page = await mp.reLaunch(TARGET)
  await page.waitFor(2500)

  console.log('=== path ===')
  console.log(page.path)

  console.log('=== data ===')
  const data = await page.data()
  console.log(JSON.stringify(data, null, 2).slice(0, 2000))

  console.log('=== nodes ===')
  for (const sel of ['.app', '.app-main', '.appbar-title', '.empty-title', '.edu-gate', '.edu-panel', '.tabs']) {
    const n = await page.$(sel)
    let text = ''
    if (n) {
      try {
        text = await n.text()
      } catch (e) {
        text = '(no text)'
      }
    }
    console.log(sel, '->', n ? 'FOUND' : 'MISSING', text ? JSON.stringify(text.slice(0, 60)) : '')
  }

  await mp.screenshot({ path: SHOT })
  console.log('screenshot ->', SHOT)

  await mp.disconnect()
}

main().catch((e) => {
  console.error('FAILED:', e && (e.stack || e.message || e))
  process.exit(1)
})

/**
 * 默认头像：学士帽（6 种低饱和底色，按用户 ID 轮换取图）。
 *
 * 为什么按 ID 取色：如果所有没设头像的用户都用同一张图，会话列表里会分不清谁是谁。
 * ⛔ 图片在 assets/images/avatars/，用绝对路径引用（小程序根目录起算）。
 */
const HATS = [
  '/assets/images/avatars/hat-1.png',
  '/assets/images/avatars/hat-2.png',
  '/assets/images/avatars/hat-3.png',
  '/assets/images/avatars/hat-4.png',
  '/assets/images/avatars/hat-5.png',
  '/assets/images/avatars/hat-6.png'
]

/** 同一用户永远拿到同一张，换设备也不变 */
function defaultAvatar(userId) {
  const n = Math.abs(Number(userId) || 0)
  return HATS[n % HATS.length]
}

module.exports = { defaultAvatar, HATS }

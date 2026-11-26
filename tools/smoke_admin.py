# -*- coding: utf-8 -*-
"""
B6 管理端审核冒烟测试：认证通过 / 认证驳回 / 内容通过 / 强制下架。
每条路径都用正反对照验证（状态真的变了 + 二次操作被 CAS 拦住）。
跑法：python tools/smoke_admin.py
"""
import json
import urllib.request

BASE = 'http://127.0.0.1:8080/api'
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(path, method='GET', body=None, token=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with OPENER.open(req, timeout=25) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


def admin_login():
    r = call('/admin/auth/login', 'POST', {'username': 'admin', 'password': 'admin123'})
    if r['code'] != 0:
        print('  ❌ 管理员登录失败:', r['msg'])
        return None
    return r['data'].get('token')


def app_login(openid):
    r = call('/app/auth/dev-login', 'POST', {'openid': openid})
    return r['data']['token']


AT = admin_login()
print('管理员登录:', 'OK' if AT else 'FAIL')
if not AT:
    raise SystemExit(1)

# ==================== 场景 1：认证通过 ====================
print('\n=== 场景 1：新用户提交认证 → 管理员通过 ===')
tk1 = app_login('dev_b6_pass')
call('/app/user/verify', 'POST',
     {'studentNo': 'B6PASS001', 'realName': '通过测试', 'cardImage': '202610/b6pass.jpg'}, tk1)
s = call('/app/user/verify', token=tk1)['data']
print('  提交后 user.verifyStatus =', s['status'], s['statusText'])

lst = call('/admin/verify/list?status=0&page=1&size=20', token=AT)['data']
mine = [x for x in lst['list'] if x['userId'] and x['studentNo'] == 'B6PASS001']
print('  待审列表里找到目标:', '是' if mine else '否', '| 当前待审总数 =', lst['total'])
if mine:
    vid = mine[0]['id']
    print('  明细: 学号=%s 姓名=%s 卡图=%s' % (mine[0]['studentNo'], mine[0]['realName'], mine[0]['cardImage']))

    r = call('/admin/verify/%s/approve' % vid, 'POST', {}, AT)
    print('  审核通过 code =', r['code'], r['msg'])

    s2 = call('/app/user/verify', token=tk1)['data']
    print('  通过后 user.verifyStatus =', s2['status'], s2['statusText'], '  ← 期望 2 已认证')

    r2 = call('/admin/verify/%s/approve' % vid, 'POST', {}, AT)
    print('  重复通过 code =', r2['code'], r2['msg'], '  ← 期望 1032 被 CAS 拦住')

# ==================== 场景 2：认证驳回 ====================
print('\n=== 场景 2：新用户提交认证 → 管理员驳回 ===')
tk2 = app_login('dev_b6_reject')
call('/app/user/verify', 'POST',
     {'studentNo': 'B6REJ001', 'realName': '驳回测试', 'cardImage': '202610/b6rej.jpg'}, tk2)
lst = call('/admin/verify/list?status=0&page=1&size=20', token=AT)['data']
mine = [x for x in lst['list'] if x['studentNo'] == 'B6REJ001']
if mine:
    vid = mine[0]['id']
    r = call('/admin/verify/%s/reject' % vid, 'POST', {'reason': '校园卡照片模糊，请重新拍摄'}, AT)
    print('  驳回 code =', r['code'], r['msg'])
    s3 = call('/app/user/verify', token=tk2)['data']
    print('  驳回后 user.verifyStatus =', s3['status'], s3['statusText'], '  ← 期望 3 驳回')
    print('  用户看到的驳回原因 =', s3.get('rejectReason'))

# ==================== 场景 3：内容审核 + 强制下架 ====================
print('\n=== 场景 3：新用户发帖（会进待审）→ 管理员通过 → 强制下架 ===')
r = call('/app/post', 'POST', {
    'bizType': 'SECONDHAND', 'categoryId': 1,
    'title': '[B6自测] 待审内容', 'content': '验证 admin 审核链路，可删除',
    'price': 9.9
}, tk1)
pid = r['data']['id'] if r['code'] == 0 else None
print('  发帖 code =', r['code'], '| id =', pid,
      '| status =', r['data']['status'] if pid else '-',
      '|', r['data']['statusText'] if pid else '')

if pid:
    pl = call('/admin/post/list?auditStatus=0&page=1&size=20', token=AT)['data']
    hit = [x for x in pl['list'] if x['id'] == pid]
    print('  待审内容列表里找到:', '是' if hit else '否', '| 当前待审总数 =', pl['total'])
    if hit:
        print('  明细: biz=%s 标题=%s 作者=%s 状态=%s 审核=%s'
              % (hit[0]['biz'], hit[0]['title'], hit[0]['user'],
                 hit[0]['statusText'], hit[0]['auditStatusText']))

    r = call('/admin/post/%s/approve' % pid, 'POST', {}, AT)
    print('  内容通过 code =', r['code'], r['msg'])
    d = call('/app/post/%s' % pid, token=tk1)['data']
    print('  通过后 status =', d['status'], d['statusText'], '  ← 期望 1 展示中')

    r = call('/admin/post/%s/offline' % pid, 'POST', {'reason': '含未核实的价格信息'}, AT)
    print('  强制下架 code =', r['code'], r['msg'])
    d2 = call('/app/post/%s' % pid, token=tk1)['data']
    print('  下架后 status =', d2['status'], d2['statusText'], '  ← 期望 2 已下架')

    r = call('/admin/post/%s/offline' % pid, 'POST', {'reason': '再下一次'}, AT)
    print('  重复下架 code =', r['code'], r['msg'], '  ← 期望 1032')

    # 清理
    r = call('/app/post/%s' % pid, 'DELETE', None, tk1)
    print('  清理测试帖 code =', r['code'])

print('\n=== 场景 4：小程序用户令牌不能调管理端（越权防护）===')
r = call('/admin/verify/list?page=1&size=5', token=tk1)
print('  用 app 令牌调 /admin/** → code =', r['code'], r['msg'], '  ← 期望 401/1003')

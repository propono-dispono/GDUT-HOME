# -*- coding: utf-8 -*-
"""
发布链路冒烟测试：把 6 条业务线的 payload 逐个打到真实后端，验证字段名与必填校验。
用途：前端 publish 页的字段定义如果和后端 PostCreateReq 不一致，这里会立刻暴露。
跑法：python tools/smoke_publish.py
"""
import json
import urllib.request

BASE = 'http://127.0.0.1:8080/api'
PROXY = urllib.request.ProxyHandler({})          # 绕开本机代理
OPENER = urllib.request.build_opener(PROXY)


def call(path, method='GET', body=None, token=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with OPENER.open(req, timeout=20) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


login = call('/app/auth/dev-login', 'POST', {'openid': 'dev_lin'})
TK = login['data']['token']
print('登录:', login['code'], '| userId =', login['data']['userId'])
print()

CASES = [
    ('SECONDHAND', {
        'bizType': 'SECONDHAND', 'categoryId': 1,
        'title': '[自测] 二手 payload 校验', 'content': '验证二手字段：price / negotiable',
        'price': 12.5, 'negotiable': 1, 'building': '东区宿舍', 'tags': '自测'
    }),
    ('ERRAND', {
        'bizType': 'ERRAND', 'categoryId': 11,
        'title': '[自测] 跑腿 payload 校验', 'content': '验证跑腿必填：pickupAddr / deliverAddr / deadline',
        'price': 5, 'pickupAddr': '菜鸟驿站（东门）', 'deliverAddr': '教学1号楼 3 层',
        'deadline': '2026-10-19 18:00:00', 'advancePay': 0
    }),
    ('SKILL', {
        'bizType': 'SKILL', 'categoryId': 21,
        'title': '[自测] 技能 payload 校验', 'content': '验证技能必填：serviceType',
        'price': 40, 'serviceType': 'PPT 美化', 'deliveryDays': 2
    }),
    ('RENTAL', {
        'bizType': 'RENTAL', 'categoryId': 31,
        'title': '[自测] 租赁 payload 校验', 'content': '验证租赁必填：rentPrice',
        'rentPrice': 45, 'rentUnit': '天', 'deposit': 200, 'minDays': 3
    }),
    ('LOST', {
        'bizType': 'LOST', 'categoryId': 6,
        'title': '[自测] 失物 payload 校验', 'content': '验证失物必填：lostType / itemName / lostPlace',
        'lostType': 2, 'itemName': '黑色长柄雨伞', 'lostPlace': '图书馆 3 楼',
        'lostTime': '2026-10-18 11:20:00'
    }),
    ('GROUPBUY', {
        'bizType': 'GROUPBUY', 'categoryId': 41,
        'title': '[自测] 拼单 payload 校验', 'content': '验证拼单必填：targetCount / groupbuyDeadline',
        'price': 18, 'targetCount': 5, 'groupbuyDeadline': '2026-10-20 22:00:00',
        'pickupPoint': '教学1号楼 门厅', 'withErrand': 0
    }),
]

created = []
print('=== 六条业务线发布 ===')
for name, payload in CASES:
    r = call('/app/post', 'POST', payload, TK)
    if r['code'] == 0:
        d = r['data']
        created.append(d['id'])
        print('  %-11s code=0  id=%-3s status=%s(%s)  cta=%s'
              % (name, d['id'], d['status'], d['statusText'], d.get('cta')))
    else:
        print('  %-11s ❌ code=%s msg=%s' % (name, r['code'], r['msg']))

print()
print('=== 敏感词拦截（拼单含「外卖」）===')
r = call('/app/post', 'POST', {
    'bizType': 'GROUPBUY', 'categoryId': 41,
    'title': '售卖外卖 拼单', 'content': '测试敏感词拦截是否生效',
    'targetCount': 3, 'groupbuyDeadline': '2026-10-20 22:00:00'
}, TK)
print('  code=%s msg=%s   %s' % (r['code'], r['msg'], '✅ 已拦截' if r['code'] == 1095 else '⚠️ 未按预期拦截'))

print()
print('=== 我的发布（含审核状态）===')
mine = call('/app/post/mine?page=1&size=20', 'GET', None, TK)
if mine['code'] == 0:
    print('  total =', mine['data']['total'])
    for it in mine['data']['list']:
        print('   #%-3s %-10s %-28s %s' % (it['id'], it['biz'], it['title'][:26], it['statusText']))

print()
print('=== 清理：只留 1 条二手 + 1 条拼单做演示，其余逻辑删除 ===')
keep = set(created[:1]) | set(created[5:6])
for pid in created:
    if pid in keep:
        print('  #%s 保留' % pid)
        continue
    r = call('/app/post/%s' % pid, 'DELETE', None, TK)
    print('  #%s 删除 code=%s' % (pid, r['code']))

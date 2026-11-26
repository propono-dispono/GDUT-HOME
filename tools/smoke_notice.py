# -*- coding: utf-8 -*-
"""
B5 通知链路冒烟：列表 / 未读数 / 单条已读 / 全部已读 / 越权防护。
跑法：python tools/smoke_notice.py [端口，默认 8080]
"""
import json
import sys
import urllib.request

PORT = sys.argv[1] if len(sys.argv) > 1 else '8080'
BASE = 'http://127.0.0.1:%s/api' % PORT
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


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


def login(openid):
    return call('/app/auth/dev-login', 'POST', {'openid': openid})['data']['token']


TK = login('dev_b6_pass')          # u3，之前被审核过，应有 2 条 AUDIT 通知
OTHER = login('dev_lin')           # u1，用来做越权对照

print('=== 1. 通知列表 ===')
r = call('/app/notice/list?page=1&size=20', token=TK)
print('  code =', r['code'], '| total =', r['data']['total'])
for n in r['data']['list']:
    print('   #%-3s [%s/%s] %-16s read=%-5s %s'
          % (n['id'], n['type'], n['typeText'], n['title'], n['read'], n['createdAt']))

print('\n=== 2. 未读数 ===')
u = call('/app/notice/unread-count', token=TK)['data']['count']
print('  unread =', u, '  ← 期望 > 0')

print('\n=== 3. 标记单条已读 ===')
first_unread = [n for n in r['data']['list'] if not n['read']]
if first_unread:
    nid = first_unread[0]['id']
    print('  标记 #%s ->' % nid, call('/app/notice/%s/read' % nid, 'POST', {}, TK)['code'])
    u2 = call('/app/notice/unread-count', token=TK)['data']['count']
    print('  未读数 %s -> %s  ← 期望减 1' % (u, u2))
else:
    print('  （没有未读了，跳过）')
    u2 = u

print('\n=== 4. 越权：用别的账号标记我这条通知 ===')
if first_unread:
    r = call('/app/notice/%s/read' % first_unread[0]['id'], 'POST', {}, OTHER)
    print('  返回 code =', r['code'], '（接口不报错，但底层 where user_id 不匹配）')
    u3 = call('/app/notice/unread-count', token=TK)['data']['count']
    print('  我的未读数仍为', u3, ' ← 期望与上一步相同，说明没被别人改掉')

print('\n=== 5. 全部已读 ===')
print('  code =', call('/app/notice/read-all', 'POST', {}, TK)['code'])
u4 = call('/app/notice/unread-count', token=TK)['data']['count']
print('  unread =', u4, '  ← 期望 0')

print('\n=== 6. 无 token 访问 ===')
r = call('/app/notice/list')
print('  code =', r['code'], r['msg'], '  ← 期望 401')

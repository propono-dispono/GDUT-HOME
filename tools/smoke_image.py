# -*- coding: utf-8 -*-
"""
图片链路验证：上传一张真图 → 发一条带图的帖子 → 确认 feed 里的 cover 是完整 URL → 确认该 URL 能取到 200。
跑法：python tools/smoke_image.py
"""
import json
import os
import urllib.request
import uuid

BASE = 'http://127.0.0.1:8080/api'
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
IMG = r'E:\SchoolMarket\prototype\assets\img\items\gb-01.jpg'


def call(path, method='GET', body=None, token=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with OPENER.open(req, timeout=30) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


def upload(file_path, token):
    """手写 multipart/form-data，等价于 wx.uploadFile"""
    boundary = '----xiaojii' + uuid.uuid4().hex
    with open(file_path, 'rb') as f:
        content = f.read()
    fname = os.path.basename(file_path)
    body = b''
    body += ('--%s\r\n' % boundary).encode()
    body += ('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % fname).encode()
    body += b'Content-Type: image/jpeg\r\n\r\n'
    body += content
    body += ('\r\n--%s--\r\n' % boundary).encode()

    req = urllib.request.Request(BASE + '/app/upload?scene=post', data=body, method='POST')
    req.add_header('Content-Type', 'multipart/form-data; boundary=' + boundary)
    req.add_header('Authorization', 'Bearer ' + token)
    try:
        with OPENER.open(req, timeout=30) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


def head(url):
    req = urllib.request.Request(url, method='GET')
    try:
        with OPENER.open(req, timeout=15) as r:
            return r.status, r.headers.get('Content-Type'), len(r.read())
    except urllib.error.HTTPError as e:
        return e.code, None, 0


tk = call('/app/auth/dev-login', 'POST', {'openid': 'dev_lin'})['data']['token']
print('原图:', IMG, '|', os.path.getsize(IMG), '字节')

print('\n=== 1. 上传 ===')
up = upload(IMG, tk)
print('  code =', up['code'], up.get('msg'))
if up['code'] != 0:
    raise SystemExit(1)
key, url = up['data']['key'], up['data']['url']
print('  key =', key)
print('  url =', url)

print('\n=== 2. 图片地址直接访问 ===')
code, ctype, size = head(url)
print('  HTTP =', code, '| Content-Type =', ctype, '| 字节 =', size)

print('\n=== 3. 发一条带图的帖子 ===')
r = call('/app/post', 'POST', {
    'bizType': 'GROUPBUY', 'categoryId': 41,
    'title': '[示例] 带图测试帖',
    'content': '这条用于验证图片链路，确认后可删除',
    'price': 12, 'targetCount': 3,
    'groupbuyDeadline': '2026-10-25 22:00:00',
    'pickupPoint': '教学1号楼 门厅',
    'images': [key]
}, tk)
print('  code =', r['code'], '| id =', r['data']['id'] if r['code'] == 0 else '-',
      '| status =', r['data']['status'] if r['code'] == 0 else '-')
pid = r['data']['id'] if r['code'] == 0 else None

print('\n=== 4. 首页 feed 里的 cover ===')
f = call('/app/post/feed?page=1&size=6', token=tk)['data']
hit = [x for x in f['list'] if x['id'] == pid]
if hit:
    print('  找到测试帖 #%s' % pid)
    print('  cover =', hit[0]['cover'])
    c2, t2, s2 = head(hit[0]['cover'])
    print('  cover 可访问? HTTP =', c2, '| 字节 =', s2)
else:
    print('  ⚠️ feed 里没找到 #%s（可能进了待审）' % pid)
    d = call('/app/post/%s' % pid, token=tk)['data']
    print('  详情 images =', d['images'])
    print('  详情状态 =', d['statusText'])

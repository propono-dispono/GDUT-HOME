# WeCollege 克隆核对报告

- **核对时间**：2026-10-14 21:57（GMT+8）
- **源仓库**：https://github.com/3160534976/WeCollege
- **克隆方式**：SSH（`git@github.com:3160534976/WeCollege.git`）
- **落地路径**：`E:\SchoolMarket\WeCollege\`
- **远端推送**：**未执行**（按你的要求，等你确认后再推）

## 一、一致性核对（结论：完整一致，无缺失）

| 核对项 | 结果 | 说明 |
|---|---|---|
| 本地 HEAD | `c9ae012b22f8acd40a99f8a9c22c2c107c8996a7` | 与远端 master 完全一致 |
| 远端 master（ls-remote） | `c9ae012b22f8acd40a99f8a9c22c2c107c8996a7` | **SHA 逐位相同 = 历史完整同步** |
| 当前分支 | `master` | 远端仅此一条分支，已全部拉到 |
| 提交总数 | **17** 条 | `--all` 与 HEAD 计数一致，无游离提交丢失 |
| 标签 | 无 | 远端本来就没有 tag |
| 工作区状态 | 干净（`status --short` 无输出） | 无文件缺失/改动 |
| 跟踪文件数 | **574** 个 | 全部检出 |
| 子模块 | 无（无 `.gitmodules`） | 不存在「子模块没拉」的隐患 |
| 完整性校验 | `git fsck --full` 无任何报错 | 对象库无损坏、无悬空缺失 |
| 仓库体积 | 17.38 MB | |

## 二、仓库内容结构

```
WeCollege/
├── .gitignore
├── cloudfunctions/                       # 微信云开发·云函数
├── demo/                                 # 演示/素材
├── miniprogram/                          # 小程序前端（WXML/WXSS/JS）
├── project.config.json                   # 小程序项目配置
├── project.private.config.json           # 本地私有配置
├── README.md
└── 校园圈小程序安装使用手册.docx          # 1.19 MB
```

技术形态：**微信原生小程序 + 云开发**（云函数 + 云数据库），即「校园圈」小程序。

## 三、提交历史（共 17 条，最早 2022-02-27 → 最新 2024-03-25）

```
c9ae012 | 2024-03-25 | Update README.md
e343e0c | 2024-03-25 | CR
2dc7900 | 2024-03-25 | CR
43ed139 | 2023-12-23 | Update data_helper.js
7a94636 | 2023-04-07 | 安装手册
5e030d8 | 2023-04-07 | Delete 图书馆小程序安装使用手册.docx
b10294b | 2023-02-07 | Update cloud_helper.js
ba9c911 | 2022-04-11 | 安装手册
5dd4c04 | 2022-04-11 | Delete 图书馆微门户小程序安装手册.docx
3d39449 | 2022-04-11 | Update calendar_index.json
75b5600 | 2022-03-10 | Update README.md
5df6ac5 | 2022-02-28 | 手册
79112f0 | 2022-02-28 | 删除文件 图书馆微门户小程序安装手册.docx
6b34f6e | 2022-02-28 | update README.md.
7eb9207 | 2022-02-27 | 手册
2beaa16 | 2022-02-27 | update README.md.
9ecb4af | 2022-02-27 | Initial Commit
```

## 四、环境备注（下次遇到同样问题可直接照做）

本机访问 GitHub 的两条 HTTP 路径都不通：

- **直连** `github.com:443` → 超时；
- **走系统注入的进程级代理**（`http://127.0.0.1:<随机端口>`）→ `CONNECT tunnel failed, response 502`（该代理只放行 `api.github.com` 等少数域名）。

**可用通道是 SSH**：`ssh -T git@github.com` 返回
`Hi 3160534976! You've successfully authenticated` —— 22 端口与 443 端口（`ssh.github.com:443`）均可用。
故本次使用 **SSH 协议克隆**，绕开失效的 HTTP 通道。

## 五、当前远端配置

```
origin  git@github.com:3160534976/WeCollege.git (fetch)
origin  git@github.com:3160534976/WeCollege.git (push)
```

远端已正确绑定到目标 GitHub 仓库，后续要推送时可直接 `git push origin master`（**本次未执行**）。

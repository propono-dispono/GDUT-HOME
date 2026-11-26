@echo off
chcp 65001 >nul
title 校集 · SSH 隧道（保持此窗口不要关）

echo ============================================
echo   校集 · 数据库隧道
echo   MySQL  127.0.0.1:13307  ->  服务器 3307
echo   Redis  127.0.0.1:16379  ->  服务器 6379
echo ============================================
echo.
echo 这个窗口保持打开，隧道就一直通。
echo 关掉窗口 = 隧道断开 = 后端会报「服务器开小差了」。
echo.

ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes -i C:\Users\damn\.ssh\id_ed25519_sxzserver -N -L 13307:127.0.0.1:3307 -L 16379:127.0.0.1:6379 root@8.134.171.71

echo.
echo [!] 隧道已断开，按任意键退出
pause >nul

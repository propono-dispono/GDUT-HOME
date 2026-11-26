@echo off
chcp 65001 >nul
title 校集后台 · 一键启动

echo ==========================================
echo   校集管理后台 · 本地启动
echo ==========================================
echo.
echo 将依次打开两个窗口：
echo   1) SSH 隧道（MySQL 13307 / Redis 16379）
echo   2) 后台前端静态站（http://127.0.0.1:5174）
echo.
echo [重要] 两个窗口都别关，关掉对应服务就停。
echo.

echo [1/2] 启动隧道...
start "校集-隧道(勿关)" cmd /k "ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes -i C:\Users\damn\.ssh\id_ed25519_sxzserver -N -L 13307:127.0.0.1:3307 -L 16379:127.0.0.1:6379 root@8.134.171.71"

timeout /t 4 >nul

echo [2/2] 启动后台前端...
start "校集-前端(勿关)" cmd /k "cd /d E:\SchoolMarket\mall-admin-web\dist && C:\Users\damn\.workbuddy\binaries\python\versions\3.13.12\python.exe -m http.server 5174 --bind 127.0.0.1"

timeout /t 3 >nul

echo.
echo 启动完成，正在打开浏览器...
start http://127.0.0.1:5174/
echo.
echo 登录账号：admin    密码：admin123
echo.
echo 别忘了：还要在 IDEA 里点绿三角启动后端（8080）。
pause

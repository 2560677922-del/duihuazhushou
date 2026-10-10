@echo off
title 对话助手 - 启动中...
echo ============================================
echo   对话助手 · AI 写作 + 冒险平台
echo ============================================
echo.

cd /d "%~dp0"

REM 检查 Node.js
where node >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Node.js，请先安装：https://nodejs.org
    pause
    exit /b 1
)

REM 检查依赖
if not exist "node_modules" (
    echo [安装] 首次运行，正在安装依赖...
    call npm.cmd install
)

REM 启动开发服务器
echo [启动] 开发服务器启动中...
echo.
call npx.cmd vite --host --port 5173

pause

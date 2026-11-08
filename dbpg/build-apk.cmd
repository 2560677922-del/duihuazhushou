@echo off
setlocal
cd /d "%~dp0"

echo =============================================
echo   对话助手 - Android APK 打包
echo =============================================
echo.

REM ---------------------------------------------------------------- 0. 环境
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [准备] 缺少 gradle-wrapper.jar，先下载...
    node tools\fetch-wrapper.mjs || (echo [错误] 下载失败 & pause & exit /b 1)
)

if not exist "app\src\main\res\mipmap-xxxhdpi\ic_launcher.png" (
    echo [准备] 缺少图标，先生成...
    node tools\make-icons.cjs || (echo [错误] 图标生成失败 & pause & exit /b 1)
)

REM ---------------------------------------------------------------- 1. SDK
if exist "local.properties" goto sdk_ok

REM 注意：这里故意不列 ProgramFiles(x86)，因为 (x86) 在 for 的括号列表里会被
REM cmd.exe 当成块结束符，直接把脚本解析坏。真要走那个位置就手写 local.properties。
for %%P in ("%LocalAppData%\Android\Sdk" "%LocalAppData%\Programs\Android Studio\sdk" "%ProgramFiles%\Android\Android Studio\sdk" "%ProgramFiles%\Android\Sdk") do if exist "%%~P\platform-tools" (
    echo [发现] Android SDK: %%~P
    node -e "require('fs').writeFileSync(process.argv[1],'sdk.dir='+process.argv[2].replace(/\\/g,'/')+'\r\n',{encoding:'utf8'})" local.properties "%%~P"
    goto sdk_ok
)

echo.
echo [错误] 找不到 Android SDK。
echo.
echo        请先安装 Android Studio: https://developer.android.com/studio
echo        装好后打开它、随便新建一个 Empty Activity 项目（让它把 SDK 初始化出来），
echo        然后关掉、回来重新跑这个脚本。
echo.
echo        如果 SDK 装在别的位置，就在 dbpg\ 下手动新建 local.properties，内容是：
echo        sdk.dir=你的SDK路径
echo.
pause
exit /b 1

:sdk_ok
echo.
REM ---------------------------------------------------------------- 2. Web
echo [1/3] 构建 Web 版本（在上一级目录执行 npm run build）...
cd /d "%~dp0\.."
where node >nul 2>nul
if errorlevel 1 (
    echo [错误] 找不到 node，请先安装 Node.js: https://nodejs.org
    pause
    exit /b 1
)
call npm run build
if errorlevel 1 (
    echo.
    echo [错误] Web 版本构建失败，请到上一级目录单独跑 npm run build 看具体报错。
    pause
    exit /b 1
)
cd /d "%~dp0"

REM ---------------------------------------------------------------- 3. APK
echo.
echo [2/3] 打包 APK（第一次会下载 Gradle 和依赖，需要联网，可能要几分钟）...
call gradlew.bat :app:assembleDebug
if errorlevel 1 (
    echo.
    echo [错误] Android 打包失败。常见原因见 dbpg\README.md 的「常见报错」一节。
    pause
    exit /b 1
)

REM ---------------------------------------------------------------- 4. 结果
echo.
echo [3/3] 完成！
echo.
dir /b "app\build\outputs\apk\debug\*.apk" 2>nul
echo.
echo APK 完整路径:
echo   %cd%\app\build\outputs\apk\debug\app-debug.apk
echo.
echo 拷到手机上安装即可（需要开启「允许安装未知来源应用」）。
echo.
pause
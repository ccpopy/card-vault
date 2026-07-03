@echo off
rem CardVault 一键构建脚本（无需 Android Studio）
rem 依赖：scoop 安装的 Temurin JDK 17、F:\tools\gradle-8.7、F:\android-sdk
rem 用法：build.bat          -> 构建 debug APK
rem       build.bat release  -> 构建 release APK（未签名）
rem       build.bat install  -> 构建并通过 adb 安装到已连接的手机

setlocal
set "JAVA_HOME=%USERPROFILE%\scoop\apps\temurin17-jdk\current"
set "GRADLE=F:\tools\gradle-8.7\bin\gradle.bat"

if "%1"=="release" (
    call "%GRADLE%" -p "%~dp0." :app:assembleRelease
    echo APK: app\build\outputs\apk\release\
    goto :eof
)

if "%1"=="install" (
    call "%GRADLE%" -p "%~dp0." :app:assembleDebug
    F:\android-sdk\platform-tools\adb.exe install -r "%~dp0app\build\outputs\apk\debug\app-debug.apk"
    goto :eof
)

call "%GRADLE%" -p "%~dp0." :app:assembleDebug
echo APK: app\build\outputs\apk\debug\app-debug.apk
endlocal

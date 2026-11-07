@echo off
rem 安卓构建入口包装，转调 build-android.ps1
rem 用法: build-android.bat [test|clean|-- :app:assembleRelease ...]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-android.ps1" %*
exit /b %ERRORLEVEL%

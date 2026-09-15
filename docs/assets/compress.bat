@echo off
setlocal enabledelayedexpansion

for /r %%i in (*.png) do (
    echo Обработка PNG: "%%i"
    ffmpeg -y -i "%%i" -c:v libwebp -quality 80 "%%~dpni.webp" > nul 2>&1
    if exist "%%~dpni.webp" (
        del "%%i"
    )
)

pause

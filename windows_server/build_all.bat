@echo off
rem ============================================================
rem  Полная сборка всех вариантов TouchCursorServer.exe
rem  Результат в dist\:
rem    TouchCursorServer.exe      - Wi-Fi (не требует зависимостей)
rem    TouchCursorServer-BT.exe   - Bluetooth (pyserial)
rem    TouchCursorServer-USB.exe  - USB/AOA   (pyusb + libusb-1.0.dll)
rem ============================================================
setlocal
cd /d "%~dp0"

call build_exe.bat     || exit /b 1
call build_exe_bt.bat  || exit /b 1
call build_exe_usb.bat || exit /b 1

echo Все сборки готовы в папке dist\
pause

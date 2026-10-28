@echo off
rem ============================================================
rem  Сборка TouchCursorServer-USB.exe (канал USB/AOA)
rem  Дополнительно требует: pyusb + DLL libusb-1.0
rem  (libusb-win32/libusbK драйвер на телефоне ставится через Zadig,
rem   см. docs\РУКОВОДСТВО_ПОДКЛЮЧЕНИЯ.md)
rem ============================================================
setlocal
cd /d "%~dp0"

echo [1/3] Установка зависимостей...
python -m pip install --upgrade pyinstaller pyusb || goto :err

echo [2/3] Сборка EXE с pyusb...
echo   ВНИМАНИЕ: положите libusb-1.0.dll в каталог рядом с python
echo   (PyInstaller подхватит её автоматически), либо скопируйте
echo   готовый libusb-1.0.dll в dist\ после сборки.
python -m PyInstaller ^
  --noconfirm --clean --onefile --console ^
  --name TouchCursorServer-USB ^
  --paths . --hidden-import usb ^
  touchcursor_server_entry.py || goto :err

echo [3/3] Готово: dist\TouchCursorServer-USB.exe
echo   Запуск: TouchCursorServer-USB.exe --usb
pause
exit /b 0

:err
echo ОШИБКА сборки.
pause
exit /b 1

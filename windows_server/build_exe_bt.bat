@echo off
rem ============================================================
rem  Сборка TouchCursorServer-BT.exe (канал Bluetooth/SPP)
rem  Дополнительно требует: pyserial
rem ============================================================
setlocal
cd /d "%~dp0"

echo [1/3] Установка зависимостей...
python -m pip install --upgrade pyinstaller pyserial || goto :err

echo [2/3] Сборка EXE с pyserial...
python -m PyInstaller ^
  --noconfirm --clean --onefile --console ^
  --name TouchCursorServer-BT ^
  --paths . --hidden-import serial ^
  touchcursor_server_entry.py || goto :err

echo [3/3] Готово: dist\TouchCursorServer-BT.exe
echo   Запуск: TouchCursorServer-BT.exe --bt COM5
pause
exit /b 0

:err
echo ОШИБКА сборки.
pause
exit /b 1

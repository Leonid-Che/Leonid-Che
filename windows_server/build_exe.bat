@echo off
rem ============================================================
rem  Сборка TouchCursorServer.exe (Windows 10/11)
rem  Требуется: Python 3.9+ (установлен и доступен как "python")
rem  Результат:  dist\TouchCursorServer.exe  (один файл, без
rem              установки Python на целевом ПК)
rem ============================================================
setlocal
cd /d "%~dp0"

echo [1/3] Установка PyInstaller...
python -m pip install --upgrade pyinstaller || goto :err

echo [2/3] Сборка EXE (one-file, консольное приложение)...
python -m PyInstaller ^
  --noconfirm --clean --onefile --console ^
  --name TouchCursorServer ^
  --paths . ^
  touchcursor_server_entry.py || goto :err

echo [3/3] Готово!
echo   Файл: %cd%\dist\TouchCursorServer.exe
echo.
echo   Запуск по Wi-Fi:  TouchCursorServer.exe
echo   По USB (AOA):     TouchCursorServer.exe --usb   (нужен pyusb+libusb, см. build_exe_usb.bat)
echo   По Bluetooth:     TouchCursorServer.exe --bt COM5 (нужен pyserial, см. build_exe_bt.bat)
pause
exit /b 0

:err
echo ОШИБКА сборки. Проверьте, что python установлен и доступен в PATH.
pause
exit /b 1

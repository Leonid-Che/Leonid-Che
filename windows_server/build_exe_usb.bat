@echo off
rem ============================================================
rem  Сборка TouchCursorServer-USB.exe (канал USB/AOA)
rem  Дополнительно требует: pyusb + DLL libusb-1.0
rem  (libusb-win32/libusbK драйвер на телефоне ставится через Zadig,
rem   см. docs\РУКОВОДСТВО_ПОДКЛЮЧЕНИЯ.md)
rem ============================================================
setlocal
cd /d "%~dp0"

echo [1/4] Установка зависимостей...
python -m pip install --upgrade pyinstaller pyusb || goto :err

echo [2/4] Сборка EXE с pyusb...
echo   ВНИМАНИЕ: положите libusb-1.0.dll в каталог рядом с python
echo   (PyInstaller подхватит её автоматически), либо скопируйте
echo   готовый libusb-1.0.dll в dist\ после сборки.
python -m PyInstaller ^
  --noconfirm --clean --onefile --console ^
  --name TouchCursorServer-USB ^
  --paths . --hidden-import usb --hidden-import usb.core --hidden-import usb.util ^
  --collect-submodules usb ^
  touchcursor_server_entry.py || goto :err

echo [3/4] Копирование libusb-1.0.dll в dist\ ...
rem Ищем DLL: рядом со скриптом -> рядом с Python -> в PATH
if exist "libusb-1.0.dll" copy /y "libusb-1.0.dll" "dist\" >nul && goto :done
for /f "delims=" %%P in ('python -c "import sys,os;print(os.path.dirname(sys.executable))"') do set PYDIR=%%P
if exist "%PYDIR%\libusb-1.0.dll" copy /y "%PYDIR%\libusb-1.0.dll" "dist\" >nul && goto :done
where libusb-1.0.dll >nul 2>&1 && for /f "delims=" %%F in ('where libusb-1.0.dll') do copy /y "%%F" "dist\" >nul && goto :done

echo   НЕ НАЙДЕН libusb-1.0.dll! Скопируйте его в dist\ вручную,
echo   иначе запуск с --usb завершится ошибкой "No backend available".

:done
echo [4/4] Готово: dist\TouchCursorServer-USB.exe
echo   Запуск: TouchCursorServer-USB.exe --usb
pause
exit /b 0

:err
echo ОШИБКА сборки.
pause
exit /b 1

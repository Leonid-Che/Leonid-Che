@echo off
rem =====================================================================
rem  build_exe.bat — автоматическая сборка TabletTouchServer.exe
rem  Запускать на Windows 10/11 (x64). Требуется Python 3.10–3.12.
rem =====================================================================
setlocal
cd /d "%~dp0"

echo [1/4] Проверка Python...
py -3.12 --version >nul 2>&1 && set PY=py -3.12
if not defined PY (
    py -3.11 --version >nul 2>&1 && set PY=py -3.11
)
if not defined PY (
    python --version >nul 2>&1 && set PY=python
)
if not defined PY (
    echo Не найден Python. Установите python.org/downloads/windows/
    pause & exit /b 1
)
echo     Использую: %PY%

echo [2/4] Установка зависимостей (PyInstaller, av, mss, websockets, numpy)...
%PY% -m pip install --upgrade pip >nul
%PY% -m pip install pyinstaller numpy mss websockets "av>=12" || (
    echo Ошибка установки пакетов & pause & exit /b 1)

echo [3/4] Сборка exe (PyInstaller onefile)...
pushd pc_server
%PY% -m PyInstaller --noconfirm --clean tabletserver.spec || (popd & pause & exit /b 1)
popd

echo [4/4] Копирование результата...
if exist pc_server\dist\TabletTouchServer.exe (
    copy /y pc_server\dist\TabletTouchServer.exe TabletTouchServer.exe >nul
    copy /y pc_server\config.json config.json >nul 2>&1
    echo.
    echo ================================================
    echo  ГОТОВО: %cd%\TabletTouchServer.exe
    echo  Рядом лежит редактируемый config.json
    echo ================================================
) else (
    echo Не найден собранный exe — смотрите вывод выше
)
pause

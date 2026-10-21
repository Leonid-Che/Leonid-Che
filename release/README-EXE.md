# TabletTouchServer.exe — сервер для Windows 10

## Почему exe нельзя выдать из этой среды
Готовый .exe может собрать только PyInstaller **на самой Windows** (он пакует
системные DLL и Windows-загрузчик интерпретатора). Эта рабочая среда — Linux,
поэтому здесь собран и протестирован «дженерик»-бинарь, а для Windows
подготовлен автоматический скрипт сборки.

## Сборка за 1 шаг (на вашем ПК с Windows 10)
1. Установите Python 3.10–3.12 (https://python.org/downloads/windows),
   поставив галочку «Add python.exe to PATH».
2. Скопируйте папку проекта на компьютер.
3. Дважды щёлкните **build_exe.bat** (в корне проекта).
   Скрипт сам: установит PyInstaller + numpy/mss/websockets/av, соберёт
   `pc_server\dist\TabletTouchServer.exe` и положет готовый файл в корень.

Ручная сборка, если предпочитаете консоль:
```bat
cd pc_server
py -m pip install pyinstaller numpy mss websockets "av>=12"
py -m PyInstaller --noconfirm --clean tabletserver.spec
```

## Запуск
- Двойной клик `TabletTouchServer.exe` → в консоли появятся адрес Wi-Fi
  (`ws://192.168.x.x:8765`) и инструкция для USB (`adb forward tcp:8765 tcp:8765`).
- Рядом с exe автоматически создаётся редактируемый `config.json`
  (fps, битрейт, порт, монитор, токен доступа).
- Windows при первом запуске спросит разрешение на доступ к сети —
  нажмите «Разрешить» (для частной сети).

## Настройка под видеокарту
В `config.json` поле `"encoder"`:
- `"auto"` — NVENC если есть RTX/GTX, иначе libx264 (по умолчанию);
- `"nvenc"` / `"x264"` — принудительно.
Для слабых CPU уменьшите `"bitrate_mbps": 4` и `"max_width": 1280`.

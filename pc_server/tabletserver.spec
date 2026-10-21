# -*- mode: python ; coding: utf-8 -*-
# Сборка сервера TabletTouch в один .exe для Windows 10 (PyInstaller).
#   cd pc_server
#   pyinstaller --noconfirm tabletserver.spec
# Результат: dist\TabletTouchServer.exe
from PyInstaller.utils.hooks import collect_submodules, collect_data_files

# mss — платформенные подмодули; av тянет ffmpeg-бинарники через свои хуки
hiddenimports = ['mss.windows', 'mss.darwin', 'mss.linux'] + collect_submodules('av')
datas = [('config.json', '.')] + collect_data_files('av')

a = Analysis(
    ['main.py'],
    pathex=['.'],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['tkinter', 'unittest', 'psutil'],
    noarchive=False,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='TabletTouchServer',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,          # с консолью: видны IP и логи; поставьте False для скрытия окна
    icon='appicon.ico',
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

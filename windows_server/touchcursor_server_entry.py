# -*- coding: utf-8 -*-
"""Точка входа для сборки EXE (PyInstaller).

Импортирует пакет touchcursor_server (он лежит рядом с этим файлом) и
вызывает _run(). Отдельный файл нужен, чтобы PyInstaller собрал пакет
целиком. Аргументы командной строки (--usb, --bt COM5, --port) передаются
как обычному запуску модуля.

Сборка: build_exe.bat / build_exe_bt.bat / build_exe_usb.bat / build_all.bat
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from touchcursor_server.server import _run

if __name__ == "__main__":
    _run()

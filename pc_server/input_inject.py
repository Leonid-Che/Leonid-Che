"""
input_inject.py — инъекция ввода в Windows (SendInput) без сторонних библиотек.

Режимы:
  absolute — курсор ставится точно в точку касания (нормализация на диапазон
             0..65535 через ABSOLUTE|VIRTUAL_DESKTOP — работает на всех мониторах);
  relative — генерируются смещения мыши (стабильнее при нестандартных DPI).

Клик/перетаскивание реализованы как LBUTTONDOWN/UP/MOVE; колесо — MOUSEWHEEL.
Мультитач-жесты (щипок → Ctrl+колесо) собираются в main.py.
"""

from __future__ import annotations

import ctypes
from ctypes import wintypes

user32 = ctypes.WinDLL("user32", use_last_error=True)

INPUT_MOUSE = 0
INPUT_KEYBOARD = 1

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_ABSOLUTE = 0x8000
MOUSEEVENTF_VIRTUALDESK = 0x4000

KEYEVENTF_KEYUP = 0x0002

VK_CONTROL = 0x11
VK_SHIFT = 0x10
VK_ALT = 0x12
VK_LWIN = 0x5B


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG),
                ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong))]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong))]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD),
                ("wParamH", wintypes.WORD)]


class INPUT_UNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("union", INPUT_UNION)]


_user32_SendInput = user32.SendInput
_user32_SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
_user32_SendInput.restype = wintypes.UINT
_INPUT_SIZE = ctypes.sizeof(INPUT)


def _send(inputs: list[INPUT]) -> int:
    if not inputs:
        return 0
    arr = (INPUT * len(inputs))(*inputs)
    sent = _user32_SendInput(len(inputs), arr, _INPUT_SIZE)
    if sent != len(inputs):
        # Часто бывает, когда блок безопасности перехватывает ввод (UAC-экран).
        err = ctypes.get_last_error()
        if err:
            raise ctypes.WinError(err)
    return sent


def _mouse(dx: int, dy: int, flags: int, data: int = 0) -> INPUT:
    inp = INPUT(type=INPUT_MOUSE)
    inp.union.mi = MOUSEINPUT(dx=dx, dy=dy, mouseData=data, dwFlags=flags,
                              time=0, dwExtraInfo=None)
    return inp


def move_absolute(nx: float, ny: float) -> None:
    """Переместить курсор в нормализованные координаты [0..1] всего рабочего стола."""
    nx = min(max(nx, 0.0), 1.0)
    ny = min(max(ny, 0.0), 1.0)
    dx = int(round(nx * 65535))
    dy = int(round(ny * 65535))
    _send([_mouse(dx, dy, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK)])


def move_relative(dx: int, dy: int) -> None:
    _send([_mouse(int(dx), int(dy), MOUSEEVENTF_MOVE)])


def button_down(button: str = "left") -> None:
    flag = {"left": MOUSEEVENTF_LEFTDOWN, "right": MOUSEEVENTF_RIGHTDOWN,
            "middle": MOUSEEVENTF_MIDDLEDOWN}[button]
    _send([_mouse(0, 0, flag)])


def button_up(button: str = "left") -> None:
    flag = {"left": MOUSEEVENTF_LEFTUP, "right": MOUSEEVENTF_RIGHTUP,
            "middle": MOUSEEVENTF_MIDDLEUP}[button]
    _send([_mouse(0, 0, flag)])


def click(button: str = "left") -> None:
    button_down(button)
    button_up(button)


def wheel(ticks: int) -> None:
    """Колесо мыши: ticks>0 — от себя; 120 = одна «щелчок»."""
    _send([_mouse(0, 0, MOUSEEVENTF_WHEEL, (ticks * 120) & 0xFFFFFFFF)])


def key_down(vk: int) -> None:
    inp = INPUT(type=INPUT_KEYBOARD)
    inp.union.ki = KEYBDINPUT(wVk=vk, wScan=0, dwFlags=0, time=0, dwExtraInfo=None)
    _send([inp])


def key_up(vk: int) -> None:
    inp = INPUT(type=INPUT_KEYBOARD)
    inp.union.ki = KEYBDINPUT(wVk=vk, wScan=0, dwFlags=KEYEVENTF_KEYUP,
                              time=0, dwExtraInfo=None)
    _send([inp])


def pinch_begin() -> None:
    """Начало жеста «щипок»: зажать Ctrl (Windows: Ctrl+колесо = масштаб)."""
    key_down(VK_CONTROL)


def pinch_end() -> None:
    key_up(VK_CONTROL)

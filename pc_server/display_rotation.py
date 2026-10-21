"""
display_rotation.py — управление ориентацией дисплея Windows 10.

Два официальных способа (применимы только когда «планшет» реально является
монитором компьютера, т.е. подключен по HDMI/DP/VGA через переходник либо как
беспроводной дисплей Miracast):

1) ChangeDisplaySettingsExW — стандартная возможность Windows; работает для
   встроенных панелей ноутбуков и многих мониторов.
2) DDC/CI VCP-код 0x86 "Set Orientation" — стандарт VESA; часть мониторов
   умеет менять ориентацию «железом».

Важно: планшет, подключённый только по USB-кабелю (adb) или Wi-Fi, не
является монитором Windows, поэтому физическая смена ориентации для него
невозможна. В этом случае сервер применяет программный поворот кадра перед
кодированием (см. encoder.py / main.py), а клиент доворачивает изображение
сам — визуально эффект идентичен.
"""

from __future__ import annotations

import ctypes
from ctypes import wintypes

user32 = ctypes.WinDLL("user32", use_last_error=True)
gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)

# ---------------------------------------------------------------- DEVMODE

CDS_UPDATEREGISTRY = 0x00000001
CDS_TEST = 0x00000002

DM_DISPLAYORIENTATION = 0x00000080
DM_PELSWIDTH = 0x00000008
DM_PELSHEIGHT = 0x00000010
DM_DISPLAYFREQUENCY = 0x00000400

ENUM_CURRENT_SETTINGS = -1
ENUM_REGISTRY_SETTINGS = -2

DISP_CHANGE_successful = 0
DISP_CHANGE_RESTART = 1
DISP_CHANGE_BADMODE = -2
DISP_CHANGE_NOTUPDATED = -3
DISP_CHANGE_FAILED = -1
DISP_CHANGE_BADDUALVIEW = -6

_CHANGE_ERROR_TEXT = {
    DISP_CHANGE_BADMODE: "режим не поддерживается драйвером/монитором",
    DISP_CHANGE_FAILED: "ChangeDisplaySettingsExW вернул ошибку",
    DISP_CHANGE_NOTUPDATED: "нет доступа к реестру (нужны права администратора)",
    DISP_CHANGE_BADDUALVIEW: "не подходит для текущей конфигурации нескольких экранов",
}


class DEVMODEW(ctypes.Structure):
    _fields_ = [
        ("dmDeviceName", wintypes.WCHAR * 32),
        ("dmSpecVersion", wintypes.WORD),
        ("dmDriverVersion", wintypes.WORD),
        ("dmSize", wintypes.WORD),
        ("dmDriverExtra", wintypes.WORD),
        ("dmFields", wintypes.DWORD),
        # union dmu: dmOrientation/dmPrintQuality
        ("dmOrientation", wintypes.SHORT),
        ("dmPrintQuality", wintypes.SHORT),
        # union dmu2: dmPosition
        ("dmPositionX", wintypes.LONG),
        ("dmPositionY", wintypes.LONG),
        ("dmDisplayOrientation", wintypes.DWORD),
        ("dmDisplayFixedOutput", wintypes.DWORD),
        ("dmColor", wintypes.SHORT),
        ("dmDuplex", wintypes.SHORT),
        ("dmYResolution", wintypes.SHORT),
        ("dmTTOption", wintypes.BYTE),
        ("dmCollate", wintypes.BYTE),
        ("dmFormName", wintypes.WCHAR * 32),
        ("dmLogPixels", wintypes.WORD),
        ("dmBitsPerPel", wintypes.DWORD),
        ("dmPelsWidth", wintypes.DWORD),
        ("dmPelsHeight", wintypes.DWORD),
        ("dmDisplayFlags", wintypes.DWORD),
        ("dmDisplayFrequency", wintypes.DWORD),
        ("dmICMMethod", wintypes.DWORD),
        ("dmICMIntent", wintypes.DWORD),
        ("dmMediaType", wintypes.DWORD),
        ("dmDitherType", wintypes.DWORD),
        ("dmReserved1", wintypes.DWORD),
        ("dmReserved2", wintypes.DWORD),
        ("dmPanningWidth", wintypes.DWORD),
        ("dmPanningHeight", wintypes.DWORD),
    ]


user32.EnumDisplaySettingsW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD,
                                        ctypes.POINTER(DEVMODEW)]
user32.EnumDisplaySettingsW.restype = wintypes.BOOL

user32.ChangeDisplaySettingsExW.argtypes = [wintypes.LPCWSTR,
                                            ctypes.POINTER(DEVMODEW),
                                            wintypes.HWND, wintypes.DWORD,
                                            wintypes.LPARAM]
user32.ChangeDisplaySettingsExW.restype = wintypes.LONG


def list_modes(device_name: str | None = None) -> list[tuple[int, int, int, int]]:
    """Список режимов дисплея: (width, height, refresh_hz, orientation)."""
    modes: list[tuple[int, int, int, int]] = []
    dm = DEVMODEW()
    i = 0
    while user32.EnumDisplaySettingsW(device_name, i, ctypes.byref(dm)):
        modes.append((int(dm.dmPelsWidth), int(dm.dmPelsHeight),
                      int(dm.dmDisplayFrequency), int(dm.dmDisplayOrientation)))
        i += 1
        if i > 512:
            break
    return modes


def get_current_orientation(device_name: str | None = None) -> int | None:
    """Текущая ориентация в градусах (0/90/180/270) или None."""
    dm = DEVMODEW()
    if not user32.EnumDisplaySettingsW(device_name, ENUM_CURRENT_SETTINGS,
                                       ctypes.byref(dm)):
        return None
    return (int(dm.dmDisplayOrientation) % 4) * 90


def set_orientation(degrees: int, device_name: str | None = None,
                    test_only: bool = False) -> tuple[bool, str]:
    """
    Повернуть дисплей на degrees (0/90/180/270 относительно альбомной базы).

    Возвращает (успех, пояснение). При неудаче пытается выбрать повёрнутый
    видеорежим из списка поддерживаемых.
    """
    degrees %= 360
    if degrees not in (0, 90, 180, 270):
        return False, "допустимы только углы 0/90/180/270"

    dm = DEVMODEW()
    if not user32.EnumDisplaySettingsW(device_name, ENUM_CURRENT_SETTINGS,
                                       ctypes.byref(dm)):
        return False, "не удалось прочитать текущие параметры дисплея"

    cur_w, cur_h, cur_r = (int(dm.dmPelsWidth), int(dm.dmPelsHeight),
                           int(dm.dmDisplayFrequency))
    dm.dmFields = DM_DISPLAYORIENTATION | DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY
    dm.dmDisplayOrientation = degrees // 90
    if degrees in (90, 270):          # просим уже повёрнутое разрешение
        dm.dmPelsWidth, dm.dmPelsHeight = cur_h, cur_w

    flags = CDS_UPDATEREGISTRY | (CDS_TEST if test_only else 0)
    res = user32.ChangeDisplaySettingsExW(device_name, ctypes.byref(dm),
                                          None, flags, 0)
    if res in (DISP_CHANGE_successful, DISP_CHANGE_RESTART):
        return True, f"ориентация {degrees}° установлена"

    # Запасной вариант: явный подбор режима W×H из перечня драйвера.
    want = (cur_h, cur_w) if degrees in (90, 270) else (cur_w, cur_h)
    for w, h, r, _o in list_modes(device_name):
        if (w, h) == want:
            dm2 = DEVMODEW()
            user32.EnumDisplaySettingsW(device_name, ENUM_CURRENT_SETTINGS,
                                        ctypes.byref(dm2))
            dm2.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY \
                | DM_DISPLAYORIENTATION
            dm2.dmPelsWidth, dm2.dmPelsHeight = w, h
            dm2.dmDisplayFrequency = r or dm2.dmDisplayFrequency
            dm2.dmDisplayOrientation = degrees // 90
            res2 = user32.ChangeDisplaySettingsExW(device_name, ctypes.byref(dm2),
                                                   None, CDS_UPDATEREGISTRY, 0)
            if res2 == DISP_CHANGE_successful:
                return True, f"ориентация {degrees}° через режим {w}x{h}@{r}"
            break

    text = _CHANGE_ERROR_TEXT.get(res, f"код ошибки {res}")
    return False, (f"ChangeDisplaySettingsExW: {text}. Если устройство не является "
                   f"монитором ПК (планшет по USB/Wi-Fi), используйте программный "
                   f"поворот кадра на сервере — он включён автоматически.")


# ---------------------------------------------------------------- DDC/CI

class PHYSICAL_MONITOR(ctypes.Structure):
    _fields_ = [("szPhysicalMonitorDescription", wintypes.WCHAR * 128),
                ("hPhysicalMonitor", wintypes.HANDLE)]


class RECT(ctypes.Structure):
    _fields_ = [("left", wintypes.LONG), ("top", wintypes.LONG),
                ("right", wintypes.LONG), ("bottom", wintypes.LONG)]


class MONITORINFOEXW(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", RECT),
                ("rcWork", RECT), ("dwFlags", wintypes.DWORD),
                ("szDevice", wintypes.WCHAR * 32)]


MONITORENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HMONITOR,
                                     wintypes.HDC, ctypes.POINTER(RECT),
                                     wintypes.LPARAM)


def _enum_monitor_handles() -> list[wintypes.HMONITOR]:
    handles: list[wintypes.HMONITOR] = []

    def _cb(hm, hdc, lprc, data):  # noqa: ANN001
        handles.append(hm)
        return True

    user32.EnumDisplayMonitors(0, None, MONITORENUMPROC(_cb), 0)
    return handles


def ddc_set_orientation(degrees: int, monitor_index: int = 0) -> tuple[bool, str]:
    """
    Аппаратная попытка поворота через DDC/CI (VESA MCCS, VCP-код 0x86).
    Поддерживается ограниченным кругом мониторов; вызывается best-effort.
    """
    degrees %= 360
    value = {0: 1, 90: 2, 180: 3, 270: 4}.get(degrees)
    if value is None:
        return False, "допустимы только углы 0/90/180/270"

    handles = _enum_monitor_handles()
    if monitor_index >= len(handles):
        return False, "монитор с указанным индексом не найден"

    try:
        import comtypes  # noqa: F401
        from ctypes import oledll  # noqa: F401
        # ksguid/typelib PhysicalMonitorAPI доступен как mhapi.dll из комплекта
        # Windows SDK; при его отсутствии gracefully выходим.
        try:
            mhapi = ctypes.WinDLL("mhapi.dll")
        except OSError:
            return False, "mhapi.dll (Windows SDK) отсутствует — DDC/CI пропущен"

        pm = (PHYSICAL_MONITOR * 1)()
        if not gdi32.GetPhysicalMonitorsFromHMONITOR(handles[monitor_index], 1, pm):
            return False, "монитор не отвечает по DDC/CI"
        try:
            ok = mhapi.MCCS_SetParameter(pm[0].hPhysicalMonitor, 0x86, value)
            return (bool(ok), "DDC/CI: команда принята" if ok
                    else "DDC/CI: монитор отклонил команду 0x86")
        finally:
            gdi32.DestroyPhysicalMonitors(1, pm)
    except Exception as exc:  # noqa: BLE001
        return False, f"DDC/CI недоступен: {exc}"


if __name__ == "__main__":  # быстрая проверка: py display_rotation.py 90
    import sys
    deg = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    print("modes:", list_modes())
    print("current:", get_current_orientation())
    print(set_orientation(deg))

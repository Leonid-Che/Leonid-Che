"""USB-канал Windows-сервера: приём команд от смартфона по Android Accessory (AOA) v1.

Смартфон в режиме AOA выглядит для Windows как аксессуар с VID=0x18D1, PID=0x2D70
(идентификаторы должны совпадать с android/.../UsbTransport.java). Сервер открывает
его интерфейс с двумя bulk-эндпоинтами и предоставляет файлoподобный объект
(read/write), который ClientHandler использует вместо TCP-сокета.

Требуется: pip install pyusb libusb (Windows: пакет libusbK/libusb через Zadig
или драйвер WinUSB из комплекта Android USB Driver).
"""

import importlib
import sys
import time


def _import_usb():
    """Импортирует pyusb с понятной диагностикой.

    Вызывается лениво (из serve_usb), поэтому Wi-Fi-версия EXE работает без pyusb.
    Если модуль не найден — печатает подсказку и завершает процесс с кодом 1,
    чтобы пользователь EXE не видел «голый» ModuleNotFoundError.
    """
    try:
        return importlib.import_module("usb.core"), importlib.import_module("usb.util")
    except ImportError:
        if getattr(sys, "frozen", False):
            msg = (
                "Не найден модуль 'usb' (pyusb).\n\n"
                "EXE собран скриптом build_exe.bat (Wi-Fi-версия), в который pyusb "
                "не включён.\nДля канала USB соберите TouchCursorServer-USB.exe "
                "скриптом build_exe_usb.bat\nили запустите сервер из Python:\n\n"
                "  pip install pyusb\n"
                "  python -m touchcursor_server --usb"
            )
        else:
            msg = (
                "Не найден модуль 'usb' (pyusb). Установите его командой:\n\n"
                "  pip install pyusb\n\n"
                "(виртуальное окружение должно быть активировано)"
            )
        print(msg, file=sys.stderr)
        try:  # для frozen-сборки дополнительно покажем окно сообщения
            import ctypes

            ctypes.windll.user32.MessageBoxW(None, msg.replace("\n\n", "\n"),
                                             "TouchCursor Server: нет pyusb", 0x10)
        except Exception:
            pass
        sys.exit(1)

# Совпадают с UsbTransport.java на стороне Android.
VENDOR_ID = 0x18D1
PRODUCT_ID = 0x2D70

EP_IN_MASK = 0x80  # бит направления в адресе эндпоинта


def find_accessory_device(timeout_s: float = 0.0):
    """Ищет AOA-аксессуар TouchCursor; при timeout_s>0 ждёт его появления."""
    usb_core, _ = _import_usb()
    deadline = time.time() + timeout_s
    while True:
        dev = usb_core.find(idVendor=VENDOR_ID, idProduct=PRODUCT_ID)
        if dev is not None:
            return dev
        if time.time() >= deadline:
            return None
        time.sleep(0.3)


class AoaChannel:
    """Файлoподобная обёртка над bulk-эндпоинтами AOA-устройства.

    Интерфейс совместим с ожиданиями server.ClientHandler:
    read(n) / write(data) / close().
    """

    def __init__(self, dev):
        _, usb_util = _import_usb()
        self.dev = dev
        dev.set_configuration()
        cfg = dev.get_active_configuration()
        intf = cfg[(0, 0)]

        self.ep_out = usb_util.find_descriptor(
            intf,
            custom_match=lambda e: usb_util.endpoint_direction(e.bEndpointAddress)
            == usb_util.ENDPOINT_OUT,
        )
        self.ep_in = usb_util.find_descriptor(
            intf,
            custom_match=lambda e: usb_util.endpoint_direction(e.bEndpointAddress)
            == usb_util.ENDPOINT_IN,
        )
        if self.ep_out is None or self.ep_in is None:
            raise RuntimeError("Bulk-эндпоинты AOA не найдены на устройстве.")
        self._rx = bytearray()
        self._closed = False

    def describe(self) -> str:
        return "USB (AOA %04X:%04X)" % (self.dev.idVendor, self.dev.idProduct)

    def read(self, n: int = 4096) -> bytes:
        """Читает до n байт из bulk-IN; пустая строка означает закрытие канала."""
        if self._closed:
            return b""
        try:
            data = bytes(self.ep_in.read(max(n, 64), 1000))
        except Exception:
            # таймаут чтения допустим - просто возвращаем накопленное/пустое
            if self._rx:
                out = bytes(self._rx[:n])
                del self._rx[: len(out)]
                return out
            return b""
        if not data and self._rx:
            out = bytes(self._rx[:n])
            del self._rx[: len(out)]
            return out
        return data

    def write(self, data: bytes) -> int:
        if self._closed:
            raise OSError("channel closed")
        return self.ep_out.write(bytes(data)) or len(data)

    def close(self):
        self._closed = True
        try:
            _, usb_util = _import_usb()
            usb_util.dispose_resources(self.dev)
        except Exception:
            pass

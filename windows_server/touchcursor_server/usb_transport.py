"""USB-канал Windows-сервера: приём команд от смартфона по Android Accessory (AOA) v1.

Смартфон в режиме AOA выглядит для Windows как аксессуар с VID=0x18D1, PID=0x2D70
(идентификаторы должны совпадать с android/.../UsbTransport.java). Сервер открывает
его интерфейс с двумя bulk-эндпоинтами и предоставляет файлoподобный объект
(read/write), который ClientHandler использует вместо TCP-сокета.

Требуется: pip install pyusb libusb (Windows: пакет libusbK/libusb через Zadig
или драйвер WinUSB из комплекта Android USB Driver).
"""

import time

import usb.core
import usb.util

# Совпадают с UsbTransport.java на стороне Android.
VENDOR_ID = 0x18D1
PRODUCT_ID = 0x2D70

EP_IN_MASK = 0x80  # бит направления в адресе эндпоинта


def find_accessory_device(timeout_s: float = 0.0):
    """Ищет AOA-аксессуар TouchCursor; при timeout_s>0 ждёт его появления."""
    deadline = time.time() + timeout_s
    while True:
        dev = usb.core.find(idVendor=VENDOR_ID, idProduct=PRODUCT_ID)
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
        self.dev = dev
        dev.set_configuration()
        cfg = dev.get_active_configuration()
        intf = cfg[(0, 0)]

        self.ep_out = usb.util.find_descriptor(
            intf,
            custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress)
            == usb.util.ENDPOINT_OUT,
        )
        self.ep_in = usb.util.find_descriptor(
            intf,
            custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress)
            == usb.util.ENDPOINT_IN,
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
            usb.util.dispose_resources(self.dev)
        except Exception:
            pass

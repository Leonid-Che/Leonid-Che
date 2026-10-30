# -*- coding: utf-8 -*-
"""
TCP-сервер для Windows 10. Принимает команды от Android-смартфона
и управляет курсором/мышью через Win32 API (user32).

Запуск:  python -m touchcursor_server [порт]
По умолчанию порт 5575.
"""
import argparse
import json
import logging
import socket
import threading

import win32api
import win32con

from . import protocol

log = logging.getLogger("touchcursor")

BUTTON_MAP = {
    "left": {
        "down": win32con.MOUSEEVENTF_LEFTDOWN,
        "up": win32con.MOUSEEVENTF_LEFTUP,
        "click": win32con.MOUSEEVENTF_LEFTDOWN | win32con.MOUSEEVENTF_LEFTUP,
    },
    "right": {
        "down": win32con.MOUSEEVENTF_RIGHTDOWN,
        "up": win32con.MOUSEEVENTF_RIGHTUP,
        "click": win32con.MOUSEEVENTF_RIGHTDOWN | win32con.MOUSEEVENTF_RIGHTUP,
    },
}


class InputController:
    """Экранирует Win32-инпут. Все координаты - абсолютные доли экрана (0..1)."""

    def __init__(self):
        self._last_abs = None  # кэш последней позиции для экономии вызовов

    def _screen_size(self):
        w = win32api.GetSystemMetrics(win32con.SM_CXSCREEN)
        h = win32api.GetSystemMetrics(win32con.SM_CYSCREEN)
        return max(1, w), max(1, h)

    def move_to(self, fx: float, fy: float):
        fx = min(max(float(fx), 0.0), 1.0)
        fy = min(max(float(fy), 0.0), 1.0)
        norm_x = int(fx * 65535)
        norm_y = int(fy * 65535)
        if self._last_abs == (norm_x, norm_y):
            return
        self._last_abs = (norm_x, norm_y)
        win32api.mouse_event(
            win32con.MOUSEEVENTF_MOVE
            | win32con.MOUSEEVENTF_ABSOLUTE
            | win32con.MOUSEEVENTF_VIRTUALDESK,
            norm_x, norm_y, 0, 0,
        )

    def move_rel(self, dx: int, dy: int):
        """Относительное перемещение курсора на dx/dy пикселей."""
        dx, dy = int(dx), int(dy)
        if dx or dy:
            self._last_abs = None  # относительное движение сбивает кэш абсолютной позиции
            win32api.mouse_event(win32con.MOUSEEVENTF_MOVE, dx, dy, 0, 0)

    def click(self, button: str, double: bool = False):
        flags = BUTTON_MAP[button]["click"]
        win32api.mouse_event(flags, 0, 0, 0, 0)
        if double:
            # Двойной клик на Windows - не два клика подряд, а событие CLICK +
            # повторный клик с интервалом меньше DoubleClickTime.
            win32api.mouse_event(flags, 0, 0, 0, 0)

    def screen_size(self):
        """Разрешение основного экрана в пикселях (для hello-ответа клиенту)."""
        w = win32api.GetSystemMetrics(win32con.SM_CXSCREEN)
        h = win32api.GetSystemMetrics(win32con.SM_CYSCREEN)
        return w, h

    def button_down(self, button: str):
        win32api.mouse_event(BUTTON_MAP[button]["down"], 0, 0, 0, 0)

    def button_up(self, button: str):
        win32api.mouse_event(BUTTON_MAP[button]["up"], 0, 0, 0, 0)

    def scroll(self, dy: float):
        step = int(round(dy * 120))
        if step:
            win32api.mouse_event(win32con.MOUSEEVENTF_WHEEL, 0, 0, step, 0)


class ClientHandler(threading.Thread):
    """Разговор с одним смартфоном. Транспорт не важен: подойдёт сокет
    (Wi-Fi/BT RFCOMM) или USB-канал AOA — лишь бы read()/write() были."""

    def __init__(self, conn, ctrl: InputController, peer: str = "?"):
        super().__init__(daemon=True)
        self.conn = conn
        self.ctrl = ctrl
        self.peer = peer
        self.buf = b""

    def _recv(self):
        if hasattr(self.conn, "recv"):
            return self.conn.recv(4096)
        return self.conn.read(4096)

    def _sendall(self, data: bytes):
        if hasattr(self.conn, "sendall"):
            self.conn.sendall(data)
        else:
            self.conn.write(data)

    def run(self):
        log.info("Клиент подключился: %s", self.peer)
        try:
            while True:
                data = self._recv()
                if not data:
                    break
                self.buf += data
                while b"\n" in self.buf:
                    line, self.buf = self.buf.split(b"\n", 1)
                    self.handle_line(line.decode("utf-8", errors="replace"))
        except (ConnectionError, OSError):
            pass
        finally:
            log.info("Клиент отключился: %s", self.peer)
            try:
                self.conn.close()
            except Exception:
                pass

    def handle_line(self, line: str):
        cmd = protocol.parse_line(line)
        if cmd is None:
            return
        name = cmd.get("cmd", "")
        try:
            if name == "hello":
                log.info("Устройство: %s (канал: %s)", cmd.get("name", "?"),
                         cmd.get("transport", "wifi"))
                w, h = self.ctrl.screen_size()
                self._sendall((json.dumps({
                    "resp": "ok", "cmd": "hello", "screen": [w, h],
                }) + "\n").encode("utf-8"))
            elif name == "move":
                self.ctrl.move_to(cmd.get("x", 0), cmd.get("y", 0))
            elif name == "move_rel":
                self.ctrl.move_rel(cmd.get("dx", 0), cmd.get("dy", 0))
            elif name == "click":
                btn = cmd.get("button", "left")
                if btn in protocol.BUTTONS:
                    self.ctrl.click(btn, bool(cmd.get("double")))
                else:
                    raise ValueError("неизвестная кнопка: %r" % btn)
            elif name == "down":
                btn = cmd.get("button", "left")
                if btn in protocol.BUTTONS:
                    self.ctrl.button_down(btn)
                else:
                    raise ValueError("неизвестная кнопка: %r" % btn)
            elif name == "up":
                btn = cmd.get("button", "left")
                if btn in protocol.BUTTONS:
                    self.ctrl.button_up(btn)
                else:
                    raise ValueError("неизвестная кнопка: %r" % btn)
            elif name == "scroll":
                self.ctrl.scroll(float(cmd.get("dy", 0)))
            else:
                raise ValueError("неизвестная команда: %r" % name)
            if name != "move":  # на move не отвечаем - слишком часто
                self._send_ok(name)
        except Exception as exc:  # noqa: BLE001
            log.warning("Ошибка команды %s: %s", name, exc)
            self._sendall(protocol.make_response(name, ok=False, msg=str(exc)))

    def _send_ok(self, name: str):
        try:
            self._sendall(protocol.make_response(name))
        except OSError:
            pass


def get_local_ips():
    """IP-адреса этого компьютера в локальной сети (для вывода в консоль)."""
    ips = []
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass
    return ips


def serve_tcp(ctrl: InputController, port: int):
    """Wi-Fi: TCP-сервер (смартфон подключается к IP компьютера)."""
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port))
    srv.listen(4)

    log.info("TCP-сервер (Wi-Fi) запущен на порту %d", port)
    for ip in get_local_ips():
        log.info("  IP в локальной сети: %s", ip)
    log.info("Откройте в брандмауэре Windows входящий TCP-порт %d для программы Python.", port)

    try:
        while True:
            conn, addr = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            ClientHandler(conn, ctrl, "%s:%d" % addr[:2]).start()
    finally:
        srv.close()


def serve_usb(ctrl: InputController):
    """USB: смартфон как AOA-аксессуар (pyusb). Кабель вместо сети."""
    from . import usb_transport

    dev = usb_transport.find_accessory_device()
    if dev is None:
        raise RuntimeError(
            "AOA-устройство TouchCursor не найдено. Подключите телефон USB-кабелем, "
            "выберите режим «Передача файлов» и включите управление кнопкой USB в приложении."
        )
    chan = usb_transport.AoaChannel(dev)
    log.info("USB-канал открыт: %s", chan.describe())
    ClientHandler(chan, ctrl, "USB/AOA").start()
    # Поток-обработчик живёт сам; ждём, пока пользователь не остановит сервер.
    threading.Event().wait()


def serve_bt(ctrl: InputController, com_port: str):
    """Bluetooth: COM-порт, привязанный к исходящему RFCOMM-соединению Windows."""
    import serial  # pyserial

    ser = serial.Serial(com_port, baudrate=9600, timeout=1)
    log.info("Bluetooth-канал открыт: %s", com_port)
    ClientHandler(ser, ctrl, com_port).start()
    threading.Event().wait()


def main(argv=None):
    parser = argparse.ArgumentParser(description="TouchCursor PC server (Windows)")
    parser.add_argument("--port", type=int, default=5575, help="TCP-порт (по умолчанию 5575)")
    parser.add_argument("--usb", action="store_true",
                        help="принимать команды по USB (Android Accessory, pyusb)")
    parser.add_argument("--bt", metavar="COM_PORT", default=None,
                        help="принимать команды по Bluetooth через COM-порт, напр. --bt COM5")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    ctrl = InputController()
    try:
        if args.usb:
            serve_usb(ctrl)
        elif args.bt:
            serve_bt(ctrl, args.bt)
        else:
            serve_tcp(ctrl, args.port)
    except KeyboardInterrupt:
        log.info("Остановка сервера.")


if __name__ == "__main__":
    main()

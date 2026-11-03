# -*- coding: utf-8 -*-
"""
TCP-сервер для Windows 10. Принимает команды от Android-смартфона
и управляет курсором/мышью через Win32 API (user32).

Запуск:  python -m touchcursor_server [порт]
По умолчанию порт 5575.
"""
import argparse
import ctypes
import errno
import json
import logging
import os
import select
import socket
import sys
import threading
import time
from ctypes.wintypes import DWORD, LONG

from . import protocol

log = logging.getLogger("touchcursor")


def _is_peer_gone(exc: OSError) -> bool:
    """Исключение означает, что ВТОРАЯ сторона закрыла соединение (FIN/RST),
    а не локальную проблему (таймаут, прерывание системным вызовом)."""
    if isinstance(exc, ConnectionError):
        return True
    if isinstance(exc, BlockingIOError):  # EAGAIN/EWOULDBLOCK — просто нет данных
        return False
    if exc.errno in (errno.EINTR, errno.EAGAIN, errno.EWOULDBLOCK,
                     errno.EALREADY, errno.EINPROGRESS, errno.ETIMEDOUT):
        return False
    return True

# --- Win32 API через ctypes (а не pywin32) -------------------------------
# Использование стандартного ctypes позволяет собрать сервер в один
# self-contained EXE (PyInstaller) без нативных DLL pywin32.
user32 = ctypes.WinDLL("user32", use_last_error=True)

SM_CXSCREEN = 0
SM_CYSCREEN = 1
SM_CXVIRTUALSCREEN = 7
SM_CYVIRTUALSCREEN = 8
SM_XVIRTUALSCREEN = 76
SM_YVIRTUALSCREEN = 77

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_VIRTUALDESK = 0x4000
MOUSEEVENTF_ABSOLUTE = 0x8000

user32.mouse_event.argtypes = [DWORD, LONG, LONG, LONG, ctypes.c_void_p]
user32.mouse_event.restype = UINT = DWORD
user32.GetSystemMetrics.argtypes = [ctypes.c_int]
user32.GetSystemMetrics.restype = ctypes.c_int


def _get_system_metrics(index: int) -> int:
    return user32.GetSystemMetrics(index)


BUTTON_MAP = {
    "left": {
        "down": MOUSEEVENTF_LEFTDOWN,
        "up": MOUSEEVENTF_LEFTUP,
        "click": MOUSEEVENTF_LEFTDOWN | MOUSEEVENTF_LEFTUP,
    },
    "right": {
        "down": MOUSEEVENTF_RIGHTDOWN,
        "up": MOUSEEVENTF_RIGHTUP,
        "click": MOUSEEVENTF_RIGHTDOWN | MOUSEEVENTF_RIGHTUP,
    },
}


class InputController:
    """Экранирует Win32-инпут. Все координаты - абсолютные доли экрана (0..1)."""

    def __init__(self):
        self._last_abs = None  # кэш последней позиции для экономии вызовов

    def _screen_size(self):
        w = _get_system_metrics(SM_CXSCREEN)
        h = _get_system_metrics(SM_CYSCREEN)
        return max(1, w), max(1, h)

    def move_to(self, fx: float, fy: float):
        fx = min(max(float(fx), 0.0), 1.0)
        fy = min(max(float(fy), 0.0), 1.0)
        norm_x = int(fx * 65535)
        norm_y = int(fy * 65535)
        if self._last_abs == (norm_x, norm_y):
            return
        self._last_abs = (norm_x, norm_y)
        user32.mouse_event(
            MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK,
            norm_x, norm_y, 0, None,
        )

    def move_rel(self, dx: int, dy: int):
        """Относительное перемещение курсора на dx/dy пикселей."""
        dx, dy = int(dx), int(dy)
        if dx or dy:
            self._last_abs = None  # относительное движение сбивает кэш абсолютной позиции
            user32.mouse_event(MOUSEEVENTF_MOVE, dx, dy, 0, None)

    def click(self, button: str, double: bool = False):
        flags = BUTTON_MAP[button]["click"]
        user32.mouse_event(flags, 0, 0, 0, None)
        if double:
            # Двойной клик на Windows - не два клика подряд, а событие CLICK +
            # повторный клик с интервалом меньше DoubleClickTime.
            user32.mouse_event(flags, 0, 0, 0, None)

    def screen_size(self):
        """Разрешение основного экрана в пикселях (для hello-ответа клиенту)."""
        w = _get_system_metrics(SM_CXSCREEN)
        h = _get_system_metrics(SM_CYSCREEN)
        return w, h

    def button_down(self, button: str):
        user32.mouse_event(BUTTON_MAP[button]["down"], 0, 0, 0, None)

    def button_up(self, button: str):
        user32.mouse_event(BUTTON_MAP[button]["up"], 0, 0, 0, None)

    def scroll(self, dy: float):
        step = int(round(dy * 120))
        if step:
            user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, step, None)


def line_cmd_summary(cmd: dict) -> str:
    """Краткое описание команды для диагностического лога (без лишних байт)."""
    parts = [str(cmd.get("cmd", "?"))]
    for key in ("button", "double", "x", "y", "dx", "dy"):
        if key in cmd:
            parts.append("%s=%s" % (key, cmd[key]))
    return ",".join(parts)[:80]


class ClientHandler(threading.Thread):
    """Разговор с одним смартфоном. Транспорт не важен: подойдёт сокет
    (Wi-Fi/BT RFCOMM) или USB-канал AOA — лишь бы read()/write() были."""

    # Максимальный размер буфера строки. Защита от "залипания" без перевода
    # строки (битый транспорт/мусор в канале): иначе buf рос бы бесконечно.
    MAX_LINE_BYTES = 64 * 1024
    # Период опроса сокета (сек). Заодно задаёт частоту heartbeat-ответов.
    POLL_INTERVAL = 0.5
    # Если клиент молчит дольше этого времени, сервер шлёт {"resp":"ping"} —
    # «доказательство жизни» для клиента и трафик против Wi-Fi энергосбережения.
    HEARTBEAT_AFTER = 5.0

    def __init__(self, conn, ctrl: InputController, peer: str = "?",
                 on_closed=None):
        super().__init__(daemon=True)
        self.conn = conn
        self.ctrl = ctrl
        self.peer = peer
        self.buf = b""
        # v1.4: диагностика обрывов — кто именно закрыл соединение.
        self.last_cmd = ""
        self.cmd_count = 0
        self.connected_at = time.time()
        # v1.5: обратный вызов «сессия завершена» (для вытеснения старых
        # клиентов, см. serve_tcp). Вызывается из finally после закрытия канала.
        self.on_closed = on_closed

    def _recv(self):
        if hasattr(self.conn, "recv"):
            return self.conn.recv(4096)
        return self.conn.read(4096)

    def _sendall(self, data: bytes):
        if hasattr(self.conn, "sendall"):
            self.conn.sendall(data)
        else:
            self.conn.write(data)

    def _sock_readable(self, sock: socket.socket) -> str:
        """Неблокирующая проверка читаемости сокета через recv(MSG_PEEK).

        Возвращает:
          'data' — в буфере есть байты (можно читать);
          'idle' — данных сейчас нет, но соединение живо (EAGAIN/ENOTCONN);
          'eof'  — вторая сторона закрыла соединение (FIN/RST, peek вернул b'').
        """
        try:
            data = sock.recv(1, socket.MSG_PEEK)
        except OSError as exc:
            if exc.errno in (errno.EAGAIN, errno.EWOULDBLOCK, errno.ENOTCONN):
                return "idle"
            if exc.errno == errno.EBADF:      # сокет уже закрыт нами
                return "eof"
            raise ConnectionError(str(exc))   # прочие ошибки считаем обрывом
        return "data" if data else "eof"

    def run(self):
        log.info("Клиент подключился: %s", self.peer)
        reason = "клиент закрыл соединение (EOF)"
        try:
            # v1.4: переводим сокет в неблокирующий режим и читаем через
            # MSG_PEEK-поллинг. Раньше чтение было блокирующим: select()
            # сигнализировал готовность, но к моменту recv() состояние канала
            # могло измениться — recv() возвращал b'' (ложный EOF), цикл
            # break'ался, и сервер печатал «Клиент отключился» ровно при
            # включении управления и первых касаниях (пик трафика move).
            # Теперь «нет данных» (EAGAIN) и «peer закрылся» (b'' из peek)
            # различаются корректно: простой больше не выглядит как обрыв.
            # Сам сокет: accept() возвращает socket напрямую; для обёрток
            # ищем _sock / _handle. Если это не сокет (COM/USB-канал) —
            # остаёмся на блокирующем чтении как раньше.
            raw = self.conn
            if not isinstance(raw, socket.socket):
                raw = getattr(self.conn, "_sock", None) or \
                      getattr(self.conn, "_handle", None)
            nonblocking = False
            if isinstance(raw, socket.socket):
                try:
                    raw.setblocking(False)
                    nonblocking = True
                except OSError:
                    nonblocking = False
            poll_sock = raw if nonblocking else None
            last_rx = time.time()
            while True:
                if poll_sock is not None:
                    state = self._sock_readable(poll_sock)
                    if state == "idle":
                        # Данных нет — это НОРМАЛЬНОЕ состояние простоя, а не
                        # ошибка. Раз в HEARTBEAT_AFTER шлём лёгкий ping: клиент
                        # видит жизнь канала, а Wi-Fi-адаптер телефона не
                        # «засыпает» на молчаливом соединении.
                        if time.time() - last_rx >= self.HEARTBEAT_AFTER:
                            last_rx = time.time()
                            try:
                                self._sendall(b'{"resp":"ping"}\n')
                            except OSError:
                                pass  # обрыв будет обнаружен следующим чтением
                        # Пауза до POLL_INTERVAL, чтобы не крутить CPU пустым
                        # циклом; select() разбудит нас раньше при приходе данных.
                        try:
                            select.select([poll_sock], [], [], self.POLL_INTERVAL)
                        except (OSError, ValueError):
                            pass
                        continue
                    if state == "eof":
                        break
                if poll_sock is not None:
                    # state == 'data': байты точно есть — снимаем их прямым
                    # recv() (неблокирующий сокет может вернуть EAGAIN, если
                    # данные уже успели прочитать — тогда просто продолжаем).
                    try:
                        data = poll_sock.recv(4096)
                    except OSError as exc:
                        if exc.errno in (errno.EAGAIN, errno.EWOULDBLOCK):
                            continue
                        raise ConnectionError(str(exc))
                else:
                    data = self._recv()
                if not data:
                    # Для неблокирующего сокета сюда попадаем только после
                    # state=='eof' (выше break), поэтому пустое чтение здесь
                    # настоящий EOF; для блокирующих транспортов (COM/USB)
                    # пустое чтение тоже штатный признак закрытия.
                    break
                last_rx = time.time()
                self.buf += data
                while b"\n" in self.buf:
                    line, self.buf = self.buf.split(b"\n", 1)
                    self.handle_line(line.decode("utf-8", errors="replace"))
                if len(self.buf) > self.MAX_LINE_BYTES:
                    log.warning("Переполнение буфера от %s — сброс %d байт",
                                self.peer, len(self.buf))
                    self.buf = b""
        except OSError as exc:
            if _is_peer_gone(exc):
                reason = "соединение разорвано второй стороной (%s)" % exc
            else:
                reason = "локальная ошибка канала (%s) — соединение сохранено" % exc
                log.warning(reason)
        except Exception as exc:  # noqa: BLE001 - поток не должен умирать молча
            reason = "внутренняя ошибка потока: %r" % exc
            log.warning(reason)
        finally:
            # v1.4: подробная диагностика — раньше «Клиент отключился» было
            # невозможно объяснить; теперь видно, на какой команде и как долго
            # жила сессия.
            log.info("Клиент отключился: %s | команд: %d, последняя: %r, "
                     "сессия: %.1f с, причина: %s",
                     self.peer, self.cmd_count, self.last_cmd,
                     time.time() - self.connected_at, reason)
            try:
                self.conn.close()
            except Exception:
                pass
            if self.on_closed is not None:
                try:
                    self.on_closed(self)
                except Exception:
                    pass

    def handle_line(self, line: str):
        cmd = protocol.parse_line(line)
        if cmd is None:
            return
        name = cmd.get("cmd", "")
        # Критично: обработчик вызывается из потока run(), и ЛЮБОЕ
        # непрехваченное исключение здесь (например, TypeError при некорректных
        # аргументах команды или MemoryError при пиковом потоке move-событий)
        # убивало бы поток, сокет закрывался в finally() — клиент видел
        # "Клиент отключился" и переподключался. Поэтому строку разбираем в
        # отдельном защищённом блоке: одна битая команда не рвёт соединение.
        try:
            self._dispatch(cmd, name)
        except Exception as exc:  # noqa: BLE001 - защита потока от падения
            log.warning("Необработанная ошибка в команде %s: %s", name, exc)

    def _dispatch(self, cmd: dict, name: str):
        # v1.4: счётчик и последняя команда — для диагностики в логе при обрыве.
        self.cmd_count += 1
        self.last_cmd = line_cmd_summary(cmd)
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

    def shutdown(self):
        """Штатное завершение сессии по инициативе СЕРВЕРА (например, при
        перезапуске): сначала закрываем listen-сокет, чтобы accept() не принял
        новый канал раньше, чем будет закрыт старый. Иначе на телефоне два
        клиента могли бы одновременно держать соединение — а закрытие старого
        экземпляра выглядело в логе как «Клиент отключился»."""
        try:
            self.conn.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.conn.close()
        except Exception:
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
    if getattr(sys, "frozen", False):
        log.info("Запуск из EXE: разрешите доступ в брандмауэре для TouchCursorServer.exe "
                 "(или выполните от администратора: netsh advfirewall firewall add rule "
                 "name=TouchCursor dir=in action=allow protocol=TCP localport=%d)", port)
    else:
        log.info("Откройте в брандмауэре Windows входящий TCP-порт %d для программы Python.", port)

    # v1.5: «последний победитель» — активный клиент на каждый IP телефона.
    # Диагностический лог («сессия: 0.0 с, последняя hello») показал, что при
    # включении управления телефон открывал ВТОРОЙ TCP-канал, не закрывая
    # первый: два PcClient жили одновременно (активность и foreground-сервис
    # запускали по копии клиента). Когда дубликат умирал, сервер печатал
    # «Клиент отключился» ровно в момент первых касаний. Теперь старый канал
    # с того же IP вытесняется сразу при появлении нового — обрывы дубликата
    # больше не видны как разрыв рабочего соединения.
    active_by_peer = {}

    def _on_closed(h):
        if active_by_peer.get(h.peer) is h:
            active_by_peer.pop(h.peer, None)

    try:
        while True:
            conn, addr = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            # v1.4: KEEPALIVE на уровне TCP. Без него «мёртвое» соединение
            # (телефон ушёл в сон/потерял Wi-Fi, обрыв кабеля роутером) может
            # висеть часами; с ним ядро само обнаруживает потерю пилона.
            # v1.5 ВАЖНО: ловим и AttributeError — на не-Windows Python у
            # объекта socket нет метода ioctl(), а предыдущий except OSError
            # его не перехватывал: поток accept() падал со stacktrace, сервер
            # продолжал «висеть» на порту, но все новые подключения обрывались
            # сразу после hello (RST). Именно это выглядело как «Клиент
            # отключился ... последняя hello» при тестировании.
            try:
                conn.ioctl(socket.SIO_KEEPALIVE_VALS, (1, 15000, 5000))
            except (OSError, AttributeError):
                pass  # не Windows / недоступно — не критично
            peer = "%s:%d" % addr[:2]
            old = active_by_peer.get(peer)
            if old is not None and old.is_alive():
                log.info("Вытесняю старый канал от %s (новый подключился раньше, "
                         "чем закрылся прежний — дубликат клиента)", peer)
                old.shutdown()
            handler = ClientHandler(conn, ctrl, peer, on_closed=_on_closed)
            active_by_peer[peer] = handler
            handler.start()
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

    if sys.platform != "win32":
        print("TouchCursorServer можно запускать только в Windows 10/11.", file=sys.stderr)
        sys.exit(1)

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


def _run():
    """Точка входа для EXE-сборки (PyInstaller).

    В оконном (--noconsole) режиме консольного вывода нет, поэтому ошибки
    пишем в лог-файл рядом с EXE и показываем окно сообщения.
    """
    try:
        main()
    except Exception as exc:  # noqa: BLE001
        if getattr(sys, "frozen", False):
            import traceback
            base = os.path.dirname(sys.executable)
            log_path = os.path.join(base, "touchcursor_server.log")
            try:
                with open(log_path, "a", encoding="utf-8") as fh:
                    fh.write(traceback.format_exc())
            except OSError:
                pass
            try:
                import ctypes
                ctypes.windll.user32.MessageBoxW(
                    None,
                    "Ошибка запуска TouchCursor Server:\n%s\n\nПодробности: %s" % (exc, log_path),
                    "TouchCursor Server", 0x10,  # MB_ICONERROR
                )
            except Exception:
                pass
        raise


if __name__ == "__main__":
    _run()

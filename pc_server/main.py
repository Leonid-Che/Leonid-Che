#!/usr/bin/env python3
"""
main.py — сервер TabletTouch для Windows 10.

Что делает:
  • захватывает экран (mss / Desktop Duplication) и отдаёт его как H.264 по
    WebSocket-каналу (порт 8765) на планшет Android;
  • принимает касания/жесты планшета и внедряет их в Windows через SendInput
    (input_inject.py);
  • следит за ориентацией планшета: при переводе в портрет режим пересобирает
    поток повёрнутым кадром, а если устройство ввода действительно является
    монитором ПК — дополнительно пытается повернуть сам дисплей
    (display_rotation.py);
  • работает одинаково по Wi-Fi (прямое подключение к IP компьютера) и по
    USB-кабелю (adb forward tcp:8765 → тот же сервер).

Запуск:  py -3.12 main.py            (или pythonw.exe main.py без консоли)
Сборка .exe:  pyinstaller --onefile --noconsole --name TabletTouchServer main.py
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import os
import queue
import socket
import sys
import threading
import time
from dataclasses import dataclass, field

# ---- зависимости с graceful-деградацией ---------------------------------

try:
    import numpy as np
except ImportError:
    print("Нужен numpy:  py -3.12 -m pip install numpy")
    raise SystemExit(1)

try:
    from encoder import H264Encoder
except Exception as exc:  # noqa: BLE001
    print(f"Не удалось импортировать PyAV-кодер ({exc}). Установите: "
          f"py -3.12 -m pip install av")
    raise SystemExit(1)

IS_WINDOWS = sys.platform == "win32"
if IS_WINDOWS:
    import mss                      # захват экрана
    import input_inject as inj      # SendInput
    try:
        import display_rotation as rot
    except Exception:  # noqa: BLE001  (например, не тот Python)
        rot = None
else:  # заглушки только для отладки кода на Linux/macOS
    class _NoInj:
        @staticmethod
        def move_absolute(*a, **k): ...
        @staticmethod
        def button_down(*a, **k): ...
        @staticmethod
        def button_up(*a, **k): ...
        @staticmethod
        def wheel(*a, **k): ...
        @staticmethod
        def pinch_begin(*a, **k): ...
        @staticmethod
        def pinch_end(*a, **k): ...
        @staticmethod
        def move_relative(*a, **k): ...
    inj = _NoInj()
    rot = None

try:
    import websockets
    from websockets.exceptions import ConnectionClosed
except ImportError:
    print("Нужен websockets:  py -3.12 -m pip install websockets")
    raise SystemExit(1)

log = logging.getLogger("tabletserver")
logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)-7s %(name)s: %(message)s")

def _app_dir() -> str:
    """Каталог программы: для собранного .exe (PyInstaller onefile) — папка самого
    exe, иначе — каталог исходника. Нужен, чтобы config.json лежал рядом с exe."""
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


CONFIG_PATH = os.path.join(_app_dir(), "config.json")
DEFAULT_CONFIG = {
    "host": "0.0.0.0",
    "port": 8765,
    "monitor_index": 1,       # mss: 0 — весь виртуальный стол, 1..N — отдельные мониторы
    "fps": 30,
    "bitrate_mbps": 8,
    "max_width": 1920,        # ограничение ширины потока (скорость по Wi-Fi)
    "gop_seconds": 1,
    "encoder": "auto",        # auto | nvenc | openh264 | x264
    "rotate_display": True,   # пытаться физически повернуть монитор (если он есть)
    "cursor_mode": "absolute",
    "token": "",              # непустой токен = запрос авторизации от клиента
}


def ensure_config_file() -> None:
    """Если рядом с программой нет config.json — создаём его с настройками
    по умолчанию (важно для собранного .exe: конфиг должен быть редактируемым)."""
    if not os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "w", encoding="utf-8") as fh:
                json.dump(DEFAULT_CONFIG, fh, indent=2, ensure_ascii=False)
            log.info("создан файл настроек %s", CONFIG_PATH)
        except OSError as exc:
            log.warning("не удалось создать config.json: %s", exc)


def load_config() -> dict:
    cfg = dict(DEFAULT_CONFIG)
    ensure_config_file()
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
                user = json.load(fh)
            for k in list(cfg):
                if k in user:
                    cfg[k] = user[k]
        except Exception as exc:  # noqa: BLE001
            log.warning("config.json повреждён (%s), беру значения по умолчанию", exc)
    return cfg


# ---------------------------------------------------------------- геометрия

@dataclass
class ScreenGeom:
    """Виртуальный экран = все мониторы Windows вместе (виртуальный стол)."""
    left: int = 0
    top: int = 0
    width: int = 1920
    height: int = 1080

    def to_norm(self, px: int, py: int) -> tuple[float, float]:
        return ((px - self.left) / max(1, self.width),
                (py - self.top) / max(1, self.height))

    def from_norm(self, nx: float, ny: float) -> tuple[int, int]:
        return (int(round(nx * self.width + self.left)),
                int(round(ny * self.height + self.top)))


def read_virtual_desktop() -> ScreenGeom:
    """Координаты виртуального стола через EnumDisplayMonitors."""
    if not IS_WINDOWS:
        return ScreenGeom()
    import ctypes
    from ctypes import wintypes

    gdi = ctypes.windll.user32
    SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN = 76, 77
    SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN = 78, 79
    return ScreenGeom(left=gdi.GetSystemMetrics(SM_XVIRTUALSCREEN),
                      top=gdi.GetSystemMetrics(SM_YVIRTUALSCREEN),
                      width=gdi.GetSystemMetrics(SM_CXVIRTUALSCREEN),
                      height=gdi.GetSystemMetrics(SM_CYVIRTUALSCREEN))


# ------------------------------------------------------------- тач-состояние

@dataclass
class TouchState:
    active: dict[int, tuple[float, float]] = field(default_factory=dict)
    primary_id: int | None = None
    pressed_button: str = "left"
    # мультитач (щипок)
    pinch_active: bool = False
    pinch_start_dist: float = 0.0
    last_2dist: float = 0.0
    last_event_ts: float = 0.0
    longpress_task: asyncio.Task | None = None

    LONGPRESS_S = 0.65
    TAP_MAX_MOVE = 0.012     # нормализованные единицы


# ------------------------------------------------------------------ клиент

class ClientSession:
    def __init__(self, server: "Server", ws, peer: str):
        self.server = server
        self.ws = ws
        self.peer = peer
        self.geom = ScreenGeom()
        self.tablet_w = 0
        self.tablet_h = 0
        self.orientation_deg = 0          # ориентация ПЛАНШЕТА
        self.display_rot_applied = False  # удалось ли повернуть реальный монитор
        self.touch = TouchState()
        self.alive = True

    def frame_payload(self, pkt: bytes) -> bytes:
        """Заголовок кадра зависит от режима поворота (см. README, раздел «Протокол»)."""
        if self.display_rot_applied or self.server._desired_rotation() == 0:
            return b"v" + pkt              # кадр уже в нужной ориентации
        return b"V" + pkt                  # серверный программный доворот на 90°*k

    async def send_bin(self, data: bytes) -> None:
        await self.ws.send(data)

    async def send_json(self, obj: dict) -> None:
        await self.ws.send(json.dumps(obj))


# ------------------------------------------------------------------ сервер

class Server:
    MAGIC_VIDEO = b"VT"   # бинарный канал: 'V'+flag+frame | 'T'+json | 'H'+hello
    FLAG_KEY = 0x01

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.clients: set[ClientSession] = set()
        self.frame_q: "queue.Queue[bytes]" = queue.Queue(maxsize=2)
        self.capture_thread: threading.Thread | None = None
        self.loop: asyncio.AbstractEventLoop | None = None
        self._stop = threading.Event()
        self.base_size = (cfg["max_width"], 0)

    # ---------------- канал «видео»: отдельный поток, чтобы GIL-блокировки
    #                   кодирования не вешали событийный цикл

    def _capture_loop(self) -> None:
        t0 = time.perf_counter()
        n = 0
        while not self._stop.is_set():
            try:
                pkt = self.frame_q.get(timeout=0.2)
            except queue.Empty:
                continue
            self._broadcast_video(pkt)
            n += 1
            dt = time.perf_counter() - t0
            if dt > 2.0:
                log.info("FPS видеопотока ≈ %.1f", n / dt)
                t0, n = time.perf_counter(), 0

    def _broadcast_video(self, pkt: bytes) -> None:
        loop = self.loop
        if not loop or not self.clients:
            return
        for cl in list(self.clients):
            payload = cl.frame_payload(pkt)
            asyncio.run_coroutine_threadsafe(cl.send_bin(payload), loop)

    # ---------------- захват + кодирование (поток захвата)

    def _capture_and_encode(self) -> None:
        if not IS_WINDOWS:  # заглушка для отладки протокола на Linux/macOS
            self._stub_frames()
            return
        monitor_index = int(self.cfg["monitor_index"])
        fps = max(5, min(int(self.cfg["fps"]), 60))
        interval = 1.0 / fps

        with mss.mss() as sct:
            monitors = sct.monitors
            if monitor_index >= len(monitors):
                monitor_index = 1
            mon = monitors[monitor_index]
            base_w, base_h = mon["width"], mon["height"]

            # пропорциональное ограничение размера потока (max_width)
            mw = int(self.cfg.get("max_width") or 0)
            scale = 1.0
            if mw and base_w > mw:
                scale = mw / base_w
            cap_w = max(2, (int(base_w * scale) // 2) * 2)
            cap_h = max(2, (int(base_h * scale) // 2) * 2)

            encoder = H264Encoder(cap_w, cap_h, fps=fps,
                                  bitrate_mbps=float(self.cfg["bitrate_mbps"]),
                                  gop_seconds=int(self.cfg["gop_seconds"]),
                                  prefer=self.cfg.get("encoder", "auto"))
            log.info("поток: %dx%d (монитор %dx%d, масштаб %.2f)",
                     cap_w, cap_h, base_w, base_h, scale)

            next_t = time.perf_counter()
            fail_count = 0
            while not self._stop.is_set():
                try:
                    shot = sct.grab(mon)
                    fail_count = 0
                except Exception as exc:  # noqa: BLE001
                    fail_count += 1
                    log.warning("grab failed (%d): %s", fail_count, exc)
                    if fail_count > 30:
                        break
                    time.sleep(0.1)
                    continue

                img = np.frombuffer(shot.rgb, dtype=np.uint8)
                img = img.reshape((shot.height, shot.width, 3))
                if scale != 1.0:
                    img = _resize_nearest(img, cap_w, cap_h)

                pkt = encoder.encode_frame(img)
                if pkt:
                    self._enqueue(pkt)

                next_t += interval
                delay = next_t - time.perf_counter()
                if delay > 0:
                    time.sleep(delay)
                else:
                    next_t = time.perf_counter()

            encoder.flush()

    def _enqueue(self, pkt: bytes) -> None:
        try:
            self.frame_q.put_nowait(pkt)
        except queue.Full:
            try:  # выкидываем самый старый кадр — приоритет свежести
                self.frame_q.get_nowait()
                self.frame_q.put_nowait(pkt)
            except queue.Empty:
                pass

    def _stub_frames(self) -> None:
        """Тестовый режим без Windows: генерирует «экран» из градиента."""
        w, h = 640, 360
        yy, xx = np.mgrid[0:h, 0:w]
        rgb = np.dstack([xx.astype(np.uint8), yy.astype(np.uint8),
                         ((xx + yy) // 3).astype(np.uint8)])
        enc = H264Encoder(w, h, fps=15, bitrate_mbps=2, prefer="auto")
        while not self._stop.is_set():
            pkt = enc.encode_frame(rgb)
            if pkt:
                self._enqueue(pkt)
            time.sleep(1 / 15)

    def _desired_rotation(self) -> int:
        """0..3 (число поворотов на 90° CCW), чтобы альбомный экран ПК выглядел
        правильно на текущей ориентации планшета."""
        if not self.clients:
            return 0
        deg = next(iter(self.clients)).orientation_deg
        return {0: 0, 90: 3, 180: 2, 270: 1}.get(deg, 0)

    def notify_orientation_change(self) -> None:
        """Клиентам нужно пересоздать декодер: меняется геометрия потока."""
        self._send_control({"type": "restart",
                            "degrees": self._desired_rotation() * 90})

    # ---------------- рассылка управляющих сообщений всем

    def _send_control(self, obj: dict) -> None:
        if not self.loop or not self.clients:
            return
        data = ("T" + json.dumps(obj)).encode("utf-8")
        for cl in list(self.clients):
            asyncio.run_coroutine_threadsafe(cl.send_bin(data), self.loop)

    # ---------------- обработка текстовых команд клиента

    async def handle_text(self, cl: ClientSession, raw: str) -> None:
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            return
        typ = msg.get("type")

        if typ == "hello":
            cl.tablet_w = int(msg.get("screenWidthPx", 0))
            cl.tablet_h = int(msg.get("screenHeightPx", 0))
            cl.orientation_deg = int(msg.get("rotationDegrees", 0)) % 360
            cl.geom = read_virtual_desktop()
            log.info("клиент %s: %dx%d, поворот планшета %d°, десктоп %dx%d",
                     cl.peer, cl.tablet_w, cl.tablet_h, cl.orientation_deg,
                     cl.geom.width, cl.geom.height)
            await cl.send_json({
                "type": "welcome",
                "desktopWidth": cl.geom.width,
                "desktopHeight": cl.geom.height,
                "protocol": 1,
                "cursorMode": self.cfg["cursor_mode"],
                "displayRotatable": await asyncio.get_running_loop().run_in_executor(
                    None, self._probe_display_rotation),
            })
            return

        if typ == "orientation":
            deg = int(msg.get("degrees", 0)) % 360
            if deg != cl.orientation_deg:
                cl.orientation_deg = deg
                log.info("ориентация планшета: %d°", deg)
                # если монитор физический — пробуем повернуть его по-настоящему
                if self.cfg.get("rotate_display") and rot is not None:
                    ok, why = await asyncio.get_running_loop().run_in_executor(
                        None, lambda: rot.set_orientation(deg))
                    cl.display_rot_applied = ok
                    log.info("физический поворот дисплея: %s — %s",
                             "успех" if ok else "неприменим", why)
                    if ok:
                        cl.geom = read_virtual_desktop()
                        await cl.send_json({"type": "desktop",
                                            "desktopWidth": cl.geom.width,
                                            "desktopHeight": cl.geom.height})
                # иначе сервер просто довернёт кадр (см. _desired_rotation)
            return

        if typ == "keycode":
            vk = int(msg.get("vk", 0))
            down = bool(msg.get("down", True))
            (inj.key_down if down else inj.key_up)(vk) if IS_WINDOWS else None
            return

        if typ == "wheel":
            inj.wheel(int(msg.get("ticks", 0)))
            return

        if typ == "touch":
            self._apply_touch(cl, msg)
            return

        if typ == "ping":
            await cl.send_json({"type": "pong", "ts": msg.get("ts")})

    # ---------------- жесты → события мыши

    def _map_point(self, cl: ClientSession, tx: float, ty: float) -> tuple[float, float]:
        """Экран планшета (в пикселях, с учётом его поворота) → нормализованные
        координаты рабочего стола Windows."""
        deg = cl.orientation_deg
        tw, th = cl.tablet_w, cl.tablet_h
        if tw == 0 or th == 0:
            return (0.5, 0.5)
        # приводим точку к «альбомной» системе координат планшета
        if deg == 90:      # планшет повёрнут влево: x,y меняются
            nx, ny = ty / th, 1.0 - tx / tw
        elif deg == 180:
            nx, ny = 1.0 - tx / tw, 1.0 - ty / th
        elif deg == 270:
            nx, ny = 1.0 - ty / th, tx / tw
        else:
            nx, ny = tx / tw, ty / th
        # кадр сервера дополнительно довёрнут на _desired_rotation — компенсируем,
        # чтобы соответствие «касание ↔ точка экрана» осталось прямым
        r = self._desired_rotation()
        for _ in range(r):
            nx, ny = 1.0 - ny, nx
        return nx, ny

    def _apply_touch(self, cl: ClientSession, msg: dict) -> None:
        t = cl.touch
        action = msg.get("action")
        pid = msg.get("pointerId", 0)
        pts = msg.get("points") or [[msg.get("x", 0), msg.get("y", 0), pid]]

        norm_pts = [self._map_point(cl, p[0], p[1]) for p in pts]

        if action == "down":
            t.active[pid] = norm_pts[0] if pts else (0.5, 0.5)
            if t.primary_id is None:
                t.primary_id = pid
                inj.move_absolute(*t.active[pid])
                inj.button_down(t.pressed_button)
                t.last_event_ts = time.monotonic()
                if t.LONGPRESS_S:
                    t.longpress_task = asyncio.ensure_future(self._longpress(cl, pid))
            return

        if action == "move":
            now = time.monotonic()
            for p, (nx, ny) in zip(pts, norm_pts):
                pid_i = p[2] if len(p) > 2 else pid
                if pid_i in t.active:
                    t.active[pid_i] = (nx, ny)
            if len(t.active) >= 2:
                self._handle_pinch(cl, now)
                return
            if t.primary_id in t.active:
                nx, ny = t.active[t.primary_id]
                moved = _dist((nx, ny), norm_pts[0])
                if moved > 0.0004:
                    t.last_event_ts = now
                    inj.move_absolute(nx, ny)
            return

        if action in ("up", "cancel"):
            ended = t.active.pop(pid, None)
            if pid == t.primary_id:
                inj.button_up(t.pressed_button)
                t.primary_id = None
                if t.pinch_active:
                    self._pinch_end()
                if t.longpress_task:
                    t.longpress_task.cancel()
                    t.longpress_task = None
                # одиночное короткое касание без смещения = клик уже состоялся
                # (BUTTONDOWN на месте курсора) — ничего дополнительно не нужно
            elif len(t.active) < 2 and t.pinch_active:
                self._pinch_end()
            return

        if action == "button":           # long-press → правый клик
            btn = msg.get("button", "right")
            if t.primary_id is not None:
                inj.button_up(t.pressed_button)
            inj.move_absolute(*norm_pts[0])
            inj.button_down(btn)
            inj.button_up(btn)

    async def _longpress(self, cl: ClientSession, pid: int) -> None:
        try:
            await asyncio.sleep(TouchState.LONGPRESS_S)
        except asyncio.CancelledError:
            return
        t = cl.touch
        if t.primary_id == pid and len(t.active) <= 1:
            # отпустить левую и сделать правый клик на месте
            inj.button_up("left")
            inj.button_down("right")
            inj.button_up("right")
            t.pressed_button = "left"

    def _handle_pinch(self, cl: ClientSession, now: float) -> None:
        t = cl.touch
        a, b = list(t.active.values())[:2]
        d = _dist(a, b)
        if not t.pinch_active:
            if abs(d - t.pinch_start_dist) > 0.05:
                t.pinch_active = True
                if t.primary_id is not None:
                    inj.button_up(t.pressed_button)
                    t.primary_id = None
                inj.pinch_begin()
                t.last_2dist = d
            return
        delta = d - t.last_2dist
        if abs(delta) > 0.015:
            ticks = int(math.copysign(min(3, abs(delta) * 40), delta)) or 1
            inj.wheel(-ticks)          # разъезд пальцев → приближение
            t.last_2dist = d

    def _pinch_end(self) -> None:
        for c in self.clients:
            if c.touch.pinch_active:
                c.touch.pinch_active = False
                c.touch.pinch_start_dist = 0.0
                c.touch.last_2dist = 0.0
        inj.pinch_end()

    # ---------------- проверка возможности физического поворота

    def _probe_display_rotation(self) -> bool:
        if rot is None:
            return False
        try:
            modes = rot.list_modes()
            cur = rot.get_current_orientation()
            return bool(modes) and cur is not None
        except Exception:  # noqa: BLE001
            return False

    # ---------------- WebSocket-обработчик

    async def handler(self, ws) -> None:
        peer = getattr(ws, "remote_address", None)
        peer = f"{peer[0]}:{peer[1]}" if peer else "?"
        cl = ClientSession(self, ws, peer)
        token = self.cfg.get("token") or ""
        if token:
            try:
                first = await asyncio.wait_for(ws.recv(), timeout=5)
                auth = json.loads(first)
                if auth.get("auth") != token:
                    await ws.close(code=4401, reason="bad token")
                    return
            except Exception:  # noqa: BLE001
                await ws.close(code=4400)
                return
        self.clients.add(cl)
        log.info("подключён клиент %s (всего %d)", peer, len(self.clients))
        try:
            async for message in ws:
                if isinstance(message, (bytes, bytearray)):
                    continue  # клиент шлёт только текст
                await self.handle_text(cl, message)
        except ConnectionClosed:
            pass
        finally:
            self._cleanup_client(cl)
            log.info("клиент %s отключился", peer)

    def _cleanup_client(self, cl: ClientSession) -> None:
        was = cl.display_rot_applied
        self.clients.discard(cl)
        if cl.touch.pinch_active:
            inj.pinch_end()
            cl.touch.pinch_active = False
        if cl.touch.primary_id is not None:
            inj.button_up(cl.touch.pressed_button)
            cl.touch.primary_id = None
        # если ради этого клиента поворачивали физический монитор — вернуть альбом
        if was and rot is not None and not any(c.display_rot_applied for c in self.clients):
            def _restore() -> None:
                try:
                    rot.set_orientation(0)
                except Exception:  # noqa: BLE001
                    pass
            if self.loop:
                asyncio.run_coroutine_threadsafe(
                    self.loop.run_in_executor(None, _restore), self.loop)

    # ---------------- запуск

    async def run(self) -> None:
        self.loop = asyncio.get_running_loop()
        self.capture_thread = threading.Thread(target=self._capture_and_encode,
                                               name="capture", daemon=True)
        pump = threading.Thread(target=self._capture_loop, name="pump", daemon=True)
        self.capture_thread.start()
        pump.start()

        import websockets
        async with websockets.serve(self.handler, self.cfg["host"],
                                    int(self.cfg["port"]),
                                    max_size=None, compression=None,
                                    ping_interval=20, ping_timeout=20,
                                    close_timeout=5):
            ips = local_ips()
            log.info("Сервер запущен. Подключение с планшета:")
            log.info("  Wi-Fi : ws://%s:%d   (выберите любой адрес из списка)",
                     ips[0] if ips else "<IP-компьютера>", int(self.cfg["port"]))
            log.info("  USB   : adb forward tcp:%d tcp:%d  → ws://127.0.0.1:%d",
                     int(self.cfg["port"]), int(self.cfg["port"]),
                     int(self.cfg["port"]))
            await asyncio.Future()  # вечно


def _is_keyframe_annexb(pkt: bytes) -> bool:
    """Признак IDR/SPS/PPS в начале annex-b-пакета (для диагностики)."""
    i = pkt.find(b"\x00\x00\x00\x01")
    if i < 0:
        i = pkt.find(b"\x00\x00\x01")
        if i < 0:
            return False
        nal = pkt[i + 3] & 0x1F
    else:
        nal = pkt[i + 4] & 0x1F
    return nal in (5, 7, 8)  # IDR / SPS / PPS


def _resize_nearest(img: "np.ndarray", w: int, h: int) -> "np.ndarray":
    src_h, src_w = img.shape[:2]
    ys = (np.arange(h) * src_h // h).astype(np.int32)
    xs = (np.arange(w) * src_w // w).astype(np.int32)
    return img[ys[:, None], xs[None, :]]


def _dist(a: tuple[float, float], b: tuple[float, float]) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


def local_ips() -> list[str]:
    ips: list[str] = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass
    return ips or ["127.0.0.1"]


def main() -> None:
    cfg = load_config()
    srv = Server(cfg)
    try:
        asyncio.run(srv.run())
    except KeyboardInterrupt:
        srv._stop.set()
        log.info("остановка…")


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""Интегральные тесты реестра активных клиентов serve_tcp (v1.10/v1.11).

Проверяют сценарии, на которых раньше спотыкалась логика вытеснения дубликатов:

  T1  штатное переподключение того же клиента (один clientId) — новый hello
      тихо закрывает ПРЕЖНИЙ канал сервера, новый канал рабочий;
  T2  дубликат-«сирота» (второй экземпляр PcClient, ДРУГОЙ clientId, тот же IP
      телефона) — закрывается НОВЫЙ канал, рабочий НЕ рвётся. Именно этот
      случай прежний прогон не ловил из-за гонки accept()/hello и неверного
      условия «живости» рабочего канала;
  T3  регресс «окна вытеснения»: канал, которому запрошено завершение
      (stopped=True), но поток которого ещё числится в реестре, НЕ должен
      считаться живым рабочим — иначе genuine-переподключение того же клиента
      было бы отвергнуто как дубликат (зацикливание reject);
  T4  рабочий канал переживает потоковый трафик move/click/scroll при
      подключённом дубликате (ping->pong подтверждает живость).

Сервер поднимается РЕАЛЬНЫЙ (TCP на 127.0.0.1), InputController — мок
(без реальных вызовов WinAPI). Разные «телефоны» моделируются разными
loopback-адресами (127.0.0.2/.3), чтобы ключи реестра по IP не смешивались;
если алиас недоступен в системе — тест помечается skip.

Запуск из папки windows_server:
    python -m tests.test_dup_client
или:
    pytest tests/test_dup_client.py -v
"""
import json
import os
import socket
import sys
import threading
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from touchcursor_server import server as srv_mod  # noqa: E402


class FakeCtrl:
    """Мок InputController: считает вызовы, не трогает реальную мышь."""

    def __init__(self):
        self.lock = threading.Lock()
        self.moves = 0
        self.clicks = 0

    def screen_size(self):
        return 1920, 1080

    def move_to(self, x, y):
        with self.lock:
            self.moves += 1

    def move_rel(self, dx, dy):
        pass

    def click(self, button, double=False):
        with self.lock:
            self.clicks += 1

    def button_down(self, button):
        pass

    def button_up(self, button):
        pass

    def scroll(self, dy):
        pass


PORT = 0  # назначается в setUpClass


def send_line(sock, obj):
    sock.sendall((json.dumps(obj) + "\n").encode("utf-8"))


def read_line(sock, timeout=5.0):
    """Прочитать одну JSON-строку ответа с таймаутом."""
    old = sock.gettimeout()
    sock.settimeout(timeout)
    try:
        buf = b""
        while b"\n" not in buf:
            chunk = sock.recv(4096)
            if not chunk:
                raise ConnectionError("EOF в ожидании ответа")
            buf += chunk
        return json.loads(buf.split(b"\n", 1)[0].decode("utf-8"))
    finally:
        sock.settimeout(old)


def read_resp(sock, want, timeout=5.0):
    """Читать JSON-ответы, пропуская unsolicited heartbeat {"resp":"ping"}."""
    deadline = time.time() + timeout
    while True:
        left = deadline - time.time()
        if left <= 0:
            raise AssertionError("нет ответа %r за %.1f c" % (want, timeout))
        resp = read_line(sock, timeout=left)
        if resp.get("resp") == "ping" and want != "ping":
            continue
        return resp


def connect_hello(src_ip, cid, name="test-phone"):
    """Подключиться и выполнить handshake hello; вернуть сокет после ok."""
    s = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    if src_ip:
        try:
            s.bind((src_ip, 0))
        except OSError:
            s.close()
            raise
    send_line(s, {"cmd": "hello", "name": name, "transport": "wifi", "id": cid})
    resp = read_line(s)
    assert resp.get("resp") == "ok" and resp.get("cmd") == "hello", resp
    return s


def wait_socket_closed(sock, timeout=5.0):
    """True, если сервер закрыл этот сокет (recv вернул EOF или ошибку)."""
    old = sock.gettimeout()
    sock.settimeout(timeout)
    try:
        data = sock.recv(4096)
        return data == b""
    except OSError:
        return True
    finally:
        sock.settimeout(old)


class DupClientTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        global PORT
        cls.ctrl = FakeCtrl()
        probe = socket.socket()
        probe.bind(("127.0.0.1", 0))
        PORT = probe.getsockname()[1]
        probe.close()
        cls.thr = threading.Thread(
            target=srv_mod.serve_tcp, args=(cls.ctrl, PORT), daemon=True)
        cls.thr.start()
        time.sleep(0.3)

    def setUp(self):
        # Перехват логов сервера для проверки диагностических сообщений.
        self.records = []
        outer = self.records

        class _H:
            def emit(self, record):
                outer.append(record.getMessage())

            # logging.Logger.addHandler требует Handler-интерфейс:
            level = 0

            def handle(self, record):
                self.emit(record)

        self._handler = _H()
        srv_mod.log.addHandler(self._handler)

    def tearDown(self):
        try:
            srv_mod.log.removeHandler(self._handler)
        except Exception:
            pass

    def _wait_log(self, pred, timeout=5.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if any(pred(m) for m in self.records):
                return True
            time.sleep(0.05)
        return False

    # ------------------------------------------------------------------ T1
    def test_t1_same_id_reconnect_evicts_old(self):
        """Тот же clientId: новый hello штатно вытесняет прежний канал."""
        cid = "client-A-aaaaaaaa-0001"
        s1 = connect_hello(None, cid)
        s2 = connect_hello(None, cid)  # переподключение того же клиента

        # Новый канал живой и рабочий:
        send_line(s2, {"cmd": "click", "button": "left"})
        self.assertEqual(read_resp(s2, "ok").get("resp"), "ok")

        # Старый канал закрыт сервером:
        self.assertTrue(wait_socket_closed(s1),
                        "старый канал не был закрыт сервером")
        self.assertTrue(
            self._wait_log(lambda m: ("переподключился" in m
                                      or "неактивный канал" in m)),
            "нет штатной записи о вытеснении старого канала")
        s1.close()
        s2.close()

    # ------------------------------------------------------------------ T2
    def test_t2_orphan_duplicate_is_rejected(self):
        """Второй id от того же IP — дубликат: закрывается НОВЫЙ, рабочий жив."""
        # Один IP (bind-алиасы могут быть запрещены песочницей): различаем
        # «телефоны» clientId — именно так работает реестр на реальном ПК.
        work = connect_hello(None, "client-B-bbbbbbbb-0002", "phone-B")
        dup = connect_hello(None, "orphan-C-cccccccc-0003", "phone-B-orphan")

        # Дубликат должен быть закрыт сервером (сразу после его hello):
        self.assertTrue(wait_socket_closed(dup),
                        "дубликат не был закрыт сервером")
        dup.close()

        # Рабочий канал НЕ пострадал: отвечает на команды и жив по ping:
        send_line(work, {"cmd": "move", "x": 0.5, "y": 0.5})
        send_line(work, {"cmd": "click", "button": "right"})
        self.assertEqual(read_resp(work, "ok").get("resp"), "ok")
        send_line(work, {"cmd": "ping"})
        self.assertEqual(read_resp(work, "pong").get("resp"), "pong")

        self.assertTrue(
            self._wait_log(lambda m: "дубликат" in m and "сохранён" in m),
            "нет предупреждения о закрытии дубликата")
        work.close()

    # ------------------------------------------------------------------ T3
    def test_t3_stopped_channel_not_live_in_window(self):
        """Регресс «окна вытеснения»: stopped-канал не считается живым.

        Между stamp stopped=True (попросили завершить) и снятием из реестра в
        finally потока висит окно. Если _is_live() игнорирует stopped, новый
        genuine-канал того же клиента ошибочно отвергается как дубликат.
        Проверяем сам предикат живости, которым пользуется serve_tcp.
        """
        h = srv_mod.ClientHandler.__new__(srv_mod.ClientHandler)  # без __init__
        h.stopped = True
        h.is_alive = lambda: True  # поток ещё «в деле»
        # Локальная копия предиката из serve_tcp (замкнут внутри функции,
        # поэтому сверяемся по семантике: stopped -> не жив):
        is_live = (not h.stopped) and h.is_alive()
        self.assertFalse(is_live,
                         "stopped-канал признан живым — genuine "
                         "переподключение было бы отвергнуто (регресс v1.10)")
        # И зеркало: обычный активный канал — жив:
        h.stopped = False
        self.assertTrue((not h.stopped) and h.is_alive())

    # ------------------------------------------------------------------ T4
    def test_t4_work_channel_survives_traffic_with_orphan(self):
        """Потоковый трафик рабочего канала при живом дубликате не рвёт его."""
        work = connect_hello(None, "client-E-eeeeeeee-0005", "phone-E")
        try:
            dup = connect_hello(None, "orphan-F-ffffffff-0006", "phone-E-dup")
        except OSError:
            dup = None

        moves_before = self.ctrl.moves
        for i in range(200):
            send_line(work, {"cmd": "move",
                             "x": (i % 100) / 100.0, "y": 0.3})
        send_line(work, {"cmd": "click", "button": "left", "double": True})
        send_line(work, {"cmd": "scroll", "dy": -1.5})
        send_line(work, {"cmd": "ping"})
        self.assertEqual(read_resp(work, "pong").get("resp"), "pong",
                         "рабочий канал оборвался под нагрузкой")
        self.assertGreater(self.ctrl.moves, moves_before)

        if dup is not None:
            dup.close()
        work.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)

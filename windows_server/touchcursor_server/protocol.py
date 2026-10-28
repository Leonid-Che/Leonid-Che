# -*- coding: utf-8 -*-
"""
Протокол обмена между Android-смартфоном и Windows-сервером.

Транспорт: TCP, UTF-8 текст, по одной JSON-команде на строку (\n).

Команды смартфона -> сервера:
  {"cmd":"hello","name":"<имя устройства>"}          - представление при подключении
  {"cmd":"move","x":0..1,"y":0..1}                   - абсолютная позиция курсора
                                                      (доля экрана по тачу на смартфоне)
  {"cmd":"click","button":"left|right","double":bool}- однократное/двойное нажатие кнопки
  {"cmd":"down","button":"left|right"}               - кнопка мыши нажата (удерживается)
  {"cmd":"up","button":"left|right"}                 - кнопка мыши отпущена
  {"cmd":"scroll","dy":-1..1"}                       - прокрутка колеса мыши

Ответы сервера (не обязательны для клиента):
  {"resp":"ok","cmd":"..."} / {"resp":"err","msg":"..."}
"""
import json

BUTTONS = ("left", "right")


def parse_line(line: str):
    """Разбирает одну строку протокола. Возвращает dict или None."""
    line = line.strip()
    if not line:
        return None
    try:
        obj = json.loads(line)
    except (ValueError, TypeError):
        return None
    if not isinstance(obj, dict) or "cmd" not in obj:
        return None
    return obj


def make_response(cmd: str, ok: bool = True, msg: str = "") -> bytes:
    resp = {"resp": "ok" if ok else "err", "cmd": cmd}
    if msg:
        resp["msg"] = msg
    return (json.dumps(resp, ensure_ascii=False) + "\n").encode("utf-8")

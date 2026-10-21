"""
encoder.py — кодирование кадров экрана в H.264 (annex-b) через PyAV/FFmpeg.

Каскад кодеков по скорости: аппаратный h264_nvenc → libx264 (ultrafast,
zerolatency). На входе — RGB-массив (H, W, 3), который отдаёт mss; конвертацию
в yuv420p выполняет встроенный reformer PyAV (одним SIMD-проходом).

При смене ориентации (или размера) кодек пересоздаётся, а клиенту шлётся
команда restart — декодирование начинается с чистого листа.
"""

from __future__ import annotations

import fractions
import logging

import av
import numpy as np

log = logging.getLogger("encoder")


class H264Encoder:
    def __init__(self, width: int, height: int, fps: int = 30,
                 bitrate_mbps: float = 8.0, gop_seconds: int = 1,
                 prefer: str = "auto"):
        self.width = width
        self.height = height
        self.fps = max(5, min(fps, 60))
        self.bitrate_bps = int(bitrate_mbps * 1_000_000)
        self.gop_frames = max(self.fps // 2, gop_seconds * self.fps)
        self.prefer = prefer
        self._pts = 0
        self.codec_name = ""
        self._codec = self._open()

    # ------------------------------------------------------------------ init

    def _make(self, codec_name: str):
        c = av.CodecContext.create(codec_name, "w")
        c.width = self.width
        c.height = self.height
        c.pix_fmt = "yuv420p"
        c.framerate = fractions.Fraction(self.fps, 1)
        c.time_base = fractions.Fraction(1, self.fps)
        c.bit_rate = self.bitrate_bps
        c.gop_size = self.gop_frames
        c.max_b_frames = 0                       # низкая задержка
        opts: dict[str, object] = {"tune": "zerolatency", "preset": "ultrafast"}
        if codec_name == "h264_nvenc":
            opts = {"preset": "p1", "tune": "ll", "rc": "cbr",
                    "b": str(self.bitrate_bps)}
        c.open(opts)
        return c

    def _open(self):
        order = {
            "nvenc": ["h264_nvenc"],
            "x264": ["libx264"],
        }.get(self.prefer, ["h264_nvenc", "libx264"])
        last_exc: Exception | None = None
        for name in order:
            try:
                ctx = self._make(name)
                self.codec_name = name
                log.info("кодек H.264: %s (%dx%d@%d, %d бит/с)",
                         name, self.width, self.height, self.fps, self.bitrate_bps)
                return ctx
            except Exception as exc:  # noqa: BLE001
                last_exc = exc
                log.debug("кодек %s недоступен: %s", name, exc)
        raise RuntimeError(f"не удалось создать ни один H.264-кодек: {last_exc}")

    # ------------------------------------------------------------------ api

    @property
    def size(self) -> tuple[int, int]:
        return self.width, self.height

    def reconfigure(self, width: int, height: int) -> None:
        """Смена геометрии кадра (поворот/масштаб) — пересоздать кодек."""
        if (width, height) == (self.width, self.height):
            return
        self.width, self.height = width, height
        self._pts = 0
        self._codec = self._open()

    def encode_frame(self, rgb: np.ndarray) -> bytes | None:
        """
        rgb — массив (H, W, 3) uint8 (shot.rgb из mss). Возвращает пакет байтов
        H.264 annex-b (готов к MediaCodec) или None, если кодек буферизует кадр.
        """
        h, w = rgb.shape[:2]
        if (w, h) != (self.width, self.height):
            self.reconfigure(w, h)

        frame = av.VideoFrame.from_ndarray(np.ascontiguousarray(rgb), format="rgb24")
        frame = frame.reformat(format="yuv420p")   # SIMD-конвертация PyAV
        frame.pts = self._pts
        frame.time_base = fractions.Fraction(1, self.fps)
        self._pts += 1

        out = bytearray()
        for packet in self._codec.encode(frame):
            out += bytes(packet)          # PyAV >= 10: Packet поддерживает bytes()
        return bytes(out) if out else None

    def flush(self) -> bytes:
        out = bytearray()
        for packet in self._codec.encode(None):
            out += bytes(packet)
        return bytes(out)

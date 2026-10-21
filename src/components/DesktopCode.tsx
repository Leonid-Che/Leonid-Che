import { useState } from 'react';

const pythonCode = `"""
AeroPen Desktop — Программа управления курсором для Windows 10
Принимает данные с USB-устройства (акселерометр) и перемещает курсор.

Требования:
    pip install pyserial pywin32 numpy
"""

import serial
import struct
import time
import ctypes
import numpy as np
from collections import deque

# ============================================================
#  НАСТРОЙКИ
# ============================================================
COM_PORT = 'COM3'          # Порт устройства (изменить под ваш)
BAUD_RATE = 115200         # Скорость последовательного порта
SAMPLE_RATE = 200          # Частота опроса (Гц)
SENSITIVITY = 5.0          # Чувствительность курсора
DAMPING = 0.92             # Коэффициент затухания скорости
DEAD_ZONE = 0.05           # Мёртвая зона (g) — игнорируем малые ускорения
SCREEN_WIDTH = 1920        # Ширина экрана
SCREEN_HEIGHT = 1080       # Высота экрана
CALIBRATION_SAMPLES = 100  # Количество образцов для калибровки

# ============================================================
#  СТРУКТУРА ДАННЫХ С УСТРОЙСТВА
# ============================================================
# Формат пакета: 3 × int16 (accelX, accelY, accelZ) + uint8 (button) + uint8 (reserved)
# Размер: 2+2+2+1+1 = 8 байт
PACKET_FORMAT = '<hhhBB'
PACKET_SIZE = struct.calcsize(PACKET_FORMAT)


class AeroPen:
    """Основной класс управления устройством AeroPen."""

    def __init__(self) -> None:
        self.serial: serial.Serial | None = None
        self.velocity = np.array([0.0, 0.0], dtype=np.float64)
        self.position = np.array([SCREEN_WIDTH // 2, SCREEN_HEIGHT // 2], dtype=np.float64)
        self.offset = np.array([0.0, 0.0, 0.0], dtype=np.float64)
        self.is_active = False
        self.running = False
        # Фильтр низких частот (скользящее среднее)
        self.accel_buffer: deque = deque(maxlen=5)

    def connect(self, port: str = COM_PORT, baud: int = BAUD_RATE) -> bool:
        """Подключение к устройству через COM-порт."""
        try:
            self.serial = serial.Serial(port, baud, timeout=0.1)
            print(f"[OK] Подключено к {port}")
            time.sleep(2)  # Ждём инициализации Arduino
            self.calibrate()
            return True
        except serial.SerialException as e:
            print(f"[ERROR] Не удалось подключиться: {e}")
            return False

    def calibrate(self) -> None:
        """Калибровка нуля акселерометра."""
        print("[INFO] Калибровка... Держите устройство неподвижно!")
        samples = []

        for _ in range(CALIBRATION_SAMPLES):
            data = self.read_packet()
            if data is not None:
                ax, ay, az, _button, _ = data
                samples.append([ax, ay, az])
            time.sleep(0.01)

        if samples:
            arr = np.array(samples, dtype=np.float64)
            self.offset = np.mean(arr, axis=0)
            # Вычитаем гравитацию из Z (1g = 16384 LSB при ±2g)
            self.offset[2] -= 16384.0
            print(f"[OK] Калибровка завершена. Offset: {self.offset}")

    def read_packet(self):
        """Чтение одного пакета данных с устройства."""
        if self.serial is None or not self.serial.is_open:
            return None
        try:
            while self.serial.in_waiting < PACKET_SIZE:
                time.sleep(0.001)
            raw = self.serial.read(PACKET_SIZE)
            if len(raw) == PACKET_SIZE:
                return struct.unpack(PACKET_FORMAT, raw)
        except (serial.SerialException, OSError, struct.error):
            pass
        return None

    @staticmethod
    def apply_dead_zone(value: float, zone: float = DEAD_ZONE) -> float:
        """Применение мёртвой зоны для фильтрации дрожания."""
        if abs(value) < zone:
            return 0.0
        return value

    def low_pass_filter(self, accel):
        """Фильтр низких частот (скользящее среднее)."""
        self.accel_buffer.append(np.array(accel, dtype=np.float64))
        return np.mean(list(self.accel_buffer), axis=0)

    def update_cursor(self, ax: float, ay: float, dt: float):
        """
        Обновление позиции курсора.
        Двойное интегрирование: ускорение → скорость → позиция.
        """
        # Применяем калибровку и конвертируем в g
        accel_g = np.array([
            (ax - self.offset[0]) / 16384.0,
            (ay - self.offset[1]) / 16384.0,
        ], dtype=np.float64)

        # Мёртвая зона
        accel_g[0] = self.apply_dead_zone(float(accel_g[0]))
        accel_g[1] = self.apply_dead_zone(float(accel_g[1]))

        # Фильтрация
        filtered = self.low_pass_filter([float(accel_g[0]), float(accel_g[1]), 0.0])
        accel_g = filtered[:2]

        # Интегрирование: ускорение → скорость
        self.velocity = self.velocity + accel_g * SENSITIVITY * 100.0 * dt

        # Затухание (имитация трения)
        self.velocity = self.velocity * DAMPING

        # Интегрирование: скорость → позиция
        self.position = self.position + self.velocity * dt

        # Ограничение границами экрана
        self.position[0] = float(np.clip(self.position[0], 0, SCREEN_WIDTH))
        self.position[1] = float(np.clip(self.position[1], 0, SCREEN_HEIGHT))

        return int(self.position[0]), int(self.position[1])

    @staticmethod
    def move_cursor(x: int, y: int) -> None:
        """Перемещение курсора Windows через Win32 API."""
        ctypes.windll.user32.SetCursorPos(int(x), int(y))

    def run(self) -> None:
        """Основной цикл программы."""
        print("=" * 50)
        print("  AeroPen — Управление курсором")
        print("  Удерживайте кнопку для перемещения")
        print("  Ctrl+C для выхода")
        print("=" * 50)

        if not self.connect():
            return

        self.running = True
        last_time = time.time()

        try:
            while self.running:
                data = self.read_packet()
                if data is None:
                    continue

                ax, ay, az, button, _ = data
                current_time = time.time()
                dt = current_time - last_time
                last_time = current_time

                if button == 1:
                    # Кнопка нажата — обновляем курсор
                    x, y = self.update_cursor(float(ax), float(ay), dt)
                    self.move_cursor(x, y)
                    self.is_active = True
                else:
                    # Кнопка отпущена — останавливаем
                    self.velocity = np.array([0.0, 0.0], dtype=np.float64)
                    self.is_active = False

                # Ограничение частоты
                sleep_time = max(0.0, (1.0 / SAMPLE_RATE) - dt)
                if sleep_time > 0.0:
                    time.sleep(sleep_time)

        except KeyboardInterrupt:
            print("\\n[EXIT] Завершение работы...")
        finally:
            self.running = False
            if self.serial is not None and self.serial.is_open:
                self.serial.close()
                print("[OK] Порт закрыт")


# ============================================================
#  ЗАПУСК
# ============================================================

if __name__ == '__main__':
    pen = AeroPen()
    pen.run()
`;

const installGuide = `# Установка и запуск AeroPen Desktop
# ===================================

# 1. Установите Python 3.8+ (если ещё не установлен)
#    Скачайте с https://python.org
#    ВАЖНО: при установке отметьте "Add Python to PATH"

# 2. Откройте командную строку (Win+R → cmd) и установите зависимости:
pip install pyserial pywin32 numpy

# 3. Подключите Arduino Pro Micro по USB
#    Определите COM-порт в Диспетчере устройств
#    (Правый клик на Пуск → Диспетчер устройств → Порты (COM и LPT))

# 4. Настройте порт в коде (строка COM_PORT)
#    Или передайте как аргумент:
python aeropen.py --port COM3

# 5. Запустите программу:
python aeropen.py

# ===================================
# Определение COM-порта
# ===================================

# В PowerShell:
[System.IO.Ports.SerialPort]::GetPortNames()

# Или в Python:
import serial.tools.list_ports
for port in serial.tools.list_ports.comports():
    print(f"{port.device} — {port.description}")

# ===================================
# Автозапуск при старте Windows
# ===================================

# 1. Win+R → shell:startup
# 2. Создайте ярлык:
#    Цель: pythonw.exe C:\\path\\to\\aeropen.py
#    (pythonw — без консольного окна)

# ===================================
# Сборка в .exe (опционально)
# ===================================

pip install pyinstaller
pyinstaller --onefile --noconsole aeropen.py

# Готовый .exe будет в папке dist/`;

export default function DesktopCode() {
  const [activeTab, setActiveTab] = useState<'serial' | 'install'>('serial');

  return (
    <div className="bg-slate-800/50 rounded-2xl p-6 border border-slate-700">
      <h3 className="text-xl font-bold text-white mb-4 flex items-center gap-2">
        <i className="fab fa-python text-yellow-400"></i>
        Десктопное приложение (Python)
      </h3>

      <div className="flex flex-wrap gap-2 mb-4">
        <button
          onClick={() => setActiveTab('serial')}
          className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
            activeTab === 'serial'
              ? 'bg-yellow-500 text-black'
              : 'bg-slate-700 text-slate-300 hover:bg-slate-600'
          }`}
        >
          <i className="fas fa-serial-port mr-2"></i>
          Serial (COM-порт)
        </button>
        <button
          onClick={() => setActiveTab('install')}
          className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
            activeTab === 'install'
              ? 'bg-yellow-500 text-black'
              : 'bg-slate-700 text-slate-300 hover:bg-slate-600'
          }`}
        >
          <i className="fas fa-download mr-2"></i>
          Установка
        </button>
      </div>

      <div className="relative">
        <pre className="bg-slate-900 rounded-lg p-4 overflow-x-auto text-xs leading-relaxed border border-slate-700 max-h-[500px] overflow-y-auto">
          <code className={`${activeTab === 'install' ? 'text-slate-300' : 'text-yellow-200'} font-mono`}>
            {activeTab === 'serial' ? pythonCode : installGuide}
          </code>
        </pre>
        <div className="absolute top-2 right-2">
          <span className="bg-slate-700 text-slate-300 text-xs px-2 py-1 rounded">
            {activeTab === 'serial' ? 'aeropen.py' : 'README.md'}
          </span>
        </div>
      </div>

      <div className="mt-4 bg-blue-500/10 border border-blue-500/30 rounded-lg p-4">
        <h4 className="text-sm font-semibold text-blue-300 mb-2">
          <i className="fas fa-lightbulb mr-2"></i>
          Как это работает
        </h4>
        <ul className="text-xs text-blue-200/80 space-y-1">
          <li>• Программа подключается к Arduino Pro Micro через COM-порт (USB Serial)</li>
          <li>• При нажатой кнопке считываются данные акселерометра (8 байт/пакет)</li>
          <li>• Ускорение интегрируется → скорость → перемещение курсора</li>
          <li>• При отпускании кнопки курсор останавливается</li>
          <li>• Используется фильтрация и калибровка для точности</li>
          <li>• Win32 API SetCursorPos() перемещает системный курсор</li>
        </ul>
      </div>
    </div>
  );
}

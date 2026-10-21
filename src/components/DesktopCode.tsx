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

# НАСТРОЙКИ
COM_PORT = 'COM3'
BAUD_RATE = 115200
SAMPLE_RATE = 200
SENSITIVITY = 5.0
DAMPING = 0.92
DEAD_ZONE = 0.05
SCREEN_WIDTH = 1920
SCREEN_HEIGHT = 1080
CALIBRATION_SAMPLES = 100

# Формат пакета: 3×int16 + uint8 + uint8 = 8 байт
PACKET_FORMAT = '<hhhBB'
PACKET_SIZE = struct.calcsize(PACKET_FORMAT)


class AeroPen:
    def __init__(self):
        self.serial = None
        self.velocity = np.array([0.0, 0.0])
        self.position = np.array([SCREEN_WIDTH // 2, SCREEN_HEIGHT // 2])
        self.offset = np.array([0.0, 0.0, 0.0])
        self.is_active = False
        self.running = False
        self.accel_buffer = deque(maxlen=5)
        
    def connect(self, port=COM_PORT, baud=BAUD_RATE):
        try:
            self.serial = serial.Serial(port, baud, timeout=0.1)
            print(f"[OK] Подключено к {port}")
            time.sleep(2)
            self.calibrate()
            return True
        except serial.SerialException as e:
            print(f"[ERROR] Не удалось подключиться: {e}")
            return False
    
    def calibrate(self):
        print("[INFO] Калибровка... Держите устройство неподвижно!")
        samples = []
        for i in range(CALIBRATION_SAMPLES):
            data = self.read_packet()
            if 
                ax, ay, az, button, _ = data
                samples.append([ax, ay, az])
            time.sleep(0.01)
        
        if samples:
            arr = np.array(samples)
            self.offset = np.mean(arr, axis=0)
            self.offset[2] -= 16384  # 1g при ±2g
            print(f"[OK] Калибровка завершена. Offset: {self.offset}")
    
    def read_packet(self):
        if not self.serial or not self.serial.is_open:
            return None
        try:
            while self.serial.in_waiting < PACKET_SIZE:
                time.sleep(0.001)
            raw = self.serial.read(PACKET_SIZE)
            if len(raw) == PACKET_SIZE:
                return struct.unpack(PACKET_FORMAT, raw)
        except Exception:
            pass
        return None
    
    def apply_dead_zone(self, value, zone=DEAD_ZONE):
        if abs(value) < zone:
            return 0.0
        return value
    
    def low_pass_filter(self, accel):
        self.accel_buffer.append(accel.copy())
        return np.mean(self.accel_buffer, axis=0)
    
    def update_cursor(self, ax, ay, dt):
        accel_g = np.array([
            (ax - self.offset[0]) / 16384.0,
            (ay - self.offset[1]) / 16384.0,
        ])
        
        accel_g[0] = self.apply_dead_zone(accel_g[0])
        accel_g[1] = self.apply_dead_zone(accel_g[1])
        accel_g = self.low_pass_filter(np.array([accel_g[0], accel_g[1], 0]))[:2]
        
        self.velocity += accel_g * SENSITIVITY * 100 * dt
        self.velocity *= DAMPING
        self.position += self.velocity * dt
        
        self.position[0] = np.clip(self.position[0], 0, SCREEN_WIDTH)
        self.position[1] = np.clip(self.position[1], 0, SCREEN_HEIGHT)
        
        return int(self.position[0]), int(self.position[1])
    
    def move_cursor(self, x, y):
        ctypes.windll.user32.SetCursorPos(int(x), int(y))
    
    def run(self):
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
                if not 
                    continue
                
                ax, ay, az, button, _ = data
                current_time = time.time()
                dt = current_time - last_time
                last_time = current_time
                
                if button == 1:
                    x, y = self.update_cursor(ax, ay, dt)
                    self.move_cursor(x, y)
                    self.is_active = True
                else:
                    self.velocity = np.array([0.0, 0.0])
                    self.is_active = False
                
                sleep_time = max(0, (1.0 / SAMPLE_RATE) - dt)
                if sleep_time > 0:
                    time.sleep(sleep_time)
                    
        except KeyboardInterrupt:
            print("\\n[EXIT] Завершение работы...")
        finally:
            self.running = False
            if self.serial and self.serial.is_open:
                self.serial.close()


if __name__ == '__main__':
    pen = AeroPen()
    pen.run()`;

const installGuide = `# Установка и запуск AeroPen Desktop
# ===================================

# 1. Установите Python 3.8+ с https://python.org

# 2. Установите зависимости
pip install pyserial pywin32 numpy

# 3. Подключите Arduino Pro Micro по USB
#    Определите COM-порт в Диспетчере устройств

# 4. Настройте порт в коде (строка COM_PORT)
python aeropen.py --port COM3

# 5. Запустите программу
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
# Сборка в .exe (опционально)
# ===================================

pip install pyinstaller
pyinstaller --onefile --noconsole aeropen.py`;

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

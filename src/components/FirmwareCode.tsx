import { useState } from 'react';

const firmwareCode = `/*
 * AeroPen Firmware — Прошивка для USB-устройства управления курсором
 * Микроконтроллер: Arduino Pro Micro (ATmega32U4)
 * Датчик: MPU6050 (акселерометр + гироскоп)
 * 
 * Библиотека: ElectronicCats MPU6050
 * https://github.com/ElectronicCats/mpu6050
 * 
 * ==========================================
 *  СОВМЕСТИМОСТЬ С ПЛАТАМИ:
 * ==========================================
 *  ✓ Arduino Pro Micro 5V / 16 MHz  — РАБОТАЕТ (этот код)
 *  ✓ Arduino Pro Micro 3.3V / 8 MHz — РАБОТАЕТ (этот код)
 *  Код не зависит от тактовой частоты и напряжения логики.
 *  
 *  ВАЖНО ПО ПИТАНИЮ MPU6050 (GY-521):
 *  ─────────────────────────────────────
 *  • Сам чип MPU6050 работает от 2.4V до 3.6V
 *  • Модуль GY-521 имеет встроенный стабилизатор 3.3V,
 *    поэтому его можно питать от 5V (VCC → 5V)
 *  • Если на модуле НЕТ стабилизатора — питать строго от 3.3V!
 *  
 *  УРОВНИ СИГНАЛОВ I2C:
 *  ─────────────────────
 *  • На Pro Micro 5V линии SDA/SCL имеют уровень 5V
 *  • MPU6050 — 3.3V устройство
 *  • Большинство модулей GY-521 толерантны к 5V на I2C
 *    (имеют pull-up к 3.3V на плате)
 *  • Если возникают сбои — используйте level shifter
 *    или питайте Pro Micro от 3.3V (выбрав в Arduino IDE
 *    "Arduino Pro Micro 3.3V, 8 MHz")
 * 
 * Распиновка Arduino Pro Micro:
 * ┌─────────────────────────────────────┐
 * │  I2C:  SDA = D2,  SCL = D3         │
 * │  Кнопка: D4 (INPUT_PULLUP)          │
 * │  LED: D6                            │
 * │  USB: нативный (через ATmega32U4)   │
 * └─────────────────────────────────────┘
 */

#include <Wire.h>
#include <I2Cdev.h>
#include <MPU6050.h>

// === Пины Arduino Pro Micro ===
#define BUTTON_PIN 4      // Кнопка (D4, INPUT_PULLUP)
#define LED_PIN 6         // Индикатор активности (D6)
// I2C пины на Pro Micro: SDA=D2, SCL=D3 (аппаратные)

// Структура пакета данных (8 байт)
typedef struct {
  int16_t accelX;
  int16_t accelY;
  int16_t accelZ;
  uint8_t button;
  uint8_t reserved;
} __attribute__((packed)) PenPacket_t;

MPU6050 mpu;
PenPacket_t packet;

// Калибровка нуля
float offsetAX = 0, offsetAY = 0, offsetAZ = 0;
const int CALIBRATION_SAMPLES = 200;

void calibrate() {
  float sumX = 0, sumY = 0, sumZ = 0;
  for (int i = 0; i < CALIBRATION_SAMPLES; i++) {
    sumX += mpu.getAccelerationX();
    sumY += mpu.getAccelerationY();
    sumZ += mpu.getAccelerationZ();
    delay(5);
  }
  offsetAX = sumX / CALIBRATION_SAMPLES;
  offsetAY = sumY / CALIBRATION_SAMPLES;
  // 1g = 16384 LSB при диапазоне ±2g
  offsetAZ = (sumZ / CALIBRATION_SAMPLES) - 16384.0;
}

void setup() {
  Serial.begin(115200);
  while (!Serial) { ; }
  
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  pinMode(LED_PIN, OUTPUT);
  
  Wire.begin();
  mpu.initialize();
  
  if (!mpu.testConnection()) {
    // Ошибка: MPU6050 не отвечает.
    // Проверьте:
    //  - Подключение SDA→D2, SCL→D3
    //  - Питание VCC (5V или 3.3V в зависимости от модуля)
    //  - Адрес 0x68 (по умолчанию)
    while (true) {
      digitalWrite(LED_PIN, !digitalRead(LED_PIN));
      delay(50);
    }
  }
  
  // ±2g (максимальная чувствительность), 1g = 16384 LSB
  mpu.setFullScaleAccelRange(MPU6050_ACCEL_FS_2);
  
  // Sample Rate = Gyro Output Rate / (1 + SMPLRT_DIV)
  // SMPLRT_DIV=0 → максимальная частота (~1kHz при DLPF on)
  mpu.setRate(0);
  
  // DLPF 42 Гц — сглаживание шума
  mpu.setDLPFMode(MPU6050_DLPF_BW_42);
  
  calibrate();
  
  // Мигаем LED — готов
  for (int i = 0; i < 3; i++) {
    digitalWrite(LED_PIN, HIGH);
    delay(100);
    digitalWrite(LED_PIN, LOW);
    delay(100);
  }
}

void loop() {
  bool buttonState = !digitalRead(BUTTON_PIN);
  digitalWrite(LED_PIN, buttonState);
  
  int16_t rawAX = mpu.getAccelerationX();
  int16_t rawAY = mpu.getAccelerationY();
  int16_t rawAZ = mpu.getAccelerationZ();
  
  if (buttonState) {
    packet.accelX = (int16_t)(rawAX - offsetAX);
    packet.accelY = (int16_t)(rawAY - offsetAY);
    packet.accelZ = (int16_t)(rawAZ - offsetAZ);
    packet.button = 1;
  } else {
    packet.accelX = 0;
    packet.accelY = 0;
    packet.accelZ = 0;
    packet.button = 0;
  }
  packet.reserved = 0;
  
  Serial.write((uint8_t*)&packet, sizeof(packet));
  delay(5); // 200 Hz
}`;

const esp32Code = `/*
 * AeroPen — версия для ESP32-S2/S3 (нативный USB)
 * 
 * Библиотека: Adafruit MPU6050
 * https://github.com/adafruit/Adafruit_MPU6050
 * 
 * Распиновка ESP32-S2:
 * ┌─────────────────────────────────────┐
 * │  I2C:  SDA = GPIO8,  SCL = GPIO9   │
 * │  Кнопка: GPIO0 (BOOT button)        │
 * │  LED: GPIO2 (встроенный)            │
 * │  USB: нативный USB OTG              │
 * └─────────────────────────────────────┘
 */

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <USB.h>
#include <USBHID.h>

#define BUTTON_PIN 0
#define LED_PIN 2
#define I2C_SDA 8
#define I2C_SCL 9

Adafruit_MPU6050 mpu;
USBHID HID;

struct __attribute__((packed)) {
  int16_t ax, ay, az;
  uint8_t button;
  uint8_t status;
} sensorData;

uint8_t const desc_hid_report[] = {
  TUD_HID_REPORT_DESC_MOUSE()
};

USBHIDDevice hidDev(&HID, desc_hid_report, sizeof(desc_hid_report));

float axOff = 0, ayOff = 0, azOff = 0;

void calibrateSensor() {
  float sx = 0, sy = 0, sz = 0;
  for (int i = 0; i < 100; i++) {
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    sx += a.acceleration.x;
    sy += a.acceleration.y;
    sz += a.acceleration.z;
    delay(10);
  }
  axOff = sx / 100.0;
  ayOff = sy / 100.0;
  azOff = sz / 100.0 - 9.81;
}

void setup() {
  Serial.begin(115200);
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  pinMode(LED_PIN, OUTPUT);
  Wire.begin(I2C_SDA, I2C_SCL);
  
  if (!mpu.begin()) {
    while (1) {
      digitalWrite(LED_PIN, !digitalRead(LED_PIN));
      delay(100);
    }
  }
  
  mpu.setAccelerometerRange(MPU6050_RANGE_2_G);
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
  calibrateSensor();
  
  HID.begin(&hidDev);
  USB.begin();
}

void loop() {
  bool pressed = !digitalRead(BUTTON_PIN);
  digitalWrite(LED_PIN, pressed);
  
  sensors_event_t accel, gyro, temp;
  mpu.getEvent(&accel, &gyro, &temp);
  
  if (pressed) {
    float ax = accel.acceleration.x - axOff;
    float ay = accel.acceleration.y - ayOff;
    int8_t dx = constrain((int)(ax * 3.0), -127, 127);
    int8_t dy = constrain((int)(ay * 3.0), -127, 127);
    hidDev.mouseMove(dx, dy);
  }
  delay(5);
}`;

export default function FirmwareCode() {
  const [activeTab, setActiveTab] = useState<'arduino' | 'esp32'>('arduino');

  return (
    <div className="bg-slate-800/50 rounded-2xl p-6 border border-slate-700">
      <h3 className="text-xl font-bold text-white mb-4 flex items-center gap-2">
        <i className="fas fa-microchip text-green-400"></i>
        Код прошивки микроконтроллера
      </h3>

      <div className="flex gap-2 mb-4">
        <button
          onClick={() => setActiveTab('arduino')}
          className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
            activeTab === 'arduino'
              ? 'bg-blue-500 text-white'
              : 'bg-slate-700 text-slate-300 hover:bg-slate-600'
          }`}
        >
          <i className="fab fa-arduino mr-2"></i>
          Arduino Pro Micro
        </button>
        <button
          onClick={() => setActiveTab('esp32')}
          className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
            activeTab === 'esp32'
              ? 'bg-blue-500 text-white'
              : 'bg-slate-700 text-slate-300 hover:bg-slate-600'
          }`}
        >
          <i className="fas fa-wifi mr-2"></i>
          ESP32-S2/S3
        </button>
      </div>

      {/* Предупреждение для Pro Micro 5V */}
      {activeTab === 'arduino' && (
        <div className="mb-4 bg-amber-500/10 border border-amber-500/30 rounded-lg p-4">
          <h4 className="text-sm font-semibold text-amber-300 mb-2">
            <i className="fas fa-bolt mr-2"></i>
            Совместимость с Pro Micro 5V / 16 MHz
          </h4>
          <p className="text-xs text-amber-200/80 leading-relaxed mb-2">
            Код <strong>полностью совместим</strong> с обеими версиями Pro Micro (5V/16 MHz и 3.3V/8 MHz).
            В Arduino IDE выбирайте вашу версию платы — на работу прошивки это не влияет.
          </p>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-2 mt-3">
            <div className="bg-slate-900/50 rounded p-2">
              <div className="text-xs font-bold text-green-300 mb-1">
                <i className="fas fa-check-circle mr-1"></i>
                Прошивка (код)
              </div>
              <div className="text-xs text-slate-300">
                Одинаковая для 5V и 3.3V версий. Библиотека MPU6050 не зависит от тактовой частоты.
              </div>
            </div>
            <div className="bg-slate-900/50 rounded p-2">
              <div className="text-xs font-bold text-yellow-300 mb-1">
                <i className="fas fa-exclamation-triangle mr-1"></i>
                Подключение MPU6050
              </div>
              <div className="text-xs text-slate-300">
                Требует внимания к питанию и уровням I2C (см. ниже).
              </div>
            </div>
          </div>
        </div>
      )}

      <div className="mb-4 bg-slate-900/50 rounded-lg p-3 border border-slate-700">
        <h4 className="text-xs font-semibold text-slate-400 mb-2">
          <i className="fas fa-map-pin text-cyan-400 mr-1"></i>
          Распиновка:
        </h4>
        {activeTab === 'arduino' ? (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-xs">
            <div className="bg-slate-800 rounded px-2 py-1">
              <span className="text-slate-500">I2C SDA:</span>{' '}
              <span className="text-green-300 font-mono">D2</span>
            </div>
            <div className="bg-slate-800 rounded px-2 py-1">
              <span className="text-slate-500">I2C SCL:</span>{' '}
              <span className="text-green-300 font-mono">D3</span>
            </div>
            <div className="bg-slate-800 rounded px-2 py-1">
              <span className="text-slate-500">Кнопка:</span>{' '}
              <span className="text-purple-300 font-mono">D4</span>
            </div>
            <div className="bg-slate-800 rounded px-2 py-1">
              <span className="text-slate-500">LED:</span>{' '}
              <span className="text-yellow-300 font-mono">D6</span>
            </div>
          </div>
        ) : (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-xs">
            <div className="bg-slate-800 rounded px-2 py-1">
              <span className="text-slate-500">I2C SDA:</span>{' '}
              <span className="text-green-300 font-mono">GPIO8</span>
            </div>
            <div className="bg-slate-800 rounded px-2 py-1">
              <span className="text-slate-500">I2C SCL:</span>{' '}
              <span className="text-green-300 font-mono">GPIO9</span>
            </div>
            <div className="bg-slate-800 rounded px-2 py-1">
              <span className="text-slate-500">Кнопка:</span>{' '}
              <span className="text-purple-300 font-mono">GPIO0</span>
            </div>
            <div className="bg-slate-800 rounded px-2 py-1">
              <span className="text-slate-500">LED:</span>{' '}
              <span className="text-yellow-300 font-mono">GPIO2</span>
            </div>
          </div>
        )}
      </div>

      <div className="relative">
        <pre className="bg-slate-900 rounded-lg p-4 overflow-x-auto text-xs leading-relaxed border border-slate-700 max-h-[500px] overflow-y-auto">
          <code className="text-green-300 font-mono">
            {activeTab === 'arduino' ? firmwareCode : esp32Code}
          </code>
        </pre>
        <div className="absolute top-2 right-2">
          <span className="bg-slate-700 text-slate-300 text-xs px-2 py-1 rounded">
            {activeTab === 'arduino' ? 'ATmega32U4' : 'ESP32-S2'}
          </span>
        </div>
      </div>

      <div className="mt-4 grid grid-cols-1 md:grid-cols-3 gap-3">
        <div className="bg-slate-900/50 rounded-lg p-3 border border-slate-700">
          <div className="text-xs text-slate-400 mb-1">Библиотека датчика</div>
          <a
            href={activeTab === 'arduino'
              ? 'https://github.com/ElectronicCats/mpu6050'
              : 'https://github.com/adafruit/Adafruit_MPU6050'}
            target="_blank"
            rel="noopener noreferrer"
            className="text-xs text-cyan-300 font-mono hover:text-cyan-200 hover:underline inline-flex items-center gap-1"
          >
            <i className="fab fa-github"></i>
            {activeTab === 'arduino' ? 'ElectronicCats/mpu6050' : 'adafruit/Adafruit_MPU6050'}
          </a>
        </div>
        <div className="bg-slate-900/50 rounded-lg p-3 border border-slate-700">
          <div className="text-xs text-slate-400 mb-1">Частота опроса</div>
          <div className="text-xs text-cyan-300 font-mono">200 Гц (5 мс)</div>
        </div>
        <div className="bg-slate-900/50 rounded-lg p-3 border border-slate-700">
          <div className="text-xs text-slate-400 mb-1">Диапазон</div>
          <div className="text-xs text-cyan-300 font-mono">±2g (макс. чувствительность)</div>
        </div>
      </div>

      {/* Детальная информация по питанию для Pro Micro */}
      {activeTab === 'arduino' && (
        <div className="mt-4 bg-slate-900/50 rounded-lg p-4 border border-slate-700">
          <h4 className="text-sm font-semibold text-slate-300 mb-3 flex items-center gap-2">
            <i className="fas fa-plug text-red-400"></i>
            Питание MPU6050 при использовании Pro Micro 5V
          </h4>
          <div className="space-y-3 text-xs text-slate-300">
            <div className="bg-slate-800/50 rounded p-3 border-l-2 border-green-500">
              <div className="font-bold text-green-300 mb-1">
                ✓ Вариант A: Модуль GY-521 со стабилизатором (большинство)
              </div>
              <p className="text-slate-400">
                Подключите <code className="bg-slate-700 px-1 rounded">VCC → 5V</code> на Pro Micro.
                Встроенный регулятор модуля понизит до 3.3V для чипа.
                Pull-up резисторы I2C на плате обычно подключены к 3.3V,
                что обеспечивает корректную работу с 5V логикой Pro Micro.
              </p>
            </div>
            <div className="bg-slate-800/50 rounded p-3 border-l-2 border-yellow-500">
              <div className="font-bold text-yellow-300 mb-1">
                ⚠ Вариант B: Модуль без стабилизатора
              </div>
              <p className="text-slate-400">
                Подключите <code className="bg-slate-700 px-1 rounded">VCC → 3.3V</code> на Pro Micro.
                Обязательно используйте level shifter для линий SDA/SCL,
                либо задействуйте встроенный 3.3V регулятор Pro Micro.
              </p>
            </div>
            <div className="bg-slate-800/50 rounded p-3 border-l-2 border-blue-500">
              <div className="font-bold text-blue-300 mb-1">
                💡 Вариант C: Pro Micro 3.3V / 8 MHz (альтернатива)
              </div>
              <p className="text-slate-400">
                Если есть возможность, используйте 3.3V версию Pro Micro —
                тогда все уровни сигналов совпадают с MPU6050 и не нужны
                дополнительные преобразователи. В Arduino IDE выберите
                плату <em>"SparkFun Pro Micro" → "ATmega32U4 (3.3V, 8 MHz)"</em>.
              </p>
            </div>
            <div className="bg-red-500/10 border border-red-500/30 rounded p-3">
              <div className="font-bold text-red-300 mb-1">
                <i className="fas fa-skull-crossbones mr-1"></i>
                НЕЛЬЗЯ делать
              </div>
              <ul className="text-slate-400 space-y-1 list-disc list-inside">
                <li>Подавать 5V напрямую на чип MPU6050 (без стабилизатора) — сгорит!</li>
                <li>Оставлять AD0 висеть в воздухе — подключите к GND (адрес 0x68)</li>
              </ul>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

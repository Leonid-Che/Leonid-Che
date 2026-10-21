import { useState } from 'react';

const firmwareCode = `/*
 * AeroPen Firmware — Прошивка для USB-устройства управления курсором
 * Микроконтроллер: Arduino Pro Micro (ATmega32U4)
 * Датчик: MPU6050 (акселерометр + гироскоп)
 * 
 * Распиновка Arduino Pro Micro (ATmega32U4):
 * ┌─────────────────────────────────────┐
 * │  I2C:  SDA = D2,  SCL = D3         │
 * │  Кнопка: D4 (INPUT_PULLUP)          │
 * │  LED: D6                            │
 * │  USB: нативный (через ATmega32U4)   │
 * └─────────────────────────────────────┘
 */

#include <Wire.h>
#include <MPU6050.h>

// === Пины Arduino Pro Micro ===
#define BUTTON_PIN 4      // Кнопка (D4, INPUT_PULLUP)
#define LED_PIN 6         // Индикатор активности (D6)
#define MPU_ADDR 0x68     // Адрес MPU6050 на шине I2C
// I2C пины на Pro Micro: SDA=D2, SCL=D3 (аппаратные)

// Структура пакета данных (8 байт)
typedef struct {
  int16_t accelX;    // Ускорение по X (raw)
  int16_t accelY;    // Ускорение по Y (raw)
  int16_t accelZ;    // Ускорение по Z (raw)
  uint8_t button;    // Состояние кнопки (1=нажата)
  uint8_t reserved;  // Заполнитель
} __attribute__((packed)) PenPacket_t;

MPU6050 mpu(MPU_ADDR);
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
  offsetAZ = (sumZ / CALIBRATION_SAMPLES) - 1.0;
}

void setup() {
  Serial.begin(115200);
  while (!Serial) { ; }
  
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  pinMode(LED_PIN, OUTPUT);
  
  Wire.begin();
  mpu.initialize();
  
  if (!mpu.testConnection()) {
    while (true) {
      digitalWrite(LED_PIN, HIGH);
      delay(50);
      digitalWrite(LED_PIN, LOW);
      delay(50);
    }
  }
  
  mpu.setFullScaleAccelRange(MPU6050_IMU::MPU6050_ACCEL_FS_2);
  mpu.setRate(MPU6050_RATE_1KHZ);
  mpu.setDLPFMode(MPU6050_DLPF_BW_44);
  
  calibrate();
  
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
    packet.reserved = 0;
  } else {
    packet.accelX = 0;
    packet.accelY = 0;
    packet.accelZ = 0;
    packet.button = 0;
    packet.reserved = 0;
  }
  
  Serial.write((uint8_t*)&packet, sizeof(packet));
  delay(5); // 200 Hz
}`;

const esp32Code = `/*
 * AeroPen — версия для ESP32-S2/S3 (нативный USB)
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
          <div className="text-xs text-slate-400 mb-1">Библиотеки</div>
          <div className="text-xs text-cyan-300 font-mono">
            {activeTab === 'arduino' ? 'Wire, MPU6050' : 'Wire, Adafruit_MPU6050, USB, USBHID'}
          </div>
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
    </div>
  );
}

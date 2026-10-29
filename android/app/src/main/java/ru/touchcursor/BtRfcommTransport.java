package ru.touchcursor;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth-канал: классический RFCOMM (SPP) к компьютеру.
 * На Windows сервер поднимает виртуальный COM-порт через
 * «Bluetooth-страница -> Больше параметров -> Исходящее COM-порт»,
 * телефон подключается к сопряжённому адаптеру ПК по UUID SPP.
 */
public class BtRfcommTransport implements Transport {

    /** Стандартный UUID профиля Serial Port Profile. */
    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context ctx;
    private final String mac; // адрес адаптера компьютера (или "" - первый связанный с именем TouchCursor-PC)
    private BluetoothSocket socket;

    public BtRfcommTransport(Context ctx, String mac) {
        this.ctx = ctx;
        this.mac = mac == null ? "" : mac.trim().toUpperCase();
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT запрашивается в MainActivity до включения BT-режима
    @Override
    public void connect() throws IOException {
        // BluetoothAdapter.getDefaultAdapter() с API 31 помечен как deprecated —
        // получаем адаптер через системный сервис BluetoothManager.
        BluetoothManager manager = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (manager != null) ? manager.getAdapter() : null;
        if (adapter == null) throw new IOException("На устройстве нет Bluetooth-адаптера.");
        if (!adapter.isEnabled()) {
            // Предложим включить; цикл переподключения повторит попытку позже.
            Intent on = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            on.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(on);
            } catch (SecurityException ignored) {
            }
            throw new IOException("Bluetooth выключен — включите его в быстром доступе.");
        }

        BluetoothDevice device = resolveDevice(adapter);
        if (device == null) {
            throw new IOException("Компьютер не найден среди сопряжённых устройств. "
                    + "Сопрягите телефон с Bluetooth-адаптером ПК.");
        }
        // BluetoothDevice.isConnected() — скрытый (@hide) API, недоступный при обычной
        // компиляции. Публичная проверка «уже ли есть соединение» делается через
        // BluetoothManager.getConnectedDevices(GATT); при отказе рефлексии считаем,
        // что соединения нет (сопряжение всё равно проверяется ниже).
        if (!isDeviceConnected(manager, device) && !ensureBonded(device)) {
            Intent pair = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            pair.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(pair);
            } catch (Exception ignored) {
            }
            throw new IOException("Устройство не сопряжено — выполните сопряжение в настройках Bluetooth.");
        }

        BluetoothSocket s;
        try {
            s = device.createRfcommSocketToServiceRecord(SPP_UUID);
        } catch (IOException e) {
            // Запасной путь для стеков без стандартного SPP-записи SDP
            s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
        }
        adapter.cancelDiscovery();
        try {
            s.connect();
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw new IOException("Не удалось установить RFCOMM-соединение: " + e.getMessage());
        }
        socket = s;
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice resolveDevice(BluetoothAdapter adapter) {
        if (!mac.isEmpty()) {
            try {
                return adapter.getRemoteDevice(mac);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        // Без явного адреса: берём связанное устройство с именем ПК (TouchCursor-PC или любое с "PC")
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        BluetoothDevice fallback = null;
        for (BluetoothDevice d : bonded) {
            String name = safeName(d);
            if (name == null) continue;
            if (name.equalsIgnoreCase("TouchCursor-PC")) return d;
            if (fallback == null && (name.toLowerCase().contains("pc") || name.toLowerCase().contains("desktop"))) {
                fallback = d;
            }
        }
        return fallback;
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice d) {
        try {
            return d.getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    /**
     * Эмуляция недоступного публично BluetoothDevice.isConnected().
     * Сначала пробуем публичный API BluetoothManager.getConnectedDevices(GATT),
     * затем — рефлексивный вызов скрытого метода isConnected(); при любом сбое
     * возвращаем false (дальше проверяется состояние сопряжения).
     */
    @SuppressLint("MissingPermission")
    private boolean isDeviceConnected(BluetoothManager manager, BluetoothDevice device) {
        if (manager != null) {
            try {
                for (BluetoothDevice d : manager.getConnectedDevices(BluetoothProfile.GATT)) {
                    if (d != null && device.getAddress().equals(d.getAddress())) return true;
                }
            } catch (SecurityException ignored) {
            } catch (Exception ignored) {
            }
        }
        try {
            java.lang.reflect.Method m = BluetoothDevice.class.getMethod("isConnected");
            Object r = m.invoke(device);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private boolean ensureBonded(BluetoothDevice device) {
        try {
            return device.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException e) {
            return false;
        }
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        BluetoothSocket s = socket;
        if (s == null) throw new IOException("BT socket not connected");
        return s.getOutputStream();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        BluetoothSocket s = socket;
        if (s == null) throw new IOException("BT socket not connected");
        return s.getInputStream();
    }

    @Override
    public void close() {
        BluetoothSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public String describe() {
        return mac.isEmpty() ? "Bluetooth (RFCOMM)" : "Bluetooth (RFCOMM) " + mac;
    }
}

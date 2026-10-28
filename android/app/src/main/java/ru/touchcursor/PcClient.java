package ru.touchcursor;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Клиент Windows-сервера TouchCursor. Протокол: одна JSON-команда на строку (UTF-8).
 * Транспорт выбирается по режиму подключения: Wi-Fi/TCP, USB (AOA) или Bluetooth (RFCOMM).
 * Автовосстановление соединения с паузой. Все методы send* потокобезопасны
 * и неблокирующие для UI-потока (запись буферизуется; при ошибке канал
 * перекрывается фоновым потоком connectLoop).
 */
public class PcClient implements Runnable {

    public interface Callback {
        void onConnected();
        void onDisconnected(String reason);
    }

    static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int RECONNECT_DELAY_MS = 2000;
    /** Максимальная частота move-команд, чтобы не топить сеть и сервер. */
    private static final long MOVE_MIN_INTERVAL_MS = 30;

    /** Режимы канала связи со смартфоном. */
    public static final int MODE_WIFI = 0;
    public static final int MODE_USB = 1;
    public static final int MODE_BT = 2;

    private final int mode;
    private final String host;      // IP (режим Wi-Fi) или MAC (режим BT), "" для USB
    private final int port;
    private final String deviceName;
    private final Context appContext;
    private final Callback callback;
    private final ScreenInfoListener screenListener;

    /** Интерфейс для получения разрешения экрана ПК из hello-ответа сервера. */
    public interface ScreenInfoListener {
        void onScreenSize(int width, int height);
    }

    public PcClient(Context appContext, int mode, String host, int port, String btMac,
                    String deviceName, Callback callback, ScreenInfoListener listener) {
        this.appContext = appContext;
        this.mode = mode;
        this.host = host;
        this.port = port;
        this.btMac = btMac == null ? "" : btMac;
        this.deviceName = deviceName;
        this.callback = callback;
        this.screenListener = listener;
    }

    private final String btMac;

    private volatile Transport transport;
    private volatile OutputStream out;
    private volatile boolean running = true;
    private volatile boolean connected = false;
    private volatile String lastError = "";
    private long lastMoveTime = 0;
    private double pendingX, pendingY; // последняя неотправленная позиция

    public boolean isConnected() {
        return connected;
    }

    public String getAddress() {
        switch (mode) {
            case MODE_USB:
                return "USB";
            case MODE_BT:
                return btMac.isEmpty() ? "Bluetooth" : "BT " + btMac;
            default:
                return host + ":" + port;
        }
    }

    public String getTransportDescription() {
        Transport t = transport;
        if (t != null) return t.describe();
        return getAddress();
    }

    public String getLastError() {
        return lastError;
    }

    /** Создаёт транспорт согласно выбранному режиму подключения. */
    private Transport createTransport() {
        switch (mode) {
            case MODE_USB:
                return new UsbTransport(appContext);
            case MODE_BT:
                return new BtRfcommTransport(appContext, btMac);
            case MODE_WIFI:
            default:
                return new TcpTransport(host, port);
        }
    }

    @Override
    public void run() {
        while (running) {
            Transport t = null;
            try {
                t = createTransport();
                t.connect();
                transport = t;
                out = t.getOutputStream();
                connected = true;
                lastError = "";
                callback.onConnected();

                JSONObject hello = new JSONObject();
                hello.put("cmd", "hello");
                hello.put("name", deviceName);
                hello.put("transport", mode == MODE_USB ? "usb" : mode == MODE_BT ? "bt" : "wifi");
                writeLine(hello.toString());

                // Читаем ответы сервера: из hello берём разрешение экрана ПК.
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(t.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = in.readLine()) != null) {
                    try {
                        JSONObject resp = new JSONObject(line);
                        if ("hello".equals(resp.optString("cmd"))
                                && resp.has("screen") && screenListener != null) {
                            org.json.JSONArray sc = resp.getJSONArray("screen");
                            screenListener.onScreenSize(sc.getInt(0), sc.getInt(1));
                        }
                    } catch (Exception ignored) {
                        // не-JSON или неизвестный ответ - пропускаем
                    }
                }
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                // связь потеряна или не установлена
            } finally {
                connected = false;
                transport = null;
                out = null;
                if (t != null) {
                    try {
                        t.close();
                    } catch (Exception ignored) {
                    }
                }
            }

            if (!running) break;
            callback.onDisconnected(lastError);
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
        connected = false;
    }

    // ---------- Отправка команд ----------

    /** Абсолютное перемещение курсора (доли экрана 0..1). Частота ограничена. */
    public synchronized void sendMove(double fx, double fy) {
        pendingX = fx;
        pendingY = fy;
        long now = System.currentTimeMillis();
        if (now - lastMoveTime < MOVE_MIN_INTERVAL_MS) {
            return; // дождёмся следующего касания - position всё равно сохранена
        }
        lastMoveTime = now;
        sendCmd("{\"cmd\":\"move\",\"x\":" + fmt(fx) + ",\"y\":" + fmt(fy) + "}");
    }

    /**
     * Относительное перемещение курсора на dx/dy пикселей — используется
     * кнопками увеличения/уменьшения скорости (чувствительности) перемещения.
     */
    public void sendMoveRelative(int dx, int dy) {
        sendCmd("{\"cmd\":\"move_rel\",\"dx\":" + dx + ",\"dy\":" + dy + "}");
    }

    public void sendClick(String button, boolean doubleClick) {
        sendCmd("{\"cmd\":\"click\",\"button\":\"" + button
                + "\",\"double\":" + (doubleClick ? "true" : "false") + "}");
    }

    public void sendButton(String action, String button) { // action: "down"/"up"
        sendCmd("{\"cmd\":\"" + action + "\",\"button\":\"" + button + "\"}");
    }

    public void sendScroll(int direction) {
        sendScrollSteps(direction > 0 ? 1 : -1);
    }

    public void sendScrollSteps(int steps) {
        if (steps == 0) return;
        sendCmd("{\"cmd\":\"scroll\",\"dy\":" + clampDy(steps / 3.0) + "}");
    }

    private static String clampDy(double v) {
        double c = Math.max(-1.0, Math.min(1.0, v));
        long n = Math.round(c * 1000);
        return (n < 0 ? "-" : "") + String.format("%04d", Math.abs(n) / 1000)
                + "." + String.format("%03d", Math.abs(n) % 1000);
    }

    private synchronized void sendCmd(String json) {
        if (!connected) return;
        writeLine(json);
    }

    private void writeLine(String json) {
        OutputStream o = out;
        Transport t = transport;
        if (o == null || t == null) return;
        try {
            o.write(json.getBytes(StandardCharsets.UTF_8));
            o.write('\n');
            o.flush();
        } catch (Exception e) {
            // обрыв при записи - закрываем канал, connectLoop переподключится
            connected = false;
            try {
                t.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Останавливает клиент и ждёт завершения фонового цикла. */
    public void shutdown() {
        running = false;
        Transport t = transport;
        if (t != null) {
            try {
                t.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String fmt(double v) {
        // без экспоненциальной записи и locale-запятых
        long n = Math.round(Math.max(0.0, Math.min(1.0, v)) * 10000);
        return Long.toString(n / 10000) + "." + String.format("%04d", n % 10000);
    }
}

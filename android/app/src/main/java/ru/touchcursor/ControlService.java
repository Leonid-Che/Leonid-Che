package ru.touchcursor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * Foreground-сервис: канал связи с Windows-компьютером (Wi-Fi/TCP, USB или
 * Bluetooth) и отправка команд. Работает в фоне, поэтому управление не
 * прерывается при сворачивании экрана.
 */
public class ControlService extends Service {

    public static final int DEFAULT_PORT = 5575;
    private static final String CHANNEL_ID = "touchcursor_ctrl";
    private static final int NOTIFICATION_ID = 1;

    /** Шкала скорости перемещения курсора (кнопки «−/+» внизу экрана). */
    public static final int SPEED_MIN = 1;
    public static final int SPEED_MAX = 10;
    public static final int SPEED_DEFAULT = 5;

    private final LocalBinder binder = new LocalBinder();

    /** v1.5: счётчик живых привязанных активностей (см. onUnbind). */
    private int boundCount = 0;

    private volatile boolean controlling = false;
    private PcClient client;
    private Thread clientThread;

    /**
     * v1.4: PARTIAL_WAKE_LOCK на время активного управления.
     * Когда экран телефона гаснет (или система уходит в Doze), CPU и Wi-Fi
     * адаптер приостанавливаются: TCP-соединение «зависает», а первое же
     * касание после пробуждения приходит в уже разорванный сокет — сервер
     * печатает «Клиент отключился», клиент переподключается. Удержание
     * partial wake lock не даёт каналу умирать между событиями.
     */
    private PowerManager.WakeLock wakeLock;

    private int speedLevel = SPEED_DEFAULT;

    public class LocalBinder extends Binder {
        public ControlService getService() {
            return ControlService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        speedLevel = getSharedPreferences("touchcursor", MODE_PRIVATE)
                .getInt("speed_level", SPEED_DEFAULT);
        createChannel();
        startForegroundSafely();
    }

    /**
     * Запуск foreground-состояния с защитой от SecurityException.
     *
     * С Android 12 (API 31) для сервиса типа connectedDevice система требует, чтобы
     * приложению было выдано хотя бы одно динамическое право этого списка:
     * BLUETOOTH_ADVERTISE/CONNECT/SCAN, CHANGE_NETWORK_STATE/WIFI_STATE, NFC и т.п.
     * Если права ещё не выданы, startForeground() бросает SecurityException и процесс
     * падает — поэтому вызов обёрнут в try/catch, а при отказе уведомляется активити
     * (оно запросит недостающие права).
     */
    private void startForegroundSafely() {
        Notification notification = buildNotification(getString(R.string.notification_idle));
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Явная передача типа FGS (Android 14 требует совпадения с манифестом)
                startForeground(NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (SecurityException e) {
            // Права для connectedDevice-FGS ещё не выданы — просим их через MainActivity,
            // после чего повторим попытку. Сервис при этом не должен ронять приложение.
            requestFgsPermissionsAndRetry();
        } catch (Exception e) {
            // Любые прочие сбои (например, на некоторых прошивках без POST_NOTIFICATIONS)
            requestFgsPermissionsAndRetry();
        }
    }

    private volatile boolean fgsRetrying = false;

    private void requestFgsPermissionsAndRetry() {
        notifyActivityPermissionDenied();
        if (!fgsRetrying) {
            fgsRetrying = true;
            // Повторная попытка через 3 секунды — за это время пользователь,
            // вероятно, подтвердит запрос прав в диалоге.
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                fgsRetrying = false;
                try {
                    startForegroundSafely();
                } catch (Exception ignored) {
                }
            }, 3000);
        }
    }

    /** Дёргаем MainActivity.onFgsPermissionDenied(), если она жива и на переднем плане. */
    private void notifyActivityPermissionDenied() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra(MainActivity.EXTRA_FGS_PERMISSION_DENIED, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        boundCount++;
        return binder;
    }

    /**
     * v1.5: защита от «сиротского» клиента.
     * Раньше onDestroy() в MainActivity безусловно вызывал stopService(): при
     * пересоздании активности (поворот экрана, восстановление системой,
     * повторный запуск приложения) это останавливало сервис и рвало живой
     * TCP-канал — сервер печатал «Клиент отключился» с последней командой
     * 'hello' ровно после включения управления и первых касаний. Теперь при
     * последнем unbind управление осознанно выключается здесь (корректный
     * shutdown клиента), а сам процесс сервиса остаётся живым до явного
     * выключения кнопкой или остановки системой.
     */
    @Override
    public boolean onUnbind(Intent intent) {
        if (boundCount > 0) boundCount--;
        if (boundCount == 0 && controlling) {
            stopControlling();
        }
        return true; // разрешить повторный onBind -> наRebind
    }

    @Override
    public void onRebind(Intent intent) {
        boundCount++;
    }

    public boolean isControlling() {
        return controlling;
    }

    public boolean isConnected() {
        PcClient c = client;
        return c != null && c.isConnected();
    }

    public String getServerAddress() {
        PcClient c = client;
        return c == null ? "" : c.getTransportDescription();
    }

    // ---------- Скорость перемещения курсора ----------

    public int getSpeedLevel() {
        return speedLevel;
    }

    /** Множитель чувствительности тачпада: 0.2 .. 2.0 от уровня 1..10. */
    public double getSpeedFactor() {
        return speedLevel / (double) SPEED_DEFAULT;
    }

    public void setSpeedLevel(int level) {
        speedLevel = Math.max(SPEED_MIN, Math.min(SPEED_MAX, level));
        getSharedPreferences("touchcursor", MODE_PRIVATE)
                .edit().putInt("speed_level", speedLevel).apply();
    }

    public void increaseSpeed() {
        setSpeedLevel(speedLevel + 1);
    }

    public void decreaseSpeed() {
        setSpeedLevel(speedLevel - 1);
    }

    /** Включить управление: подключиться к ПК по выбранному каналу и держать соединение. */
    public synchronized void startControlling(int mode, String ip, int port, String btMac, String deviceName) {
        // Идемпотентность: повторный вызов с теми же параметрами (например, при
        // пересоздании активности после поворота экрана) НЕ должен рвать живое
        // соединение — иначе сервер фиксирует "Клиент отключился" ровно в момент
        // включения управления или первого касания тачпада.
        if (controlling && client != null
                && client.sameParams(mode, ip, port, btMac, deviceName)) {
            PcClient c = client;
            Thread t = clientThread;
            // v1.5: но если поток клиента почему-то завершился (например, сервис
            // был перезапущен системой), поднимем новый экземпляр, а не будем
            // «ждать» мёртвый клиент вечно.
            boolean threadDead = (t != null && !t.isAlive());
            // v1.7: страховка для случая, когда поток ещё жив, но цикл уже
            // вышел из-за interrupt()/ошибки канала (isRunning()==false):
            // такой клиент никогда не переподключится сам. Даём ему 2 секунды
            // на достройку соединения и перезапускаемся.
            boolean zombie = false;
            if (!threadDead && !c.isConnected()) {
                try {
                    wait(2000);
                } catch (InterruptedException ignored) {
                }
                zombie = !c.isConnected() && !c.isRunning();
            }
            if (threadDead || zombie) {
                stopControlling();
            } else {
                return;
            }
        } else if (controlling) {
            stopControlling(); // параметры изменились - переподключаемся осознанно
        }
        // v1.5: страховка от дубликатов — никогда не запускать второй PcClient,
        // пока предыдущий поток ещё жив (два TCP-канала с одного телефона =
        // ложные «Клиент отключился» в логе сервера, когда дубликат умирал).
        // v1.7: shutdown() ставит флаг stopped — даже если старый поток не
        // завершится за join(1000) (завис в BT/USB-сокете), он больше не
        // откроет второго TCP-канала и не вытеснит живой.
        if (client != null) {
            PcClient stale = client;
            Thread st = clientThread;
            client = null;
            clientThread = null;
            stale.shutdown();
            if (st != null) {
                try {
                    st.join(1000);
                } catch (InterruptedException ignored) {
                }
            }
        }
        controlling = true;
        acquireWakeLock();
        updateNotification(getString(R.string.notification_connecting));

        client = new PcClient(this, mode, ip, port, btMac, deviceName,
                new PcClient.Callback() {
                    @Override
                    public void onConnected() {
                        updateNotification(getString(R.string.notification_connected,
                                client == null ? "" : client.getTransportDescription()));
                    }

                    @Override
                    public void onDisconnected(String reason) {
                        // Обрыв связи - оставляем режим включённым, клиент сам переподключается.
                        updateNotification(getString(R.string.notification_reconnecting,
                                reason == null || reason.isEmpty()
                                        ? getString(R.string.status_no_link) : reason));
                    }
                }, (w, h) -> {
                    // Разрешение экрана ПК из hello-ответа сервера.
                    getSharedPreferences("touchcursor", MODE_PRIVATE)
                            .edit().putInt("pc_w", w).putInt("pc_h", h).apply();
                });
        clientThread = new Thread(client, "PcClient");
        client.setThread(clientThread); // v1.5: для корректной остановки в shutdown()
        clientThread.start();
    }

    /** Выключить управление: разорвать соединение. */
    public synchronized void stopControlling() {
        controlling = false;
        releaseWakeLock();
        PcClient c = client;
        client = null;
        if (c != null) c.shutdown();
        if (clientThread != null) {
            try {
                clientThread.join(1000);
            } catch (InterruptedException ignored) {
            }
            clientThread = null;
        }
        // v1.7: разбудить поток(ы), ожидающие в startControlling (см. wait(2000)).
        notifyAll();
        updateNotification(getString(R.string.notification_idle));
    }

    // ---------- Отправка команд (потокобезопасно внутри PcClient) ----------

    public void sendMove(double fx, double fy) {
        PcClient c = client;
        if (c != null) c.sendMove(fx, fy);
    }

    public void sendClick(String button, boolean doubleClick) {
        PcClient c = client;
        if (c != null) c.sendClick(button, doubleClick);
    }

    public void sendButtonDown(String button) {
        PcClient c = client;
        if (c != null) c.sendButton("down", button);
    }

    public void sendButtonUp(String button) {
        PcClient c = client;
        if (c != null) c.sendButton("up", button);
    }

    public void sendScroll(int direction) {
        PcClient c = client;
        if (c != null) c.sendScroll(direction);
    }

    /** Прокрутка на несколько "щелчков" колеса. */
    public void sendScrollSteps(int steps) {
        PcClient c = client;
        if (c != null) c.sendScrollSteps(steps);
    }

    /** Относительное перемещение курсора (для отладки каналов USB/BT). */
    public void sendMoveRelative(int dx, int dy) {
        PcClient c = client;
        if (c != null) c.sendMoveRelative(dx, dy);
    }

    // ---------- Wake lock (v1.4) ----------

    private void acquireWakeLock() {
        try {
            PowerManager.WakeLock wl = wakeLock;
            if (wl == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TouchCursor::ctrl");
                wl.setReferenceCounted(false);
                wakeLock = wl;
            }
            if (!wl.isHeld()) {
                // Таймаут-страховка: 2 часа — дальше система сама отпустит блокировку.
                wl.acquire(2 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) {
            // Нет права WAKE_LOCK / нестандартная прошивка — управление продолжит
            // работать, просто без защиты от зависания канала во сне.
        }
    }

    private void releaseWakeLock() {
        try {
            PowerManager.WakeLock wl = wakeLock;
            if (wl != null && wl.isHeld()) {
                wl.release();
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- Уведомление ----------

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        stopControlling();
        releaseWakeLock();
        super.onDestroy();
    }
}

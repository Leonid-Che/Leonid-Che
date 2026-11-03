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
    /** v1.4: экспоненциальная задержка переподключения — от 2 до 30 секунд. */
    private static final int RECONNECT_DELAY_MAX_MS = 30000;
    /** Максимальная частота move-команд, чтобы не топить сеть и сервер. */
    private static final long MOVE_MIN_INTERVAL_MS = 30;
    /** v1.4: после скольких подряд таймаутов чтения слать пробный ping. */
    private static final int READ_TIMEOUT_MISSES = 5;

    /**
     * v1.7: «сиротский» клиент (см. shutdown()). Если цикл переподключения
     * бездействует дольше этого времени, он завершается сам — иначе мёртвый
     * объект PcClient навсегда держит TCP-сокет открытым и рвёт живое
     * соединение при следующем включении управления.
     */
    private static final long IDLE_SHUTDOWN_MS = 90_000;

    /** Режимы канала связи со смартфоном. */
    public static final int MODE_WIFI = 0;
    public static final int MODE_USB = 1;
    public static final int MODE_BT = 2;

    private final int mode;
    private final String host;      // IP (режим Wi-Fi) или MAC (режим BT), "" для USB
    private final int port;
    private final String deviceName;
    /** v1.9: уникальный идентификатор клиента в протоколе ("hello"). Сервер
     *  использует его вместо IP:порт для распознавания дубликатов: повторное
     *  подключение того же экземпляра клиента НЕ вытесняет живой канал, а
     *  новый клиент корректно заменяет старый. Раньше ключом служил IP телефона,
     *  и любое второе hello (даже от «сиротского» цикла переподключения) рвало
     *  рабочее соединение — ровно это и выглядело как «Клиент отключился ...
     *  последняя hello» при включении управления. */
    private final String clientId = java.util.UUID.randomUUID().toString();
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
    /** v1.5: поток connectLoop (нужен для аккуратной остановки в shutdown). */
    private volatile Thread clientThread;
    private volatile OutputStream out;
    private volatile boolean running = true;
    private volatile boolean connected = false;
    private volatile String lastError = "";
    /** Локк атомарной публикации/снятия канала (см. run/writeLine/shutdown). */
    private final Object connLock = new Object();
    private long lastMoveTime = 0;
    private double pendingX, pendingY; // последняя неотправленная позиция

    public boolean isConnected() {
        return connected;
    }

    /** v1.5: жив ли ещё цикл connectLoop этого клиента (см. ControlService). */
    public boolean isRunning() {
        return running;
    }

    /** v1.5: ссылка на поток connectLoop для корректной остановки в shutdown(). */
    public void setThread(Thread t) {
        clientThread = t;
    }

    /**
     * Сравнение параметров канала: используется ControlService для идемпотентного
     * startControlling (не переподключаться, если параметры не изменились).
     */
    public boolean sameParams(int mode, String host, int port, String btMac, String deviceName) {
        return this.mode == mode
                && this.port == port
                && safeEq(this.host, host)
                && safeEq(this.btMac, btMac == null ? "" : btMac)
                && safeEq(this.deviceName, deviceName);
    }

    private static boolean safeEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
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

    /**
     * v1.7: клиент был остановлен штатно (stopControlling/shutdown).
     * Пока флаг не сброшен, connectLoop не запускается повторно — это
     * защита от «сиротского» клиента, который без shutdown() открыл бы
     * второй TCP-канал и оборвал живой (см. shutdown()).
     */
    private volatile boolean stopped = false;

    public boolean isStopped() {
        return stopped;
    }

    @Override
    public void run() {
        // v1.7: остановленный экземпляр не перезапускаем — иначе «сиротский»
        // клиент открыл бы второй TCP-канал и вытеснил живой (см. shutdown()).
        if (stopped) {
            running = false;
            return;
        }
        int backoff = RECONNECT_DELAY_MS; // v1.4: экспоненциальная задержка (2..30 с)
        long lastConnectAttempt = System.currentTimeMillis();
        while (running) {
            Transport t = null;
            try {
                // v1.7: страховка от зависания потока accept() на Windows-сервере.
                // Если предыдущее соединение было прервано грубо (RST из-за
                // вытеснения дубликата), серверный поток иногда не освобождает
                // очередь listen-сокета, и новый connect() висел бы до 30+ с
                // (на Android таймаут коннекта игнорируется ядром). Жёсткий
                // watchdog будит поток через CONNECT_TIMEOUT_MS: interrupt()
                // обрывает блокирующее socket.connect(), а закрываемый следом
                // транспорт добивает сокет гарантированно.
                final Thread self = Thread.currentThread();
                Thread watchdog = new Thread(() -> {
                    try {
                        Thread.sleep(PcClient.CONNECT_TIMEOUT_MS + 1500L);
                    } catch (InterruptedException e) {
                        return; // соединение установлено — watchdog больше не нужен
                    }
                    if (!self.isInterrupted()) self.interrupt();
                }, "PcClientWatchdog");
                watchdog.setDaemon(true);
                watchdog.start();
                try {
                    t = createTransport();
                    t.connect();
                } finally {
                    watchdog.interrupt();
                }
                backoff = RECONNECT_DELAY_MS; // соединение удалось — сброс паузы
                // Публикуем канал атомарно (см. publish/unpublish): старый
                // экземпляр клиента мог ещё оставаться в поле transport у UI-потока,
                // и его закрытие здесь обрывало РАБОТАЮЩЕЕ соединение — сервер видел
                // "Клиент отключился" ровно в момент включения управления/первого касания.
                synchronized (connLock) {
                    transport = t;
                    out = t.getOutputStream();
                    connected = true;
                }
                lastError = "";
                callback.onConnected();

                JSONObject hello = new JSONObject();
                hello.put("cmd", "hello");
                hello.put("name", deviceName);
                hello.put("id", clientId); // v1.9: ключ вытеснения дубликатов на сервере
                hello.put("transport", mode == MODE_USB ? "usb" : mode == MODE_BT ? "bt" : "wifi");
                writeLine(hello.toString());

                // Читаем ответы сервера: из hello берём разрешение экрана ПК.
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(t.getInputStream(), StandardCharsets.UTF_8));
                String line;
                int missedReads = 0;
                while (running) {
                    try {
                        line = in.readLine();
                    } catch (java.net.SocketTimeoutException ste) {
                        // v1.4: таймаут чтения = простой канала, а НЕ обрыв.
                        line = "";
                    }
                    if (line == null) break;   // настоящий EOF: сервер закрылся
                    if (line.isEmpty()) {
                        // v1.4: пустая строка = таймаут чтения. Это НЕ обрыв:
                        // сервер живёт в простое между командами. Считаем
                        // промахи и при накоплении шлём "ping" — лёгкую команду,
                        // на которую сервер гарантированно ответит (см.
                        // server.py: unknown cmd -> err-ответ). Наличие ответа
                        // доказывает жизнь канала без разрыва соединения.
                        if (++missedReads >= READ_TIMEOUT_MISSES) {
                            missedReads = 0;
                            try {
                                JSONObject probe = new JSONObject();
                                probe.put("cmd", "ping");
                                writeLine(probe.toString());
                            } catch (Exception ignored) {
                            }
                        }
                        continue;
                    }
                    missedReads = 0;
                    try {
                        JSONObject resp = new JSONObject(line);
                        if ("hello".equals(resp.optString("cmd"))
                                && resp.has("screen") && screenListener != null) {
                            org.json.JSONArray sc = resp.getJSONArray("screen");
                            screenListener.onScreenSize(sc.getInt(0), sc.getInt(1));
                        }
                        // {"resp":"ping"} от сервера — просто подтверждение
                        // жизни канала, игнорируем.
                    } catch (Exception ignored) {
                        // не-JSON или неизвестный ответ - пропускаем
                    }
                }
                // v1.4: если цикл вышел из-за readLine()==null, но поток ещё
                // открыт и running==true — это штатное закрытие (shutdown()).
                // Реальный обрыв здесь же: ставим понятную причину.
                if (running && lastError == null) {
                    lastError = "Соединение закрыто сервером";
                }
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                // связь потеряна или не установлена
            } finally {
                boolean wasMine;
                synchronized (connLock) {
                    // Сбрасываем поля только если они всё ещё указывают на ЭТОТ
                    // транспорт (а не на более новый, созданный параллельным экземпляром).
                    wasMine = (transport == t);
                    if (wasMine) {
                        connected = false;
                        transport = null;
                        out = null;
                    }
                }
                if (t != null && wasMine) {
                    try {
                        t.close();
                    } catch (Exception ignored) {
                    }
                }
            }

            if (!running) break;
            callback.onDisconnected(lastError);
            // v1.7: «сиротский» клиент — корень повторяющихся обрывов Wi-Fi.
            // Если экземпляр был вытеснен новым (startControlling вызывается
            // повторно при повороте экрана, смене канала, выдаче прав), но его
            // shutdown() не был вызван, цикл вечно переподключается каждые 2-30
            // с и держит ВТОРОЙ TCP-канал. Сервер вытесняет им живой канал —
            // в логе ровно это выглядит как «Клиент отключился ... последняя
            // hello» сразу после включения управления и первых касаний.
            // Мёртвый клиент сам завершается после IDLE_SHUTDOWN_MS безрезультатных
            // попыток и закрывает сокет.
            long now = System.currentTimeMillis();
            if (now - lastConnectAttempt > IDLE_SHUTDOWN_MS) {
                running = false;
                break;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            lastConnectAttempt = System.currentTimeMillis();
            // v1.4: экспоненциальная пауза до 30 с — при устойчивых обрывах
            // не «долбить» сервер каждые 2 секунды (это само по себе создавало
            // лавину подключений/разрывов в логе).
            backoff = Math.min(backoff * 2, RECONNECT_DELAY_MAX_MS);
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
        // Копируем ссылку на поток под локком: если connectLoop в этот момент
        // переканаливает соединение, запись уйдёт либо в старый живой канал,
        // либо в новый - но не в закрытый посередине операции.
        OutputStream o;
        Transport t;
        synchronized (connLock) {
            o = out;
            t = transport;
        }
        if (o == null || t == null) return;
        try {
            o.write(json.getBytes(StandardCharsets.UTF_8));
            o.write('\n');
            o.flush();
        } catch (Exception e) {
            // Обрыв при записи. ВАЖНО: НЕ закрываем транспорт сами - иначе
            // сервер фиксировал "Клиент отключился" на каждое проблемное
            // касание. Просто помечаем канал мёртвым; connectLoop заметит
            // ошибку чтения/записи и переподключится штатно.
            synchronized (connLock) {
                if (transport == t) {
                    connected = false;
                    transport = null;
                    out = null;
                } else {
                    return; // это был устаревший экземпляр канала - игнорируем
                }
            }
            try {
                t.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Останавливает клиент и ждёт завершения фонового цикла. */
    public void shutdown() {
        running = false;
        stopped = true; // v1.7: запрет на повторный запуск этого экземпляра (см. run())
        Transport t;
        synchronized (connLock) {
            t = transport;
            connected = false;
            transport = null;
            out = null;
        }
        if (t != null) {
            try {
                // v1.5: close() прерывает блокирующий readLine() цикла и
                // корректно отправляет FIN — сервер видит честный EOF вместо
                // таймаута heartbeat.
                t.close();
            } catch (Exception ignored) {
            }
        }
        // v1.7: если транспорт не закрылся (BT/USB-зависание), принудительно
        // обрываем его ещё раз и будим поток чтения: иначе он «спит» в recv()
        // до 5 секунд (SoTimeout), join(1500) выходит по таймауту, а живой
        // TCP-канал остаётся открытым после выключения управления — при
        // следующем включении сервер вытесняет его новым соединением и печатает
        // «Клиент отключился ... последняя hello».
        Transport t2;
        synchronized (connLock) {
            t2 = transport;
        }
        if (t2 != null) {
            try {
                t2.close();
            } catch (Exception ignored) {
            }
        }
        // v1.5: гарантированная остановка зависшего в сокете потока чтения.
        // Если connectLoop застрял в блокирующем recv() на канале, который не
        // удалось закрыть (BT/USB), поток остаётся живым и держит старый TCP-
        // сокет открытым: с телефона одновременно живут 2 клиента, и гибель
        // дубликата сервер печатает как «Клиент отключился» ровно при включении
        // управления. interrupt() будит поток (SocketTimeoutException по
        // setSoTimeout уже обрабатывается), join ждёт коротко и отпускает UI.
        Thread th = clientThread;
        if (th != null && th.isAlive()) {
            th.interrupt();
            try {
                th.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String fmt(double v) {
        // без экспоненциальной записи и locale-запятых
        long n = Math.round(Math.max(0.0, Math.min(1.0, v)) * 10000);
        return Long.toString(n / 10000) + "." + String.format("%04d", n % 10000);
    }
}

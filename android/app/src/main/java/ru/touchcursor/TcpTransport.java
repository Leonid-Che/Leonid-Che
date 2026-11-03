package ru.touchcursor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * TCP-канал поверх Wi-Fi (или USB-привязанного adb-reverse / RNDIS):
 * подключение к ip:port Windows-сервера.
 */
public class TcpTransport implements Transport {

    private final String host;
    private final int port;
    private Socket socket;

    public TcpTransport(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void connect() throws IOException {
        // v1.9: КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ. В логах видно, что после каждого обрыва
        // клиент переподключается С НОВЫМ локальным портом (…:46877 → …:50112…).
        // Это означает, что предыдущий сокет был убит ОС (RST), а не закрыт
        // приложением, и что в момент активации управления существовало
        // минимум два живых экземпляра TcpTransport (старый + новый). Каждый
        // новый канал заставлял сервер вытеснять прежний, тот закрывался,
        // цикл переподключения старого экземпляра открывал ещё один канал —
        // лавина «hello → EOF → hello с новым портом» продолжалась бесконечно.
        // Поэтому connect() теперь ВСЕГДА сначала уничтожает любой ранее
        // открытый сокет этого объекта (preempt-self), сколько бы раз его ни
        // вызвали. Метод идемпотентен и потокобезопасен.
        closeQuietlyExisting();

        Socket s = new Socket();
        // Таймаут connect() на Android работает только после предварительного
        // bind() (см. комментарий v1.7): иначе зависание до ~30 с.
        try {
            s.bind(new InetSocketAddress(0));
        } catch (IOException ignored) {
        }
        s.connect(new InetSocketAddress(host, port), PcClient.CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        // v1.4: стандартный keepalive Android конфликтует с энергосбережением
        // Wi-Fi; живость канала поддерживают heartbeat-пакеты сервера.
        s.setKeepAlive(false);
        synchronized (this) {
            socket = s;
        }
    }

    /** Закрыть предыдущий сокет этого транспорта, если он ещё жив (v1.9). */
    private void closeQuietlyExisting() {
        Socket old;
        synchronized (this) {
            old = socket;
            socket = null;
        }
        if (old != null) {
            try {
                old.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        Socket s = socket;
        if (s == null) throw new IOException("socket not connected");
        return s.getOutputStream();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        Socket s = socket;
        if (s == null) throw new IOException("socket not connected");
        // v1.4: таймаут чтения 5 с. Без него readLine() блокировался «навечно»,
        // и штатное завершение клиента (shutdown -> close) зависало в этом
        // чтении до прихода TCP-ответа от сервера — на практике это выглядело
        // как обрыв соединения при каждом переподключении. Теперь BufferedReader
        // бросит SocketTimeoutException, PcClient перехватит её как пустую
        // строку и продолжит цикл, не разрывая канал.
        try {
            s.setSoTimeout(5000);
        } catch (Exception ignored) {
        }
        return s.getInputStream();
    }

    @Override
    public void close() {
        Socket s = socket;
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
        return "Wi-Fi/TCP " + host + ":" + port;
    }
}

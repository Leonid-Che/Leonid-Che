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
        Socket s = new Socket();
        // v1.7: ГЛАВНАЯ ПРИЧИНА повторяющихся обрывов Wi-Fi.
        // На Android (Linux) таймаут, переданный в connect(addr, timeout),
        // НЕ ДЕЙСТВУЕТ, если сокет не был предварительно привязан к локальному
        // адресу bind(): ядро игнорирует SO_SNDTIMEO и соединение зависает до
        // ~30 секунд (TCP SYN retries). При включении управления приложение
        // пересоздаёт клиента (идемпотентный startControlling не срабатывал после
        // поворота экрана/выдачи прав), новый экземпляр открывал ВТОРОЙ канал,
        // сервер вытеснял им живой — в логе ровно «Клиент отключился ...
        // последняя hello» сразу после включения и первых касаний.
        // Предварительный bind() на автоматический порт включает действие
        // таймаута: connect() бросит SocketTimeoutException через 4 с.
        try {
            s.bind(new InetSocketAddress(0));
        } catch (IOException ignored) {
            // bind не критичен — пробуем подключиться и так
        }
        s.connect(new InetSocketAddress(host, port), PcClient.CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        // v1.4: setKeepAlive(false) вместо setKeepAlive(true).
        // Стандартный TCP keepalive Android имеет интервал ~2 часа и никогда
        // не включается при живом трафике; на некоторых прошивках он, наоборот,
        // конфликтовал с энергосбережением Wi-Fi (Doze/WoW-фильтры роутера):
        // «молчаливое» соединение без входящего трафика выгружалось адаптером,
        // а первое же касание тачпада/кнопки вызывало RST — сервер печатал
        // «Клиент отключился». Живость канала теперь поддерживают heartbeat-пакеты
        // сервера (см. server.py), поэтому keepalive на сокете не нужен.
        s.setKeepAlive(false);
        socket = s;
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

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
        s.connect(new InetSocketAddress(host, port), PcClient.CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
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

package ru.touchcursor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Абстракция транспортного канала «смартфон <-> компьютер».
 * Реализации: TCP через Wi-Fi (TcpTransport), USB AOA (UsbTransport),
 * классический Bluetooth RFCOMM (BtRfcommTransport).
 * Все три несут один и тот же протокол: строки JSON, разделённые '\n'.
 */
public interface Transport {

    /** Подключиться к серверу. Блокирующий вызов — только из фонового потока. */
    void connect() throws IOException;

    OutputStream getOutputStream() throws IOException;

    InputStream getInputStream() throws IOException;

    void close();

    /** Человекочитаемое описание канала для статуса UI («USB», «Bluetooth…»). */
    String describe();
}

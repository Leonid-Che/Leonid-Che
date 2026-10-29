package ru.touchcursor;

import android.app.PendingIntent;
import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * USB-канал смартфон <-> компьютер по протоколу Android Accessory (AOA) v1.
 * Смартфон выступает «аксессуаром»: при подключении кабеля Windows принимает
 * устройство как «Android Accessory Interface» (драйвер WinUSB из комплекта
 * ADB / Google USB Driver), сервер TouchCursor открывает его USB-интерфейс
 * и читает/пишет команды через bulk-эндпоинты — интернет не нужен.
 *
 * На стороне Android ничего не запрашивается у пользователя, кроме стандартного
 * диалога «Разрешить приложению TouchCursor доступ к устройству?» (он появляется
 * автоматически при подключении кабеля, если приложение уже зарегистрировано
 * на ACTION_USB_DEVICE_ATTACHED в filter.xml).
 */
public class UsbTransport implements Transport {

    /** Те же идентификаторы, что использует windows_server/usb_transport.py. */
    public static final int VENDOR_ID = 0x18D1;   // Google
    public static final int PRODUCT_ID = 0x2D70;  // AOA accessory product id
    private static final String MANUFACTURER = "TouchCursor";
    private static final String MODEL = "TouchCursor PC Link";
    private static final String DESCRIPTION = "Mouse control over USB";
    private static final String VERSION = "1.0";

    private static final byte GET_PROTOCOL = 51;
    private static final byte SEND_STRING = 52;
    private static final byte START_ACCESSORY = 53;
    private static final int STRING_MANUFACTURER = 0;
    private static final int STRING_MODEL = 1;
    private static final int STRING_DESCRIPTION = 2;
    private static final int STRING_VERSION = 3;

    private final Context ctx;
    private final UsbManager manager;
    private volatile UsbDeviceConnection connection;
    private volatile UsbEndpoint epIn, epOut;
    private volatile boolean closed = false;

    public UsbTransport(Context ctx) {
        this.ctx = ctx;
        this.manager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
    }

    @Override
    public void connect() throws IOException {
        closed = false;
        UsbDevice device = findDevice();
        if (device == null) {
            throw new IOException("Компьютер не найден в USB. Проверьте кабель и переподключите его.");
        }
        if (!manager.hasPermission(device)) {
            // Запрашиваем разрешение; после подтверждения пользователем
            // цикл переподключения PcClient повторит попытку.
            int flags = android.os.Build.VERSION.SDK_INT >= 31
                    ? PendingIntent.FLAG_IMMUTABLE : 0;
            manager.requestPermission(device,
                    PendingIntent.getBroadcast(ctx, 0,
                            new android.content.Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED), flags));
            throw new IOException("Ожидание разрешения на доступ к USB-устройству…");
        }
        if (!switchToAccessory(device)) {
            throw new IOException("Устройство не перешло в режим AOA-аксессуара.");
        }
        openAccessoryStreams();
    }

    /** Ищем подключённое AOA-устройство (VID/PID аксессуара или любое UDC-устройство ПК). */
    private UsbDevice findDevice() {
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getVendorId() == VENDOR_ID && d.getProductId() == PRODUCT_ID) return d;
        }
        // Устройство ещё в режиме «телефон» (до switch): ищем любой подключенный хост
        // по классу USB_CLASS_PER_INTERFACE с хотя бы одним контрольным эндпоинтом —
        // практический маркер: у телефона нет прав управлять этим портом, поэтому
        // просто возвращаем первое устройство, отличное от собственного host-режима.
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (isPlausibleHost(d)) return d;
        }
        return null;
    }

    private boolean isPlausibleHost(UsbDevice d) {
        // Компьютер-хост обычно виден как hub/unknown; пробуем переключение на любом
        // устройстве, которое не является нашей же AOA-парой.
        return !(d.getVendorId() == VENDOR_ID && d.getProductId() == PRODUCT_ID);
    }

    /** Классическое AOA-переключение: control transfer'ы на эндпоинт устройства. */
    private boolean switchToAccessory(UsbDevice device) throws IOException {
        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) return false;
        try {
            byte[] buf = new byte[1];
            int proto = conn.controlTransfer(0xC0, GET_PROTOCOL, 0, 0, buf, 1, 500);
            if (proto < 1 || buf[0] < 1) return false; // устройство не поддерживает AOA
            sendString(conn, STRING_MANUFACTURER, MANUFACTURER);
            sendString(conn, STRING_MODEL, MODEL);
            sendString(conn, STRING_DESCRIPTION, DESCRIPTION);
            sendString(conn, STRING_VERSION, VERSION);
            int r = conn.controlTransfer(0x40, START_ACCESSORY, VENDOR_ID, PRODUCT_ID, null, 0, 500);
            return r >= 0;
        } finally {
            conn.close(); // после START_ACCESSORY дескрипторы перестроятся — откроем заново
        }
    }

    private void sendString(UsbDeviceConnection conn, int index, String value) throws IOException {
        // StandardCharsets.UTF_8 — без checked-исключения UnsupportedEncodingException
        byte[] payload = (value + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int r = conn.controlTransfer(0x40, SEND_STRING, 0, index, payload, payload.length, 2000);
        if (r < 0) throw new IOException("AOA SEND_STRING failed");
    }

    /** После переключения устройство перег enumeration'ится — ждём и открываем потоки. */
    private void openAccessoryStreams() throws IOException {
        UsbDevice acc = null;
        long deadline = System.currentTimeMillis() + 8000;
        while (acc == null && System.currentTimeMillis() < deadline && !closed) {
            for (UsbDevice d : manager.getDeviceList().values()) {
                if (d.getVendorId() == VENDOR_ID && d.getProductId() == PRODUCT_ID) {
                    acc = d;
                    break;
                }
            }
            if (acc == null) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (acc == null) throw new IOException("AOA-устройство не появилось после переключения.");
        if (!manager.hasPermission(acc)) {
            throw new IOException("Нет разрешения USB для AOA-устройства.");
        }
        UsbDeviceConnection conn = manager.openDevice(acc);
        if (conn == null) throw new IOException("Не удалось открыть USB-соединение.");
        UsbInterface iface = null;
        for (int i = 0; i < acc.getInterfaceCount(); i++) {
            UsbInterface it = acc.getInterface(i);
            if (it.getInterfaceClass() == 0xFF
                    && it.getEndpointCount() >= 2) { // AOA: two bulk endpoints
                iface = it;
                break;
            }
        }
        if (iface == null) {
            conn.close();
            throw new IOException("Интерфейс AOA не найден.");
        }
        conn.claimInterface(iface, true);
        UsbEndpoint in = null, out = null;
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint ep = iface.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
            else out = ep;
        }
        if (in == null || out == null) {
            conn.close();
            throw new IOException("Bulk-эндпоинты AOA не найдены.");
        }
        this.connection = conn;
        this.epIn = in;
        this.epOut = out;
        readerThread = new Thread(this::pumpReader, "UsbAoaReader");
        readerThread.setDaemon(true);
        writerThread = new Thread(this::pumpWriter, "UsbAoaWriter");
        writerThread.setDaemon(true);
        readerThread.start();
        writerThread.start();
    }

    // ---------- Потоки поверх bulk-пересылок ----------
    // UsbDeviceConnection.bulkTransfer не даёт потокового API, поэтому поднимаем
    // два фоновых насоса и связываем их с PipeOutputStream/PipeInputStream.

    private final LinkedBlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>(64);
    private final LinkedBlockingQueue<byte[]> readQueue = new LinkedBlockingQueue<>(64);
    private volatile InputStream inputStream;
    private volatile OutputStream outputStream;
    private Thread readerThread, writerThread;

    private void pumpReader() {
        byte[] buf = new byte[16384];
        UsbDeviceConnection conn = connection;
        UsbEndpoint ep = epIn;
        try {
            while (!closed && conn != null && ep != null) {
                int n = conn.bulkTransfer(ep, buf, buf.length, 200);
                if (n > 0) {
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    readQueue.put(chunk);
                }
            }
        } catch (InterruptedException ignored) {
        } finally {
            readQueue.offer(new byte[0]); // EOF-маркер для читателя протокола
        }
    }

    private void pumpWriter() {
        UsbDeviceConnection conn = connection;
        UsbEndpoint ep = epOut;
        try {
            while (!closed && conn != null && ep != null) {
                byte[] chunk = writeQueue.take();
                int off = 0;
                while (off < chunk.length && !closed) {
                    int len = Math.min(chunk.length - off, 16384);
                    int n = conn.bulkTransfer(ep, chunk, off, len, 2000);
                    if (n < 0) {
                        closed = true;
                        break;
                    }
                    off += n;
                }
            }
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public synchronized OutputStream getOutputStream() {
        if (outputStream == null) outputStream = new PipeOutputStream();
        return outputStream;
    }

    @Override
    public synchronized InputStream getInputStream() {
        if (inputStream == null) inputStream = new PipeInputStream();
        return inputStream;
    }

    @Override
    public void close() {
        closed = true;
        UsbDeviceConnection conn = connection;
        connection = null;
        epIn = null;
        epOut = null;
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        }
        writeQueue.clear();
        readQueue.clear();
    }

    @Override
    public String describe() {
        return "USB (AOA)";
    }

    /** Поток записи: блокирующие put() в очередь, которую забирает bulk-насос. */
    private class PipeOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] data, int off, int len) throws IOException {
            if (closed) throw new IOException("USB transport closed");
            byte[] chunk = new byte[len];
            System.arraycopy(data, off, chunk, 0, len);
            if (!writeQueue.offer(chunk)) {
                try {
                    writeQueue.put(chunk);
                } catch (InterruptedException e) {
                    throw new IOException("interrupted", e);
                }
            }
        }

        @Override
        public void flush() {
            // bulkTransfer выполняется сразу в насосе — flush не требуется
        }

        @Override
        public void close() {
            UsbTransport.this.close();
        }
    }

    /** Поток чтения: выбирает чанки из очереди bulk-насоса. */
    private class PipeInputStream extends InputStream {
        private byte[] current;
        private int pos;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            while (true) {
                if (current == null || pos >= current.length) {
                    if (current != null && current.length == 0) return -1; // EOF
                    try {
                        current = readQueue.take();
                    } catch (InterruptedException e) {
                        throw new IOException("interrupted", e);
                    }
                    pos = 0;
                    if (closed && current.length == 0) return -1;
                    continue;
                }
                int n = Math.min(len, current.length - pos);
                System.arraycopy(current, pos, b, off, n);
                pos += n;
                return n;
            }
        }

        @Override
        public void close() {
            UsbTransport.this.close();
        }
    }
}

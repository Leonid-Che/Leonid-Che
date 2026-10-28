package android.hardware.usb;
public class UsbDeviceConnection implements java.io.Closeable {
    public boolean claimInterface(UsbInterface intf, boolean force) { return false; }
    public void close() {}
    public int controlTransfer(int requestType, int request, int value, int index, byte[] buffer, int length, int timeout) { return -1; }
    public int bulkTransfer(UsbEndpoint pipe, byte[] data, int length, int timeout) { return -1; }
}

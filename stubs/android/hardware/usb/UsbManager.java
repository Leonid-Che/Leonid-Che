package android.hardware.usb;
import android.app.PendingIntent;
import java.util.HashMap;
public class UsbManager {
    public static final String ACTION_USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    public HashMap<String, UsbDevice> getDeviceList() { return null; }
    public boolean hasPermission(UsbDevice device) { return false; }
    public void requestPermission(UsbDevice device, PendingIntent pi) {}
    public UsbDeviceConnection openDevice(UsbDevice device) { return null; }
}

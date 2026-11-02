package android.content;
public class Context {
    public static final String BLUETOOTH_SERVICE = "bluetooth";
    public static final String USB_SERVICE = "usb";
    public Object getSystemService(String name) { return null; }
    public void startActivity(Intent i) {}
    public static final int MODE_PRIVATE = 0;
    public static final int BIND_AUTO_CREATE = 1;
    public SharedPreferences getSharedPreferences(String n, int m) { return null; }
    public boolean bindService(Intent i, ServiceConnection sc, int f) { return false; }
    public void unbindService(ServiceConnection sc) {}
    public ComponentName startService(Intent i) { return null; }
    public ComponentName startForegroundService(Intent i) { return null; }
    public boolean stopService(Intent i) { return false; }
    public String getString(int r) { return ""; }
    public String getString(int r, Object... a) { return ""; }
}

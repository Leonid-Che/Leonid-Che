package android.app;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
public abstract class Service {
    public abstract IBinder onBind(Intent intent);
    public void onCreate() {}
    public void onDestroy() {}
    public Object getSystemService(Class<?> c) { return null; }
    public String getString(int r) { return ""; }
    public String getString(int r, Object... a) { return ""; }
    public android.content.SharedPreferences getSharedPreferences(String n, int m) { return null; }
    public static final int MODE_PRIVATE = 0;
    public void startForeground(int id, Notification n) {}
}

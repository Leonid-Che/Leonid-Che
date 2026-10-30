package android.widget;
import android.content.Context;
public class Toast {
    public static final int SHORT=0, LONG=1;
    public static Toast makeText(Context c, CharSequence s, int len) { return new Toast(); }
    public static Toast makeText(Context c, int resId, int len) { return new Toast(); }
    public void show() {}
}

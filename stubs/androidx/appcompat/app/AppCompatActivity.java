package androidx.appcompat.app;
import android.app.Activity;
public class AppCompatActivity extends Activity {
    public int checkSelfPermission(String p) { return 0; }
    public static final int PERMISSION_GRANTED = 0;
    public static final int PERMISSION_DENIED = -1;
}
